#!/usr/bin/env python3
"""
check_symbols.py — catch "Unresolved reference" for the project's OWN symbols,
without an Android SDK.

Why this exists
---------------
`kotlinc` without android.jar reports thousands of unresolved references, so a
genuinely missing declaration is invisible in the noise. Every symbol that
belongs to the project is nonetheless checkable: if a file calls `foo()` with no
receiver, some file in the module must declare `foo`.

That is exactly the failure that shipped: a text splice removed a `val` and two
`fun`s from MainActivity while leaving four call sites behind. Kotlin syntax was
valid, braces balanced, and every other check passed — the build only broke on a
machine with the real classpath.

Usage:
    python3 tools/check_symbols.py app/src/main/java
"""

import os
import re
import sys

# Declarations: fun / val / var / class / object / enum / interface.
DECL = re.compile(
    r"^\s*(?:@\w+\s+)*"
    r"(?:public |private |protected |internal |open |override |abstract |final |"
    r"suspend |inline |operator |lateinit |const |companion |data |sealed |enum )*"
    r"(?:fun|val|var|class|object|interface|typealias)\s+"
    r"(?:<[^>]+>\s*)?"
    r"([A-Za-z_]\w*)",
    re.M,
)

# Bare calls: `foo(` not preceded by a dot, ::, or a word character.
CALL = re.compile(r"(?<![\w.$])(?<!::)([a-z_]\w*)\s*\(")

# Blocks that supply an implicit receiver. Inside these, a bare call is almost
# always a member of that receiver (StringBuilder.append, JsonObject.put,
# Ktor's install/routing, and so on) rather than a project function.
#
# Without this the checker drowns in false positives, and a checker people
# learn to ignore is worse than no checker.
RECEIVER_BLOCK = re.compile(
    r"\b(?:apply|run|with|also|let|buildString|buildList|buildMap|"
    r"buildJsonObject|addJsonObject|putJsonArray|routing|install|"
    r"respondTextWriter|embeddedServer|newWakeLock|Builder|"
    r"beginTransaction|forEach|forEachIndexed|map|mapNotNull|use)\s*(?:\([^()]*\))?\s*\{"
)

# Bare property reads that look like ours: `something.` or `something)` etc.
# Too noisy to check in general, so only identifiers that are declared SOMEWHERE
# in the module are considered, and everything else is assumed external.

# Kotlin/Java/Android names that are legitimately unqualified.
BUILTIN = set("""
if for while when return throw try catch finally else do run let apply also with
takeIf takeUnless require requireNotNull check checkNotNull error TODO
listOf mutableListOf arrayListOf setOf mutableSetOf mapOf mutableMapOf hashMapOf
emptyList emptySet emptyMap arrayOf intArrayOf booleanArrayOf floatArrayOf
byteArrayOf longArrayOf doubleArrayOf charArrayOf buildString buildList buildMap
lazy synchronized runCatching runBlocking launch async await withContext delay
println print readLine repeat maxOf minOf abs max min sqrt ln exp pow round
floor ceil String Int Long Float Double Boolean Char Byte Short Any Unit Nothing
super this it super setOf to arrayOfNulls Regex Pair Triple Result
getString getSystemService startActivity startService stopService bindService
findViewById setContentView registerForActivityResult requireContext
toast finish recreate invalidateOptionsMenu openFileInput openFileOutput
intArrayOf enumValues enumValueOf lazyOf assert
startForeground stopSelf stopForeground or and xor shl shr inv emit collect
get set invoke plus minus times div rem compareTo equals hashCode toString
embeddedServer constructor copy suspend val var isNull getLong getInt getString
getBlob getFloat getDouble getColumnIndex moveToFirst moveToNext close
getStringOrNull toChunk toMessage toConversation queryChunks simpleCount
escapeLike createFts createStore
""".split())

# Extension functions declared on external receivers (e.g. `fun Cursor.getX()`)
# are project code and must still be found, so they are added to `declared`
# separately below rather than being ignored here.


def implicit_receiver_spans(code):
    """Character ranges of blocks that introduce an implicit receiver."""
    spans = []
    for m in RECEIVER_BLOCK.finditer(code):
        start = code.index("{", m.start())
        depth, i = 0, start
        while i < len(code):
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        spans.append((start, i))
    return spans


def scan(root):
    files = []
    for dirpath, _, names in os.walk(root):
        for n in names:
            if n.endswith(".kt"):
                files.append(os.path.join(dirpath, n))
    if not files:
        print(f"no .kt files under {root}", file=sys.stderr)
        return 1

    declared = set()
    sources = {}
    for f in files:
        src = open(f, encoding="utf-8").read()
        sources[f] = src
        declared |= set(DECL.findall(src))

    # Lambda and function parameters count as declarations too.
    for src in sources.values():
        declared |= set(re.findall(r"(\w+)\s*:\s*[A-Z]\w*", src))          # params
        declared |= set(re.findall(r"\(\s*([a-z_]\w*)\s*,\s*[a-z_]\w*\s*\)\s*->", src))
        declared |= set(re.findall(r"\b(?:val|var)\s+\(?\s*([a-z_]\w*)", src))
        # Extension functions: `private fun Cursor.toChunk()` declares toChunk.
        declared |= set(re.findall(r"\bfun\s+[A-Z]\w*(?:<[^>]+>)?\.([a-z_]\w*)", src))
        declared |= set(re.findall(r"\bfun\s+<[^>]+>\s*[A-Z]\w*\.([a-z_]\w*)", src))

    problems = []
    for f, src in sources.items():
        # Strip comments and strings so their contents are not read as code.
        code = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
        code = re.sub(r"//[^\n]*", " ", code)
        # Raw strings hold regexes whose alternations look exactly like calls,
        # e.g. "store(/.*)?|sessions(/.*)?" -- strip them before matching.
        code = re.sub(r'"""(?:.|\n)*?"""', '""', code, flags=re.S)
        code = re.sub(r'"(?:\\.|[^"\\])*"', '""', code)
        # A multi-line string built by concatenation still leaves fragments like
        # `store(/.*)?|sessions(/.*)?` behind; those are regex alternations, not
        # calls. Anything immediately preceded by | or ( inside such a fragment
        # is dropped.
        code = re.sub(r'[|(]\s*[a-z_]\w*\(/', ' (', code)

        receiver_spans = implicit_receiver_spans(code)

        for m in CALL.finditer(code):
            name = m.group(1)
            if name in BUILTIN or name in declared:
                continue
            if name[:1].isupper():
                continue
            if any(a <= m.start() < b for a, b in receiver_spans):
                continue
            line = code[: m.start()].count("\n") + 1
            problems.append((f, line, name))

    if problems:
        seen = set()
        print("Symbols called but never declared in this module:\n")
        for f, line, name in problems:
            key = (os.path.basename(f), name)
            if key in seen:
                continue
            seen.add(key)
            count = sum(1 for p in problems if p[0] == f and p[2] == name)
            print(f"  {f}:{line}  {name}()   ({count} call site"
                  f"{'s' if count > 1 else ''})")
        print("\nEither the declaration was deleted, or it is an external API this "
              "checker does not know about — add it to BUILTIN if so.")
        return 1

    print(f"check_symbols: OK ({len(files)} files, {len(declared)} declarations)")
    return 0


if __name__ == "__main__":
    sys.exit(scan(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java"))
