#!/usr/bin/env bash
# Reproduces, on the JVM, the exact path an Aniyomi-family host takes to load an
# extension APK:
#
#   PackageManager  -> isPackageAnExtension (uses-feature)
#   -> ChildFirstPathClassLoader (system loader first, then the APK, then parent)
#   -> Class.forName(meta-data "tachiyomi.animeextension.class").newInstance()
#   -> AnimeSourceFactory.createSources()
#
# AniZen does not use the same abstract members as Aniyomi (it keeps the legacy
# RxJava entry points abstract), and a class missing one of them becomes
# abstract -> newInstance() throws -> the host silently drops the extension.
# This test loads the REAL AniZen interfaces (fetched from GitHub, compiled
# locally) so the check is done against the actual contract.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${CACHE:-$ROOT/tools/.cache}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}";   JAVA="${JAVA:-java}"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/javac}"; JAVAC="${JAVAC:-javac}"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/jar}";     JAR="${JAR:-jar}"
KOTLINC="${KOTLINC:-$CACHE/bin/kotlinc/bin/kotlinc}"
ANIZEN="https://raw.githubusercontent.com/salmanbappi/AniZen/master/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/animesource"
SRC="$CACHE/hostapi/src"
OUT="$CACHE/hostapi/out2"
CLASSES="$CACHE/build/classes"
[[ -d "$CLASSES" ]] || { echo "no compiled classes - run build-manual.sh first"; exit 1; }

mkdir -p "$SRC" "$OUT" "$CACHE/hostapi/androidstub" "$CACHE/hostapi/test"

# 1. AniZen's real source-api interfaces -------------------------------------
for f in AnimeSource.kt AnimeCatalogueSource.kt AnimeSourceFactory.kt; do
  if [[ ! -s "$SRC/$f" ]]; then
    echo "fetch  $f"
    curl -sL "$ANIZEN/$f" -o "$SRC/$f" || { echo "cannot fetch $f"; exit 1; }
  fi
done

# 2. minimal stubs of the host classes (JVM 11 compatible) -------------------
"$KOTLINC" -nowarn -jvm-target 11 -cp "$CACHE/build/rxstub:$CACHE/deps/kotlin-stdlib.jar" \
  "$ROOT/tools/hostemu/stubs/Stubs.kt" \
  "$ROOT/tools/hostemu/stubs/Pref.kt" \
  "$ROOT/tools/hostemu/stubs/Config.kt" \
  "$SRC/AnimeSource.kt" "$SRC/AnimeCatalogueSource.kt" "$SRC/AnimeSourceFactory.kt" \
  "$ROOT/tools/hostemu/stubs/AwaitStub.kt" "$ROOT/tools/hostemu/stubs/AwaitStub2.kt" \
  -d "$OUT" || { echo "kotlinc failed"; exit 1; }
(cd "$OUT" && "$JAR" --create --file ../anizen-api11.jar .) || exit 1
"$JAVAC" -nowarn -d "$CACHE/hostapi/androidstub" $(find "$ROOT/tools/hostemu/stubs/android" -name '*.java') || exit 1

# 3. the load test itself -----------------------------------------------------
"$KOTLINC" -nowarn -jvm-target 11 -cp "$CACHE/deps/kotlin-stdlib.jar" \
  "$ROOT/tools/hostemu/LoadTest.kt" -d "$CACHE/hostapi/test" || exit 1

DEPS=$(ls "$CACHE"/deps/*.jar | grep -vE 'extlib|android-all' | tr '\n' ':')
CP="$CACHE/hostapi/anizen-api11.jar:$CACHE/hostapi/androidstub:$DEPS$CACHE/deps/kotlin-stdlib.jar:$CACHE/build/rxstub:$CACHE/hostapi/test"
"$JAVA" -cp "$CP" LoadTestKt "$CLASSES"
