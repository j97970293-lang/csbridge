# Cloudstream Bridge — extension Aniyomi / AniZen

Utilise les **extensions Cloudstream (`.cs3`)** directement dans Aniyomi et ses forks
(AniZen, Anikku, Komikku…). Chaque site Cloudstream installé devient **une source
Aniyomi indépendante**, avec son catalogue, sa recherche, ses épisodes et ses serveurs.

```
Aniyomi  ──►  Cloudstream Bridge  ──►  plugin .cs3 (AnimoFlix, Zenix, French Stream…)
                    │
                    ├── dépôts Cloudstream (repo.json + plugins.json)
                    ├── réglages du pont (dépôts, catalogue, diagnostic)
                    └── réglages propres à chaque plugin (si le plugin les expose)
```

## Installation

### Méthode 1 — via un dépôt Aniyomi (recommandé)

1. Aniyomi / AniZen → **Paramètres → Parcourir → Dépôts d'extensions** (ou *Repos*).
2. Ajoutez l'une de ces URL :

   | URL | Quand l'utiliser |
   |---|---|
   | `https://cdn.jsdelivr.net/gh/j97970293-lang/csbridge@v2/repo/index.min.json` | **par défaut** — jsDelivr (CDN) contourne les lenteurs et blocages de `raw.githubusercontent.com` |
   | `https://cdn.jsdelivr.net/gh/j97970293-lang/csbridge@main/repo/index.min.json` | même chose, mais le cache de branche peut traîner jusqu'à 12 h après une mise à jour |
   | `https://raw.githubusercontent.com/j97970293-lang/csbridge/main/repo/index.min.json` | si jsDelivr est bloqué chez vous |

   L'URL épinglée sur `@v2` sert toujours exactement la v17.2 ; à chaque nouvelle
   version, remplacez `@v2` par le nouveau tag (`@v3`, …).

3. **Parcourir → Extensions** → installez **Cloudstream Bridge**.

Les deux servent exactement les deux mêmes fichiers : `repo/index.min.json` (le catalogue)
et `repo/apk/csbridge.apk` (l'extension).

### Méthode 2 — APK direct

Téléchargez `csbridge.apk` depuis les *Releases* et installez-le.
(Autorisez « sources inconnues » pour l'application qui installe.)

## Utilisation

1. **Parcourir → Extensions → Cloudstream Bridge → ⚙️**
2. Section **Dépôts** → *Ajouter un dépôt* → collez l'URL d'un dépôt Cloudstream, par exemple :
   - `https://raw.githubusercontent.com/j97970293-lang/plugin-fr/builds/repo.json`
   - n'importe quel lien `cloudstreamrepo://…`
3. Section **Catalogue** → installez les sites voulus.
4. **Parcourir → Sources** : chaque site installé apparaît comme une source.
   Ouvrez-la, naviguez, puis choisissez un **serveur** au moment de la lecture.
5. En cas de problème : ⚙️ → **Diagnostic → Voir le journal** (erreurs datées).

## Ce qui est supporté

| Fonction | État |
|---|---|
| Dépôts Cloudstream (`repo.json` + `pluginLists`) | ✅ |
| Installation / mise à jour / désinstallation d'un plugin | ✅ |
| Un catalogue par site (une source Aniyomi par provider) | ✅ |
| Recherche, détails, épisodes, saisons | ✅ |
| Serveurs / extracteurs (`loadLinks`) regroupés par serveur | ✅ |
| Sous-titres | ✅ |
| Réglages exposés par le plugin | ✅ (si le plugin les fournit) |
| Surcharge de l'URL principale d'un site | ✅ |
| Proxy CDN jsDelivr pour `raw.githubusercontent.com` | ✅ |

## Compatibilité

Testé sur **AniZen 0.5.213 (Android 8.1)**. Le pont implémente **les deux** formes de
`AnimeCatalogueSource` en circulation :

* Aniyomi / extensions-lib 17 → API `suspend` abstraite ;
* AniZen / Komikku → API RxJava héritée (`fetchPopularAnime`…) abstraite.

Il n'utilise **aucune bibliothèque absente de l'app hôte** : les dialogues passent par
`android.app.AlertDialog` (AppCompat n'est pas fourni par les forks) et les saisies par
un `EditTextPreference` inline.

## Compiler

Aucun Android SDK n'est nécessaire : `tools/build-manual.sh` télécharge kotlinc, D8,
aapt2 et les dépendances, compile, dex, package et signe.

```bash
export JAVA_HOME=/chemin/vers/jdk17      # ou jdk11
bash tools/build-manual.sh               # -> apk/csbridge.apk
```

Contrôles qualité :

```bash
bash tools/check-compat.sh                      # point d'entrée du manifeste + membres abstraits
python3 tools/check-hostapi.py tools/.cache/deps/extlib.jar "Aniyomi"
bash tools/host-emu.sh                          # rejoue le chargement par l'app sur JVM
python3 tools/check-dex.py apk/csbridge.apk    # références non résolues
bash tools/run-tests.sh                         # tests logiques
```

## Publication (GitHub Actions)

`.github/workflows/release.yml` construit l'APK et publie une *Release* à chaque tag
`v*`. Le fichier `repo/index.min.json` est le catalogue Aniyomi servi en brut par GitHub.

## Dépannage

| Symptôme | Cause / solution |
|---|---|
| Un seul site apparaît, les autres sont vides | Le fournisseur de cryptographie manquait (réglé) : les plugins qui appellent `registerExtractorAPI()` mouraient au chargement. Vérifiez `extractor APIs registered:` dans le journal. |
| Catalogue vide, recherche cassée, aucun serveur | `com.lagradost.cloudstream3.network.CloudflareKiller` n'existe que dans le module *app* de Cloudstream, jamais dans `library-android`. Le pont fournit sa propre implémentation (résolution Cloudflare par WebView). |
| `NoClassDefFoundError: org.jsoup…` | jsoup était compilé mais pas dexé — corrigé, avec `re2j` pour les expressions régulières. |
| Dépôt injoignable | Utilisez l'URL jsDelivr ci-dessus plutôt que `raw.githubusercontent.com`. |

Le journal se lit dans ⚙️ → **Diagnostic → Voir le journal**.

## Licence

Apache 2.0 — voir [LICENSE](LICENSE). Le binaire embarque des bibliothèques tierces
(Cloudstream `library-android`, okhttp, jsoup, kotlinx.serialization, whyoleg.cryptography…).
