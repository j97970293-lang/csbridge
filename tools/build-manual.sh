#!/usr/bin/env bash
#
# Build the Cloudstream Bridge APK *without* Android Studio / the Android SDK.
#
# This is the exact pipeline that produced `apk/csbridge.apk` in this repository.
# It only needs: bash, curl, unzip, zip/jar, a JDK 17+ and a Kotlin compiler.
#
# Everything else (the compiler, D8, aapt2, apksigner, the dependency jars) is
# downloaded on first run into $CACHE (default: ./.cache, 100% throw-away).
#
#   ./tools/build-manual.sh                # -> apk/csbridge.apk
#   CACHE=/tmp/csbcache ./tools/build-manual.sh
#
# Environment overrides (reuse an existing toolchain instead of downloading):
#   JAVA_HOME JDK17   KOTLINC (path to kotlinc)   R8_JAR   AAPT2   APKSIGNER_JAR
#   ZIPALIGN  ANDROID_JAR (framework jar with resources.arsc, e.g. robolectric
#             android-all)   DEPS_DIR (already downloaded dependency jars)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${CACHE:-$ROOT/tools/.cache}"
OUT="${OUT:-$ROOT/apk}"
mkdir -p "$CACHE/bin" "$CACHE/deps" "$OUT"

MVN="https://repo1.maven.org/maven2"
JP="https://jitpack.io"
GOOGLE="https://dl.google.com/dl/android/maven2"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
get() { # get <url> <dest>
  [[ -s "$2" ]] && { echo "  cache: $(basename "$2")"; return; }
  echo "  get : $1"
  curl -sSL --fail -o "$2.part" "$1" && mv "$2.part" "$2"
  # jar/aar must be a zip archive: Maven answers 200 for metadata-only modules
  case "$2" in
    *.jar|*.aar)
      head -c2 "$2" | grep -q PK || { echo "  !! $(basename "$2") is not a zip archive"; rm -f "$2"; return 1; }
      ;;
  esac
}

# ---------------------------------------------------------------- 1. toolchain
say "Toolchain"

