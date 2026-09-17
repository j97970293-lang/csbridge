package aniyomi.csbridge.source

import androidx.preference.PreferenceScreen
import aniyomi.csbridge.ui.CsSettingsScreen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import java.security.MessageDigest

/**
 * Always-present source of the bridge.
 *
 * Aniyomi drops an extension from its lists when `AnimeSourceFactory
 * .createSources()` throws *or* returns nothing (see AnimeExtensionLoader ->
 * AnimeLoadResult.Error). Before any Cloudstream plugin is installed the bridge
 * has no provider to expose, so it would simply never show up - and the user
 * would have no way to reach the settings screen that installs plugins.
 *
 * This source is therefore always returned:
 *   * it makes the extension visible in Browse > Extensions right away ;
 *   * its gear button opens the full bridge settings (repositories, catalog…) ;
 *   * it never performs any network call and never needs a Context, so it can
 *     even be returned when the bridge failed to boot.
 */
class CsHubSource : AnimeCatalogueSource, ConfigurableAnimeSource {

    override val id: Long = generateId("__bridge__")

    override val name: String = "Cloudstream Bridge"

    override val lang: String = "all"

    override val supportsLatest: Boolean = false

    override val supportsRelatedAnime: Boolean = false

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    override suspend fun getPopularAnime(page: Int): AnimesPage = EMPTY_PAGE

    override suspend fun getLatestUpdates(page: Int): AnimesPage = EMPTY_PAGE

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = EMPTY_PAGE

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate = SAnimeEpisodeUpdate(anime = anime, episodes = episodes)

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = SAnimeSeasonUpdate(anime = anime, seasons = seasons)

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = emptyList()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = emptyList()

    // -------------------------------------------------------------------------
    // Explicitly implemented, NOT inherited from an interface default.
    //
    // Kotlin compiles a class that relies on an interface default with an
    // `invokespecial` stub pointing at the interface method. Several hosts
    // (AniZen, Komikku-derived source-api) declare getFilterList / getAnimeDetails
    // / getEpisodeList / getSeasonList as ABSTRACT: the stub then throws
    // AbstractMethodError the moment the app calls it (crash observed on
    // 0.5.213 when opening a source). Real bodies make the class safe on every
    // fork.
    // -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    @Suppress("DEPRECATION")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()

    @Suppress("DEPRECATION")
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    // ------------------------------------------------------------------ legacy
    // RxJava entry points kept abstract by several Aniyomi forks (AniZen,
    // Komikku-derived source-api). Without them ART makes this class abstract
    // and the host drops the extension without any visible error.
    // `override` is deliberately omitted: the official extensions-lib stub
    // (compiled against here) does not declare them, the JVM still matches them
    // to the interface method at runtime.

    @Deprecated("Legacy RxJava API, implemented for host compatibility")
    fun fetchPopularAnime(page: Int): rx.Observable<AnimesPage> = rxObservable { getPopularAnime(page) }

    @Deprecated("Legacy RxJava API, implemented for host compatibility")
    fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): rx.Observable<AnimesPage> =
        rxObservable { getSearchAnime(page, query, filters) }

    @Deprecated("Legacy RxJava API, implemented for host compatibility")
    fun fetchLatestUpdates(page: Int): rx.Observable<AnimesPage> = rxObservable { getLatestUpdates(page) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CsSettingsScreen.setup(screen, null)
    }

    internal companion object {
        val EMPTY_PAGE = AnimesPage(emptyList(), false)

        private fun generateId(key: String): Long {
            val bytes = MessageDigest.getInstance("MD5").digest("cs3:$key".toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.substring(0, 16).toULong(16).toLong()
        }
    }
}
