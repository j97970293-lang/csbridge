package aniyomi.csbridge

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Talks to Cloudstream repositories.
 *
 * The repository format is 100% compatible with Cloudstream:
 *  - `<repo url>`            -> `Repository`       (iconUrl, name, pluginLists...)
 *  - each entry of pluginLists -> `Array<SitePlugin>`
 */
object CsRepositoryManager {

    /** Timeout in seconds for the metadata requests. */
    private const val TIMEOUT = 60L

    /** Human readable reason of the last failure, shown in the catalog. */
    @Volatile
    var lastError: String? = null
        private set

    private fun readable(t: Throwable): String = when (t) {
        is java.io.InterruptedIOException ->
            "Delai depasse (${TIMEOUT}s) : le serveur ne repond pas. Reessaie plus tard."
        is java.net.UnknownHostException ->
            "Nom de domaine inconnu (pas de reseau ?)."
        is kotlinx.serialization.SerializationException ->
            "JSON invalide : ${t.message?.take(160)}"
        else -> t.message?.take(220) ?: t.toString()
    }

    fun clearError() { lastError = null }

    /**
     * Downloads a repository / plugin-list document.
     *
     * Slow or flaky connections are common: the request is retried, and a
     * raw.githubusercontent.com URL is automatically retried through the
     * jsDelivr CDN. HTML error pages (502 from paste hosts...) are detected and
     * reported as such instead of raising a cryptic JSON error.
     */
    private suspend fun fetchDocument(url: String): String {
        val candidates = buildList {
            add(url)
            val proxied = applyCdnProxy(url, true)
            if (proxied != url) add(proxied)
            add(url) // last chance on the direct URL
        }
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val response = app.get(
                    candidate,
                    cacheTime = 5,
                    cacheUnit = TimeUnit.MINUTES,
                    timeout = TIMEOUT,
                )
                val code = response.okhttpResponse.code
                val body = response.text
                val head = body.trimStart()
                if (code >= 400) throw IllegalStateException("HTTP $code sur $candidate")
                if (!head.startsWith("{") && !head.startsWith("[")) {
                    val excerpt = head.take(90).replace('\n', ' ')
                    throw IllegalStateException(
                        "Reponse non-JSON (HTTP $code) : \u00ab $excerpt\u2026 \u00bb " +
                            "- le serveur a renvoye une page d'erreur, pas les metadonnees",
                    )
                }
                return body
            } catch (t: Throwable) {
                failure = t
                CsLog.w("Fetch failed for $candidate: ${t.message}")
            }
        }
        throw failure ?: IllegalStateException("Echec inconnu pour $url")
    }

    // ------------------------------------------------------------------ urls

    /**
     * Accepts everything Cloudstream accepts:
     *   https://..., cloudstreamrepo://..., https://cs.repo/?...
     */
    fun normalizeRepoUrl(input: String): String {
        val url = input.trim()
        val http = Regex("^https?://")

        // NOTE: the short forms must be tested first, otherwise a
        // "https://cs.repo/?..." link is mistaken for a plain URL.
        // (Cloudstream checks `^https?://` first and therefore never expands them.)
        val short = Regex("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)")
        if (url.contains(short)) {
            val stripped = url.replace(short, "")
            // Some generators percent-encode the payload: https://cs.repo/?https%3A%2F%2F...
            val decoded = if (stripped.contains("%3A%2F%2F", ignoreCase = true)) {
                runCatching { java.net.URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
            } else {
                stripped
            }
            return if (decoded.contains(http)) decoded else "https://$decoded"
        }
        return url
    }

    /** Cloudstream can proxy raw.githubusercontent.com through jsDelivr. */
    fun applyCdnProxy(url: String, useProxy: Boolean): String {
        if (!useProxy) return url
        val match = Regex("^https://raw.githubusercontent.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/(.*)$")
            .find(url) ?: return url
        val (user, repo, rest) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$rest"
    }

    // ------------------------------------------------------------------ http

    suspend fun fetchRepository(data: RepositoryData, useProxy: Boolean = false): Repository? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = applyCdnProxy(normalizeRepoUrl(data.url), useProxy)
                val text = fetchDocument(url)
                CsPrefs.json.decodeFromString<Repository>(text)
            }.onFailure {
                lastError = "${data.url}\n${readable(it)}"
                CsLog.e("Failed to read repository ${data.url}", it)
            }.getOrNull()
        }

    /** Fetches every `pluginLists` entry in parallel, like Cloudstream does. */
    suspend fun fetchPlugins(repo: Repository, useProxy: Boolean = false): List<SitePlugin> =
        withContext(Dispatchers.IO) {
            supervisorScope {
                repo.pluginLists.map { listUrl ->
                    async {
                        runCatching {
                            val url = applyCdnProxy(listUrl, useProxy)
                            val text = fetchDocument(url)
                            CsPrefs.json.decodeFromString<List<SitePlugin>>(text)
                        }.onFailure {
                            lastError = "$listUrl\n${readable(it)}"
                            CsLog.e("Failed to read plugin list $listUrl", it)
                        }.getOrDefault(emptyList())
                    }
                }.flatMap { it.await() }.distinctBy { it.url }
            }
        }

    /** Downloads a `.cs3` file and verifies its `sha256-<hex>` hash if provided. */
    suspend fun downloadPlugin(
        context: Context,
        plugin: SitePlugin,
        target: File,
        useProxy: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()

        // Write to a temp file first so a failed download can never corrupt an
        // installed plugin.
        val temp = File.createTempFile(target.name, ".tmp", context.cacheDir)
        try {
            val url = applyCdnProxy(plugin.url, useProxy)
            val response = app.get(url, timeout = 120L)
            response.okhttpResponse.body.byteStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            plugin.fileHash?.let { expected ->
                val actual = sha256(temp)
                if (!expected.equals(actual, ignoreCase = true)) {
                    throw IllegalStateException(
                        "Hash mismatch for '${plugin.name}'!\nexpected: $expected\ngot:      $actual",
                    )
                }
            }

            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            runCatching { target.setReadOnly() }
            target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** `sha256-<hex>`, the format used by Cloudstream repositories. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ file names

    /** Keeps file names safe and unique, mirrors Cloudstream's naming. */
    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(80)
            .ifBlank { "plugin" }
        return "$cleaned.${name.hashCode()}"
    }
}
