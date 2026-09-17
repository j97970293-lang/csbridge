package aniyomi.csbridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON models compatible with the Cloudstream repository format, so that any
 * existing Cloudstream repository can be added to Aniyomi as-is.
 *
 * Reference: com.lagradost.cloudstream3.plugins.RepositoryManager
 */

@Serializable
data class RepositoryData(
    @SerialName("url") val url: String,
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String = "",
)

@Serializable
data class Repository(
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("manifestVersion") val manifestVersion: Int = 1,
    @SerialName("pluginLists") val pluginLists: List<String> = emptyList(),
)

@Serializable
data class SitePlugin(
    @SerialName("url") val url: String,
    @SerialName("status") val status: Int = 1,
    @SerialName("version") val version: Int = 1,
    @SerialName("apiVersion") val apiVersion: Int = 1,
    @SerialName("name") val name: String,
    @SerialName("internalName") val internalName: String,
    @SerialName("authors") val authors: List<String> = emptyList(),
    @SerialName("description") val description: String? = null,
    @SerialName("repositoryUrl") val repositoryUrl: String? = null,
    @SerialName("tvTypes") val tvTypes: List<String>? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    @SerialName("fileHash") val fileHash: String? = null,
) {
    /** Cloudstream status: 0 = down, 1 = ok, 2 = slow, 3 = beta only. */
    val isDisabled: Boolean get() = status == 0
}

@Serializable
data class InstalledPlugin(
    @SerialName("internalName") val internalName: String,
    @SerialName("name") val name: String,
    @SerialName("url") val url: String? = null,
    @SerialName("repositoryUrl") val repositoryUrl: String? = null,
    @SerialName("filePath") val filePath: String,
    @SerialName("version") val version: Int = 0,
    @SerialName("language") val language: String? = null,
    @SerialName("authors") val authors: List<String> = emptyList(),
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("installedAt") val installedAt: Long = 0L,
    @SerialName("sideloaded") val sideloaded: Boolean = false,
)

/** Content of `manifest.json` inside a `.cs3` file. */
@Serializable
data class PluginManifest(
    @SerialName("name") val name: String? = null,
    @SerialName("pluginClassName") val pluginClassName: String? = null,
    @SerialName("requiresResources") val requiresResources: Boolean = false,
    @SerialName("version") val version: Int? = null,
)
