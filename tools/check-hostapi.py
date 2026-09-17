#!/usr/bin/env python3
"""Compare the abstract members of a host source-api against our classes.

Why this exists
---------------
ART turns a class that does not implement every abstract method of its
interfaces into an *abstract* class: ``newInstance()`` then throws and
Aniyomi-family hosts drop the whole extension with no visible error
(``LoadResult.Error``).  Different forks disagree on which members are
abstract (Aniyomi keeps the suspend API abstract, AniZen keeps the legacy
RxJava one), so our sources have to satisfy the union of all of them.

Usage:  check-hostapi.py <host-jar> <label> [--classes ClassA ClassB ...]
"""
import re
import subprocess
import sys

JAVAP = "/usr/lib/jvm/jdk-11/bin/javap"

INTERFACES = [
    "eu.kanade.tachiyomi.animesource.AnimeSource",
    "eu.kanade.tachiyomi.animesource.AnimeCatalogueSource",
    "eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource",
]

OUR_CLASSES = [
    "aniyomi.csbridge.source.CsHubSource",
    "aniyomi.csbridge.source.CsAnimeSource",
]

METHOD_LINE = re.compile(r"^\s*public\b.*\(.*\)\s*;\s*$")


def split_args(a):
    """Split a javap argument list on top-level commas (generics nest)."""
    out, depth, cur = [], 0, ""
    for ch in a:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def norm(t):
    t = re.sub(r"<[^<>]*>", "", t)            # drop generics
    t = t.replace("...", "[]")
    t = t.replace("? super", "").replace("? extends", "")
    t = t.replace("final ", "").strip()
    return "".join(t.split())


def sig(name, args):
    if isinstance(args, str):
        args = split_args(args)
    return "%s(%s)" % (name, ",".join(norm(a) for a in args))


def javap(cp, target):
    return subprocess.run([JAVAP, "-cp", cp, target],
                          capture_output=True, text=True).stdout


def _parse(cp, target, want_abstract):
    res = {}
    for line in javap(cp, target).splitlines():
        if not METHOD_LINE.match(line):
            continue
        if want_abstract and "abstract" not in line:
            continue
        body = line[line.find("public ") + len("public "):]
        for mod in ("abstract ", "final ", "static "):
            body = body.replace(mod, "")
        body = body.rstrip(";").strip()
        if "(" not in body:
            continue
        name = body[:body.find("(")].split()[-1]
        args = body[body.find("(") + 1:body.rfind(")")]
        res[sig(name, args)] = line.strip()
    return res



def default_stubs(cp, cls):
    """Methods whose body is only an `invokespecial` to an interface default.

    Kotlin generates those when a class relies on an interface default method.
    Hosts that declare the member abstract (AniZen, Komikku...) then throw
    AbstractMethodError at runtime - so every one of them must be implemented
    for real in our sources.
    """
    out = subprocess.run([JAVAP, "-c", "-p", "-cp", cp, cls],
                         capture_output=True, text=True).stdout
    bad, current = [], None
    for line in out.splitlines():
        if line.startswith("  ") and line.rstrip().endswith(");"):
            current = line.strip().rstrip(";")
            continue
        if "invokespecial" in line and "eu/kanade/tachiyomi/animesource/" in line \
                and "InterfaceMethod" in line:
            bad.append((current, line.strip()))
    return bad


def public_methods(cp, cls):
    return _parse(cp, cls, False)


def abstract_methods(cp, iface):
    return _parse(cp, iface, True)


def main():
    jar = sys.argv[1]
    label = sys.argv[2]
    deps = jar + ":" + sys.argv[3] if len(sys.argv) > 3 else jar
    classes = OUR_CLASSES

    required = {}
    for iface in INTERFACES:
        got = abstract_methods(deps, iface)
        if got:
            required.setdefault(iface, {}).update(got)

    print("== host: %s (%d abstract members)" %
          (label, sum(len(v) for v in required.values())))
    fail = 0
    for cls in classes:
        have = public_methods(deps + ":/home/user/csbridge/tools/.cache/build/classes", cls)
        if not have:
            print("   %s: javap produced nothing (is it compiled?)" % cls)
            fail = 1
            continue
        missing = []
        for iface, methods in required.items():
            for s, decl in methods.items():
                if s in have:
                    continue
                name = s.split("(")[0]
                if not any(k.split("(")[0] == name for k in have):
                    missing.append((s, decl))
        status = "OK" if not missing else "MISSING %d" % len(missing)
        print("   %-24s %s  (%d methods implemented)" %
              (cls.rsplit(".", 1)[-1], status, len(have)))
        for s, decl in missing:
            print("       - %s" % decl)
            fail = 1
        stubs = default_stubs(deps + ":/home/user/csbridge/tools/.cache/build/classes", cls)
        if stubs:
            print("       ! %d method(s) only delegate to an interface default "
                  "(AbstractMethodError on hosts where it is abstract):" % len(stubs))
            for name, insn in stubs:
                print("         %s" % (name or "?"))
            fail = 1
        else:
            print("       no interface-default delegation stub")
    return fail


if __name__ == "__main__":
    sys.exit(main())