if [[ -z "${KOTLINC:-}" ]]; then
  KOTLINC="$CACHE/bin/kotlinc/bin/kotlinc"
  [[ -x "$KOTLINC" ]] || {
    get "https://github.com/JetBrains/kotlin/releases/download/v2.4.0/kotlin-compiler-2.4.0.zip" "$CACHE/kotlinc.zip"
    unzip -qo "$CACHE/kotlinc.zip" -d "$CACHE/bin" && chmod +x "$CACHE"/bin/kotlinc/bin/*
  }
fi
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"
JDK17="${JDK17:-${JAVA_HOME:-}}"

R8_JAR="${R8_JAR:-$CACHE/bin/r8.jar}"
get "$GOOGLE/com/android/tools/r8/9.4.17/r8-9.4.17.jar" "$R8_JAR"

AAPT2="${AAPT2:-$CACHE/bin/aapt2}"
if [[ ! -x "$AAPT2" ]]; then
  get "$GOOGLE/com/android/tools/build/aapt2/9.4.0-15978811/aapt2-9.4.0-15978811-linux.jar" "$CACHE/aapt2.jar"
  unzip -p "$CACHE/aapt2.jar" aapt2 > "$AAPT2" && chmod +x "$AAPT2"
fi

APKSIGNER_JAR="${APKSIGNER_JAR:-$CACHE/bin/apksigner.jar}"
ZIPALIGN="${ZIPALIGN:-$CACHE/bin/zipalign}"
if [[ ! -s "$APKSIGNER_JAR" ]]; then
  get "https://dl.google.com/android/repository/build-tools_r34-linux.zip" "$CACHE/build-tools.zip"
  # lib64/ is required: zipalign links against libc++.so shipped inside it
  unzip -qo "$CACHE/build-tools.zip" \
    'android-14/lib/apksigner.jar' 'android-14/zipalign' 'android-14/lib64/*' -d "$CACHE/bt"
  mv "$CACHE/bt/android-14/lib/apksigner.jar" "$APKSIGNER_JAR"
  mv "$CACHE/bt/android-14/zipalign" "$ZIPALIGN" && chmod +x "$ZIPALIGN"
fi

ANDROID_JAR="${ANDROID_JAR:-$CACHE/deps/android-all.jar}"
get "https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar" "$ANDROID_JAR"

# ------------------------------------------------------------- 2. dependencies
say "Dependencies (compile classpath + runtime payload)"
D="${DEPS_DIR:-$CACHE/deps}"

# --- compile only (provided by Aniyomi at runtime) ---
get "$JP/com/github/aniyomiorg/extensions-lib/v17/extensions-lib-v17.aar"          "$D/extlib.aar"
get "$GOOGLE/androidx/preference/preference/1.2.1/preference-1.2.1.aar"               "$D/pref.aar"
get "$GOOGLE/androidx/preference/preference-ktx/1.2.1/preference-ktx-1.2.1.aar"       "$D/prefktx.aar"
get "$GOOGLE/androidx/appcompat/appcompat/1.7.1/appcompat-1.7.1.aar"                  "$D/appcompat.aar"
get "$MVN/com/squareup/okhttp3/okhttp-jvm/5.4.0/okhttp-jvm-5.4.0.jar"              "$D/okhttp.jar"
get "$MVN/com/squareup/okio/okio-jvm/3.10.2/okio-jvm-3.10.2.jar"                   "$D/okio.jar"
get "$MVN/org/jsoup/jsoup/1.22.2/jsoup-1.22.2.jar"                                 "$D/jsoup.jar"
get "$MVN/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar" "$D/coroutines.jar"
get "$MVN/org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.jar"         "$D/kotlin-stdlib.jar"

# --- Cloudstream runtime + its deps (bundled in the APK) ---
CS_VER="${CS_LIBRARY_VERSION:-v4.8.0}"
get "$JP/com/github/recloudstream/cloudstream/library-android/$CS_VER/library-android-$CS_VER.aar" "$D/cs.aar"
get "$JP/com/github/Blatzar/NiceHttp/0.4.18/NiceHttp-0.4.18.jar"                   "$D/nicehttp.jar"
get "$MVN/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.11.0/kotlinx-serialization-json-jvm-1.11.0.jar" "$D/ser.jar"
get "$MVN/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.11.0/kotlinx-serialization-core-jvm-1.11.0.jar" "$D/sercore.jar"
get "$MVN/com/fasterxml/jackson/core/jackson-core/2.13.1/jackson-core-2.13.1.jar"                     "$D/jackson-core.jar"
get "$MVN/com/fasterxml/jackson/core/jackson-databind/2.13.1/jackson-databind-2.13.1.jar"             "$D/jackson-databind.jar"
get "$MVN/com/fasterxml/jackson/core/jackson-annotations/2.13.1/jackson-annotations-2.13.1.jar"       "$D/jackson-annotations.jar"
get "$MVN/com/fasterxml/jackson/module/jackson-module-kotlin/2.13.1/jackson-module-kotlin-2.13.1.jar" "$D/jackson-module-kotlin.jar"
get "$MVN/org/mozilla/rhino/1.8.1/rhino-1.8.1.jar"                                 "$D/rhino.jar"
get "$MVN/com/fleeksoft/ksoup/ksoup-android/0.2.6/ksoup-android-0.2.6.aar"         "$D/ksoup.aar"
get "$MVN/io/ktor/ktor-http-jvm/3.5.0/ktor-http-jvm-3.5.0.jar"                     "$D/ktor-http.jar"
get "$MVN/io/ktor/ktor-utils-jvm/3.5.0/ktor-utils-jvm-3.5.0.jar"                   "$D/ktor-utils.jar"
get "$MVN/io/ktor/ktor-events-jvm/3.5.0/ktor-events-jvm-3.5.0.jar"                 "$D/ktor-events.jar"
get "$MVN/org/jetbrains/kotlinx/kotlinx-datetime-jvm/0.8.0/kotlinx-datetime-jvm-0.8.0.jar" "$D/datetime.jar"
get "$MVN/org/jetbrains/kotlinx/kotlinx-io-core-jvm/0.9.1/kotlinx-io-core-jvm-0.9.1.jar"   "$D/kio.jar"
get "$MVN/org/jetbrains/kotlinx/kotlinx-io-bytestring-jvm/0.9.1/kotlinx-io-bytestring-jvm-0.9.1.jar" "$D/bytestring.jar"
get "$MVN/org/jetbrains/kotlinx/atomicfu-jvm/0.33.0/atomicfu-jvm-0.33.0.jar"       "$D/atomicfu.jar"
get "$MVN/dev/whyoleg/cryptography/cryptography-core-jvm/0.6.0/cryptography-core-jvm-0.6.0.jar" "$D/crypto-core.jar"
get "$MVN/dev/whyoleg/cryptography/cryptography-provider-optimal-jvm/0.6.0/cryptography-provider-optimal-jvm-0.6.0.jar" "$D/crypto-opt.jar"
get "$MVN/org/jetbrains/kotlin/kotlin-reflect/2.3.20/kotlin-reflect-2.3.20.jar"    "$D/kotlin-reflect.jar"
get "$MVN/me/xdrop/fuzzywuzzy/1.4.0/fuzzywuzzy-1.4.0.jar"                          "$D/fuzzywuzzy.jar"
# gson is used by the built-in Dailymotion / GDMirrorbot / Voe extractors
get "$MVN/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"                            "$D/gson.jar"
get "$MVN/io/ktor/ktor-io-jvm/3.5.0/ktor-io-jvm-3.5.0.jar"                               "$D/ktor-io.jar"
get "$MVN/co/touchlab/stately-concurrency-jvm/2.1.0/stately-concurrency-jvm-2.1.0.jar"   "$D/stately-concurrency.jar"
get "$MVN/co/touchlab/stately-common-jvm/2.1.0/stately-common-jvm-2.1.0.jar"             "$D/stately-common.jar"
get "$MVN/dev/whyoleg/cryptography/cryptography-bigint-jvm/0.6.0/cryptography-bigint-jvm-0.6.0.jar"     "$D/crypto-bigint.jar"
get "$MVN/dev/whyoleg/cryptography/cryptography-random-jvm/0.6.0/cryptography-random-jvm-0.6.0.jar"     "$D/crypto-random.jar"
get "$MVN/dev/whyoleg/cryptography/cryptography-provider-jdk-jvm/0.6.0/cryptography-provider-jdk-jvm-0.6.0.jar"     "$D/crypto-provider.jar"
get "$MVN/dev/whyoleg/cryptography/cryptography-provider-base-jvm/0.6.0/cryptography-provider-base-jvm-0.6.0.jar"   "$D/crypto-provider-base.jar"
get "$MVN/dev/whyoleg/cryptography/cryptography-serialization-asn1-jvm/0.6.0/cryptography-serialization-asn1-jvm-0.6.0.jar" \
    "$D/crypto-asn1.jar"
get "$MVN/com/fleeksoft/charset/charset-android/0.0.8/charset-android-0.0.8.aar"         "$D/charset.aar"
get "$MVN/com/fleeksoft/io/io-core-android/0.0.8/io-core-android-0.0.8.aar"              "$D/fleekio.aar"

# extract classes.jar from every aar
for aar in extlib pref prefktx appcompat cs ksoup charset fleekio; do
  [[ -s "$D/$aar.jar" ]] || unzip -p "$D/$aar.aar" classes.jar > "$D/$aar.jar"
done

# ---------------------------------------------------------------- 3. kotlinc
say "Compiling Kotlin"
# REQUIRED: without the serialization compiler plugin the @Serializable data
# classes get no generated serializer and every json.decodeFromString<T>() call
# would throw SerializationException at runtime (Gradle applies it via the
# `kotlin-serialization` plugin, we have to do it by hand here).
KOTLINC_HOME="${KOTLINC_HOME:-$(cd "$(dirname "$KOTLINC")/.." && pwd)}"
SERIAL_PLUGIN="${SERIAL_PLUGIN:-$KOTLINC_HOME/lib/kotlin-serialization-compiler-plugin.jar}"
[[ -s "$SERIAL_PLUGIN" ]] || { echo "missing serialization compiler plugin: $SERIAL_PLUGIN" >&2; exit 1; }
echo "  plugin: $SERIAL_PLUGIN"
BUILD="$CACHE/build"; rm -rf "$BUILD"; mkdir -p "$BUILD/classes" "$BUILD/gen"
cat > "$BUILD/gen/BuildConfig.kt" <<EOF
package eu.kanade.tachiyomi.animeextension.all.csbridge
object BuildConfig {
    const val APPLICATION_ID = "eu.kanade.tachiyomi.animeextension.all.csbridge"
    // Displayed in the extension settings ("Build") so a tester can tell which
    // APK is really installed on the device.
    const val BUILD_TIME = "$(date -u '+%Y-%m-%d %H:%M UTC')"
}
EOF
find "$ROOT/app/src/main/kotlin" -name '*.kt' > "$BUILD/sources.txt"
echo "$BUILD/gen/BuildConfig.kt" >> "$BUILD/sources.txt"

# ORDER MATTERS: extensions-lib ships *stubs* of androidx.preference, so the real
# AndroidX libraries must come first on the classpath (same rule as in
# app/build.gradle.kts, where they are declared before extensions-lib).
CP="$ANDROID_JAR:$D/pref.jar:$D/prefktx.jar:$D/appcompat.jar:$(ls "$D"/*.jar | grep -vE '/(android-all|pref|prefktx|appcompat)[.]jar$' | tr "\n" ":")"

# Tiny compile-time stub of rx.Observable (a HOST class). It is compiled into
# its own directory, added to the classpath, and never dexed: several forks
# (AniZen) still expose the legacy RxJava entry points of AnimeCatalogueSource
# as abstract methods, so the sources must reference the type.
JAVAC="${JAVAC:-${JAVA_HOME:+$JAVA_HOME/bin/javac}}"
JAVAC="${JAVAC:-javac}"
mkdir -p "$BUILD/rxstub"
"$JAVAC" -nowarn -d "$BUILD/rxstub" "$ROOT/tools/stubs/rx/Observable.java" \
  || { echo "javac failed: cannot compile tools/stubs/rx/Observable.java" >&2; exit 1; }
CP="$CP:$BUILD/rxstub"
# "$JAVA" -version
# jvm-target: 17 by default, downgraded automatically when the JVM in use is
# older (a JDK 11 refuses `-jvm-target 17`).
JVM_TARGET="${JVM_TARGET:-}"
if [[ -z "$JVM_TARGET" ]]; then
  JVM_MAJOR="$("$JAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  JVM_TARGET=17
  [[ -n "$JVM_MAJOR" && "$JVM_MAJOR" -lt 17 ]] && JVM_TARGET=11
fi
echo "  jvm-target: $JVM_TARGET (java $("$JAVA" -version 2>&1 | head -1))"

"$KOTLINC" -jvm-target "$JVM_TARGET" -nowarn -Xplugin="$SERIAL_PLUGIN" \
  -opt-in=com.lagradost.cloudstream3.InternalAPI \
  -opt-in=com.lagradost.cloudstream3.Prerelease \
  -opt-in=kotlinx.serialization.ExperimentalSerializationApi \
  -cp "$CP" @"$BUILD/sources.txt" -d "$BUILD/classes"

# ------------------------------------------------------------------- 4. dex
say "Dexing (D8, min-api 26)"
mkdir -p "$BUILD/dex"
(cd "$BUILD/classes" && jar --create --file "$BUILD/mine.jar" .)
"$JAVA" -Xmx1500m -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 26 \
  --output "$BUILD/dex" \
  "$BUILD/mine.jar" "$D/cs.jar" "$D/nicehttp.jar" "$D/ser.jar" "$D/sercore.jar" \
  "$D/jackson-core.jar" "$D/jackson-databind.jar" "$D/jackson-annotations.jar" "$D/jackson-module-kotlin.jar" \
  "$D/rhino.jar" "$D/ktor-http.jar" "$D/ktor-utils.jar" "$D/ktor-events.jar" "$D/datetime.jar" \
  "$D/kio.jar" "$D/bytestring.jar" "$D/atomicfu.jar" "$D/crypto-core.jar" "$D/crypto-opt.jar" \
  "$D/kotlin-reflect.jar" "$D/fuzzywuzzy.jar" "$D/ksoup.jar" \
  "$D/ktor-io.jar" "$D/stately-concurrency.jar" "$D/stately-common.jar" \
  "$D/crypto-bigint.jar" "$D/crypto-random.jar" "$D/crypto-provider.jar" "$D/crypto-provider-base.jar" "$D/crypto-asn1.jar" "$D/charset.jar" "$D/fleekio.jar" "$D/gson.jar"

# ------------------------------------------------------------- 5. resources
VER_NAME="${VER_NAME:-17.1}"
VER_CODE="${VER_CODE:-1}"

say "Compiling resources (aapt2)"
rm -rf "$BUILD/res"; mkdir -p "$BUILD/res"
cp -r "$ROOT"/app/src/main/res/* "$BUILD/res/" && rm -f "$BUILD/res/web_hi_res_512.png"
(cd "$BUILD" && "$AAPT2" compile --dir res -o compiled.zip)
# AGP takes the package from the namespace; aapt2 needs it in the manifest.
sed -e 's|<manifest xmlns:android=|<manifest package="eu.kanade.tachiyomi.animeextension.all.csbridge" xmlns:android=|' \
  "$ROOT/app/src/main/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$BUILD/AndroidManifest.xml" \
  -o "$BUILD/unsigned.apk" --min-sdk-version 26 --target-sdk-version 36 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" --auto-add-overlay "$BUILD/compiled.zip"

# ---------------------------------------------------------- 6. dex + sign
say "Packaging"
(cd "$BUILD/dex" && jar --update --file "$BUILD/unsigned.apk" classes*.dex)

# D8 only keeps classes: ServiceLoader descriptors must be copied by hand.
# Without this one, dev.whyoleg.cryptography has no provider at all and every
# Cloudstream plugin that registers an extractor fails to load
# ("No providers registered").
say "Adding ServiceLoader resources"
rm -rf "$BUILD/svc"; mkdir -p "$BUILD/svc"
(cd "$BUILD/svc" && unzip -o -q "$D/crypto-provider.jar" 'META-INF/services/*')
ls "$BUILD/svc/META-INF/services/" | sed 's/^/  /'
(cd "$BUILD/svc" && jar --update --file "$BUILD/unsigned.apk" META-INF)
LD_LIBRARY_PATH="$CACHE/bt/android-14/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KS="$ROOT/tools/bridge.keystore"
if [[ ! -s "$KS" ]]; then
  keytool -genkeypair -v -keystore "$KS" -storetype PKCS12 -alias csbridge -keyalg RSA \
    -keysize 2048 -validity 10950 -storepass csbridge -keypass csbridge \
    -dname "CN=Cloudstream Bridge, OU=Aniyomi, O=CSBridge, C=MG" >/dev/null
fi
"$JAVA" -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-type PKCS12 --ks-key-alias csbridge \
  --ks-pass pass:csbridge --key-pass pass:csbridge --min-sdk-version 26 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/csbridge.apk" "$BUILD/aligned.apk"

say "Done"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT/csbridge.apk" | sed 's/^/    /'

# ------------------------------------------- 7. Aniyomi repository index
# repo/ can be served as-is (raw.githubusercontent.com works): index.min.json
# is the catalogue Aniyomi reads, repo/apk/<apk> is what it downloads.
say "Updating the repository index"
FINGERPRINT=$("$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT/csbridge.apk" \
  | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr 'A-Z' 'a-z')
mkdir -p "$ROOT/repo/apk"
cp "$OUT/csbridge.apk" "$ROOT/repo/apk/csbridge.apk"
cat > "$ROOT/repo/index.min.json" <<JSON
[
  {
    "name": "Cloudstream Bridge",
    "pkg": "eu.kanade.tachiyomi.animeextension.all.csbridge",
    "apk": "csbridge.apk",
    "lang": "all",
    "code": $VER_CODE,
    "version": "$VER_NAME",
    "nsfw": 0,
    "sources": [
      {
        "name": "Cloudstream Bridge",
        "lang": "all",
        "id": "-7830516264863407733",
        "baseUrl": ""
      }
    ]
  }
]
JSON
echo "$FINGERPRINT" > "$ROOT/repo/signing-fingerprint.txt"
echo "  fingerprint: $FINGERPRINT"
echo "  repo/index.min.json: v$VER_NAME (code $VER_CODE)"

ls -lh "$OUT/csbridge.apk"
