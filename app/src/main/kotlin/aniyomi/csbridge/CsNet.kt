package aniyomi.csbridge

import android.content.Context
import com.lagradost.cloudstream3.app
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Network journal.
 *
 * Every Cloudstream plugin goes through `app.baseClient`. Adding one
 * interceptor there logs every request made by every plugin -- the only way to
 * see what really happens when a provider swallows its own exception.
 *
 * Failures are always logged; successful requests only when "Journal réseau" is
 * enabled (a single AnimeSama page fires dozens of them).
 */
object CsNet {

    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val client = app.baseClient
            val logging = object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val request = chain.request()
                    val started = System.currentTimeMillis()
                    return try {
                        val response = chain.proceed(request)
                        val ms = System.currentTimeMillis() - started
                        val line = "HTTP ${response.code} ${request.url} (${ms} ms)"
                        if (CsPrefs.networkLog(context) || response.code >= 400) {
                            if (response.code >= 400) CsLog.w(line) else CsLog.d(line)
                        }
                        response
                    } catch (t: Throwable) {
                        CsLog.w(
                            "HTTP échec ${request.url} — ${t::class.java.simpleName}: ${t.message}",
                        )
                        throw t
                    }
                }
            }
            val withLog = client.newBuilder().addInterceptor(logging).build()

            // `baseClient` is a var on the Cloudstream object; the setter is
            // resolved reflectively so that a host shipping a val only loses
            // the journal instead of crashing.
            val setter = app.javaClass.methods.firstOrNull {
                it.name == "setBaseClient" && it.parameterCount == 1
            }
            if (setter == null) {
                CsLog.w("Journal réseau indisponible (baseClient non modifiable)")
                return
            }
            setter.invoke(app, withLog)
            CsLog.i("Journal réseau installé")
        }.onFailure { CsLog.w("Journal réseau : ${it::class.java.simpleName}: ${it.message}") }
    }
}
