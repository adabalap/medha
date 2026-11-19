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

What it does NOT see
--------------------
Declarations that are not at the start of a line. DECL is anchored with
`^\\s*`, so a member written inline -- `class X { fun y() = 1 }` -- is not
recorded, and a call to it is reported as undeclared. Kotlin style puts
members on their own line, so this has never fired on real code; it showed up
writing the mutation fixtures, where three cases passed for the wrong reason
until they were spread over several lines.

Scope rules it DOES apply
-------------------------
`private` is file-scoped. A private declaration in one file does not satisfy
a call in another, for plain functions, extension functions and vals alike.
`internal`, `protected`, `open` and public all stay module-wide.

Usage:
    python3 tools/check_symbols.py app/src/main/java

Its own tests live in tools/tests/checker_mutations.sh.
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

def _names(block):
    """Whitespace-separated names from a block, ignoring `#` comment lines."""
    return {
        w
        for line in block.splitlines()
        if not line.strip().startswith("#")
        for w in line.split()
    }


# A project name may shadow a Kotlin/Android one for real. Each entry here is
# a deliberate decision, not a parking space for anything audit_builtin()
# complains about: adding a name here turns the check off for it, which is
# precisely how `toast` hid.
EXPECTED_COLLISIONS = {
    # kotlinx.coroutines.launch, called bare inside a CoroutineScope, vs
    # BriefActivity's `private fun launch(url: String): Boolean`. Both are
    # real and both are called bare. Dropping `launch` from BUILTIN would
    # report every coroutine launch in the project.
    "launch",
}


# Kotlin/Java/Android names that are legitimately unqualified.
#
# HOW THIS LIST IS BUILT, AND WHY IT IS NOT `.split()`
#
# It used to be `set("""...""".split())`, which splits the comment lines in
# the block below along with the names -- so every English word inside them
# became an allowlisted symbol. 39 of 235 entries were prose: `read`, `live`,
# `where`, `on`, `of`, `region`, `bare`, `own`, `string`, `receiver`. Any
# project function with one of those names was invisible to this checker,
# and the list grew another few every time someone explained something.
#
# Comment lines are dropped before splitting now. A `#` line is documentation;
# only the rest contributes names.
#
# NOTHING IN HERE MAY BE A NAME THIS PROJECT DECLARES PRIVATELY. That is what
# `toast` was: Medha's own `private fun toast` in MainActivity, sitting in a
# row of real Activity methods where it looked like one. It made every
# unresolved toast() call resolve, in every file, for five releases.
# audit_builtin() below enforces this, with EXPECTED_COLLISIONS for the one
# case where a project name genuinely shadows a Kotlin one.
BUILTIN = _names("""
sin cos tan asin acos atan atan2 sqrt cbrt exp ln log log10 log2 hypot floor ceil sign truncate
# Ktor extensions on ApplicationCall. Called bare inside this project's own
# `ApplicationCall.xxx()` helpers, where the receiver is implicit. These were
# invisible until the literal-stripping bug above was fixed, because the
# region of LocalServer.kt they live in was being read as string contents.
respond respondText respondBytes respondRedirect respondFile
receive receiveText receiveMultipart receiveParameters
if for while when return throw try catch finally else do run let apply also with
takeIf takeUnless require requireNotNull check checkNotNull error TODO
listOf mutableListOf arrayListOf setOf mutableSetOf mapOf mutableMapOf hashMapOf
linkedMapOf linkedSetOf sortedMapOf sortedSetOf emptyMap emptyList emptySet
sequenceOf emptySequence generateSequence buildList buildSet buildMap
emptyList emptySet emptyMap emptyArray arrayOf intArrayOf booleanArrayOf floatArrayOf
byteArrayOf longArrayOf doubleArrayOf charArrayOf buildString buildList buildMap
lazy synchronized runCatching runBlocking async await withContext withTimeout delay
println print readLine repeat maxOf minOf abs max min sqrt ln exp pow round
floor ceil String Int Long Float Double Boolean Char Byte Short Any Unit Nothing
super this it super setOf to arrayOfNulls Regex Pair Triple Result
getString getSystemService startActivity startService stopService bindService
findViewById setContentView registerForActivityResult requireContext
finish recreate invalidateOptionsMenu openFileInput openFileOutput
intArrayOf enumValues enumValueOf lazyOf assert
startForeground stopSelf stopForeground or and xor shl shr inv emit collect
get set invoke plus minus times div rem compareTo equals hashCode toString
getItem submitList notifyDataSetChanged notifyItemChanged notifyItemInserted
notifyItemRemoved notifyItemRangeChanged
setResult setPackage getCallingPackage getApplicationLabel getApplicationInfo
resolveActivity finishActivity
embeddedServer constructor copy suspend val var isNull getLong getInt getString
getBlob getFloat getDouble getColumnIndex moveToFirst moveToNext close
# kotlinx.coroutines.launch, called bare inside a CoroutineScope. This is the
# one entry that knowingly collides with a private project declaration --
# BriefActivity's `private fun launch(url: String)` -- so it is listed in
# EXPECTED_COLLISIONS rather than being quietly tolerated.
launch
""")

