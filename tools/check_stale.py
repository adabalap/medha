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


ROOTS = ("app/src", "tools", "docs", "samples", ".github")
IGNORE_PARTS = {"build", "node_modules", ".git", "__pycache__", ".gradle", ".idea"}
IGNORE_SUFFIX = {".jar", ".apk", ".class", ".pyc", ".keystore", ".log"}


def orphans():
    """Files on disk that this release does not ship.

    tools/removed.txt only catches deletions somebody wrote down, and only
    from v0.13.0 on. This catches the rest: connectors/SmsConnector.kt was
    deleted in v0.9.0 and survived in a consumer repo for nine releases,
    because nothing ever asked "should this file be here at all?".

    No manifest means no answer, not a failure: a checkout that predates
    tools/manifest.txt is not broken, it just cannot be checked this way.
    """
    mf = pathlib.Path(__file__).resolve().parent / "manifest.txt"
    if not mf.exists():
        return None
    known = {
        line.strip() for line in mf.read_text().splitlines()
        if line.strip() and not line.startswith("#")
    }
    found = []
    for top in ROOTS:
        base = ROOT / top
        if not base.exists():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            rel = p.relative_to(ROOT)
            if IGNORE_PARTS & set(rel.parts) or p.suffix in IGNORE_SUFFIX:
                continue
            if rel.as_posix() not in known:
                found.append(rel.as_posix())
    return sorted(found)


def main():
    entries = listed()
    stale = [(p, why) for p, why in entries if (ROOT / p).exists()]
    extra = orphans() or []

    if not stale and not extra:
        print(f"check_stale: {len(entries)} known removal(s) absent, "
              f"nothing outside the manifest")
        return 0

    print("Files on disk that this release does not ship.\n")
    print("A release zip extracted over an existing checkout adds and")
    print("overwrites but never deletes, so anything removed upstream stays")
    print("in your repo and breaks the build from behind -- reported against")
    print("files you did not edit.\n")

    if stale:
        by_why = {}
        for path, why in stale:
            by_why.setdefault(why, []).append(path)
        for why, paths in by_why.items():
            print(f"  {why}:")
            for path in paths:
                print(f"    {path}")
            print()

    if extra:
        print("  not shipped by this release:")
        for e in extra:
            print(f"    {e}")
        print()

    everything = [p for p, _ in stale] + extra
    print("Delete them:\n")
    print("  git rm " + " \\\n         ".join(everything))
    print(f"\ncheck_stale: {len(everything)} stale file(s)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
