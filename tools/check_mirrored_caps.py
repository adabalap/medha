#!/usr/bin/env python3
"""
Asserts the server's limits and the copies of them in the sample clients
still agree.

WHY THIS EXISTS
---------------
Medha's reference clients mirror the server's caps deliberately: a client
that trims locally shows the user the loss where they can see it, rather
than posting 20 items and being handed back 5 with no explanation. The
mirroring is the right design and it has one failure mode, which is that a
mirror drifts.

It drifted. BriefPolicy.MAX_ITEMS_PER_SECTION was raised from 5 to 20 --
specifically because the cap was unwanted -- and both reference clients kept
their own MAX_ITEMS at 5. So samples/medha_brief.py and samples/medha-brief.js,
the files every local client and every PWA copies, silently truncated every
post to five items and returned success. Nothing failed anywhere: the server
would have accepted all twenty, and the loss happened client-side before the
request was sent.

It survived because the tests were written against literals. Both suites
asserted "capped at five" using six valid items, which passed while the cap
was 5 and kept passing after it moved, for the wrong reason. Those fixtures
now derive from the constant; this file is the other half, because a test
can only check the client against itself.

WHAT IT DOES NOT DO
-------------------
It is not a parser. Each value is pulled out with one anchored regex, and a
value it cannot find is an ERROR rather than a pass -- a renamed constant
must break this loudly instead of quietly disabling the check it was the
subject of.

Run: python3 -I tools/check_mirrored_caps.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

failures = []
checks = 0


def fail(msg):
    failures.append(msg)
    print(f"  FAIL  {msg}")


def ok(msg):
    print(f"  ok    {msg}")


def check(msg, condition):
    global checks
    checks += 1
    if condition:
        ok(msg)
    else:
        fail(msg)


def find(path, pattern, what):
    """The one capture group of `pattern` in `path`, or None with a failure.

    A missing value is reported rather than skipped. The whole point of this
    file is to notice when a mirrored constant stops being where it was, and
    a checker that passes because it could not find anything is worse than
    no checker.
    """
    global checks
    f = ROOT / path
    if not f.is_file():
        checks += 1
        fail(f"{path} is missing, so {what} cannot be checked")
        return None
    # MULTILINE because the Python mirrors are module-level constants and the
    # patterns anchor on `^` to avoid matching them inside a comment or a
    # docstring. Without the flag `^` only matches the start of the file, and
    # every one of those lookups fails -- which is how the first run of this
    # checker reported five missing constants that were all present.
    m = re.search(pattern, f.read_text(), re.M)
    if not m:
        checks += 1
        fail(
            f"{what} not found in {path} -- it was renamed or removed, and "
            f"this check went silent rather than failing. Update the pattern "
            f"in tools/check_mirrored_caps.py."
        )
        return None
    return int(m.group(1))


# Each row: a human name, the authoritative Kotlin declaration, and every
# place that copies it. `None` anywhere means `find` already reported why.
MIRRORS = [
    (
        "items per section",
        ("app/src/main/java/com/adabala/medha/notify/BriefPolicy.kt",
         r"MAX_ITEMS_PER_SECTION\s*=\s*(\d+)"),
        [
            ("samples/medha_brief.py", r"^MAX_ITEMS\s*=\s*(\d+)"),
            ("samples/medha-brief.js", r"MAX_ITEMS\s*=\s*(\d+)"),
        ],
    ),
    (
        "title length",
        ("app/src/main/java/com/adabala/medha/LocalServer.kt",
         r"MAX_BRIEF_TITLE\s*=\s*(\d+)"),
        [("samples/medha_brief.py", r"^MAX_TITLE\s*=\s*(\d+)")],
    ),
    (
        "text length",
        ("app/src/main/java/com/adabala/medha/LocalServer.kt",
         r"MAX_BRIEF_TEXT\s*=\s*(\d+)"),
        [("samples/medha_brief.py", r"^MAX_TEXT\s*=\s*(\d+)")],
    ),
    (
        "link length",
        ("app/src/main/java/com/adabala/medha/notify/BriefJson.kt",
         r"MAX_LINK_LENGTH\s*=\s*(\d+)"),
        [("samples/medha_brief.py", r"^MAX_LINK\s*=\s*(\d+)")],
    ),
    (
        "obligation id length",
        ("app/src/main/java/com/adabala/medha/notify/BriefJson.kt",
         r"MAX_ID_LENGTH\s*=\s*(\d+)"),
        [("samples/medha_brief.py", r"^MAX_ID\s*=\s*(\d+)")],
    ),
]


def main():
    print("check_mirrored_caps\n")
    for name, (src_path, src_rx), mirrors in MIRRORS:
        authoritative = find(src_path, src_rx, f"{name} (authoritative)")
        if authoritative is None:
            continue
        for m_path, m_rx in mirrors:
            got = find(m_path, m_rx, f"{name} in {m_path}")
            if got is None:
                continue
            check(
                f"{name}: {m_path} says {got}, {pathlib.Path(src_path).name} "
                f"says {authoritative}",
                got == authoritative,
            )
            if got != authoritative:
                fail(
                    f"  {m_path} trims to {got} BEFORE sending, so a client "
                    f"posting more than that loses the rest locally and gets "
                    f"a success back. The server would have taken "
                    f"{authoritative}."
                )

    # The docs table is read by integrators deciding how much to post, so a
    # stale number there is a client written to the wrong limit.
    per_section = find(
        "app/src/main/java/com/adabala/medha/notify/BriefPolicy.kt",
        r"MAX_ITEMS_PER_SECTION\s*=\s*(\d+)", "items per section (for docs)",
    )
    if per_section is not None:
        doc = find(
            "docs/BRIEF-API.md",
            r"\|\s*Items per client\s*\|\s*(\d+)\s*\|", "the docs limits table",
        )
        if doc is not None:
            check(
                f"docs/BRIEF-API.md limits table says {doc}", doc == per_section
            )

    print()
    if failures:
        print(f"check_mirrored_caps: {len(failures)} of {checks} checks FAILED")
        return 1
    print(f"check_mirrored_caps: OK ({checks} mirrored limits agree)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
