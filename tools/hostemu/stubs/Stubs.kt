// Minimal stand-ins for the host classes, compiled for JVM 11 so the load test
// can run on the sandbox JDK. Only names/signatures matter here (parameter
// names are kept identical to the real models because the bridge uses named
// arguments).
package eu.kanade.tachiyomi.animesource.model

open class SAnime {
    companion object { fun create(): SAnime = SAnime() }
}
open class SEpisode {
    companion object { fun create(): SEpisode = SEpisode() }
}
open class Video
open class Hoster
open class AnimesPage(val animes: List<SAnime>, val hasNextPage: Boolean)
open class AnimeFilterList
open class AnimeRelation
open class SAnimeEpisodeUpdate(val anime: SAnime, val episodes: List<SEpisode>)
open class SAnimeSeasonUpdate(val anime: SAnime, val seasons: List<SAnime>)
