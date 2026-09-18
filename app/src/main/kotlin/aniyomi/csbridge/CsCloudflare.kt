package aniyomi.csbridge

import android.app.AlertDialog
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URI

/**
 * Manual Cloudflare verification.
 *
 * The automatic solver runs a headless WebView, which some challenges refuse.
 * This opens a real WebView in a dialog: the user solves the challenge by hand,
 * presses "Terminé" and the `cf_clearance` cookie is stored for every plugin
 * ([CloudflareKiller.cookieMap]) and kept on disk for a few hours.
 */
object CsCloudflare {

    fun manual(context: Context, url: String) {
        val webView = WebView(context)
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = CloudflareKiller.WEBVIEW_UA
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        } catch (t: Throwable) {
            CsLog.w("WebView: ${t.message}")
        }

        val host = runCatching { URI(url).host }.getOrNull() ?: url
        val dialog = AlertDialog.Builder(context)
            .setTitle("Cloudflare — $host")
            .setMessage(
                "Résolvez le défi affiché ci-dessous (coche, captcha…), " +
                    "attendez que la page se charge, puis appuyez sur « Terminé ».\n\n" +
                    "Le cookie obtenu est réutilisé automatiquement ensuite.",
            )
            .setView(webView)
            .setPositiveButton("Terminé") { d, _ ->
                val saved = harvest(context, url)
                Toast.makeText(
                    context,
                    if (saved) "Cookie Cloudflare enregistré pour $host" else "Aucun cookie cf_clearance trouvé",
                    Toast.LENGTH_LONG,
                ).show()
                runCatching { webView.destroy() }
                d.dismiss()
            }
            .setNegativeButton("Annuler") { d, _ ->
                runCatching { webView.destroy() }
                d.dismiss()
            }
            .create()

        runCatching {
            dialog.show()
            webView.loadUrl(url)
        }.onFailure {
            CsLog.w("Dialogue WebView impossible: ${it.message}")
            Toast.makeText(
                context,
                "Impossible d'ouvrir la WebView : ${it.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Reads the cookies of [url] and shares them with every plugin. */
    fun harvest(context: Context, url: String): Boolean {
        val cookie = runCatching { CookieManager.getInstance()?.getCookie(url) }.getOrNull()
        val host = runCatching { URI(url).host }.getOrNull()
        if (cookie.isNullOrBlank() || host == null) return false
        val map = CloudflareKiller.parseCookieMap(cookie)
        if (!map.containsKey("cf_clearance")) return false
        CloudflareKiller.remember(host, map)
        CsLog.i("Cloudflare: cookie manuel enregistré pour $host (${map.size} cookie(s))")
        return true
    }
}
