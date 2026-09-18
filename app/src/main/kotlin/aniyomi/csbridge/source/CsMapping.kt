package aniyomi.csbridge.source

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorLink
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers

/**
 * Conversions between the Cloudstream model and the Aniyomi model.
 */
object CsMapping {

    /** Flattened Cloudstream episode, ready to become an [SEpisode]. */
    data class CsEpisode(
        val data: String,
        val name: String?,
        val season: Int?,
        val episode: Int?,
        val posterUrl: String?,
        val description: String?,
        val date: Long?,
        val dubLabel: String?,
    )

    // ------------------------------------------------------------ search hits

    fun toSAnime(provider: MainAPI, res: SearchResponse): SAnime = SAnime.create().apply {
        url = provider.fixUrl(res.url)
        title = res.name
        thumbnail_url = res.posterUrl?.let { provider.fixUrl(it) }
        genre = res.type?.let { typeLabel(it) }
        status = SAnime.UNKNOWN
        initialized = false
    }

    // -------------------------------------------------------------- details

    fun toSAnime(provider: MainAPI, load: LoadResponse, base: SAnime? = null): SAnime =
        (base ?: SAnime.create()).apply {
            url = provider.fixUrl(load.url)
            title = load.name
            thumbnail_url = load.posterUrl?.let { provider.fixUrl(it) }
            background_url = load.backgroundPosterUrl?.let { provider.fixUrl(it) }
            author = (load as? AnimeLoadResponse)?.engName
            artist = load.actors?.joinToString(", ") { it.actor.name }
            genre = load.tags?.joinToString(", ")
            status = when ((load as? com.lagradost.cloudstream3.EpisodeResponse)?.showStatus) {
                ShowStatus.Ongoing -> SAnime.ONGOING
                ShowStatus.Completed -> SAnime.COMPLETED
                else -> base?.status ?: SAnime.UNKNOWN
            }
            description = buildDescription(provider, load)
            initialized = true
        }

    private fun buildDescription(provider: MainAPI, load: LoadResponse): String = buildString {
        load.plot?.trim()?.let { appendLine(it); appendLine() }
        val meta = mutableListOf<String>()
        load.year?.let { meta += "Année : $it" }
        load.duration?.let { meta += "Durée : $it min" }
        load.score?.let { meta += "Note : ${"%.1f".format(it.toDouble())}/10" }
        (load as? AnimeLoadResponse)?.showStatus?.let {
            meta += "Statut : ${if (it == ShowStatus.Ongoing) "En cours" else "Terminé"}"
        }
        (load as? AnimeLoadResponse)?.synonyms?.takeIf { it.isNotEmpty() }
            ?.let { meta += "Autres titres : ${it.joinToString(", ")}" }
        meta += "Type : ${typeLabel(load.type)}"
        meta += "Site : ${provider.name}"
        append(meta.joinToString("\n"))
    }

    private fun typeLabel(type: TvType): String = when (type) {
        TvType.Movie -> "Film"
        TvType.AnimeMovie -> "Film d'animation"
        TvType.TvSeries -> "Série"
        TvType.Cartoon -> "Dessin animé"
        TvType.Anime -> "Anime"
        TvType.OVA -> "OVA"
        TvType.Torrent -> "Torrent"
        TvType.Documentary -> "Documentaire"
        TvType.AsianDrama -> "Drama asiatique"
        TvType.Live -> "Direct"
        TvType.NSFW -> "NSFW"
        TvType.Music -> "Musique"
        TvType.AudioBook -> "Livre audio"
        TvType.Audio -> "Audio"
        TvType.Podcast -> "Podcast"
        TvType.Video -> "Vidéo"
        else -> "Autre"
    }

    // -------------------------------------------------------------- episodes

    fun episodesOf(
        provider: MainAPI,
        load: LoadResponse,
        mergeVariants: Boolean = false,
    ): List<CsEpisode> {
        val result = LinkedHashMap<String, CsEpisode>()
        fun add(episode: Episode, dubLabel: String?) {
            val data = episode.data
            if (data.isBlank() || result.containsKey(data)) return
            result[data] = CsEpisode(
                data = data,
                name = episode.name,
                season = episode.season,
                episode = episode.episode,
                posterUrl = episode.posterUrl?.let { runCatching { provider.fixUrl(it) }.getOrNull() },
                description = episode.description,
                date = episode.date,
                dubLabel = dubLabel,
            )
        }

        when (load) {
            is AnimeLoadResponse -> {
                // Several dubs may be available: keep them all but de-duplicate,
                // Aniyomi only shows one list.
                val order = listOf(DubStatus.Subbed, DubStatus.Dubbed, DubStatus.None)
                val map = load.episodes
                order.forEach { status ->
                    map[status]?.forEach { add(it, status.label()) }
                }
                if (result.isEmpty()) {
                    map.values.flatten().forEach { add(it, null) }
                }
            }

            is TvSeriesLoadResponse -> load.episodes.forEach { add(it, null) }

            is MovieLoadResponse -> add(
                @Suppress("DEPRECATION_ERROR")
                Episode(data = load.dataUrl, name = load.name, episode = 1),
                null,
            )

            is LiveStreamLoadResponse -> add(
                @Suppress("DEPRECATION_ERROR")
                Episode(data = load.dataUrl, name = load.name, episode = 1),
                null,
            )
        }
        val episodes = result.values.toList()
        if (!mergeVariants) return episodes

        // One entry per (season, episode): the other language versions become
        // hosters instead of duplicated episodes.
        val groups = LinkedHashMap<String, MutableList<CsEpisode>>()
        episodes.forEach { e ->
            groups.getOrPut("${e.season ?: 1}|${e.episode ?: 0}") { mutableListOf() }.add(e)
        }
        val merged = ArrayList<CsEpisode>(groups.size)
        groups.values.forEach { group ->
            val primary = group.first()
            CsVariants.put(
                primary.data,
                group.map { e -> (e.dubLabel?.takeIf { it.isNotBlank() } ?: "Version") to e.data },
            )
            merged += if (group.size > 1) primary.copy(dubLabel = null) else primary
        }
        return merged
    }

