#!/usr/bin/env python3
"""
check_imports.py — catch "Unresolved reference: <Type>" for missing imports,
without an Android SDK.

Why this exists
---------------
check_symbols.py answers "is this function declared anywhere I can see it?".
It says nothing about TYPES, and a missing import fails the build in exactly
the same way, from exactly the same kind of edit.

v0.18.3 shipped both halves of that at once. BriefActivity called `toast(...)`
with no declaration in the file AND no `import android.widget.Toast`. Adding
the function alone would have produced a second red build naming the import;
adding the import alone, a second one naming the function. Nothing here could
see either, so the fix was guesswork until CI ran.

How it decides
--------------
A CamelCase name used in a file must be one of:

  * imported there (`import a.b.C`, or an alias `import a.b.C as D`),
  * declared in the same package by some file in this tree,
  * generated into the app package by the build (BuildConfig, R),
  * a type parameter the declaration introduces (`fun <Item> ...`),
  * on the DEFAULT list below -- Kotlin's own default imports plus the
    java.lang names Kotlin maps.

Anything else is a name the compiler will not resolve.

Why CamelCase specifically: a type reference starts uppercase AND contains a
lowercase letter. `TAG`, `TTL_MS`, `CHANNEL_ID` are constants, not types, and
need no import. Treating every capitalised word as a type reports 274 names
on this project; requiring a lowercase letter reports none.

This list is DELIBERATELY separate from check_symbols.BUILTIN. Sharing one
allowlist between "functions that resolve bare" and "types that need no
import" is how `toast` -- a project function -- ended up sitting in a row of
Android Activity methods, making every unresolved call to it invisible.

What it catches, measured
-------------------------
Every import in the project was dropped one at a time and the checker re-run
(312 of the 315 are actually needed; 3 are dead imports whose simple name
appears nowhere else, and staying silent on those is correct).

    caught                                   256 / 312
    missed: function imports (lowercase)      53
    missed: ALL-CAPS type names                3

So every CamelCase type import is caught -- 256 of 256 -- and the misses are
two known categories:

  * FUNCTION imports: `kotlinx.coroutines.launch`, `kotlin.math.sqrt`,
    `io.ktor.server.application.install`. A type checker cannot see these,
    and check_symbols cannot either, because most of them sit in its BUILTIN
    list precisely so that bare calls to them resolve. Dropping one of those
    53 still reaches CI unseen. It is the largest remaining hole in the
    pre-build checks and it is NOT closed.

  * ALL-CAPS type names: CIO, URL, PI. A type reference has to contain a
    lowercase letter, or every TAG and TTL_MS in the project gets reported.
    `R` is special-cased because it is the one that actually gets dropped.

Usage:
    python3 tools/check_imports.py app/src/main/java

Its own tests live in tools/tests/check_imports_mutations.sh, and the
drop-every-import measurement above is how the two bugs in its first draft
were found -- a loose `<...>` pattern that treated the Foo in `List<Foo>` as
a locally declared type parameter, and nested types being granted
package-wide visibility they do not have.
"""
import collections
import os
import pathlib
import re
import sys

# strip_literals is shared rather than copied: it is subtle (a `//` inside a
# string used to end the "comment" and swap code for string contents for the
# rest of the file), and two copies would drift.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from check_symbols import strip_literals  # noqa: E402

# Kotlin's default imports (kotlin.*, kotlin.collections.*, kotlin.text.*,
# kotlin.io.*) plus the java.lang names Kotlin makes available unqualified.
# Names only, one per line group; `#` lines are documentation, not entries.
DEFAULT = {
    w
    for line in """
String Int Long Float Double Boolean Char Byte Short Any Unit Nothing Number
Array List Map Set MutableList MutableMap MutableSet Collection Iterable
Sequence Pair Triple Result Regex RegexOption MatchResult Comparable
CharSequence Throwable Exception RuntimeException IllegalArgumentException
IllegalStateException UnsupportedOperationException NullPointerException
NumberFormatException IndexOutOfBoundsException ClassCastException
ArithmeticException ConcurrentModificationException NoSuchElementException
Error Comparator Lazy Function StringBuilder StringBuffer Thread Runnable
Runtime Math System Process ProcessBuilder Object
ByteArray ShortArray IntArray LongArray FloatArray DoubleArray BooleanArray
CharArray ArrayList HashMap HashSet LinkedHashMap LinkedHashSet TreeMap
Charsets Class Enum Annotation Iterator ListIterator Entry
Appendable Cloneable AutoCloseable Closeable Void Companion
Deprecated DeprecationLevel ReplaceWith JvmStatic JvmField JvmOverloads
JvmName Suppress Volatile Synchronized Strictfp Throws OptIn Target
Retention Repeatable MustBeDocumented Transient
""".splitlines()
    if not line.strip().startswith("#")
    for w in line.split()
}

# Names the Android build generates into the application package. They exist
# at compile time but in no .kt file, so nothing in this tree declares them.
GENERATED = {"BuildConfig", "R"}
APP_PACKAGE = "com.adabala.medha"

# A type reference: starts uppercase and contains a lowercase letter. Not
# preceded by a dot (that is member access on something already resolved) or
# by a word character.
TYPE_USE = re.compile(r"(?<![\w.])([A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*)")

