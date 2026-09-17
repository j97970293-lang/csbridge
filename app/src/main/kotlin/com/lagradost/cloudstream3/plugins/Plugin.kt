package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder

/**
 * `com.lagradost.cloudstream3.plugins.Plugin` lives in the Cloudstream *app*
 * module, not in the published `library` artifact — but virtually every plugin
 * published today extends it, so this host must provide the exact same class.
 *
 * Faithful re-implementation of
 * `cloudstream/app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt`.
 * Do **not** rename or move this class: plugin .cs3 files are linked against
 * `com.lagradost.cloudstream3.plugins.Plugin` by name.
 */
abstract class Plugin : BasePlugin() {

    /**
     * Called when the plugin is loaded.
     * Most plugins override this instead of [BasePlugin.load].
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by the plugin then try the cross-platform load()
        load()
    }

    /**
     * Used to register VideoClickAction instances.
     * The action is stored but never invoked (Aniyomi has no such concept).
     */
    fun registerVideoClickAction(element: VideoClickAction) {
        element.sourcePlugin = this.filename
        // AtomicList is exposed as a read-only List by the library metadata, but
        // it really is mutable (it implements add/remove) - hence the cast.
        @Suppress("UNCHECKED_CAST")
        (VideoClickActionHolder.allVideoClickActions as MutableList<VideoClickAction>).add(element)
    }

    /** Contains the plugin resources when `requiresResources` is set in its manifest. */
    var resources: Resources? = null

    /** Set by plugins that ship their own settings UI; surfaced by the bridge. */
    var openSettings: ((context: Context) -> Unit)? = null
}
