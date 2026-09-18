package aniyomi.csbridge

import android.content.Context

/**
 * Tiny store for the Cloudflare clearance cookies.
 *
 * `cf_clearance` stays valid for hours, and solving it again on every start is
 * exactly what makes a site look empty. The cookies are kept per host and
 * dropped after [TTL]; the WebView is only used again once they are gone.
 */
object CsCookies {

    private const val KEY = "cf_cookies"
    private const val SEP = "\u001F"
    private const val FIELD = "\u001E"
    private const val PAIR = "\u001D"
    private const val TTL = 6 * 60 * 60 * 1000L // 6 h

    private fun prefs(context: Context) = CsPrefs.prefs(context)

    /** host -> cookies, expired entries dropped. */
    fun load(context: Context): Map<String, Map<String, String>> {
        val raw = runCatching { prefs(context).getString(KEY, "") }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyMap()
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, Map<String, String>>()
        raw.split("\n").forEach { line ->
            val parts = line.split(SEP)
            if (parts.size != 3) return@forEach
            val host = parts[0]
            val at = parts[1].toLongOrNull() ?: return@forEach
            if (now - at > TTL) return@forEach
            val cookies = HashMap<String, String>()
            parts[2].split(FIELD).forEach { pair ->
                val kv = pair.split(PAIR)
                if (kv.size == 2) cookies[kv[0]] = kv[1]
            }
            if (cookies.isNotEmpty()) out[host] = cookies
        }
        return out
    }

    fun save(context: Context, host: String, cookies: Map<String, String>) {
        runCatching {
            val kept = load(context).toMutableMap()
            kept[host] = cookies
            val now = System.currentTimeMillis()
            val raw = kept.entries.joinToString("\n") { (host2, map) ->
                val body = map.entries.joinToString(FIELD) { (k, v) -> "$k$PAIR$v" }
                "$host2$SEP$now$SEP$body"
            }
            prefs(context).edit().putString(KEY, raw).apply()
        }.onFailure { CsLog.w("Cookies Cloudflare non sauvegardés : ${it.message}") }
    }

    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY).apply() }
    }
}
