package aniyomi.csbridge

import aniyomi.csbridge.source.CsMapping
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * End-to-end self test of one site: catalogue -> fiche -> épisodes -> serveurs.
 *
 * Providers swallow their own errors (`runCatching { app.get(...) }`), so a
 * failure usually surfaces as a generic "Fiche inaccessible". This walks the
 * whole chain step by step, times each one and ends with a network probe, which
 * tells whether the site itself answers and whether Cloudflare stands in the way.
 */
object CsDiag {

    suspend fun run(api: MainAPI, stepTimeoutMs: Long = 45_000L): String {
        val out = StringBuilder()
        fun say(line: String) {
            out.appendLine(line)
            CsLog.i("DIAG $line")
        }

        say("=== ${api.name} — ${api.mainUrl} ===")

        // ---------------------------------------------------------- 1. catalog
        val pages: List<com.lagradost.cloudstream3.MainPageData> = try {
            api.mainPage
        } catch (t: Throwable) {
            say("1) mainPage illisible : ${t.message}")
            emptyList()
        }
        say("1) catégories : ${pages.size}")

        val items: List<SearchResponse> = withTimeoutOrNull(stepTimeoutMs) {
            try {
                if (pages.isEmpty()) {
                    say("   aucune page d'accueil -> recherche de secours")
                    val results: List<SearchResponse> = api.search("a") ?: emptyList()
                    results
                } else {
                    val first = pages.first()
                    val page = api.getMainPage(
                        1,
                        MainPageRequest(first.name, first.data, first.horizontalImages),
                    )
                    val list = ArrayList<SearchResponse>()
                    page?.items?.forEach { home -> list.addAll(home.list) }
                    list
                }
            } catch (t: Throwable) {
                say("   échec : ${t::class.java.simpleName}: ${t.message}")
                emptyList<SearchResponse>()
            }
        } ?: run {
            say("   délai dépassé (${stepTimeoutMs} ms)")
            emptyList<SearchResponse>()
        }

        say("2) catalogue : ${items.size} entrée(s)")
        if (items.isEmpty()) {
            probe(api, ::say)
            return out.toString()
        }

        // ------------------------------------------------------------ 2. fiche
        val first = items.first()
        say("   première entrée : ${first.name}")
        val url: String = try {
            api.fixUrl(first.url)
        } catch (t: Throwable) {
            first.url
        }

        val load: LoadResponse? = withTimeoutOrNull(stepTimeoutMs * 2) {
            try {
                api.load(url)
            } catch (t: Throwable) {
                say("   échec : ${t::class.java.simpleName}: ${t.message}")
                CsLog.e("load($url) a échoué", t)
                null
            }
        }
        if (load == null) {
            probe(api, ::say)
            return out.toString()
        }
        say("3) fiche OK : ${load.name}")

        // -------------------------------------------------------- 3. épisodes
        val episodes = try {
            CsMapping.episodesOf(api, load)
        } catch (t: Throwable) {
            say("   épisodes illisibles : ${t.message}")
            emptyList()
        }
        val seasons = try {
            CsMapping.seasonsOf(api, load)
        } catch (t: Throwable) {
            linkedMapOf<Int, List<aniyomi.csbridge.source.CsMapping.CsEpisode>>()
        }
        say("4) épisodes : ${episodes.size} — saisons : ${seasons.keys.joinToString()}")
        if (episodes.isEmpty()) {
            probe(api, ::say)
            return out.toString()
        }

        // --------------------------------------------------------- 4. serveurs
        val episode = episodes.first()
        val links: MutableList<ExtractorLink> = Collections.synchronizedList(mutableListOf())
        withTimeoutOrNull(stepTimeoutMs) {
            try {
                api.loadLinks(
                    episode.data,
                    false,
                    { _: SubtitleFile -> },
                    { link: ExtractorLink -> links.add(link) },
                )
            } catch (t: Throwable) {
                say("   échec : ${t::class.java.simpleName}: ${t.message}")
                CsLog.e("loadLinks(${episode.data}) a échoué", t)
            }
        }
        val names = links.take(6).joinToString { it.name.ifBlank { it.source } }
        say("5) serveurs : ${links.size} lien(s)${if (names.isNotBlank()) " — $names" else ""}")

        probe(api, ::say)
        return out.toString()
    }

    /** Does the site answer at all, and is Cloudflare in front of it? */
    private fun probe(api: MainAPI, say: (String) -> Unit) {
        val target = api.mainUrl
        try {
            val started = System.currentTimeMillis()
            app.baseClient.newCall(okhttp3.Request.Builder().url(target).head().build())
                .execute().use { response ->
                    say(
                        "réseau : HEAD $target -> ${response.code} " +
                            "(${System.currentTimeMillis() - started} ms, " +
                            "server=${response.header("Server") ?: "?"})",
                    )
                }
        } catch (t: Throwable) {
            say("réseau : HEAD $target impossible — ${t::class.java.simpleName}: ${t.message}")
        }
    }
}
