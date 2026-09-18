/**
 * Pure-logic tests, runnable on the JVM (no Android needed).
 *
 * They cover the parts of the bridge that can be checked without a device:
 * repository URL normalisation, CDN proxying, file name sanitising, sha256 and
 * the JSON models of the Cloudstream repository format.
 *
 * Compile & run (after tools/build-manual.sh once, so that every jar is cached):
 *
 *   kotlinc -cp "$CP:<build dir>/classes" -d /tmp/t tests-logic.kt
 *   java   -cp "/tmp/t:$CP:<build dir>/classes" TestLogicKt
 *
 * (see tools/run-tests.sh, which wires $CP for you)
 */
import aniyomi.csbridge.CsPrefs
import aniyomi.csbridge.CsRepositoryManager
import aniyomi.csbridge.InstalledPlugin
import aniyomi.csbridge.PluginManifest
import aniyomi.csbridge.Repository
import aniyomi.csbridge.SitePlugin
import aniyomi.csbridge.source.CsMapping
import java.io.File

private var passed = 0
private val failures = mutableListOf<String>()

private fun eq(name: String, expected: String, actual: String) {
    if (expected == actual) passed++ else failures += "$name\n      expected: $expected\n      actual  : $actual"
}

private fun ok(name: String, condition: Boolean) {
    if (condition) passed++ else failures += name
}

