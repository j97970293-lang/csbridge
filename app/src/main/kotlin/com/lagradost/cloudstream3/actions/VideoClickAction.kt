package com.lagradost.cloudstream3.actions

import com.lagradost.cloudstream3.utils.AtomicList
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf

/**
 * Minimal stand-in for the Cloudstream "video click action" API.
 *
 * The real class lives in the Cloudstream app and depends on a lot of app-only
 * types ([com.lagradost.cloudstream3.utils.UiText], ResultEpisode,
 * LinkLoadingResult...), none of which exist inside Aniyomi.
 *
 * It exists only so that plugins which merely *reference* the API can still be
 * loaded: actions registered by a plugin are stored but never invoked, because
 * Aniyomi has no equivalent concept (it plays [eu.kanade.tachiyomi.animesource.model.Video]
 * objects directly).
 */
abstract class VideoClickAction {
    abstract val name: String

    /** Which plugin registered this action (full path of the .cs3 file). */
    var sourcePlugin: String? = null

    open fun shouldShow(): Boolean = false
}

object VideoClickActionHolder {
    val allVideoClickActions: AtomicList<VideoClickAction> = atomicListOf()

    fun getByUniqueId(uniqueId: String): VideoClickAction? =
        allVideoClickActions.firstOrNull { it.sourcePlugin + ":" + it::class.qualifiedName == uniqueId }
}
