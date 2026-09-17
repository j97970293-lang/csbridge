package aniyomi.csbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SettingsJson
import eu.kanade.tachiyomi.animesource.AnimeSource
import aniyomi.csbridge.source.CsAnimeSource
import aniyomi.csbridge.source.CsHubSource
import com.lagradost.api.setContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

/**
 * Heart of the extension: owns the Cloudstream runtime, the loaded plugins and
 * the Aniyomi sources that expose them.
 *
 * One Aniyomi source == one Cloudstream provider (which usually means one site),
 * exactly like in Cloudstream.
 */
object CsBridge {

    private const val DIR_PLUGINS = "csbridge/plugins"
    private const val DIR_SIDELOAD = "csbridge/sideload"

    /** plugin file path -> loaded plugin */
    private val loadedPlugins = LinkedHashMap<String, CsPluginLoader.LoadedPlugin>()

    /** "pluginInternalName|providerName" -> provider */
    private val providers = LinkedHashMap<String, ProviderRef>()

    @Volatile
    private var initialized = false

    data class ProviderRef(
        val pluginInternalName: String,
        val pluginName: String,
        val provider: MainAPI,
        val record: InstalledPlugin?,
    ) {
        val key: String get() = keyOf(pluginInternalName, provider.name)
    }

    fun keyOf(pluginInternalName: String, providerName: String) =
        "$pluginInternalName|$providerName"

    // ------------------------------------------------------------------ dirs

    fun pluginsDir(context: Context): File =
        File(context.filesDir, DIR_PLUGINS).apply { mkdirs() }

    fun sideloadDir(context: Context): File =
        File(context.filesDir, DIR_SIDELOAD).apply { mkdirs() }

    /** Mirror of Cloudstream: `<repo hash>/<plugin hash>.cs3` */
    fun pluginFile(context: Context, internalName: String, repositoryUrl: String): File =
        File(
            File(pluginsDir(context), CsRepositoryManager.sanitizeFileName(repositoryUrl)).apply { mkdirs() },
            "${CsRepositoryManager.sanitizeFileName(internalName)}.cs3",
        )

    // ------------------------------------------------------------- bootstrap

    /**
     * Called from [CloudstreamBridgeFactory]. Must be fast and must never
     * throw: a failure here would hide every Cloudstream source.
     */
    @Synchronized
    fun bootstrap(context: Context): List<AnimeSource> {
        CsContext.set(context)
        CsCrypto.ensure()
        val started = System.currentTimeMillis()
        try {
            initRuntime(context)
            loadInstalledPlugins(context)
        } catch (t: Throwable) {
            CsLog.e("Bootstrap failed", t)
        }
        initialized = true
        CsLog.i("Bootstrap done in ${System.currentTimeMillis() - started}ms — ${providers.size} provider(s)")
        return sources(context)
    }

    private fun initRuntime(context: Context) {
        // Cloudstream's platform glue (locale, webview helper...)
        runCatching { setContext(WeakReference<Any>(context.applicationContext)) }
            .onFailure { CsLog.w("setContext failed: ${it.message}") }

        runCatching {
            MainAPI.settingsForProvider = SettingsJson(enableAdult = CsPrefs.enableAdult(context))
        }.onFailure { CsLog.w("Could not set provider settings: ${it.message}") }
    }

    private fun loadInstalledPlugins(context: Context) {
        val installed = CsPrefs.installed(context)
        installed.filter { it.enabled }.forEach { record ->
            runCatching { ensureLoaded(context, record) }
                .onFailure { CsLog.e("Could not load plugin ${record.name}", it) }
        }
        runCatching { APIHolder.initAll() }
    }

    /**
     * Unloads a plugin that is already loaded from [path], so that a newer copy
     * of the same file can be loaded again (update / reinstall).
     *
     * Without this, `ensureLoaded` would see the path as already loaded and the
     * user would keep running the *old* code until Aniyomi is restarted.
     */
    @Synchronized
    fun unloadFile(path: String) {
        loadedPlugins.remove(path)?.let { loaded ->
            runCatching { CsPluginLoader.unload(loaded) }
                .onFailure { CsLog.w("unload failed: ${it.message}") }
        }
        providers.entries.removeIf { (_, ref) -> ref.provider.sourcePlugin == path }
    }

