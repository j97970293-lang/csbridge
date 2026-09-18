package aniyomi.csbridge

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Global (i.e. shared by every Cloudstream source) preferences.
 *
 * Aniyomi's `sourcePreferences` are scoped per source id, but repositories and
 * the list of installed plugins are global to the bridge, hence our own file.
 */
object CsPrefs {

    private const val FILE = "csbridge_prefs"
    private const val KEY_REPOS = "repositories"
    private const val KEY_INSTALLED = "installed_plugins"
    private const val KEY_UPDATE_ON_START = "update_on_start"
    private const val KEY_ENABLE_ADULT = "enable_adult"
    private const val KEY_LINKS_TIMEOUT = "links_timeout_ms"
    private const val KEY_MERGE_VARIANTS = "merge_variants"
    private const val KEY_SEASONS = "seasons_enabled"
    private const val KEY_NET_LOG = "network_log"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- repos

    fun repositories(context: Context): List<RepositoryData> = read {
        val raw = prefs(context).getString(KEY_REPOS, null) ?: return emptyList()
        runCatching { json.decodeFromString<List<RepositoryData>>(raw) }
            .onFailure { CsLog.e("Cannot parse repositories", it) }
            .getOrDefault(emptyList())
    }

    fun saveRepositories(context: Context, list: List<RepositoryData>) = write {
        prefs(context).edit().putString(KEY_REPOS, json.encodeToString(list)).apply()
    }

    fun addRepository(context: Context, data: RepositoryData) = write {
        val current = repositories(context)
        if (current.any { it.url == data.url }) return@write
        saveRepositories(context, current + data)
    }

    fun removeRepository(context: Context, url: String) = write {
        saveRepositories(context, repositories(context).filter { it.url != url })
    }

    // ------------------------------------------------------------ installed

    fun installed(context: Context): List<InstalledPlugin> = read {
        val raw = prefs(context).getString(KEY_INSTALLED, null) ?: return emptyList()
        runCatching { json.decodeFromString<List<InstalledPlugin>>(raw) }
            .onFailure { CsLog.e("Cannot parse installed plugins", it) }
            .getOrDefault(emptyList())
    }

    fun saveInstalled(context: Context, list: List<InstalledPlugin>) = write {
        prefs(context).edit().putString(KEY_INSTALLED, json.encodeToString(list)).apply()
    }

    fun upsertInstalled(context: Context, plugin: InstalledPlugin) = write {
        val list = installed(context).filter { it.internalName != plugin.internalName } + plugin
        saveInstalled(context, list)
    }

    fun removeInstalled(context: Context, internalName: String) = write {
        saveInstalled(context, installed(context).filter { it.internalName != internalName })
    }

    fun setEnabled(context: Context, internalName: String, enabled: Boolean) = write {
        saveInstalled(
            context,
            installed(context).map {
                if (it.internalName == internalName) it.copy(enabled = enabled) else it
            },
        )
    }

    // ------------------------------------------------------------- settings

    fun updateOnStart(context: Context): Boolean = prefs(context).getBoolean(KEY_UPDATE_ON_START, false)

    fun setUpdateOnStart(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_UPDATE_ON_START, value).apply()

    fun enableAdult(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLE_ADULT, false)

    fun setEnableAdult(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLE_ADULT, value).apply()

    /** How long we let `loadLinks` run before giving up, in milliseconds. */
    fun linksTimeoutMs(context: Context): Long = prefs(context).getLong(KEY_LINKS_TIMEOUT, 60_000L)

    fun setLinksTimeoutMs(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_LINKS_TIMEOUT, value).apply()

    // VF / VOSTFR: one entry per episode (the versions become hosters) or one
    // entry per version.
    fun mergeVariants(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MERGE_VARIANTS, true)

    fun setMergeVariants(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_MERGE_VARIANTS, value).apply()

    fun seasonsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SEASONS, true)

    fun setSeasonsEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SEASONS, value).apply()

    fun networkLog(context: Context): Boolean = prefs(context).getBoolean(KEY_NET_LOG, false)

    fun setNetworkLog(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_NET_LOG, value).apply()

    // ------------------------------------------------- per provider options

    /** Cloudstream "clone site": overrides the provider `mainUrl`. */
    fun mainUrlOverride(context: Context, key: String): String =
        prefs(context).getString("mainurl_$key", "") ?: ""

    fun setMainUrlOverride(context: Context, key: String, value: String) =
        prefs(context).edit().putString("mainurl_$key", value.trim()).apply()

    fun providerDisabled(context: Context, key: String): Boolean =
        prefs(context).getBoolean("disabled_$key", false)

    fun setProviderDisabled(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean("disabled_$key", value).apply()

    // ---------------------------------------------------------------- utils

    private inline fun <T> read(block: () -> T): T = block()

    private inline fun write(block: () -> Unit) {
        synchronized(this) { block() }
    }
}
