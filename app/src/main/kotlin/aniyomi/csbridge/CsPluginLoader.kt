package aniyomi.csbridge

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import dalvik.system.PathClassLoader
import java.io.File
import java.io.InputStreamReader

/**
 * Loads a Cloudstream plugin (`.cs3` / `.zip`) the exact same way the
 * Cloudstream app does:
 *
 * 1. a [PathClassLoader] is created for the archive, parented to *our* class
 *    loader (which already contains the whole `com.lagradost.cloudstream3.*`
 *    runtime we ship inside this APK) ;
 * 2. `manifest.json` is read from the archive to find the plugin entry class ;
 * 3. the class is instantiated and `load()` (or `load(context)`) is called, which
 *    registers its providers through `BasePlugin.registerMainAPI`.
 */
object CsPluginLoader {

    class LoadedPlugin(
        val file: File,
        val manifest: PluginManifest,
        val instance: BasePlugin,
        val classLoader: ClassLoader,
        val providers: List<MainAPI>,
    )

    /**
     * @return the loaded plugin, or throws with a human readable message.
     */
    fun load(context: Context, file: File): LoadedPlugin {
        if (!file.exists()) throw IllegalArgumentException("File does not exist: ${file.absolutePath}")
        if (file.length() == 0L) throw IllegalArgumentException("Empty plugin file: ${file.name}")

        // Android 14+ refuses to load dex files that are world writable.
        runCatching { if (!file.setReadOnly()) CsLog.w("Could not mark ${file.name} read-only") }
            .onFailure { CsLog.w("setReadOnly failed: ${it.message}") }

        val loader = PathClassLoader(file.absolutePath, CsPluginLoader::class.java.classLoader)

        val manifestText = loader.getResourceAsStream("manifest.json")?.use { stream ->
            InputStreamReader(stream).use { it.readText() }
        } ?: throw IllegalArgumentException(
            "${file.name} does not contain a manifest.json — is it really a Cloudstream plugin?",
        )

        val manifest = CsPrefs.json.decodeFromString<PluginManifest>(manifestText)
        val className = manifest.pluginClassName
            ?: throw IllegalArgumentException("${file.name}: manifest.json has no pluginClassName")

        val pluginClass = loader.loadClass(className)
        val instance = pluginClass.getDeclaredConstructor().newInstance() as? BasePlugin
            ?: throw IllegalArgumentException("$className does not extend BasePlugin")
        instance.filename = file.absolutePath

        val before = registeredProviders()

        if (manifest.requiresResources) attachResources(context, file, instance)

        if (instance is Plugin) instance.load(context) else instance.load()

        val providers = registeredProviders().filter { it !in before }
        // Make sure every provider is reachable through APIHolder.getApiFromNameNull()
        providers.forEach { runCatching { APIHolder.addPluginMapping(it) } }

        CsLog.i("Loaded plugin ${manifest.name ?: file.name} (${providers.size} provider(s))")
        return LoadedPlugin(file, manifest, instance, loader, providers)
    }

    fun unload(plugin: LoadedPlugin) {
        runCatching { plugin.instance.beforeUnload() }
            .onFailure { CsLog.w("beforeUnload failed: ${it.message}") }

        val fileName = plugin.file.absolutePath
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { it.sourcePlugin == fileName }
        }
        plugin.providers.forEach { runCatching { APIHolder.removePluginMapping(it) } }
    }

    /**
     * Some plugins bundle their own resources (strings, drawables...).
     * Same trick as Cloudstream: build an AssetManager pointing at the archive.
     */
    private fun attachResources(context: Context, file: File, instance: BasePlugin) {
        runCatching {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)
            addAssetPath.invoke(assets, file.absolutePath)
            @Suppress("DEPRECATION")
            (instance as? Plugin)?.resources = Resources(
                assets,
                context.resources.displayMetrics,
                context.resources.configuration,
            )
        }.onFailure { CsLog.w("Could not attach plugin resources: ${it.message}") }
    }

    private fun registeredProviders(): Set<MainAPI> =
        APIHolder.allProviders.withLock { APIHolder.allProviders.toSet() }
}
