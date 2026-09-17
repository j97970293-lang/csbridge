#!/usr/bin/env python3
"""Static sanity check of the produced APK.

Every type referenced by classes*.dex must be resolvable at runtime:
  * inside the APK itself (bundled),
  * in the Android framework,
  * or in a library the Aniyomi host app provides (okhttp, kotlin-stdlib,
    coroutines, the aniyomi source-api, androidx preference...).

Whatever is left over is a potential NoClassDefFoundError on the device.

Usage:
    python3 tools/check-dex.py [path/to/csbridge.apk]

It reuses the jars downloaded by tools/build-manual.sh (override with
$DEPS_DIR / $ANDROID_JAR).
"""
import collections
import os
import struct
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "..", "apk", "csbridge.apk")
HERE = os.path.dirname(os.path.abspath(__file__))
D = os.environ.get("DEPS_DIR", os.path.join(HERE, ".cache", "deps"))
ANDROID_JAR = os.environ.get("ANDROID_JAR", os.path.join(D, "android-all.jar"))

# NOTE: `appcompat.jar` is deliberately NOT part of the host jars. Aniyomi and
# its forks ship androidx.preference but NOT androidx.appcompat (an extension
# using AlertDialog.Builder from appcompat crashes with NoClassDefFoundError at
# runtime - it did). Anything appcompat-related must therefore use the
# framework android.app.AlertDialog instead.
HOST_JARS = ["kotlin-stdlib.jar", "okhttp.jar", "okio.jar", "jsoup.jar",
             "coroutines.jar", "extlib.jar", "pref.jar", "prefktx.jar",
             "ser.jar", "sercore.jar"]

# Referenced by code paths we know are optional / desktop-only / guarded.
KNOWN_OPTIONAL = (
    "com/uwetrottmann/tmdb2/",      # TmdbProvider (needs retrofit, not bundled)
    "org/schabi/newpipe/",          # NewPipe extractor (YouTube, desktop build)
    "jdk/dynalink/",                # Rhino optimiser (needs JDK 9 dynalink)
    "org/mozilla/javascript/xml",   # Rhino E4X, removed upstream
    "retrofit2/",                   # only reachable through TmdbProvider
    "org/slf4j/",                   # idem
    "java/beans/",                  # jackson/Rhino, probed with Class.forName
    "okhttp3/dnsoverhttps/",        # shipped by Aniyomi (okhttp-dnsoverhttps)
    "dalvik/annotation/",           # dex bookkeeping
    "rx/",                          # RxJava 1.x, shipped by the host app
    # whyoleg.cryptography: the provider we ship (JDK) optionally talks to
    # BouncyCastle and to its own PEM/ASN.1 modules for exotic curves
    # (Ed25519/Ed448/X448). Cloudstream extractors only use AES/RC4 with raw
    # keys, so these paths are guarded and never reached here.
    "org/bouncycastle/",
    "dev/whyoleg/cryptography/serialization/",
                                    # (only referenced: the legacy fetch* entry
                                    #  points required by AniZen/Komikku hosts)
    "java/", "javax/", "sun/", "jdk/", "org/w3c/", "org/xml/", "org/json/",
)

PRIMITIVES = set("BCDFIJSZV")


def read_uleb(b, off):
    result = shift = 0
    while True:
        x = b[off]
        off += 1
        result |= (x & 0x7F) << shift
        if not x & 0x80:
            return result, off
        shift += 7


def mutf8(b, off):
    _, start = read_uleb(b, off)
    return b[start:b.index(b"\x00", start)].decode("utf8", "replace")


def dex_types(data):
    u4 = lambda o: struct.unpack_from("<I", data, o)[0]
    str_size, str_off = u4(0x38), u4(0x3C)
    typ_size, typ_off = u4(0x40), u4(0x44)
    cls_size, cls_off = u4(0x60), u4(0x64)
    strings = [mutf8(data, u4(str_off + i * 4)) for i in range(str_size)]
    types = [strings[u4(typ_off + i * 4)] for i in range(typ_size)]
    defined = {types[u4(cls_off + i * 32)] for i in range(cls_size)}
    return types, defined, u4(0x58)


def norm(t):
    return t[1:-1] if t.startswith("L") and t.endswith(";") else t


def jar_classes(path):
    if not os.path.exists(path):
        return set()
    with zipfile.ZipFile(path) as z:
        return {n[:-6] for n in z.namelist() if n.endswith(".class")}


def main():
    with zipfile.ZipFile(APK) as z:
        dexes = sorted(n for n in z.namelist() if n.endswith(".dex"))

    types, defined, methods = set(), set(), 0
    for n in dexes:
        with zipfile.ZipFile(APK) as z:
            t, d, m = dex_types(z.read(n))
        types |= set(t)
        defined |= d
        methods += m
        print("%-14s %5.1f MB   methods %d" % (n, len(zipfile.ZipFile(APK).read(n)) / 1e6, m))

    defined = {norm(x) for x in defined}
    provided = jar_classes(ANDROID_JAR)
    for h in HOST_JARS:
        provided |= jar_classes(os.path.join(D, h))

    missing = collections.Counter()
    for t in types:
        if t[0] in PRIMITIVES or t.startswith("["):
            continue
        n = norm(t)
        if n in defined or n in provided:
            continue
        if n.startswith(KNOWN_OPTIONAL):
            continue
        missing[n] += 1

    print("types referenced : %d" % len(types))
    print("classes bundled  : %d" % len(defined))
    print("host classes     : %d" % len(provided))
    print("UNRESOLVED       : %d (excluding the known-optional list)" % len(missing))
    for n, c in missing.most_common(30):
        print("   %5d  %s" % (c, n))
    return 0 if not missing else 1


sys.exit(main())
