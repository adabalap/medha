#!/usr/bin/env python3
"""
Fails if a screen or dialog exists that nothing in the UI can open.

WHY THIS EXISTS

v0.17.0 replaced a sixteen-item navigation drawer with three destinations
and wired every drawer entry to its new home -- except one. The clients
dialog went inline, and "Add an API client" had only ever been the NEUTRAL
BUTTON on that dialog, so when the dialog stopped being reachable the whole
flow went with it. There was no longer any way to add a client at all.

Nothing noticed. The code still compiled, every resource still resolved,
check_symbols was happy because the function was still CALLED -- by the
dialog nobody could open. The build was green and a feature was gone.

So: every `showXxxDialog` / `confirmXxx` / `openXxx` in an Activity must be
reachable from something that is not itself one of them. Reachability is
computed transitively, so a dialog that is only opened by an unreachable
dialog is unreachable too, which is exactly the case that bit us.

WHAT IT CANNOT SEE

That a reachable entry point is findable. A row buried three cards down in
Settings passes this and may still be bad design. This checks for the
absolute failure -- no path at all -- not for good navigation.

Usage: tools/check_reachable.py
Exit 0 and a summary on success; the orphans and exit 1 otherwise.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ACTIVITIES = sorted((ROOT / "app/src/main/java/com/adabala/medha").glob("*Activity.kt"))

# What counts as a user-facing entry point worth protecting.
ENTRY = re.compile(r"^(?:show|confirm|open)[A-Z]\w*$")

problems = []


def bodies_of(src):
    """name -> body text, by brace matching from each private fun."""
    out = {}
    for m in re.finditer(r"private fun (\w+)\s*\([^)]*\)[^{=]*\{", src):
        name, i = m.group(1), m.end()
        depth, j = 1, i
        while j < len(src) and depth:
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
            j += 1
        out[name] = src[i:j]
    return out


def check(path):
    src = path.read_text()
    bodies = bodies_of(src)
    declared = set(bodies)
    if not declared:
        return

    def calls_in(text):
        return {f for f in declared if re.search(r"\b" + f + r"\s*\(", text)}

    # Anything called from outside every private-fun body is a root: a click
    # listener, onCreate, a menu branch.
    inside = set()
    for b in bodies.values():
        inside |= calls_in(b)
    outside = set()
    for f in declared:
        total = len(re.findall(r"\b" + f + r"\s*\(", src)) - 1
        within = sum(len(re.findall(r"\b" + f + r"\s*\(", b)) for b in bodies.values())
        if total - within > 0:
            outside.add(f)

    reach, frontier = set(outside), list(outside)
    while frontier:
        for g in calls_in(bodies.get(frontier.pop(), "")):
            if g not in reach:
                reach.add(g)
                frontier.append(g)

    for f in sorted(declared - reach):
        if ENTRY.match(f):
            problems.append(f"{path.name}: {f}() cannot be opened from anywhere")


def main():
    for a in ACTIVITIES:
        check(a)

    if problems:
        print("Screens or dialogs with no way in:\n")
        for p in problems:
            print(f"  FAIL  {p}")
        print(
            "\nEither wire it to a control, or delete it. A dialog nobody can\n"
            "open is a feature that has been removed without anyone saying so."
        )
        print(f"\ncheck_reachable: {len(problems)} orphaned entry point(s)")
        return 1

    print(f"check_reachable: every entry point in {len(ACTIVITIES)} activity file(s) "
          "is reachable")
    return 0


if __name__ == "__main__":
    sys.exit(main())
