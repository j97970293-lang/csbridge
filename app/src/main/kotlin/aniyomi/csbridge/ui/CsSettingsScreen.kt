package aniyomi.csbridge.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import android.app.AlertDialog
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.csbridge.CsBridge
import aniyomi.csbridge.CsLog
import aniyomi.csbridge.CsPrefs
import aniyomi.csbridge.CsRepositoryManager
import aniyomi.csbridge.InstalledPlugin
import aniyomi.csbridge.RepositoryData
import aniyomi.csbridge.SitePlugin
import aniyomi.csbridge.source.CsAnimeSource
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the user can configure, built with the plain AndroidX preference
 * API (an extension cannot declare its own Activity, so no custom screens).
 *
 * The screen is shared by every Cloudstream source: repositories, the plugin
 * catalog, the installed plugins and the diagnostics are global, and a section
 * at the top is dedicated to the site (provider) you opened the settings from.
 */
object CsSettingsScreen {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setup(screen: PreferenceScreen, source: CsAnimeSource?) {
        val context = screen.context
        screen.removeAll()

        sectionSite(context, screen, source)
        sectionRepositories(context, screen)
        sectionCatalog(context, screen)
        sectionInstalled(context, screen)
        sectionDiagnostics(context, screen)
    }

    // ------------------------------------------------------------------- site