    /** Loads a plugin if it is not loaded yet. Safe to call from any thread. */
    @Synchronized
    fun ensureLoaded(context: Context, record: InstalledPlugin) {
        val file = File(record.filePath)
        if (loadedPlugins.containsKey(file.absolutePath)) return
        if (!file.exists()) {
            CsLog.w("Plugin file missing: ${file.absolutePath}")
            return
        }
        val loaded = CsPluginLoader.load(context, file)
        loadedPlugins[file.absolutePath] = loaded
        loaded.providers.forEach { provider ->
            runCatching { applyMainUrlOverride(context, record, provider) }
            providers[keyOf(record.internalName, provider.name)] =
                ProviderRef(record.internalName, record.name, provider, record)
        }
    }

    private fun applyMainUrlOverride(context: Context, record: InstalledPlugin, provider: MainAPI) {
        val override = CsPrefs.mainUrlOverride(context, keyOf(record.internalName, provider.name))
        if (override.isNotBlank() && provider.canBeOverridden) {
            runCatching { provider.mainUrl = override }
        }
    }

    // ---------------------------------------------------------------- sources

    /**
     * One source per Cloudstream provider (one catalog per site), plus the
     * always-present hub source that gives access to the settings.
     *
     * It must return at least one element: Aniyomi hides extensions that expose
     * no source at all.
     */
    @Synchronized
    fun sources(context: Context): List<AnimeSource> {
        if (!initialized) return bootstrap(context)
        val list = try {
            providers.values
                .filter { !CsPrefs.providerDisabled(context, it.key) }
                .map { ref ->
                    CsAnimeSource(
                        ref = ref,
                        contextProvider = { context.applicationContext },
                    )
                }
                .sortedBy { it.name.lowercase() }
        } catch (t: Throwable) {
            CsLog.e("Cannot build the source list", t)
            emptyList()
        }
        // The hub comes last so that real sites appear first in the list.
        return list + CsHubSource()
    }

    fun providerFor(key: String): ProviderRef? = providers[key]

    fun providerList(): List<ProviderRef> = providers.values.toList()

    fun isLoaded(internalName: String): Boolean =
        providers.values.any { it.pluginInternalName == internalName }

    fun loadedPluginFor(internalName: String): CsPluginLoader.LoadedPlugin? =
        loadedPlugins.values.firstOrNull { plugin ->
            plugin.providers.any { keyOf(internalName, it.name) in providers }
        }

    // ------------------------------------------------------- install / remove

    /**
     * Downloads and installs a plugin from a repository.
     * @return true on success. Sources are refreshed through a broadcast, no
     *         restart needed.
     */
    suspend fun install(context: Context, plugin: SitePlugin, repositoryUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = pluginFile(context, plugin.internalName, repositoryUrl)
            runCatching {
                CsRepositoryManager.downloadPlugin(context, plugin, target)
                // A previous version may still be loaded in this process: drop it
                // before loading the file we just wrote (update / reinstall).
                unloadFile(target.absolutePath)
                val record = InstalledPlugin(
                    internalName = plugin.internalName,
                    name = plugin.name,
                    url = plugin.url,
                    repositoryUrl = repositoryUrl,
                    filePath = target.absolutePath,
                    version = plugin.version,
                    language = plugin.language,
                    authors = plugin.authors,
                    iconUrl = plugin.iconUrl,
                    installedAt = System.currentTimeMillis(),
                )
                CsPrefs.upsertInstalled(context, record)
                ensureLoaded(context, record)
                CsLog.i("Installed ${plugin.name} v${plugin.version}")
                true
            }.onFailure { CsLog.e("Install failed for ${plugin.name}", it) }.getOrDefault(false)
        }

