package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.csbridge.CsContext
import aniyomi.csbridge.CsLog
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.Requests.Companion.await
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cloudflare challenge solver.
 *
 * Cloudstream keeps this class in its **app** module, not in `library-android`,
 * so it is absent from every published artefact -- yet a lot of plugins (17 of
 * the 26 in `plugin-fr`, for instance) instantiate it and pass it to
 * `app.get(..., interceptor = cfKiller)`. Without it every request of those
 * plugins dies with `NoClassDefFoundError`, which looks like "empty catalog,
 * broken search, no server".
 *
 * This is a faithful re-implementation: it is a plain okhttp [Interceptor] that
 * retries a 403/503 coming from a Cloudflare edge after solving the challenge
 * in a real [WebView] and harvesting the `cf_clearance` cookie.
 */
class CloudflareKiller : Interceptor {

    companion object {
        private const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        /**
         * Plain Chrome UA: the default WebView UA carries a `; wv` marker that
         * Cloudflare rejects, and the clearance cookie is bound to the UA that
         * solved the challenge -- so the very same string is reused for the
         * retried request.
         */
        const val WEBVIEW_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** How long we let the WebView chew on the challenge. */
        @Volatile
        var webViewTimeoutMs: Long = 45_000L

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(";").mapNotNull { part ->
                val split = part.split("=", limit = 2)
                val key = split.getOrNull(0)?.trim().orEmpty()
                val value = split.getOrNull(1)?.trim().orEmpty()
                if (key.isBlank() || value.isBlank()) null else key to value
            }.toMap()
    }

    /** host -> cookies, so the WebView is only used once per domain. */
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** Headers (cookies + WebView UA) for a manual request to [url]. */
    fun getCookieHeaders(url: String): Headers {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        return cfHeaders(mapOf("user-agent" to WEBVIEW_UA), savedCookies[host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val host = request.url.host

        savedCookies[host]?.let { return@runBlocking proceed(request, it) }

        val response = chain.proceed(request)
        if (!isCloudflareError(response)) return@runBlocking response

        response.close()
        CsLog.i("$TAG: Cloudflare challenge on ${request.url} -> solving with WebView")
        if (solveWithWebView(request.url.toString())) {
            val cookies = savedCookies[host]
            if (cookies != null) {
                CsLog.i("$TAG: solved ${request.url}")
                return@runBlocking proceed(request, cookies)
            }
        }
        CsLog.w("$TAG: could not solve ${request.url}")
        chain.proceed(request)
    }

    // ------------------------------------------------------------------ http

    private fun isCloudflareError(response: Response): Boolean =
        response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response =
        app.baseClient.newCall(
            request.newBuilder()
                .headers(cfHeaders(request.headers.toMap() + ("user-agent" to WEBVIEW_UA), cookies))
                .build(),
        ).await()

    private fun cfHeaders(headers: Map<String, String>, cookies: Map<String, String>): Headers {
        val builder = Headers.Builder()
        headers.forEach { (k, v) -> builder.add(k, v) }
        if (cookies.isNotEmpty()) {
            builder.removeAll("Cookie")
            builder.add("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        return builder.build()
    }

    // --------------------------------------------------------------- webview

    private fun clearanceCookie(url: String): String? =
        runCatching {
            CookieManager.getInstance()?.getCookie(url)?.takeIf { it.contains("cf_clearance") }
        }.onFailure { CsLog.w("$TAG: CookieManager error: ${it.message}") }.getOrNull()

    private fun remember(url: String, cookie: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        savedCookies[host] = parseCookieMap(cookie)
    }

    /**
     * Loads the page in a background [WebView] until `cf_clearance` shows up in
     * the CookieManager (or the timeout expires). Must never be called from the
     * main thread: the challenge takes seconds and we block on purpose.
     */
    private fun solveWithWebView(url: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CsLog.w("$TAG: called on the main thread, skipping the WebView")
            return false
        }

        val created = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        var webView: WebView? = null

        Handler(Looper.getMainLooper()).post {
            val view = try {
                WebView(CsContext.get())
            } catch (t: Throwable) {
                CsLog.w("$TAG: WebView unavailable: ${t.message}")
                created.countDown()
                destroyed.countDown()
                return@post
            }
            webView = view
            try {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, true)
                }
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = WEBVIEW_UA
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                view.webViewClient = WebViewClient()
                view.loadUrl(url)
            } catch (t: Throwable) {
                CsLog.w("$TAG: WebView setup failed: ${t.message}")
            } finally {
                created.countDown()
            }
        }

        if (!created.await(15, TimeUnit.SECONDS)) {
            CsLog.w("$TAG: the WebView never started")
            return false
        }

        val deadline = System.currentTimeMillis() + webViewTimeoutMs
        var solved = false
        while (System.currentTimeMillis() < deadline) {
            val cookie = clearanceCookie(url)
            if (cookie != null) {
                remember(url, cookie)
                solved = true
                break
            }
            runCatching { Thread.sleep(500) }
        }

        Handler(Looper.getMainLooper()).post {
            try {
                webView?.apply {
                    stopLoading()
                    webViewClient = WebViewClient()
                    destroy()
                }
            } catch (t: Throwable) {
                CsLog.w("$TAG: WebView cleanup failed: ${t.message}")
            } finally {
                destroyed.countDown()
            }
        }
 destroyed.await(5, TimeUnit.SECONDS)
        return solved
    }
}
