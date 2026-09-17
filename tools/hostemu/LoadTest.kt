import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Reproduces AniZen/Aniyomi's ChildFirstPathClassLoader load path on the JVM:
 *   system class loader (== the host app)  ->  our dex  ->  parent.
 */
class ChildFirst(urls: Array<URL>, parent: ClassLoader?) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }
        try {
            getSystemClassLoader()?.loadClass(name)?.let { return it }
        } catch (_: ClassNotFoundException) {
        }
        return try {
            findClass(name)
        } catch (_: ClassNotFoundException) {
            super.loadClass(name, resolve)
        }
    }
}

fun main(args: Array<String>) {
    val dir = args[0]
    val loader = ChildFirst(arrayOf(File(dir).toURI().toURL()), ClassLoader.getSystemClassLoader())

    val factoryCls = loader.loadClass("aniyomi.csbridge.CloudstreamBridgeFactory")
    println("loaded  : " + factoryCls.name + " (loader=" + factoryCls.classLoader + ")")

    val animeSource = Class.forName("eu.kanade.tachiyomi.animesource.AnimeSource")
    val animeFactory = Class.forName("eu.kanade.tachiyomi.animesource.AnimeSourceFactory")
    println("host iface: " + animeSource.name + " (loader=" + animeSource.classLoader + ")")
    println("  abstract members of AnimeCatalogueSource:")
    Class.forName("eu.kanade.tachiyomi.animesource.AnimeCatalogueSource").methods
        .filter { it.isDefault.not() }
        .forEach { println("    - " + it.name + "(" + it.parameterTypes.joinToString { it.simpleName } + ")") }

    // Cryptography provider: without one, every Cloudstream plugin registering
    // an extractor fails with ExceptionInInitializerError.
    runCatching {
        val crypto = loader.loadClass("aniyomi.csbridge.CsCrypto")
        crypto.methods.first { it.name == "ensure" && it.parameterTypes.isEmpty() }
            .invoke(crypto.getField("INSTANCE").get(null))
        val provider = Class.forName("dev.whyoleg.cryptography.CryptographyProvider")
            .getField("Companion").get(null)
        val name = provider.javaClass.methods.first { it.name == "getDefault" }.invoke(provider)
        println("cryptography provider: " + (name?.javaClass?.methods
            ?.firstOrNull { it.name == "getName" }?.invoke(name) ?: "NONE"))
    }.onFailure { println("cryptography: " + it) }

    // Cloudstream's extractor registry: its static initialiser needs a working
    // cryptography provider. If it fails, every plugin calling
    // registerExtractorAPI() dies -> no catalog, no servers.
    runCatching {
        val kt = Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt")
        val list = kt.methods.first { it.name == "getExtractorApis" }.invoke(null) as List<*>
        println("extractor APIs registered: " + list.size)
    }.onFailure {
        println("extractor APIs: FAILED -> " + it::class.java.simpleName + ": " + it.message)
        var cause = it.cause
        while (cause != null) { println("    caused by: $cause"); cause = cause.cause }
    }

    val instance = factoryCls.getDeclaredConstructor().newInstance()
    println("instantiated OK, isSourceFactory=" + animeFactory.isInstance(instance))

    val create = factoryCls.methods.first { it.name == "createSources" && it.parameterTypes.isEmpty() }
    val sources = create.invoke(instance) as List<Any?>
    println("createSources -> " + sources.size + " source(s)")
    sources.forEach { s ->
        val name = runCatching { s!!.javaClass.methods.first { it.name == "getName" }.invoke(s) }.getOrNull()
        val lang = runCatching { s!!.javaClass.methods.first { it.name == "getLang" }.invoke(s) }.getOrNull()
        println("   * " + s?.javaClass?.name + " name=$name lang=$lang isAnimeSource=" + animeSource.isInstance(s))
    }
    if (sources.isEmpty()) {
        println("FAIL: no source returned - the host would drop the extension")
        kotlin.system.exitProcess(1)
    }
    println("HOST EMULATION OK")
}
