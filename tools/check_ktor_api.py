#!/usr/bin/env python3
"""
Flags Ktor 1.x API names in a project that compiles against Ktor 2.x.

Why this is narrow, and why it exists anyway
--------------------------------------------
The sandbox this project is developed in has no Maven access, so nothing
here can compile against Ktor. Every Ktor call is written from reading the
library's source (github.com/ktorio/ktor at the pinned tag) and verified by
CI, ten minutes later, on someone else's attention.

v0.11.0 spent one of those round trips on `part.streamProvider().readBytes()`
in the multipart handler. That name is from Ktor 1.x. It *does* still exist
at 2.3.12, which is what makes it nasty: it survives as a JVM-only
**extension property** in ktor-http/jvm's MultipartJvm.kt

    public val PartData.FileItem.streamProvider: () -> InputStream
        get() = { provider().asStream() }

so it needs an import of its own, and without one the compiler reports
"Unresolved reference 'streamProvider'" — which reads like a removed API
rather than a missing import. The member is `provider(): Input`, and
`provider().readBytes()` is the idiom Ktor's own server code uses.

What this does NOT do
---------------------
This is a blacklist, not a type checker. It catches the names listed below
and nothing else. An import inventory was considered and rejected: the bug
above was an *unimported extension call*, so no amount of import checking
would have seen it.

The real guard is still CI. This exists so that one specific mistake, which
has already cost a build, cannot cost a second one — and so the next person
to reach for a Ktor 1.x idiom gets told what to use instead, here, rather
than from a compiler message that points the wrong way.

Adding to it: when CI rejects a Ktor symbol, add the name here with the 2.x
replacement. That is the whole maintenance model.

Run: python3 -I tools/check_ktor_api.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "java"

# name -> (what to use instead, where that was verified in the Ktor source)
KTOR_1X = {
    "streamProvider": (
        "PartData.FileItem.provider().readBytes()",
        "ktor-http/common/src/io/ktor/http/content/Multipart.kt — FileItem's member "
        "is `provider: () -> Input`; streamProvider survives only as a jvm-only "
        "extension property in ktor-http/jvm/.../MultipartJvm.kt and needs its own import",
    ),
    "ApplicationCallPipeline.Features": (
        "ApplicationCallPipeline.Plugins",
        "renamed in Ktor 2.0 when Features became Plugins",
    ),
    "io.ktor.application.": (
        "io.ktor.server.application.",
        "every server package gained a .server. segment in Ktor 2.0",
    ),
    "io.ktor.request.": (
        "io.ktor.server.request.",
        "every server package gained a .server. segment in Ktor 2.0",
    ),
    "io.ktor.response.": (
        "io.ktor.server.response.",
        "every server package gained a .server. segment in Ktor 2.0",
    ),
    "io.ktor.routing.": (
        "io.ktor.server.routing.",
        "every server package gained a .server. segment in Ktor 2.0",
    ),
    "io.ktor.features.": (
        "io.ktor.server.plugins.",
        "Features became Plugins in Ktor 2.0",
    ),
    "call.receiveOrNull": (
        "call.receiveNullable() or a try/catch around receive()",
        "receiveOrNull was removed in Ktor 2.0",
    ),
    "ContentNegotiation.Configuration": (
        "the plugin's own config lambda",
        "the Configuration class was removed in Ktor 2.0",
    ),
}

# Comment and string content is skipped: a doc comment explaining the very
# rename this file checks for is not a violation, and this file's own name
# list appears verbatim in LocalServer's comments.
LINE_COMMENT = re.compile(r"//.*$", re.M)
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
STRING_LIT = re.compile(r'"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"')


def strip_noise(src: str) -> str:
    """Blanks comments and string literals, preserving line numbers."""
    def blank(m: re.Match) -> str:
        return "".join("\n" if c == "\n" else " " for c in m.group(0))

    src = BLOCK_COMMENT.sub(blank, src)
    src = STRING_LIT.sub(blank, src)
    src = LINE_COMMENT.sub(blank, src)
    return src


def main() -> int:
    if not SRC.is_dir():
        print(f"check_ktor_api: FAIL — {SRC} not found", file=sys.stderr)
        return 1

    files = sorted(SRC.rglob("*.kt"))
    if not files:
        print("check_ktor_api: FAIL — no Kotlin sources found", file=sys.stderr)
        return 1

    failures = []
    for f in files:
        code = strip_noise(f.read_text(encoding="utf-8"))
        for lineno, line in enumerate(code.splitlines(), 1):
            for bad, (replacement, why) in KTOR_1X.items():
                if bad in line:
                    failures.append(
                        f"{f.relative_to(ROOT)}:{lineno}\n"
                        f"      found:   {bad}  (Ktor 1.x)\n"
                        f"      use:     {replacement}\n"
                        f"      because: {why}"
                    )

    if failures:
        print("check_ktor_api: FAIL")
        for x in failures:
            print(f"  {x}")
        return 1

    print(
        f"check_ktor_api: OK ({len(files)} files, "
        f"{len(KTOR_1X)} Ktor 1.x names checked for)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