# Types declared in a file. Indentation decides the scope, and it has to:
#
#   * At column 0 the type is top-level, so every file in the package can
#     name it without an import.
#   * Indented, it is NESTED inside another type. Kotlin does not make a
#     nested type visible to the rest of its package -- `NotificationFilter.
#     Posted` needs `import ...NotificationFilter.Posted` or the qualified
#     name, even one file away in the same package. Treating nested types as
#     package-level made that import droppable without complaint.
#
# A nested type stays known inside its OWN file, where it genuinely resolves
# bare.
# NB `[ \t]*`, not `\s*`: `\s` matches newlines, so with re.M the match can
# start at a blank line, swallow the newline, and report a top-level type as
# indented. Harmless while the result was one flat set; now that indentation
# decides scope it declared MainActivity, ConversationEntity and MessageEntity
# nested and broke three files that were fine.
TYPE_DECL = re.compile(
    r"^([ \t]*)(?:@\w+[ \t]+)*(?:\w+[ \t]+)*?"
    r"(?:class|object|interface|typealias)[ \t]+([A-Z]\w*)",
    re.M,
)

# Type parameters a declaration INTRODUCES. Anchored to the declaration forms
# that can introduce one, because `<...>` on its own is usually a generic
# ARGUMENT, not a parameter: a loose `<([A-Za-z0-9_,\s:?]+)>` treats the Foo
# in `List<Foo>` as locally declared and stops checking it. That hid 6 of the
# project's real imports -- View, ImageView, TextView, JSONArray, File,
# Content -- every one of which can be dropped without this checker noticing.
TYPE_PARAMS = re.compile(
    r"\bfun\s*<([^>]+)>|\b(?:class|interface)\s+\w+\s*<([^>]+)>"
)

# `R` is the most-dropped import in an Android project and TYPE_USE cannot
# see it: a type reference is required to contain a lowercase letter, which
# is what keeps TAG and TTL_MS from being reported, and R has none. It is
# generated into the application package, so only a file in that exact
# package may use it unqualified; anything in a subpackage needs the import.
R_USE = re.compile(r"(?<![\w.])R\.(?:string|drawable|layout|id|color|dimen|"
                   r"style|xml|array|plurals|raw|mipmap|attr|bool|integer|menu)\b")


def kt_files(root):
    return sorted(
        os.path.join(d, n)
        for d, _, ns in os.walk(root)
        for n in ns
        if n.endswith(".kt")
    )


def check(root):
    files = kt_files(root)
    if not files:
        print(f"no .kt files under {root}", file=sys.stderr)
        return 1

    srcs, pkg_of = {}, {}
    by_package = collections.defaultdict(set)
    nested_in_file = collections.defaultdict(set)
    for f in files:
        s = open(f, encoding="utf-8").read()
        srcs[f] = s
        m = re.search(r"^package\s+([\w.]+)", s, re.M)
        pkg_of[f] = m.group(1) if m else ""
        for indent, name in TYPE_DECL.findall(s):
            if indent:
                nested_in_file[f].add(name)
            else:
                by_package[pkg_of[f]].add(name)
    by_package[APP_PACKAGE] |= GENERATED

    problems = []
    for f in files:
        s = srcs[f]
        imported = set()
        for m in re.finditer(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", s, re.M):
            imported.add(m.group(2) or m.group(1).rsplit(".", 1)[-1])

        code = strip_literals(s)
        code = re.sub(r"^\s*(?:package|import)\s+.*$", "", code, flags=re.M)

        params = set()
        for m in TYPE_PARAMS.finditer(code):
            for group in m.groups():
                if not group:
                    continue
                for part in group.split(","):
                    name = part.split(":")[0].strip().rstrip("?")
                    if name:
                        params.add(name)

        known = (DEFAULT | imported | by_package[pkg_of[f]]
                 | nested_in_file[f] | params)
        seen = {}
        if pkg_of[f] != APP_PACKAGE and "R" not in imported:
            m = R_USE.search(code)
            if m:
                seen["R"] = code[: m.start()].count("\n") + 1
        for m in TYPE_USE.finditer(code):
            n = m.group(1)
            if n in known:
                continue
            seen.setdefault(n, code[: m.start()].count("\n") + 1)
        for n, line in sorted(seen.items(), key=lambda kv: kv[1]):
            problems.append((f, line, n))

    if problems:
        print("Types used but neither imported nor declared in this module:\n")
        for f, line, name in problems:
            print(f"  {f}:{line}  {name}")
        print(
            "\nEither the import was dropped, or the type lives in another\n"
            "package and needs one. If it is a Kotlin or java.lang name that\n"
            "needs no import, add it to DEFAULT in this file -- and only if it\n"
            "genuinely is one. Do not add a type this project declares: that\n"
            "turns the check off for it, which is how check_symbols went blind\n"
            "to its own `toast`."
        )
        print(f"\ncheck_imports: {len(problems)} unresolved type reference(s)")
        return 1

    total = sum(len(v) for v in by_package.values()) + sum(
        len(v) for v in nested_in_file.values())
    print(f"check_imports: OK ({len(files)} files, {total} types in "
          f"{len(by_package)} packages)")
    return 0


if __name__ == "__main__":
    sys.exit(check(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java"))