fun main() {
    // ---------------------------------------------------------- repository urls
    eq(
        "https url (trimmed)",
        "https://example.com/repo/manifest.json",
        CsRepositoryManager.normalizeRepoUrl("  https://example.com/repo/manifest.json\n"),
    )
    eq(
        "cloudstreamrepo:// with scheme",
        "https://example.com/repo/manifest.json",
        CsRepositoryManager.normalizeRepoUrl("cloudstreamrepo://https://example.com/repo/manifest.json"),
    )
    eq(
        "cloudstreamrepo:// without scheme",
        "https://example.com/repo/manifest.json",
        CsRepositoryManager.normalizeRepoUrl("cloudstreamrepo://example.com/repo/manifest.json"),
    )
    eq(
        "cs.repo short link (plain)",
        "https://example.com/repo/manifest.json",
        CsRepositoryManager.normalizeRepoUrl("https://cs.repo/?https://example.com/repo/manifest.json"),
    )
    eq(
        "cs.repo short link (percent encoded)",
        "https://example.com/repo/manifest.json",
        CsRepositoryManager.normalizeRepoUrl("https://cs.repo/?https%3A%2F%2Fexample.com%2Frepo%2Fmanifest.json"),
    )

    // ---------------------------------------------------------------- cdn proxy
    eq(
        "jsdelivr proxy on",
        "https://cdn.jsdelivr.net/gh/user/repo@master/plugins.json",
        CsRepositoryManager.applyCdnProxy("https://raw.githubusercontent.com/user/repo/master/plugins.json", true),
    )
    eq(
        "jsdelivr proxy off",
        "https://raw.githubusercontent.com/user/repo/master/plugins.json",
        CsRepositoryManager.applyCdnProxy("https://raw.githubusercontent.com/user/repo/master/plugins.json", false),
    )
    eq(
        "jsdelivr proxy leaves other hosts",
        "https://example.com/plugins.json",
        CsRepositoryManager.applyCdnProxy("https://example.com/plugins.json", true),
    )

    // ------------------------------------------------------------ file names
    val safe = CsRepositoryManager.sanitizeFileName("My Plugin/Name:v2*?")
    ok("sanitizeFileName removes path separators", !safe.contains('/') && !safe.contains('\\') && safe.isNotBlank())
    eq("sanitizeFileName is stable", safe, CsRepositoryManager.sanitizeFileName("My Plugin/Name:v2*?"))

    // ---------------------------------------------------------------- sha256
    val tmp = File.createTempFile("csbridge-test", ".bin").apply { writeText("hello csbridge") }
    val expectedSha = try {
        ProcessBuilder("sha256sum", tmp.absolutePath).start()
            .inputStream.bufferedReader().readText().split(Regex("\\s+")).first()
    } catch (_: Throwable) {
        "" // sha256sum unavailable -> only check the format below
    }
    val sha = CsRepositoryManager.sha256(tmp)
    // Cloudstream file hashes are stored as "sha256-<hex>"
    if (expectedSha.isNotBlank()) eq("sha256 matches sha256sum", "sha256-$expectedSha", sha)
    ok("sha256 is 'sha256-' + 64 hex chars", sha.matches(Regex("sha256-[0-9a-f]{64}")))
    tmp.delete()

    // ------------------------------------------------------- repository models
    val repoJson = """
        {
          "name": "My repo",
          "description": "test",
          "iconUrl": "https://example.com/icon.png",
          "manifestVersion": 1,
          "pluginLists": ["https://example.com/plugins.json"],
          "someFutureField": 42
        }
    """.trimIndent()
    val repo = CsPrefs.json.decodeFromString<Repository>(repoJson)
    eq("repository name", "My repo", repo.name)
    eq("repository pluginLists", "https://example.com/plugins.json", repo.pluginLists.first())
    ok("unknown keys are ignored", repo.manifestVersion == 1)

    val pluginsJson = """
        [
          {
            "name": "AnimeWorld",
            "internalName": "AnimeWorldProvider",
            "url": "https://example.com/AnimeWorld.cs3",
            "version": 4,
            "apiVersion": 1,
            "authors": ["someone"],
            "description": "Italian anime provider",
            "repositoryUrl": "https://example.com/",
            "tvTypes": ["Anime", "TvSeries"],
            "language": "it",
            "iconUrl": "https://example.com/icon.png",
            "fileSize": 123456,
            "fileHash": "sha256-abc",
            "status": 1
          },
          {
            "name": "Broken",
            "internalName": "BrokenProvider",
            "url": "https://example.com/Broken.cs3",
            "version": 1,
            "status": 0
          }
        ]
    """.trimIndent()
    val plugins = CsPrefs.json.decodeFromString<List<SitePlugin>>(pluginsJson)
    eq("plugin count", "2", plugins.size.toString())
    eq("plugin internalName", "AnimeWorldProvider", plugins[0].internalName)
    eq("plugin version", "4", plugins[0].version.toString())
    eq("plugin language", "it", plugins[0].language ?: "")
    eq("plugin fileHash", "sha256-abc", plugins[0].fileHash ?: "")
    eq("plugin authors", "someone", plugins[0].authors.first())
    ok("status 1 is enabled", !plugins[0].isDisabled)
    ok("status 0 is disabled", plugins[1].isDisabled)

    // ------------------------------------------------------- plugin manifest
    val manifest = CsPrefs.json.decodeFromString<PluginManifest>(
        """
        {"name":"AnimeWorld","pluginClassName":"com.example.AnimeWorldPlugin","requiresResources":true,"version":4}
        """.trimIndent(),
    )
    eq("manifest class", "com.example.AnimeWorldPlugin", manifest.pluginClassName ?: "")
    ok("manifest requiresResources", manifest.requiresResources)

    // ------------------------------------------------------------- round trip
    val record = InstalledPlugin(
        internalName = "AnimeWorldProvider",
        name = "AnimeWorld",
        url = "https://example.com/AnimeWorld.cs3",
        repositoryUrl = "https://example.com/manifest.json",
        filePath = "/data/data/app/files/csbridge/plugins/repo/AnimeWorldProvider.cs3",
        version = 4,
        language = "it",
        authors = listOf("someone"),
        sideloaded = false,
    )
    val back = CsPrefs.json.decodeFromString<InstalledPlugin>(CsPrefs.json.encodeToString(record))
    eq("round trip internalName", record.internalName, back.internalName)
    eq("round trip filePath", record.filePath, back.filePath)
    eq("round trip authors", "someone", back.authors.first())
    ok("round trip enabled default", back.enabled)

    // ------------------------------------------------------------ season urls
    // A season is a second-class SAnime whose url carries "#cs3season=N".
    eq(
        "season url",
        "https://site.fr/anime/one-piece#cs3season=2",
        CsMapping.seasonUrl("https://site.fr/anime/one-piece", 2),
    )
    eq(
        "season url (idempotent)",
        "https://site.fr/anime/one-piece#cs3season=3",
        CsMapping.seasonUrl(CsMapping.seasonUrl("https://site.fr/anime/one-piece", 2), 3),
    )
    eq("season of url", "2", CsMapping.seasonOf("https://site.fr/x#cs3season=2").toString())
    eq("season of plain url", "null", CsMapping.seasonOf("https://site.fr/x").toString())
    eq(
        "base url of a season",
        "https://site.fr/x",
        CsMapping.baseUrlOf("https://site.fr/x#cs3season=2"),
    )

    // ---------------------------------------------------------------- report
    println("passed: $passed")
    if (failures.isEmpty()) {
        println("ALL LOGIC TESTS OK")
    } else {
        println("FAILURES: ${failures.size}")
        failures.forEach { println("  ✗ $it") }
        kotlin.system.exitProcess(1)
    }
}