# Extension functions declared on external receivers (e.g. `fun Cursor.getX()`)
# are project code and must still be found, so they are added to `declared`
# separately below rather than being ignored here.



def strip_literals(src):
    """Blank out strings, chars and comments in ONE left-to-right pass.

    The previous version ran a sequence of regexes: block comments, then
    line comments, then strings. That ordering is wrong and it bit us. A
    line comment is stripped from the first `//` on the line -- including a
    `//` that is INSIDE a string, so `"http://127.0.0.1:$port/health"` lost
    everything from the slashes onward and left a `"` with no partner. Every
    quote after it in the file then paired up shifted by one, and whole
    regions of real code were read as string contents while string contents
    were read as code.

    It passed anyway for a long time, because the file happened to contain
    an even number of such URLs and the damage cancelled out. Adding one
    comment in v0.18.1 moved the boundary and the checker started reporting
    `found()` and `ready()` as undeclared functions -- both of which were
    fragments of `append("classes NOT found (")` and `"Model ready (...)"`.
    A checker that passes by luck is worse than no checker.

    Scanning once, in order, makes the question unambiguous: whatever opens
    first wins, so a `//` inside a string is text and a `"` inside a comment
    is nothing at all.

    String TEMPLATES are deliberately kept as code. `"${foo()}"` really does
    call foo(), and dropping the braces would hide genuine call sites.
    """
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            out.append('""')
            i = n if end == -1 else end + 3
        elif src[i] == '"':
            out.append('""')
            i += 1
            while i < n and src[i] != '"' and src[i] != '\n':
                if src[i] == '\\':
                    i += 2
                elif src.startswith('${', i):
                    # Template expression: real code, kept and scanned.
                    depth, j = 1, i + 2
                    while j < n and depth:
                        if src[j] == '{':
                            depth += 1
                        elif src[j] == '}':
                            depth -= 1
                        j += 1
                    out.append(' ' + src[i + 2:j - 1] + ' ')
                    i = j
                else:
                    i += 1
            i += 1
        elif src[i] == "'":
            out.append("''")
            i += 1
            while i < n and src[i] != "'" and src[i] != '\n':
                i += 2 if src[i] == '\\' else 1
            i += 1
        elif src.startswith('//', i):
            end = src.find('\n', i)
            out.append(' ')
            i = n if end == -1 else end
        elif src.startswith('/*', i):
            end = src.find('*/', i + 2)
            out.append(' ')
            i = n if end == -1 else end + 2
        else:
            out.append(src[i])
            i += 1
    return ''.join(out)


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

    # `private` is file-scoped, so a private declaration in one file must NOT
    # satisfy a call in another. Keeping one flat set of declarations made the
    # checker blind to a whole class of real failure, and it stayed blind for
    # five releases: MainActivity's `private fun toast` silently resolved
    # BriefActivity's `toast(...)`, and the first thing anyone heard about it
    # was :app:compileDebugKotlin failing on two lines the checker had read
    # and approved.
    declared = set()
    private_by_file = {}
    sources = {}
    for f in files:
        src = open(f, encoding="utf-8").read()
        sources[f] = src
        pub, priv = set(), set()
        for m in DECL.finditer(src):
            # `fun String.zzExt()` makes DECL capture the RECEIVER, not the
            # function. Recording it would declare the type `String`, and a
            # private extension would then declare it privately -- which the
            # allowlist audit below reads as "BUILTIN is hiding a project
            # symbol called String". The extension's real name is picked up
            # by the extension patterns further down.
            if src[m.end():m.end() + 1] == ".":
                continue
            (priv if "private " in m.group(0) else pub).add(m.group(1))
        declared |= pub
        private_by_file[f] = priv

    # Lambda and function parameters count as declarations too. These stay
    # module-wide on purpose: a parameter name is far more local than that,
    # but tightening it would reject ordinary code, and a parameter is almost
    # never the thing being CALLED.
    for src in sources.values():
        declared |= set(re.findall(r"(\w+)\s*:\s*[A-Z]\w*", src))          # params
        declared |= set(re.findall(r"\(\s*([a-z_]\w*)\s*,\s*[a-z_]\w*\s*\)\s*->", src))
        # Function-typed names are handled in the private-aware loop below,
        # NOT here: `private val zzFn: () -> Int` is callable as `zzFn()`,
        # and filing it module-wide would resolve that call from any file in
        # the project. That is the `toast` defect in a new costume, and the
        # mutation suite catches it -- the first draft of this fix put the
        # pattern on this line and `private val does not leak across files`
        # went red.

    # Extension functions, and vals that carry a modifier. Both honour
    # `private` the same way DECL does above -- the old code did not, and its
    # own comment gave `private fun Cursor.toChunk()` as the example while
    # filing it module-wide.
    for f, src in sources.items():
        priv = private_by_file[f]
        for rx in (r"\b(?:val|var)\s+\(?\s*([a-z_]\w*)",
                   r"\bfun\s+[A-Z]\w*(?:<[^>]+>)?\.([a-z_]\w*)",
                   r"\bfun\s+<[^>]+>\s*[A-Z]\w*\.([a-z_]\w*)",
                   # Anything whose declared type is a FUNCTION type, which
                   # the parameter patterns above cannot see: they key off a
                   # type name starting with a capital, and `onGrant: () ->
                   # Unit` starts with a bracket.
                   #
                   # This is the one shape that is routinely CALLED rather
                   # than passed, so missing it is not a harmless
                   # under-declaration -- it reports every callback
                   # invocation in the project as unresolved. Found by
                   # MainActivity's `confirmCaptures(subject, onGrant)`,
                   # where the checker flagged `onGrant()` and suggested
                   # BUILTIN: the same suggestion that put `toast` there.
                   #
                   # In this loop rather than the module-wide one above
                   # because `private` has to be honoured. A parameter is
                   # narrower than file scope anyway -- it is only in scope
                   # inside its own body -- so filing it per-file can never
                   # hide a call that should have resolved.
                   #
                   # The `->` is required rather than a bare `name: (`, so a
                   # named argument carrying a parenthesised expression does
                   # not become a declaration.
                   r"(\w+)\s*:\s*(?:suspend\s+)?\([^()]*\)\s*->"):
            for m in re.finditer(rx, src):
                start = src.rfind("\n", 0, m.start()) + 1
                head = src[start:m.start()]
                (priv if re.search(r"\bprivate\b", head) else declared).add(m.group(1))

    # Before checking any call: is the allowlist itself lying?
    #
    # A name in BUILTIN that this project declares ONLY as private is the
    # `toast` defect exactly. BUILTIN says "external, assume it resolves";
    # `private` says "resolves in one file only". Together they resolve it
    # everywhere, which is wrong everywhere but the one file -- and it fails
    # at compile time, long after this checker has said OK.
    #
    # A name also declared non-private is harmless: it resolves module-wide
    # through `declared` whether or not BUILTIN mentions it.
    private_only = set()
    for f, priv in private_by_file.items():
        private_only |= priv
    private_only -= declared
    masked = sorted((BUILTIN & private_only) - EXPECTED_COLLISIONS)
    if masked:
        print("The allowlist is hiding this project's own private symbols:\n")
        for name in masked:
            where = sorted(
                os.path.basename(f) for f, p in private_by_file.items() if name in p
            )
            print(f"  FAIL  {name}()   declared private in {', '.join(where)}, "
                  f"and listed in BUILTIN")
        print(
            "\nBUILTIN means 'external API, assume it resolves anywhere'. A\n"
            "private declaration resolves in one file. A name in both resolves\n"
            "everywhere and compiles nowhere but that file.\n\n"
            "Remove it from BUILTIN -- it already resolves where it is visible.\n"
            "Add it to EXPECTED_COLLISIONS only if a real external API shares\n"
            "the name, and say which one."
        )
        return 1

    problems = []
    for f, src in sources.items():
        # Strip comments and strings so their contents are not read as code.
        code = strip_literals(src)
        # A multi-line string built by concatenation still leaves fragments like
        # `store(/.*)?|sessions(/.*)?` behind; those are regex alternations, not
        # calls. Anything immediately preceded by | or ( inside such a fragment
        # is dropped.
        code = re.sub(r'[|(]\s*[a-z_]\w*\(/', ' (', code)

        receiver_spans = implicit_receiver_spans(code)
        visible_here = private_by_file[f]

        for m in CALL.finditer(code):
            name = m.group(1)
            if name in BUILTIN or name in declared or name in visible_here:
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
