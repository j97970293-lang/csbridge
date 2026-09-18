package aniyomi.csbridge.source

import android.content.Context
import aniyomi.csbridge.CsBridge
import aniyomi.csbridge.CsLog
import aniyomi.csbridge.CsPrefs
import aniyomi.csbridge.ui.CsSettingsScreen
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.app
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
import java.util.concurrent.ConcurrentHashMap

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

    private data class CachedLoad(val at: Long, val load: LoadResponse)

    private val responseCache = ConcurrentHashMap<String, CachedLoad>()

    /**
     * `load()` is expensive (some providers fire dozens of requests) and the
     * host asks for the details, the episodes and the seasons one after the
     * other: the response is kept for three minutes.
     */
    private suspend fun loadCached(api: MainAPI, url: String): LoadResponse? {
        val key = CsMapping.baseUrlOf(url)
        responseCache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.at < RESPONSE_TTL) return entry.load
            responseCache.remove(key)
        }
        val load = runCatching { api.load(key) }
            .onFailure { t ->
                CsLog.w("load($key) failed: ${t::class.java.simpleName}: ${t.message}")
                probe(api, key)
            }
            .getOrNull() ?: return null
        if (responseCache.size > 24) responseCache.clear()
        responseCache[key] = CachedLoad(System.currentTimeMillis(), load)
        return load
    }

    /**
     * Providers usually swallow the real cause (`runCatching { app.get(...) }`
     * then a generic `ErrorLoadingException`). This probe says whether the site
     * answers at all, and whether Cloudflare stands in the way.
     */
    private fun probe(api: MainAPI, url: String) {
        val target = runCatching { java.net.URI(url) }.getOrNull()
            ?.let { "${it.scheme}://${it.host}" } ?: api.mainUrl
        runCatching {
            val started = System.currentTimeMillis()
            app.baseClient.newCall(okhttp3.Request.Builder().url(target).head().build())
                .execute().use { response ->
                    CsLog.w(
                        "probe $target -> ${response.code} " +
                            "(${System.currentTimeMillis() - started} ms, " +
                            "server=${response.header("Server") ?: "?"})",
                    )
                }
        }.onFailure {
            CsLog.w("probe $target impossible: ${it::class.java.simpleName}: ${it.message}")
        }
    }

    private fun detailsOf(api: MainAPI, load: LoadResponse?, anime: SAnime): SAnime {
        if (load == null) return anime
        val updated = CsMapping.toSAnime(api, load, anime)
        // toSAnime rewrites the url: keep the "#cs3season=" marker, otherwise
        // the host forgets which season it opened.
        if (CsMapping.seasonOf(anime.url) != null) updated.url = anime.url
        return updated
    }

    private fun seasonsOf(api: MainAPI, load: LoadResponse, anime: SAnime): List<SAnime> {
        val bySeason = CsMapping.seasonsOf(api, load)
        if (bySeason.size <= 1) return emptyList()
        val base = CsMapping.baseUrlOf(anime.url)
        return bySeason.entries.map { (season, episodes) ->
            SAnime.create().apply {
                url = CsMapping.seasonUrl(base, season)
                title = "Saison $season"
                thumbnail_url = anime.thumbnail_url
                description = anime.description
                author = anime.author
                artist = anime.artist
                genre = anime.genre
                status = anime.status
                initialized = true
            }.also { CsLog.i("${api.name}: saison $season (${episodes.size} épisode(s))") }
        }
    }

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate = withProvider { api ->
        if (!fetchDetails && !fetchEpisodes) return@withProvider SAnimeEpisodeUpdate(anime, episodes)

        val load = loadCached(api, anime.url)
            ?: throw Exception("« ${anime.title} » est introuvable sur ${api.name}")

        val updated = if (fetchDetails) detailsOf(api, load, anime) else anime
        val newEpisodes = if (fetchEpisodes) episodesOf(api, load, anime.url) else episodes
        SAnimeEpisodeUpdate(updated, newEpisodes)
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = withProvider { api ->
        if (!fetchDetails && !fetchSeasons) return@withProvider SAnimeSeasonUpdate(anime, seasons)
        val load = loadCached(api, anime.url)
        val updated = if (fetchDetails) detailsOf(api, load, anime) else anime
        val newSeasons = if (fetchSeasons && load != null) seasonsOf(api, load, anime) else seasons
        SAnimeSeasonUpdate(updated, newSeasons)
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

    private fun episodesOf(api: MainAPI, load: LoadResponse, url: String): List<SEpisode> {
        val all = CsMapping.episodesOf(api, load, CsPrefs.mergeVariants(context))
        val season = CsMapping.seasonOf(url)
        val filtered = if (season == null) all else all.filter { (it.season ?: 1) == season }
        CsLog.i(
            "${api.name}: ${all.size} épisode(s)" +
                (if (season != null) ", saison $season -> ${filtered.size}" else ""),
        )
        return filtered.map { CsMapping.toSEpisode(it) }
    }

    // ---------------------------------------------------------------- servers

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = withProvider { api ->
        // In "merge" mode one episode carries several versions (VF / VOSTFR):
        // each of them becomes its own set of hosters.
        val variants = CsVariants.of(episode.url).ifEmpty { listOf(null to episode.url) }
        val budget = (CsPrefs.linksTimeoutMs(context) / variants.size).coerceAtLeast(20_000L)
        val hosters = ArrayList<Hoster>()
        variants.forEach { (label, data) ->
            hosters += hostersFor(api, episode, data, label, budget)
        }
        if (hosters.isEmpty()) {
            throw Exception("Aucun serveur n'a été trouvé pour « ${episode.name} »")
        }
        hosters
    }

    private suspend fun hostersFor(
        api: MainAPI,
        episode: SEpisode,
        data: String,
        label: String?,
        timeout: Long,
    ): List<Hoster> {
        val links: MutableList<ExtractorLink> = Collections.synchronizedList(mutableListOf())
        val subtitles: MutableList<SubtitleFile> = Collections.synchronizedList(mutableListOf())

        val ok = withTimeoutOrNull(timeout) {
            runCatching {
                api.loadLinks(
                    data,
                    false,
                    { sub -> subtitles.add(sub) },
                    { link -> links.add(link) },
                )
            }.onFailure { t ->
                // Logged with the stack trace: without it a provider whose
                // loadLinks dies (missing extractor, crypto, blocked host) just
                // looks like "no server" and is impossible to diagnose.
                CsLog.e(
                    "loadLinks failed on ${api.name} for ${episode.name} (data=$data)",
                    t,
                )
            }.getOrNull()
        }
        if (ok == null) CsLog.w("loadLinks timed out after ${timeout}ms on ${api.name}")
        CsLog.i("loadLinks ${api.name}: ${links.size} link(s), ${subtitles.size} subtitle(s)")
        if (links.isEmpty()) return emptyList()

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

        val suffix = label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return grouped.entries.mapIndexed { index, (serverName, group) ->
            val videos = group
                .sortedByDescending { it.quality }
                .mapIndexed { i, link ->
                    CsMapping.toVideo(link, subs).let { video ->
                        if (index == 0 && i == 0) preferred(video) else video
                    }
                }
            Hoster(
                hosterUrl = data,
                hosterName = (if (group.size > 1) "$serverName (${group.size})" else serverName) + suffix,
                videoList = videos,
                internalData = data,
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
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withProvider { api ->
        detailsOf(api, loadCached(api, anime.url), anime)
    }

    @Suppress("DEPRECATION")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withProvider { api ->
        val load = loadCached(api, anime.url) ?: return@withProvider emptyList<SEpisode>()
        episodesOf(api, load, anime.url)
    }

    // Cloudstream has no season entity, but its episodes carry one: each season
    // becomes a second-class SAnime, which is exactly what this hook is for.
    @Suppress("DEPRECATION")
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = withProvider { api ->
        if (!CsPrefs.seasonsEnabled(context)) return@withProvider emptyList<SAnime>()
        if (CsMapping.seasonOf(anime.url) != null) return@withProvider emptyList<SAnime>()
        val load = loadCached(api, anime.url) ?: return@withProvider emptyList<SAnime>()
        seasonsOf(api, load, anime)
    }

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
        /** LoadResponse cache lifetime (ms): details, episodes and seasons. */
        private const val RESPONSE_TTL = 180_000L

        private val latestKeywords = listOf(
            "latest", "recent", "newest", "new release", "just added",
            "nouveau", "nouveaut", "récent", "recent", "ajout",
            "last update", "lastest", "updated",
        )
    }
}
