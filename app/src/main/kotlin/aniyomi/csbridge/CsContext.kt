package aniyomi.csbridge

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.animeextension.all.csbridge.BuildConfig

/**
 * Extensions are instantiated by Aniyomi with `Class.forName(...).newInstance()`,
 * so we never receive a [Context] through a constructor.
 *
 * `extensions-lib` v17 calls `AnimeSourceFactory.createSources()` without any
 * argument, hence this small helper: several fallbacks are tried and the result
 * is cached for the lifetime of the process.
 */
object CsContext {

    @Volatile
    private var cached: Context? = null

    fun set(context: Context) {
        cached = context.applicationContext ?: context
    }

    fun get(): Context {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return@synchronized it }
            val resolved = resolve() ?: error(
                "CSBridge: unable to obtain a Context. This should never happen " +
                    "while running inside Aniyomi.",
            )
            cached = resolved
            resolved
        }
    }

    fun application(): Application = get().applicationContext as Application

    /** Package name of the *host app* (not of this extension APK). */
    fun hostPackage(): String = get().packageName

    /** Package name of this extension APK, used to ask the app to reload us. */
    fun extensionPackage(): String = BuildConfig.APPLICATION_ID

    private fun resolve(): Context? {
        // 1. ActivityThread.currentApplication() -> the Application of the host app
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getDeclaredMethod("currentApplication").apply {
                isAccessible = true
            }
            (method.invoke(null) as? Application)?.let { return it }
        }.onFailure { CsLog.w("ActivityThread lookup failed: ${it.message}") }

        // 2. AppGlobals.getInitialApplication()
        runCatching {
            val appGlobals = Class.forName("android.app.AppGlobals")
            val method = appGlobals.getDeclaredMethod("getInitialApplication").apply {
                isAccessible = true
            }
            (method.invoke(null) as? Application)?.let { return it }
        }.onFailure { CsLog.w("AppGlobals lookup failed: ${it.message}") }

        CsLog.e("No Context could be resolved")
        return null
    }
}
