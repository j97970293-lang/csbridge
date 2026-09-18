package aniyomi.csbridge.source

import java.util.concurrent.ConcurrentHashMap

/**
 * Alternative versions (VF / VOSTFR) of one episode.
 *
 * Aniyomi has no notion of "dub status", so when a provider exposes the same
 * episode twice (one per language) the episode list fills with duplicates.
 * In "merge" mode the bridge keeps a single entry and remembers the other
 * variants here; [aniyomi.csbridge.source.CsAnimeSource.getHosterList] then
 * loads them all and offers one hoster per version.
 *
 * The map is in-memory on purpose: if the process is restarted the entry
 * simply falls back to its own (primary) version.
 */
object CsVariants {

    /** primary episode data -> (label, data) for every version, primary first */
    private val variants = ConcurrentHashMap<String, List<Pair<String, String>>>()

    fun put(primary: String, all: List<Pair<String, String>>) {
        if (all.size > 1) variants[primary] = all
    }

    fun of(data: String): List<Pair<String, String>> = variants[data].orEmpty()

    fun clear() = variants.clear()
}