    /** Installs a `.cs3` file the user copied somewhere on the device. */
    suspend fun installLocal(context: Context, source: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = File(sideloadDir(context), source.name)
                unloadFile(dest.absolutePath)
                source.copyTo(dest, overwrite = true)
                val loaded = CsPluginLoader.load(context, dest)
                val internalName = CsRepositoryManager.sanitizeFileName(dest.nameWithoutExtension)
                val record = InstalledPlugin(
                    internalName = internalName,
                    name = loaded.manifest.name ?: dest.nameWithoutExtension,
                    filePath = dest.absolutePath,
                    version = loaded.manifest.version ?: 0,
                    installedAt = System.currentTimeMillis(),
                    sideloaded = true,
                )
                CsPrefs.upsertInstalled(context, record)
                loadedPlugins[dest.absolutePath] = loaded
                loaded.providers.forEach { provider ->
                    providers[keyOf(internalName, provider.name)] =
                        ProviderRef(internalName, record.name, provider, record)
                }
                CsLog.i("Installed local plugin ${record.name}")
                true
            }.onFailure { CsLog.e("Local install failed", it) }.getOrDefault(false)
        }

    @Synchronized
    fun uninstall(context: Context, internalName: String): Boolean {
        val record = CsPrefs.installed(context).firstOrNull { it.internalName == internalName }
            ?: return false
        val path = record.filePath
        loadedPlugins.remove(path)?.let { CsPluginLoader.unload(it) }
        providers.entries.removeIf { it.value.pluginInternalName == internalName }
        runCatching { File(path).delete() }
        CsPrefs.removeInstalled(context, internalName)
        CsLog.i("Uninstalled ${record.name}")
        return true
    }

    /** Updates every installed plugin that has a newer version in its repository. */
    suspend fun updateAll(context: Context): Int = withContext(Dispatchers.IO) {
        val catalog = catalog(context)
        var updated = 0
        CsPrefs.installed(context).filter { !it.sideloaded && it.repositoryUrl != null }
            .forEach { record ->
                val online = catalog.firstOrNull { it.plugin.internalName == record.internalName }
                    ?: return@forEach
                if (online.plugin.version > record.version) {
                    if (install(context, online.plugin, online.repository.url)) updated++
                }
            }
        updated
    }

    // --------------------------------------------------------------- catalog

    data class CatalogEntry(val repository: RepositoryData, val plugin: SitePlugin)

    /** Fetches every repository and every plugin they advertise. */
    suspend fun catalog(context: Context): List<CatalogEntry> = withContext(Dispatchers.IO) {
        supervisorScope {
            CsPrefs.repositories(context).map { repo ->
                async {
                    val repository = CsRepositoryManager.fetchRepository(repo) ?: return@async emptyList()
                    CsRepositoryManager.fetchPlugins(repository).map { CatalogEntry(repo, it) }
                }
            }.flatMap {
                runCatching { it.await() }
                    .onFailure { CsLog.e("Repository error", it) }
                    .getOrDefault(emptyList())
            }
        }
    }

    // ---------------------------------------------------------------- reload

    /**
     * Asks Aniyomi to reload this extension, which re-creates the source list.
     *
     * Aniyomi registers a (non exported) receiver for
     * `<app package>.ACTION_EXTENSION_REPLACED`; because we run inside the app
     * process we are allowed to send it. This is how a freshly installed
     * Cloudstream plugin shows up without restarting the app.
     */
    fun requestReload(context: Context) {
        runCatching {
            val host = CsContext.hostPackage()
            val intent = Intent("$host.ACTION_EXTENSION_REPLACED").apply {
                data = Uri.parse("package:${CsContext.extensionPackage()}")
                `package` = host
            }
            context.sendBroadcast(intent)
            CsLog.i("Reload requested")
        }.onFailure {
            CsLog.e("Could not request a reload (restart the app manually)", it)
        }
    }

    /** Diagnostics shown in the settings screen. */
    fun status(context: Context): String = buildString {
        appendLine("Plugins chargés : ${loadedPlugins.size}")
        appendLine("Sources (providers) : ${providers.size}")
        appendLine("Dépôts : ${CsPrefs.repositories(context).size}")
        appendLine("Dossier : ${pluginsDir(context).absolutePath}")
    }
}
