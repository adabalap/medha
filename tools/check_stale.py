#!/usr/bin/env python3
"""
Fails if a file this project has deleted is still present in the tree.

Medha ships as a zip of its source. Extracting one over an existing checkout
adds and overwrites but never deletes, so every file a release removes
survives in the consumer's repo and then breaks the build from behind --
reported against a file nobody edited, usually as a missing resource or a
duplicate class, never as "this file should not be here".

tools/removed.txt lists the paths, with the reasoning. This turns the whole
class of failure into one message naming the file and the command to run.

It is first in the CI order on purpose: a stale file makes every later check
lie. v0.13.0's leftover MedhaWidgetProvider.kt surfaced as

    R.string.widget_footer not declared

from check_resources, which is true, useless, and three steps from the cause.

Usage: tools/check_stale.py
Exit 0 and a summary on success; the paths, the reason and an rm command on
failure.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LIST = pathlib.Path(__file__).resolve().parent / "removed.txt"


def listed():
    """(path, section heading it sits under) for every entry."""
    if not LIST.exists():
        return []
    out, heading = [], "removed in an earlier version"
    for raw in LIST.read_text().splitlines():
        line = raw.strip()
        if line.startswith("# ---") and "removed in" in line:
            heading = line.strip("# -").strip()
        elif line and not line.startswith("#"):
            out.append((line, heading))
    return out


def main():
    entries = listed()
    if not entries:
        print("check_stale: tools/removed.txt lists nothing; nothing to check")
        return 0

    stale = [(p, why) for p, why in entries if (ROOT / p).exists()]
    if not stale:
        print(f"check_stale: none of the {len(entries)} removed file(s) are present")
        return 0

    print("Files that were deleted from this project are still in the tree.\n")
    print("This happens when a release zip is extracted over an existing")
    print("checkout: extracting adds and overwrites, it never deletes. The")
    print("leftovers then break the build from behind, reported against files")
    print("you did not edit.\n")

    by_why = {}
    for p, why in stale:
        by_why.setdefault(why, []).append(p)
    for why, paths in by_why.items():
        print(f"  {why}:")
        for p in paths:
            print(f"    {p}")
        print()

    print("Delete them:\n")
    print("  git rm " + " \\\n         ".join(p for p, _ in stale))
    print(f"\ncheck_stale: {len(stale)} stale file(s)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
