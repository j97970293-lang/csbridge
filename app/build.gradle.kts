@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val properties = Properties().apply {
    rootProject.file("gradle.properties").inputStream().use { load(it) }
}
val csLibraryVersion: String = properties.getProperty("CS_LIBRARY_VERSION") ?: "v4.8.0"
val bundleCoroutines: Boolean = (properties.getProperty("CS_BUNDLE_COROUTINES") ?: "false").toBoolean()
val bundleOkhttp: Boolean = (properties.getProperty("CS_BUNDLE_OKHTTP") ?: "false").toBoolean()

android {
    namespace = "eu.kanade.tachiyomi.animeextension.all.csbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.all.csbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // Aniyomi reads the extension-lib version from `aniyomix.extensionLib`, but a
        // versionName of "17.x" keeps older loaders happy too.
        versionName = "17.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Never obfuscate: the Cloudstream plugins we load at runtime are compiled
    // against the un-renamed `com.lagradost.cloudstream3.*` API.
    @Suppress("UnstableApiUsage")
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            // The Cloudstream library marks part of its API as internal / prerelease.
            // Plugins are allowed to use it, so we must not fail on it either.
            "-opt-in=com.lagradost.cloudstream3.InternalAPI",
            "-opt-in=com.lagradost.cloudstream3.Prerelease",
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    // ---------------------------------------------------------------------
    // 1. Host app API (stubs). compileOnly => NOT bundled, the real
    //    implementations come from Aniyomi at runtime.
    // ---------------------------------------------------------------------
    // IMPORTANT - declaration order matters here:
    // extensions-lib bundles *stub* copies of androidx.preference (they only
    // expose `addPreference`, no `Preference(Context)` constructor...). The real
    // AndroidX library must therefore be declared FIRST so that it wins on the
    // compile classpath. If you ever see "too many arguments for constructor
    // Preference()" or "unresolved reference removeAll", the order got flipped.
    compileOnly(libs.preference.ktx)
    compileOnly(libs.appcompat)

    compileOnly(libs.aniyomi.extensions.lib)
    compileOnly(libs.kotlin.stdlib)

    if (bundleOkhttp) {
        // Not recommended, see gradle.properties
        implementation(libs.okhttp)
        implementation(libs.jsoup)
    } else {
        compileOnly(libs.okhttp)
        compileOnly(libs.jsoup)
    }

    if (bundleCoroutines) {
        implementation(libs.coroutines.core)
        implementation(libs.coroutines.android)
    } else {
        compileOnly(libs.coroutines.core)
        compileOnly(libs.coroutines.android)
    }

    // ---------------------------------------------------------------------
    // 2. The Cloudstream runtime. This is what makes .cs3 plugins work.
    // ---------------------------------------------------------------------
    // NOTE: for Android consumers the Kotlin Multiplatform module is published
    // as `library-android` (the plain `library` coordinates only resolve for JVM).
    implementation("com.github.recloudstream.cloudstream:library-android:$csLibraryVersion") {
        isTransitive = true
        // kotlin-stdlib: provided by the host (child-first classloader => we MUST
        // not ship our own copy, otherwise kotlin.String would be two classes).
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
        // okhttp / okio / jsoup: shared with the host app (Video.headers, mangas...).
        exclude(group = "com.squareup.okhttp3")
        exclude(group = "com.squareup.okio")
        exclude(group = "org.jsoup")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
        // Desktop-only YouTube extractor, several MB, unused on Android.
        exclude(group = "com.github.TeamNewPipe")
    }

    // Explicit versions of the Cloudstream runtime dependencies so that the
    // transitive resolution can never pick an incompatible one.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.rhino)
    implementation(libs.ksoup)
    implementation(libs.ktor.http)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.core)
    implementation(libs.atomicfu)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider)
    implementation(libs.gson)
    implementation(libs.nicehttp) {
        exclude(group = "com.squareup.okhttp3")
        exclude(group = "com.squareup.okio")
    }
}
