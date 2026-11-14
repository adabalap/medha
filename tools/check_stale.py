#!/usr/bin/env python3
"""
Fails if a file this project does not ship is still present in the tree.

Medha ships as a zip of its source. Extracting one over an existing checkout
adds and overwrites but never deletes, so every file a release removes
survives in the consumer's repo and then breaks the build from behind --
reported against a file nobody edited, usually as a missing resource or a
duplicate class, never as "this file should not be here".

Two lists answer that, and they fail differently:

  tools/removed.txt   paths a release deleted, with the reasoning. Precise,
                      and useless for anything nobody wrote down -- it only
                      goes back to v0.13.0.

  tools/manifest.txt  every file this release DOES ship, generated. Anything
                      else under a tracked root is an orphan, whenever it
                      was orphaned and whether or not anyone recorded it.
                      This is what finally named connectors/SmsConnector.kt,
                      deleted in v0.9.0 and resident for nine releases.

The manifest cannot tell a leftover from a file the consumer added
themselves, so tools/local.txt lists the paths they own and this check
leaves those alone. Without it the check's own failure message would tell
someone to delete their own work, which is worse than the problem it solves.

It is first in the CI order on purpose: a stale file makes every later check
lie. v0.13.0's leftover MedhaWidgetProvider.kt surfaced as

    R.string.widget_footer not declared

from check_resources, which is true, useless, and three steps from the cause.

Usage: tools/check_stale.py
Exit 0 and a summary on success; the paths, the reason and a git rm command
on failure.
"""
import pathlib
import sys

# Single source of truth for which roots a release owns and what is noise
# inside them. Duplicating these lists is how the check and the manifest
# drift apart: a root the manifest skips and this scans reports every file
# under it as stale, and a root this skips hides orphans forever.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from make_manifest import TRACKED, IGNORE_PARTS, IGNORE_SUFFIX  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = pathlib.Path(__file__).resolve().parent
LIST = TOOLS / "removed.txt"
MANIFEST = TOOLS / "manifest.txt"
LOCAL = TOOLS / "local.txt"


def entries(path):
    """Non-comment, non-blank lines of one of our list files."""
    if not path.exists():
        return []
    return [
        line.strip() for line in path.read_text().splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]


def listed():
    """(path, section heading it sits under) for every removed.txt entry."""
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


def owned_locally(rel, local):
    """True if the consumer has claimed this path in tools/local.txt.

    A bare path matches itself; a path ending in / matches everything
    beneath it.
    """
    for e in local:
        if e.endswith("/"):
            if rel.startswith(e):
                return True
        elif rel == e:
            return True
    return False


def on_disk():
    """Every tracked file actually present, as repo-relative posix paths."""
    found = []
    for top in TRACKED:
        base = ROOT / top
        if not base.exists():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            rel = p.relative_to(ROOT)
            if IGNORE_PARTS & set(rel.parts) or p.suffix in IGNORE_SUFFIX:
                continue
            found.append(rel.as_posix())
    return sorted(found)


def orphans(local, already):
    """Files on disk that this release does not ship, the consumer has not
    claimed, and removed.txt has not already explained.

    The two lists overlap on purpose -- recording a manifest find in
    removed.txt is how it acquires a reason -- so a file in both must be
    reported once, under the heading that says why it went. Reporting it
    twice doubles the count and repeats every path in the git rm, and a
    report that cannot count is one people learn to skim.

    No manifest means no answer, not a failure: a checkout that predates
    tools/manifest.txt is not broken, it just cannot be checked this way.
    """
    if not MANIFEST.exists():
        return None
    known = set(entries(MANIFEST))
    return [
        r for r in on_disk()
        if r not in known and r not in already and not owned_locally(r, local)
    ]


def pointless_local(local):
    """local.txt entries that name a file this release ships anyway.

    Harmless -- a shipped file is never an orphan, so the entry does
    nothing -- but it means somebody expected it to do something.
    """
    if not MANIFEST.exists():
        return []
    known = set(entries(MANIFEST))
    return sorted(e for e in local if e in known)


def main():
    local = entries(LOCAL)
    known_removals = listed()
    stale = [(p, why) for p, why in known_removals if (ROOT / p).exists()]
    extra = orphans(local, {p for p, _ in stale}) or []

    # Deliberate asymmetry: local.txt suppresses a manifest orphan but NOT a
    # recorded removal. The manifest only knows a file is unaccounted for,
    # which is exactly the case that can be somebody's own work. removed.txt
    # knows Medha deleted this specific file for a stated reason -- and for
    # anything under app/src that reason is usually "it still compiles into
    # the APK". Letting a one-line claim re-open that is how SmsConnector.kt
    # would come back.
    overridden = sorted(p for p, _ in stale if owned_locally(p, local))

    for e in pointless_local(local):
        print(f"check_stale: note -- tools/local.txt lists {e}, which this "
              f"release ships; the entry does nothing")

    if not stale and not extra:
        claimed = f", {len(local)} local path(s) claimed" if local else ""
        print(f"check_stale: {len(known_removals)} known removal(s) absent, "
              f"nothing outside the manifest{claimed}")
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

    # Order-preserving dedupe: removed.txt can list a path under two headings
    # (a file removed, restored and removed again), and a git rm that repeats
    # a path is a report that cannot count.
    everything, seen = [], set()
    for p in [p for p, _ in stale] + extra:
        if p not in seen:
            seen.add(p)
            everything.append(p)

    print("Delete them:\n")
    print("  git rm " + " \\\n         ".join(everything))

    if overridden:
        print(
            "\ntools/local.txt claims these, and is deliberately ignored for "
            "them:\n"
        )
        for p in overridden:
            print(f"    {p}")
        print(
            "\nA path in tools/removed.txt is one Medha deleted on purpose, "
            "with a\nreason recorded there -- under app/src the reason is "
            "usually that it\nstill compiles into the APK. Claiming it back "
            "needs that entry removed\nfrom tools/removed.txt, not a line in "
            "local.txt, so the decision is\nvisible to whoever reads the "
            "history next."
        )
    if extra:
        print(
            "\nIf you added any of the unshipped files yourself, they are not\n"
            "leftovers: list them in tools/local.txt and this check will leave\n"
            "them alone."
        )
    print(f"\ncheck_stale: {len(everything)} stale file(s)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
