#!/usr/bin/env bash
#
# Runs tools/test-logic.kt on the JVM: repository URL normalisation, CDN proxy,
# file name sanitising, sha256 and the JSON models of the Cloudstream repository
# format (29 assertions). No emulator needed.
#
#   ./tools/run-tests.sh
#
# Same environment overrides as tools/build-manual.sh (JAVA_HOME, CACHE,
# DEPS_DIR, KOTLINC, ANDROID_JAR...). Run tools/build-manual.sh first so that
# the compiled classes are available in $CACHE/build/classes.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${CACHE:-$ROOT/tools/.cache}"
D="${DEPS_DIR:-$CACHE/deps}"
ANDROID_JAR="${ANDROID_JAR:-$D/android-all.jar}"
CLASSES="$CACHE/build/classes"
KOTLINC="${KOTLINC:-$CACHE/bin/kotlinc/bin/kotlinc}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"   # the test classes target Java 17

[[ -d "$CLASSES" ]] || { echo "no compiled classes in $CLASSES - run tools/build-manual.sh first" >&2; exit 1; }

mkdir -p "$CACHE/test"
CP="$ANDROID_JAR:$D/pref.jar:$D/prefktx.jar:$D/appcompat.jar:$(ls "$D"/*.jar | grep -vE '/(android-all|pref|prefktx|appcompat)[.]jar$' | tr '\n' ':')$CLASSES"

echo "==> Compiling tests"
"$KOTLINC" -jvm-target "${JVM_TARGET:-11}" -nowarn -cp "$CP" "$ROOT/tools/test-logic.kt" -d "$CACHE/test"

echo "==> Running tests"
"$JAVA" -cp "$CACHE/test:$CP" Test_logicKt
