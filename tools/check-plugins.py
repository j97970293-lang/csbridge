#!/usr/bin/env python3
"""Checks real Cloudstream plugins (.cs3) against what our APK actually ships.

Every class a plugin references but that is neither defined inside our APK nor
provided by Android/the host shows up as a NoClassDefFoundError at runtime
(e.g. com.lagradost.cloudstream3.network.CloudflareKiller, which lives in the
Cloudstream *app* module, not in library-android).

Usage:
    python3 tools/check-plugins.py apk/csbridge.apk <plugin.cs3> [<plugin.cs3> ...]
"""
import struct
import sys
import zipfile
from collections import defaultdict

PKG = ("Lcom/lagradost/", "Lcom/uwetrottmann/", "Landroidx/", "Lorg/jsoup/")


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7F) << s
        if not x & 0x80:
            return r, o
        s += 7


def dex_types(data):
    """(defined_classes, all_referenced_types) of one dex blob."""
    (mo, sis, sio, tis, tio, ps, po, fis, fio, mes, meo, cds, cdo) = struct.unpack_from("<13I", data, 0x34)
    strings = []
    for i in range(sis):
        off, = struct.unpack_from("<I", data, sio + 4 * i)
        ln, o = uleb(data, off)
        strings.append(data[o:o + ln].decode("utf8", "replace"))
    types = [strings[t] for t in struct.unpack_from("<%dI" % tis, data, tio)]
    defined = {types[struct.unpack_from("<I", data, cdo + 32 * i)[0]] for i in range(cds)}
    return defined, set(types)


def load(path):
    """-> (defined, referenced) from an APK or a .cs3."""
    defined, referenced = set(), set()
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith(".dex"):
                d, r = dex_types(z.read(n))
                defined |= d
                referenced |= r
    return defined, referenced


def short(name):
    return name[1:-1].replace("/", ".")


def main():
    apk = sys.argv[1]
    plugins = sys.argv[2:]
    shipped, _ = load(apk)
    missing = defaultdict(set)
    total = 0
    for p in plugins:
        defined, referenced = load(p)
        total += len([c for c in defined if c.startswith("Lcom/lagradost/")])
        for t in sorted(referenced):
            if t.startswith(PKG) and t not in shipped:
                # a plugin may define its own helper classes
                if t in defined:
                    continue
                missing[t].add(p.split("/")[-1])
    if not missing:
        print("OK: every Cloudstream/Android type used by the plugins is shipped.")
        return 0
    print("%d missing type(s) referenced by %d plugin(s):\n" % (len(missing), len(plugins)))
    for t in sorted(missing, key=lambda t: -len(missing[t])):
        users = ", ".join(sorted(missing[t])[:4])
        more = "" if len(missing[t]) <= 4 else " (+%d)" % (len(missing[t]) - 4)
        print("  %-72s %s%s" % (short(t), users, more))
    return 1


if __name__ == "__main__":
    sys.exit(main())
