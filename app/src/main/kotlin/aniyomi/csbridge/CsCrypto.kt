package aniyomi.csbridge

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem

/**
 * Makes sure a `dev.whyoleg.cryptography` provider is available.
 *
 * Why: Cloudstream's `ExtractorApi` static initialiser builds every extractor,
 * and some of them (Rabbitstream...) call `CryptographyProvider.getDefault()`.
 * That call resolves the provider through `ServiceLoader`, and a plain APK
 * built without the `META-INF/services` resource has no provider at all ->
 * `IllegalStateException: No providers registered` -> the whole plugin install
 * fails with `ExceptionInInitializerError` (seen on Aniyomi 0.5.213).
 *
 * Two belts:
 *   1. the APK ships `META-INF/services/...CryptographyProviderContainer`
 *      (added by tools/build-manual.sh, D8 drops resources) ;
 *   2. if the ServiceLoader still finds nothing, the JDK provider shipped in the
 *      APK is instantiated reflectively and registered by hand.
 */
object CsCrypto {

    private const val PROVIDER_KT = "dev.whyoleg.cryptography.providers.jdk.JdkCryptographyProviderKt"

    @Volatile
    private var done = false

    @Synchronized
    fun ensure() {
        if (done) return
        done = true

        val already = runCatching { CryptographyProvider.Default.name }.getOrNull()
        if (already != null) {
            CsLog.i("Cryptography provider: $already")
            return
        }

        CsLog.w("No cryptography provider registered, registering the bundled JDK one")
        runCatching {
            val companion = Class.forName("dev.whyoleg.cryptography.CryptographyProvider")
                .getField("Companion")
                .get(null)
            val factory = Class.forName(PROVIDER_KT).methods
                .first { it.name == "getJDK" && it.parameterTypes.size == 1 }
            val provider = factory.invoke(null, companion) as CryptographyProvider
            CryptographySystem.setDefaultProvider(provider)
            CsLog.i("Cryptography provider registered: ${provider.name}")
        }.onFailure {
            CsLog.e("Cannot register a cryptography provider (extractors needing crypto will fail)", it)
        }
    }
}