    private fun sectionSite(context: Context, screen: PreferenceScreen, source: CsAnimeSource?) {
        if (source == null) {
            val category = PreferenceCategory(context).apply {
                title = "Cloudstream Bridge"
                screen.addPreference(this)
            }
            category.addPreference(
                Preference(context).apply {
                    title = "Installer des sites"
                    summary = "Ajoute un dépôt ci-dessous, puis installe les sites depuis le catalogue. " +
                        "Chaque site installé devient une source Aniyomi."
                    isSelectable = false
                },
            )
            return
        }
        val key = source.ref.key
        val category = PreferenceCategory(context).apply {
            title = "Site : ${source.name}"
            screen.addPreference(this)
        }

        category.addPreference(
            Preference(context).apply {
                title = "Plugin"
                summary = "${source.ref.pluginName} — ${source.mainUrl.ifBlank { "url inconnue" }}"
                isSelectable = false
            },
        )

        category.addPreference(
            SwitchPreferenceCompat(context).apply {
                // An explicit key + default value: some PreferenceFragmentCompat
                // setups always call onSetInitialValue(), even with no persisted
                // value, which would otherwise reset the widget to false/null.
                setKey("cs_enabled_$key")
                setDefaultValue(!CsPrefs.providerDisabled(context, key))
                title = "Activer ce site"
                summary = "Masque la source dans Aniyomi sans désinstaller le plugin"
                isChecked = !CsPrefs.providerDisabled(context, key)
                setOnPreferenceChangeListener { _, value ->
                    CsPrefs.setProviderDisabled(context, key, !(value as Boolean))
                    Toast.makeText(context, "Redémarrage de l'extension…", Toast.LENGTH_SHORT).show()
                    CsBridge.requestReload(context)
                    true
                }
            },
        )

        category.addPreference(
            EditTextPreference(context).apply {
                // Same reason as above: key + default value so that
                // onSetInitialValue() can never overwrite the field with null.
                setKey("cs_mainurl_$key")
                setDefaultValue(CsPrefs.mainUrlOverride(context, key))
                title = "Adresse du site (miroir)"
                dialogTitle = "Remplacer l'URL du site"
                summary = CsPrefs.mainUrlOverride(context, key)
                    .ifBlank { "Laisser vide pour utiliser l'URL du plugin" }
                text = CsPrefs.mainUrlOverride(context, key)
                setOnPreferenceChangeListener { _, value ->
                    val url = value.toString().trim()
                    CsPrefs.setMainUrlOverride(context, key, url)
                    val provider = source.providerInstance
                    if (url.isNotBlank() && provider != null && provider.canBeOverridden) {
                        runCatching { provider.mainUrl = url }
                            .onFailure { CsLog.e("Cannot override mainUrl", it) }
                    }
                    summary = url.ifBlank { "Laisser vide pour utiliser l'URL du plugin" }
                    true
                }
            },
        )

        // Cloudstream plugins can expose their own settings screen.
        val pluginInstance = CsBridge.loadedPluginFor(source.pluginInternalName)?.instance
        if (pluginInstance is Plugin && pluginInstance.openSettings != null) {
            category.addPreference(
                Preference(context).apply {
                    title = "Réglages du plugin Cloudstream"
                    summary = "Ouvre l'écran fourni par le plugin (peut ne pas fonctionner hors de Cloudstream)"
                    setOnPreferenceClickListener {
                        runCatching { pluginInstance.openSettings?.invoke(context) }
                            .onFailure {
                                CsLog.e("openSettings failed", it)
                                showMessage(
                                    context,
                                    screen,
                                    "Réglages indisponibles",
                                    "Le plugin utilise une interface Cloudstream qui n'existe pas dans Aniyomi.\n\n${it.message}",
                                )
                            }
                        true
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------ repositories

    private fun sectionRepositories(context: Context, screen: PreferenceScreen) {
        val category = PreferenceCategory(context).apply {
            title = "Dépôts Cloudstream"
            screen.addPreference(this)
        }

        category.addPreference(
            Preference(context).apply {
                title = "Ajouter un dépôt"
                summary = "Colle une URL de dépôt (manifest.json) ou un lien cloudstreamrepo://"
                setOnPreferenceClickListener {
                    prompt(
                        context,
                        screen,
                        "Ajouter un dépôt",
                        "https://raw.githubusercontent.com/monrepo/monrepo/refs/heads/master/",
                        "URL du dépôt",
                    ) { input ->
                        val url = CsRepositoryManager.normalizeRepoUrl(input)
                        if (url.isBlank()) return@prompt
                        scope.launch {
                            val repos = withContext(Dispatchers.IO) { CsPrefs.repositories(context) }
                            if (repos.any { it.url == url }) {
                                Toast.makeText(context, "Dépôt déjà présent", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            CsPrefs.addRepository(context, RepositoryData(url = url, name = url))
                            refreshRepositories(context, category)
                            Toast.makeText(context, "Dépôt ajouté", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
            },
        )

        refreshRepositories(context, category)
    }

    private fun refreshRepositories(context: Context, category: PreferenceCategory) {
        val repositories = CsPrefs.repositories(context)
        // Remove the dynamic entries but keep the "add" preference
        val children = (0 until category.preferenceCount)
            .map { category.getPreference(it) }
            .filter { it.key?.startsWith("cs_repo_") == true }
        children.forEach { category.removePreference(it) }

        if (repositories.isEmpty()) {
            category.addPreference(
                Preference(context).apply {
                    key = "cs_repo_empty"
                    title = "Aucun dépôt"
                    summary = "Ajoute un dépôt Cloudstream pour installer des sites"
                    isSelectable = false
                },
            )
            return
        }

        repositories.forEach { repo ->
            category.addPreference(
                Preference(context).apply {
                    key = "cs_repo_${repo.url}"
                    title = repo.name.ifBlank { repo.url }
                    summary = repo.url
                    setOnPreferenceClickListener {
                        showItems(
                            context,
                            repo.name.ifBlank { repo.url },
                            arrayOf("Supprimer le dépôt"),
                        ) { which ->
                            if (which == 0) {
                                CsPrefs.removeRepository(context, repo.url)
                                refreshRepositories(context, category)
                            }
                        }
                        true
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------- catalog

    private fun sectionCatalog(context: Context, screen: PreferenceScreen) {
        val category = PreferenceCategory(context).apply {
            title = "Catalogue (installer un site)"
            screen.addPreference(this)
        }
        category.addPreference(
            Preference(context).apply {
                key = "cs_catalog_loading"
                title = "Chargement…"
                isSelectable = false
            },
        )
        loadCatalog(context, category)
    }

    private fun loadCatalog(context: Context, category: PreferenceCategory) {
        scope.launch {
            CsRepositoryManager.clearError()
            val entries = runCatching { CsBridge.catalog(context) }
                .onFailure { CsLog.e("Catalog failed", it) }
                .getOrDefault(emptyList())
            val installed = withContext(Dispatchers.IO) { CsPrefs.installed(context) }

            clearCategory(category)
            if (entries.isEmpty()) {
                category.addPreference(
                    Preference(context).apply {
                        key = "cs_catalog_empty"
                        title = "Aucun plugin trouvé"
                        summary = when {
                            CsPrefs.repositories(context).isEmpty() ->
                                "Ajoute d'abord un dépôt ci-dessus."
                            CsRepositoryManager.lastError != null ->
                                "Dernière erreur :\n${CsRepositoryManager.lastError}"
                            else ->
                                "Les dépôts n'ont renvoyé aucun plugin (URL invalide ou hors ligne)."
                        }
                        isSelectable = false
                    },
                )
                return@launch
            }

            entries
                .filter { !it.plugin.isDisabled || installed.any { p -> p.internalName == it.plugin.internalName } }
                .sortedBy { it.plugin.name.lowercase() }
                .forEach { entry ->
                    val plugin = entry.plugin
                    val record = installed.firstOrNull { it.internalName == plugin.internalName }
                    category.addPreference(
                        Preference(context).apply {
                            key = "cs_plugin_${plugin.internalName}"
                            title = plugin.name + if (plugin.isDisabled) " (désactivé)" else ""
                            summary = buildPluginSummary(plugin, record)
                            setOnPreferenceClickListener {
                                when (record) {
                                    null -> installPlugin(context, plugin, entry.repository.url)
                                    else -> pluginActions(context, plugin, record, entry.repository.url)
                                }
                                true
                            }
                        },
                    )
                }
        }
    }

    private fun buildPluginSummary(plugin: SitePlugin, record: InstalledPlugin?): String {
        val authors = plugin.authors.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val api = "api v${plugin.apiVersion}"
        return buildString {
            append(api)
            append(" • v${plugin.version}")
            plugin.language?.let { append(" • $it") }
            authors?.let { append(" • $it") }
            if (record != null) {
                append("\nInstallé : v${record.version}")
                if (plugin.version > record.version) append(" — mise à jour disponible")
            }
            plugin.description?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
        }
    }

    private fun installPlugin(context: Context, plugin: SitePlugin, repositoryUrl: String) {
        scope.launch {
            Toast.makeText(context, "Installation de ${plugin.name}…", Toast.LENGTH_SHORT).show()
            val ok = CsBridge.install(context, plugin, repositoryUrl)
            Toast.makeText(
                context,
                if (ok) "${plugin.name} installé" else "Échec de l'installation (voir le journal)",
                Toast.LENGTH_LONG,
            ).show()
            if (ok) CsBridge.requestReload(context)
        }
    }

    private fun pluginActions(
        context: Context,
        plugin: SitePlugin,
        record: InstalledPlugin,
        repositoryUrl: String,
    ) {
        val items = mutableListOf("Réinstaller / Mettre à jour", "Désinstaller")
        if (record.enabled) items += "Désactiver" else items += "Activer"
        items += "Forcer le rechargement"

        showItems(context, plugin.name, items.toTypedArray()) { which ->
            when (which) {
                0 -> installPlugin(context, plugin, repositoryUrl)
                1 -> {
                    CsBridge.uninstall(context, record.internalName)
                    Toast.makeText(context, "${record.name} désinstallé", Toast.LENGTH_SHORT).show()
                    CsBridge.requestReload(context)
                }
                2 -> {
                    CsPrefs.setEnabled(context, record.internalName, !record.enabled)
                    Toast.makeText(context, "Redémarrage de l'extension…", Toast.LENGTH_SHORT).show()
                    CsBridge.requestReload(context)
                }
                3 -> CsBridge.requestReload(context)
            }
        }
    }

    // --------------------------------------------------------------- installed

    private fun sectionInstalled(context: Context, screen: PreferenceScreen) {
        val category = PreferenceCategory(context).apply {
            title = "Plugins installés"
            screen.addPreference(this)
        }

        category.addPreference(
            Preference(context).apply {
                key = "cs_update_all"
                title = "Mettre à jour tous les plugins"
                summary = "Compare les versions installées avec celles des dépôts"
                setOnPreferenceClickListener {
                    scope.launch {
                        Toast.makeText(context, "Recherche de mises à jour…", Toast.LENGTH_SHORT).show()
                        val count = CsBridge.updateAll(context)
                        Toast.makeText(context, "$count plugin(s) mis à jour", Toast.LENGTH_LONG).show()
                        if (count > 0) CsBridge.requestReload(context)
                    }
                    true
                }
            },
        )

        category.addPreference(
            Preference(context).apply {
                key = "cs_install_local"
                title = "Installer un fichier .cs3"
                summary = "Chemin complet du fichier sur l'appareil (ex: /sdcard/Download/monplugin.cs3)"
                setOnPreferenceClickListener {
                    prompt(context, screen, "Installer un .cs3", "/sdcard/Download/", "Chemin du fichier") { path ->
                        val file = java.io.File(path.trim())
                        if (!file.exists()) {
                            Toast.makeText(context, "Fichier introuvable", Toast.LENGTH_LONG).show()
                            return@prompt
                        }
                        scope.launch {
                            val ok = CsBridge.installLocal(context, file)
                            Toast.makeText(
                                context,
                                if (ok) "Plugin installé" else "Échec (voir le journal)",
                                Toast.LENGTH_LONG,
                            ).show()
                            if (ok) CsBridge.requestReload(context)
                        }
                    }
                    true
                }
            },
        )

        val installed = CsPrefs.installed(context)
        if (installed.isEmpty()) {
            category.addPreference(
                Preference(context).apply {
                    key = "cs_installed_empty"
                    title = "Aucun plugin installé"
                    isSelectable = false
                },
            )
        } else {
            installed.forEach { record ->
                category.addPreference(
                    Preference(context).apply {
                        key = "cs_installed_${record.internalName}"
                        title = record.name + if (record.enabled) "" else " (désactivé)"
                        summary = buildString {
                            append("v${record.version}")
                            record.language?.let { append(" • $it") }
                            if (record.sideloaded) append(" • local")
                            append("\n${record.filePath}")
                        }
                        setOnPreferenceClickListener {
                            showItems(
                                context,
                                record.name,
                                arrayOf("Désinstaller", "Réinstaller"),
                            ) { which ->
                                when (which) {
                                0 -> {
                                    CsBridge.uninstall(context, record.internalName)
                                    CsBridge.requestReload(context)
                                }
                                1 -> record.url?.let { url ->
                                    val repo = record.repositoryUrl ?: ""
                                    installPlugin(
                                        context,
                                        SitePlugin(
                                            url = url,
                                            name = record.name,
                                            internalName = record.internalName,
                                            version = record.version,
                                            language = record.language,
                                            authors = record.authors,
                                        ),
                                        repo,
                                    )
                                    }
                                }
                            }
                            true
                        }
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------- diagnostics

    private fun sectionDiagnostics(context: Context, screen: PreferenceScreen) {
        val category = PreferenceCategory(context).apply {
            title = "Diagnostic"
            screen.addPreference(this)
        }

        category.addPreference(
            Preference(context).apply {
                title = "Build"
                summary = eu.kanade.tachiyomi.animeextension.all.csbridge.BuildConfig.BUILD_TIME
                isSelectable = false
            },
        )

        category.addPreference(
            Preference(context).apply {
                title = "État du pont"
                summary = CsBridge.status(context)
                isSelectable = false
            },
        )

        category.addPreference(
            Preference(context).apply {
                title = "Voir le journal"
                summary = "Erreurs de chargement des plugins, extracteurs, réseau…"
                setOnPreferenceClickListener {
                    showMessage(context, screen, "Journal CSBridge", CsLog.snapshot())
                    true
                }
            },
        )

        category.addPreference(
            Preference(context).apply {
                title = "Recharger les sources"
                summary = "Demande à Aniyomi de relire la liste des plugins installés"
                setOnPreferenceClickListener {
                    CsBridge.requestReload(context)
                    Toast.makeText(context, "Rechargement demandé", Toast.LENGTH_SHORT).show()
                    true
                }
            },
        )

        category.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = "cs_adult"
                setDefaultValue(CsPrefs.enableAdult(context))
                title = "Autoriser le contenu adulte"
                isChecked = CsPrefs.enableAdult(context)
                setOnPreferenceChangeListener { _, value ->
                    CsPrefs.setEnableAdult(context, value as Boolean)
                    true
                }
            },
        )

        category.addPreference(
            Preference(context).apply {
                title = "Effacer le journal"
                setOnPreferenceClickListener {
                    CsLog.clear()
                    true
                }
            },
        )
    }

    /**
     * Shows a simple list dialog.
     *
     * Uses the framework `android.app.AlertDialog`: androidx.appcompat is not
     * shipped by Aniyomi forks, and importing it used to crash the whole
     * settings screen (NoClassDefFoundError). If the dialog cannot be shown
     * (context without a window token...) we degrade to a toast instead of
     * crashing.
     */
    private fun showItems(
        context: Context,
        title: String,
        items: Array<String>,
        onPick: (Int) -> Unit,
    ) {
        try {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(items) { _, which -> onPick(which) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            CsLog.e("Dialogue indisponible ($title)", t)
            Toast.makeText(context, "Action indisponible : $title", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ utils

    private fun clearCategory(category: PreferenceCategory) {
        val children = (0 until category.preferenceCount).map { category.getPreference(it) }
        children.forEach { if (it.key != "cs_update_all" && it.key != "cs_install_local") category.removePreference(it) }
    }

    /**
     * Asks a value through an inline EditTextPreference.
     *
     * Deliberately dialog-free: the previous implementation used
     * androidx.appcompat's AlertDialog, which Aniyomi forks do not ship
     * (NoClassDefFoundError). An EditTextPreference is native to the host's
     * settings screen, works everywhere and cannot fail.
     */
    private fun prompt(
        context: Context,
        screen: PreferenceScreen,
        title: String,
        initial: String,
        hint: String,
        onOk: (String) -> Unit,
    ) {
        val key = "cs_prompt_${title.hashCode()}"
        runCatching { screen.findPreference<Preference>(key)?.let { screen.removePreference(it) } }
        screen.addPreference(
            EditTextPreference(context).apply {
                setKey(key)
                setTitle(title)
                setSummary(hint)
                setDefaultValue(initial)
                text = initial
                setOnPreferenceChangeListener { pref, value ->
                    runCatching { screen.removePreference(pref) }
                    onOk(value.toString())
                    true
                }
            },
        )
    }

    private fun showMessage(
        context: Context,
        screen: PreferenceScreen,
        title: String,
        message: String,
    ) {
        try {
            val textView = TextView(context).apply {
                text = message
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(48, 32, 48, 32)
                gravity = Gravity.START
            }
            val scroll = ScrollView(context).apply { addView(textView) }
            android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        } catch (t: Throwable) {
            CsLog.e("Dialogue indisponible ($title), repli sans dialogue", t)
        }

        val key = "cs_message_${title.hashCode()}"
        runCatching { screen.findPreference<Preference>(key)?.let { screen.removePreference(it) } }
        screen.addPreference(
            Preference(context).apply {
                setKey(key)
                setTitle(title)
                setSummary(message)
                isSelectable = false
            },
        )
    }
}
