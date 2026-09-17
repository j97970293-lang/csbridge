#!/usr/bin/env bash
# Verifies that every source class of the bridge implements ALL the abstract
# members required by the different Aniyomi-family hosts:
#   * Aniyomi / extensions-lib 17   -> suspend API abstract
#   * AniZen / Komikku source-api   -> legacy RxJava API (fetch*) abstract
# A class missing one of them is silently turned into an abstract class by ART,
# `newInstance()` then throws and the host hides the whole extension.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${CACHE:-$ROOT/tools/.cache}"
JAVAP="${JAVA_HOME:+$JAVA_HOME/bin/javap}"; JAVAP="${JAVAP:-javap}"
command -v "$JAVAP" >/dev/null 2>&1 || JAVAP="/usr/lib/jvm/jdk-11/bin/javap"
command -v "$JAVAP" >/dev/null 2>&1 || { echo "javap not found (set JAVA_HOME)"; exit 1; }
CLASSES="$CACHE/build/classes"
EXT="$CACHE/deps/extlib.jar"
[[ -d "$CLASSES" ]] || { echo "no compiled classes at $CLASSES - run build-manual.sh first"; exit 1; }
echo "javap: $JAVAP"

# ---- 0. the class named in the manifest MUST exist -------------------------
# A wrong name here = ClassNotFoundException inside ExtensionLoader =
# LoadResult.Error = the host drops the extension with no visible error.
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
PKG=$(grep -o 'package="[^"]*"' "$MANIFEST" | head -1 | sed 's/package="//; s/"//')
CLSMETA=$(grep -A1 'tachiyomi.animeextension.class' "$MANIFEST" \
          | grep -o 'android:value="[^"]*"' | head -1 | sed 's/android:value="//; s/"//')
case "$CLSMETA" in
  .*) FQN="$PKG$CLSMETA" ;;
  *)  FQN="$CLSMETA" ;;
esac
FQNPATH="$CLASSES/$(echo "$FQN" | tr '.' '/').class"
echo "manifest entry point: $CLSMETA -> $FQN"
if [[ -f "$FQNPATH" ]]; then
  echo "   found: $FQNPATH"
else
  echo "   FAIL: $FQNPATH is missing (the host would throw ClassNotFoundException)"
  fail=1
fi

# ---- 1. abstract members demanded by the official extensions-lib stubs ------
mapfile -t REQ < <(
  for i in AnimeSource AnimeCatalogueSource ConfigurableAnimeSource; do
    "$JAVAP" -cp "$EXT" "eu.kanade.tachiyomi.animesource.$i" 2>/dev/null
  done | grep 'public abstract' | sed -E 's/^.*abstract //; s/;$//' | sort -u
)
echo "required (extensions-lib): ${#REQ[@]}"

# ---- 2. extra members that AniZen's source-api keeps abstract --------------
ANIZEN=(
  "rx.Observable fetchPopularAnime(int)"
  "rx.Observable fetchSearchAnime(int, java.lang.String, eu.kanade.tachiyomi.animesource.model.AnimeFilterList)"
  "rx.Observable fetchLatestUpdates(int)"
)

fail=0
for cls in aniyomi.csbridge.source.CsHubSource aniyomi.csbridge.source.CsAnimeSource; do
  echo "== $cls"
  sig=$("$JAVAP" -p -cp "$CLASSES:$EXT" "$cls" 2>/dev/null)
  [[ -n "$sig" ]] || { echo "   FAIL: javap produced nothing"; fail=1; continue; }
  decl=$(echo "$sig" | grep -E '^(public |final |abstract )*(final )?class ' | head -1)
  [[ -n "$decl" ]] && ! echo "$decl" | grep -q 'abstract' \
    || { echo "   FAIL: class is abstract or missing: ${decl:-<none>}"; fail=1; }
  have=$(echo "$sig" | grep -E '^ +public' | sed -E 's/^ +public (final )?//; s/;$//')
  missing=0
  for m in "${REQ[@]}" "${ANIZEN[@]}"; do
    name="${m%%\(*}"; name="${name##* }"
    echo "$have" | grep -qF "$name(" || { echo "   MISSING: $m"; missing=$((missing + 1)); fail=1; }
  done
  echo "   checked $((${#REQ[@]} + ${#ANIZEN[@]})) members, missing $missing"
done

# ---- 3. the rx stub must never be bundled (it is a HOST class) -------------
if [[ -d "$CLASSES/rx" ]]; then echo "   FAIL: rx stub compiled into the dex input"; fail=1; fi
if [[ -f "$ROOT/apk/csbridge.apk" ]]; then
  python3 - "$ROOT/apk/csbridge.apk" <<'CHECKPY'
import struct, sys, zipfile


def uleb(b, o):
    r = 0
    s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7F) << s
        if not x & 0x80:
            return r, o
        s += 7


# Minimal dex parser: list the classes actually DEFINED by the dex files. A
# *reference* to Lrx/Observable; in a method descriptor is expected, a
# definition would shadow the host's real class and must never happen.
z = zipfile.ZipFile(sys.argv[1])
defined = set()
for n in z.namelist():
    if not n.endswith('.dex') or n.startswith('META-INF'):
        continue
    d = z.read(n)
    if d[:4] != b'dex\n':
        continue
    (map_off, string_ids_size, string_ids_off,
     type_ids_size, type_ids_off,
     _prs, _pro, _fis, _fio, _mes, _meo,
     class_defs_size, class_defs_off) = struct.unpack_from('<13I', d, 0x34)
    strs = []
    for i in range(string_ids_size):
        off, = struct.unpack_from('<I', d, string_ids_off + 4 * i)
        ln, o = uleb(d, off)
        strs.append(d[o:o + ln].decode('utf-8', 'replace'))
    types = []
    if type_ids_size:
        idxs = struct.unpack_from('<%dI' % type_ids_size, d, type_ids_off)
        types = [strs[t] for t in idxs]
    for i in range(class_defs_size):
        idx, = struct.unpack_from('<I', d, class_defs_off + 32 * i)
        defined.add(types[idx])
bad = 'Lrx/Observable;' in defined
print("   rx.Observable stub defined inside the dex:", bad)
sys.exit(1 if bad else 0)
CHECKPY
  [[ $? -eq 0 ]] || { echo "   FAIL: rx stub leaked into the APK"; fail=1; }
fi

[[ $fail -eq 0 ]] && echo "ALL COMPAT CHECKS OK" || { echo "COMPAT CHECKS FAILED"; exit 1; }