    // -------------------------------------------------------------- seasons

    /** Seasons are second-class SAnime entries: `url#cs3season=N`. */
    private const val SEASON_MARKER = "#cs3season="

    fun seasonUrl(url: String, season: Int): String = baseUrlOf(url) + SEASON_MARKER + season

    fun seasonOf(url: String): Int? =
        url.substringAfter(SEASON_MARKER, "").takeIf { it.isNotBlank() }?.toIntOrNull()

    fun baseUrlOf(url: String): String = url.substringBefore(SEASON_MARKER)

    /** season -> episodes, in the order the provider returned them. */
    fun seasonsOf(provider: MainAPI, load: LoadResponse): LinkedHashMap<Int, List<CsEpisode>> {
        val bySeason = LinkedHashMap<Int, MutableList<CsEpisode>>()
        episodesOf(provider, load).forEach { e ->
            bySeason.getOrPut(e.season ?: 1) { mutableListOf() }.add(e)
        }
        val out = LinkedHashMap<Int, List<CsEpisode>>()
        bySeason.forEach { (season, list) -> out[season] = list }
        return out
    }

    private fun DubStatus.label(): String = when (this) {
        DubStatus.Subbed -> "VOSTFR"
        DubStatus.Dubbed -> "VF"
        DubStatus.None -> ""
    }

    fun toSEpisode(csEpisode: CsEpisode): SEpisode = SEpisode.create().apply {
        url = csEpisode.data
        name = buildName(csEpisode)
        episode_number = csEpisode.episode?.toFloat() ?: -1f
        date_upload = csEpisode.date ?: 0L
        scanlator = listOfNotNull(
            csEpisode.dubLabel?.takeIf { it.isNotBlank() },
            csEpisode.season?.let { "Saison $it" },
        ).joinToString(" • ").takeIf { it.isNotBlank() }
        summary = csEpisode.description
        preview_url = csEpisode.posterUrl
    }

    private fun buildName(e: CsEpisode): String {
        val number = e.episode
        val season = e.season
        // Always "S<season>E<episode>": the host derives the season from the
        // name. With a plain "Épisode 5" for season 1, the whole first season
        // was filed under "Extras" while season 2 showed up correctly.
        val prefix = when {
            number != null && season != null -> "S${season}E$number"
            number != null -> "Épisode $number"
            season != null -> "Saison $season"
            else -> "Épisode"
        }
        val custom = e.name?.takeIf { it.isNotBlank() && it != number.toString() }
        val dub = e.dubLabel?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return if (custom != null) "$prefix — $custom$dub" else "$prefix$dub"
    }

    // ---------------------------------------------------------------- videos

    fun toVideo(link: ExtractorLink, subtitles: List<SubtitleFile>): Video = Video(
        videoUrl = link.url,
        videoTitle = buildVideoTitle(link),
        resolution = link.quality.takeIf { it > 0 },
        headers = buildHeaders(link),
        subtitleTracks = subtitles.map { Track(it.url, it.lang) },
        audioTracks = link.audioTracks.map { Track(it.url, "") },
    )

    private fun buildVideoTitle(link: ExtractorLink): String {
        val quality = qualityLabel(link.quality)
        val type = when (link.type) {
            com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 -> "HLS"
            com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH -> "DASH"
            com.lagradost.cloudstream3.utils.ExtractorLinkType.TORRENT -> "Torrent"
            com.lagradost.cloudstream3.utils.ExtractorLinkType.MAGNET -> "Magnet"
            else -> null
        }
        val server = link.name.takeIf { it.isNotBlank() } ?: link.source
        return listOfNotNull(server.takeIf { it.isNotBlank() }, quality, type)
            .joinToString(" • ")
            .ifBlank { "Lien" }
    }

    private fun qualityLabel(quality: Int): String? = when {
        quality <= 0 -> null
        quality >= 2160 -> "4K"
        quality >= 1440 -> "1440p"
        quality >= 1080 -> "1080p"
        quality >= 720 -> "720p"
        quality >= 480 -> "480p"
        quality >= 360 -> "360p"
        quality >= 240 -> "240p"
        else -> "${quality}p"
    }

    private fun buildHeaders(link: ExtractorLink): Headers? {
        val map = runCatching { link.getAllHeaders() }.getOrNull() ?: emptyMap()
        if (map.isEmpty()) return null
        val builder = Headers.Builder()
        map.forEach { (key, value) ->
            // Drop invalid header names/values, okhttp would throw otherwise
            if (key.isNotBlank() && value.isNotBlank() && key.all { it.code in 33..126 }) {
                runCatching { builder.add(key, value) }
            }
        }
        return runCatching { builder.build() }.getOrNull()
    }
}
