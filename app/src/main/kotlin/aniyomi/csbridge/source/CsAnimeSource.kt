package aniyomi.csbridge.source

import android.content.Context
import aniyomi.csbridge.CsBridge
import aniyomi.csbridge.CsLog
import aniyomi.csbridge.CsPrefs
import aniyomi.csbridge.ui.CsSettingsScreen
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import androidx.preference.PreferenceScreen
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
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.Collections

/**
 * One Aniyomi source == one Cloudstream provider (one site), like in Cloudstream.
 *
 * Everything is delegated to the provider: catalog (`getMainPage`), search,
 * details (`load`) and servers (`loadLinks` -> one [Hoster] per server).
 */
class CsAnimeSource internal constructor(
    internal val ref: CsBridge.ProviderRef,
    private val contextProvider: () -> Context,
) : AnimeCatalogueSource, ConfigurableAnimeSource {

    private val context: Context get() = contextProvider()

    private val providerKey: String = ref.key

    override val id: Long = generateId(providerKey)

    override val name: String = ref.provider.name

    override val lang: String = normalizeLang(ref.provider.lang)

    override val supportsLatest: Boolean = ref.provider.hasMainPage

    override val supportsRelatedAnime: Boolean = true

    // --------------------------------------------------------------- provider

    private fun provider(): MainAPI = CsBridge.providerFor(providerKey)?.provider ?: run {
        ref.record?.let { runCatching { CsBridge.ensureLoaded(context, it) } }
        CsBridge.providerFor(providerKey)?.provider
            ?: throw Exception(
                "Le plugin Cloudstream « ${ref.pluginName} » n'est pas chargé. " +
                    "Ouvrez les réglages de cette source pour voir l'erreur.",
            )
    }

    private suspend fun <T> withProvider(block: suspend (MainAPI) -> T): T {
        val api = provider()
        return try {
            block(api)
        } catch (t: Throwable) {
            CsLog.e("${api.name} failed", t)
            throw t as? Exception ?: Exception("${api.name}: ${t.message}", t)
        }
    }

    // ---------------------------------------------------------------- catalog

    override suspend fun getPopularAnime(page: Int): AnimesPage = withProvider { api ->
        if (api.hasMainPage) {
            mainPage(api, page, categoryFilter = null)
        } else {
            // Providers without a home page: fall back on an empty search.
            searchInternal(api, "", page)
        }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = withProvider { api ->
        if (!api.hasMainPage) return@withProvider AnimesPage(emptyList(), false)
        val latest = api.mainPage.firstOrNull { it.name.isLatestCategory() }
            ?: api.mainPage.firstOrNull()
            ?: MainPageData("", "", false)
        mainPage(api, page, categoryFilter = latest)
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withProvider { api ->
        if (query.isBlank()) {
            if (api.hasMainPage) mainPage(api, page, null) else AnimesPage(emptyList(), false)
        } else if (api.hasQuickSearch && page == 1) {
            val results = runCatching { api.quickSearch(query) }.getOrNull()
            if (results != null) {
                toAnimesPage(api, results, false)
            } else {
                searchInternal(api, query, page)
            }
        } else {
            searchInternal(api, query, page)
        }
    }

    private suspend fun mainPage(
        api: MainAPI,
        page: Int,
        categoryFilter: MainPageData?,
    ): AnimesPage {
        val categories = api.mainPage.ifEmpty { listOf(MainPageData("", "", false)) }
        val wanted = listOfNotNull(categoryFilter).ifEmpty { categories }
        val isFullPage = categoryFilter == null

        val merged = LinkedHashMap<String, SAnime>()
        var hasNext = false
        wanted.forEach { data ->
            val response = runCatching {
                api.getMainPage(page, MainPageRequest(data.name, data.data, data.horizontalImages))
            }.onFailure { CsLog.w("getMainPage(${data.name}) failed: ${it.message}") }.getOrNull()
                ?: return@forEach

            response.items.forEach { list ->
                list.list.forEach { item ->
                    val anime = CsMapping.toSAnime(api, item)
                    merged.putIfAbsent(anime.url, anime)
                }
            }
            hasNext = hasNext || response.hasNext
        }
        if (merged.isEmpty() && isFullPage) {
            // Some providers only implement search()
            return searchInternal(api, "", page)
        }
        return AnimesPage(merged.values.toList(), hasNext)
    }

    private suspend fun searchInternal(api: MainAPI, query: String, page: Int): AnimesPage {
        val paged = runCatching { api.search(query, page) }
            .onFailure { CsLog.w("search($query, $page) failed: ${it.message}") }
            .getOrNull()
        if (paged != null) return toAnimesPage(api, paged.items, paged.hasNext)

        val plain = runCatching { api.search(query) }
            .onFailure { CsLog.w("search($query) failed: ${it.message}") }
            .getOrNull()
        return toAnimesPage(api, plain.orEmpty(), false)
    }

    private fun toAnimesPage(api: MainAPI, items: List<SearchResponse>?, hasNext: Boolean): AnimesPage {
        val list = LinkedHashMap<String, SAnime>()
        items.orEmpty().forEach { item ->
            runCatching {
                val anime = CsMapping.toSAnime(api, item)
                list.putIfAbsent(anime.url, anime)
            }.onFailure { CsLog.w("Skipping search result: ${it.message}") }
        }
        return AnimesPage(list.values.toList(), hasNext)
    }

    private fun String.isLatestCategory(): Boolean {
        val value = lowercase()
        return latestKeywords.any { value.contains(it) }
    }

    // ---------------------------------------------------------------- details

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate = withProvider { api ->
        if (!fetchDetails && !fetchEpisodes) return@withProvider SAnimeEpisodeUpdate(anime, episodes)

        val load = api.load(anime.url)
            ?: throw Exception("« ${anime.title} » est introuvable sur ${api.name}")

        val updated = if (fetchDetails) CsMapping.toSAnime(api, load, anime) else anime
        val newEpisodes = if (fetchEpisodes) episodesOf(api, load) else episodes
        SAnimeEpisodeUpdate(updated, newEpisodes)
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = withProvider { api ->
        // Cloudstream has no season concept, so there is never a season list.
        if (!fetchDetails) return@withProvider SAnimeSeasonUpdate(anime, seasons)
        val load = api.load(anime.url) ?: return@withProvider SAnimeSeasonUpdate(anime, seasons)
        SAnimeSeasonUpdate(CsMapping.toSAnime(api, load, anime), seasons)
    }

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> =
        withProvider { api ->
            val load = runCatching { api.load(anime.url) }.getOrNull()
            val recommendations = load?.recommendations
                ?.mapNotNull { runCatching { CsMapping.toSAnime(api, it) }.getOrNull() }
                .orEmpty()
            if (recommendations.isEmpty()) {
                emptyList<AnimeRelation>()
            } else {
                listOf(AnimeRelation("Recommandations", recommendations))
            }
        }

    private fun episodesOf(api: MainAPI, load: LoadResponse): List<SEpisode> =
        CsMapping.episodesOf(api, load).map { CsMapping.toSEpisode(it) }

    // ---------------------------------------------------------------- servers

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = withProvider { api ->
        val links: MutableList<ExtractorLink> = Collections.synchronizedList(mutableListOf())
        val subtitles: MutableList<SubtitleFile> = Collections.synchronizedList(mutableListOf())

        val timeout = CsPrefs.linksTimeoutMs(context)
        val ok = withTimeoutOrNull(timeout) {
            runCatching {
                api.loadLinks(
                    episode.url,
                    false,
                    { sub -> subtitles.add(sub) },
                    { link -> links.add(link) },
                )
            }.onFailure { t ->
                // Logged with the stack trace: without it a provider whose
                // loadLinks dies (missing extractor, crypto, blocked host) just
                // looks like "no server" and is impossible to diagnose.
                CsLog.e("loadLinks failed on ${api.name} for ${episode.name} (data=${episode.url})", t)
            }.getOrNull()
        }
        if (ok == null) CsLog.w("loadLinks timed out after ${timeout}ms on ${api.name}")
        CsLog.i("loadLinks ${api.name}: ${links.size} link(s), ${subtitles.size} subtitle(s)")
        if (links.isEmpty()) {
            throw Exception("Aucun serveur n'a été trouvé pour « ${episode.name} »")
        }

        val subs = subtitles.toList()
        val playable = links.filter {
            it.type != ExtractorLinkType.TORRENT && it.type != ExtractorLinkType.MAGNET
        }.ifEmpty { links }

        // One hoster per server, like the Cloudstream "server" list.
        val grouped = LinkedHashMap<String, MutableList<ExtractorLink>>()
        playable.forEach { link ->
            val name = link.name.takeIf { it.isNotBlank() }
                ?: link.source.takeIf { it.isNotBlank() }
                ?: "Serveur"
            grouped.getOrPut(name) { mutableListOf() }.add(link)
        }

        grouped.entries.mapIndexed { index, (serverName, group) ->
            val videos = group
                .sortedByDescending { it.quality }
                .mapIndexed { i, link ->
                    CsMapping.toVideo(link, subs).let { video ->
                        if (index == 0 && i == 0) preferred(video) else video
                    }
                }
            Hoster(
                hosterUrl = episode.url,
                hosterName = if (group.size > 1) "$serverName (${group.size})" else serverName,
                videoList = videos,
                internalData = episode.url,
                lazy = false,
            )
        }
    }

    @Suppress("DEPRECATION_ERROR")
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        hoster.videoList?.takeIf { it.isNotEmpty() }?.let { return it }

        // The hoster lost its videos (process restart, serialization...):
        // re-extract and return the matching server.
        val data = hoster.internalData.takeIf { it.isNotBlank() } ?: hoster.hosterUrl
        if (data.isBlank()) return emptyList()
        val episode = SEpisode.create().apply {
            url = data
            name = hoster.hosterName
        }
        return getHosterList(episode)
            .firstOrNull { it.hosterName == hoster.hosterName }
            ?.videoList
            .orEmpty()
    }

    private fun preferred(video: Video): Video = Video(
        videoUrl = video.videoUrl,
        videoTitle = video.videoTitle,
        resolution = video.resolution,
        bitrate = video.bitrate,
        headers = video.headers,
        preferred = true,
        subtitleTracks = video.subtitleTracks,
        audioTracks = video.audioTracks,
    )

    // ---------------------------------------------------------------- prefs

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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

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
        CsSettingsScreen.setup(screen, this)
    }

    // ---------------------------------------------------------------- helpers

    internal val mainUrl: String get() = runCatching { provider().mainUrl }.getOrDefault("")

    internal val pluginInternalName: String get() = ref.pluginInternalName

    internal val providerInstance: MainAPI? get() = runCatching { CsBridge.providerFor(providerKey)?.provider }.getOrNull()

    private fun normalizeLang(lang: String): String {
        val cleaned = lang.trim().lowercase()
        if (cleaned.isBlank()) return "all"
        // Aniyomi expects a 2 letter code (optionally with a region)
        return cleaned.replace('_', '-').take(8)
    }

    private fun generateId(key: String): Long {
        // Stable across restarts & updates: it is only derived from the plugin
        // and provider names, never from an index or a hash of the whole file.
        val bytes = MessageDigest.getInstance("MD5").digest("cs3:$key".toByteArray())
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 16).toULong(16).toLong()
    }

    companion object {
        private val latestKeywords = listOf(
            "latest", "recent", "newest", "new release", "just added",
            "nouveau", "nouveaut", "récent", "recent", "ajout",
            "last update", "lastest", "updated",
        )
    }
}
