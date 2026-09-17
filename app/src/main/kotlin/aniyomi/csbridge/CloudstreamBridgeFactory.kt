package aniyomi.csbridge

import android.content.Context
import aniyomi.csbridge.source.CsHubSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

/**
 * Entry point declared in the manifest (`tachiyomi.animeextension.class`).
 *
 * Aniyomi instantiates this class and calls [createSources]; we return one
 * [AnimeSource] per installed Cloudstream plugin provider, i.e. one catalog per
 * site, exactly like Cloudstream.
 */
@Suppress("unused")
class CloudstreamBridgeFactory : AnimeSourceFactory {

    /**
     * extensions-lib >= 16 (no argument).
     *
     * IMPORTANT: Aniyomi hides any extension whose factory throws or returns an
     * empty list (AnimeExtensionLoader -> AnimeLoadResult.Error), so this must
     * never fail - the hub source is always returned as a last resort.
     */
    override fun createSources(): List<AnimeSource> = try {
        CsBridge.bootstrap(CsContext.get())
    } catch (t: Throwable) {
        CsLog.e("createSources failed", t)
        listOf(CsHubSource())
    }

    /** Legacy signature used by older versions of Aniyomi. */
    fun createSources(context: Context): List<AnimeSource> = try {
        CsBridge.bootstrap(context)
    } catch (t: Throwable) {
        CsLog.e("createSources(context) failed", t)
        listOf(CsHubSource())
    }
}
