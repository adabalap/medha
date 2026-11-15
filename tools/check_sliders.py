#!/usr/bin/env python3
"""
check_sliders.py — catch a Material Slider that will throw when it is drawn.

Why this exists
---------------
A Material Slider validates its configuration in onDraw, not when the value
is set. Give it a value that is not valueFrom plus a whole number of
stepSize and nothing happens; the IllegalStateException arrives later, the
first time that view is actually drawn.

v0.17.0's layout rewrite left both thermal sliders on a placeholder range:

    android:valueFrom="0" android:valueTo="10" android:stepSize="1"

while setupScheduler assigned them thermal-headroom fractions:

    binding.pauseSlider.value = prefs.getFloat(KEY_THERMAL_PAUSE, 0.85f)

0.85 is inside 0..10, so nothing looked out of range -- but it lands between
two ticks. The sliders live in the Settings tab, which starts GONE, so
onCreate set the bad value in silence and the app died the instant anyone
tapped Settings. Android drops you on the launcher when an activity dies, so
it read as "Settings just minimises the app", and Device Care started
reporting Medha as crashing frequently. Three releases of checkers saw
nothing: the code compiled, every symbol resolved, every resource existed.

What it checks
--------------
Per slider in a layout:

  1. valueFrom < valueTo.
  2. (valueTo - valueFrom) is a whole number of stepSize. Material requires
     the range itself to land on a tick, so 0..1 step 0.3 is invalid.
  3. android:value, if present, is in range and lands on a tick.

Per slider ASSIGNMENT in Kotlin:

  4. A float literal in `binding.<id>.value = ...` is in range and lands on
     a tick. This is the one that catches the bug above: 0.85f against
     stepSize 1.
  5. A float literal in `binding.<id>.setSnapped(...)` is in range. Ticks
     are not checked there -- snapping is what that helper is for.
  6. `coerceIn(a, b)` inside a slider statement stays within the XML range.
     Code clamping to 0.30..1.20 while the XML says 0..10 means one of the
     two is wrong, whatever the values happen to do.

Usage:
    python3 tools/check_sliders.py

Its own tests live in tools/tests/check_sliders_mutations.sh.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
LAYOUTS = ROOT / "app/src/main/res/layout"
KOTLIN = ROOT / "app/src/main/java"
ANDROID = "{http://schemas.android.com/apk/res/android}"

# Material's own tolerance in valueLandsOnTick.
EPS = 1e-4

problems = []


def num(el, attr):
    raw = el.get(ANDROID + attr)
    if raw is None:
        return None
    try:
        return float(raw.rstrip("dpsip"))
    except ValueError:
        return None


def lands_on_tick(value, start, step):
    if not step:
        return True
    n = (value - start) / step
    return abs(round(n) - n) < EPS


def sliders_in(path):
    """id -> (valueFrom, valueTo, stepSize) for every Slider in a layout."""
    found = {}
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return found  # check_resources owns well-formedness
    for el in tree.iter():
        if not el.tag.endswith("slider.Slider") and not el.tag.endswith("slider.RangeSlider"):
            continue
        raw_id = el.get(ANDROID + "id") or ""
        sid = raw_id.split("/")[-1]
        lo, hi, step = num(el, "valueFrom"), num(el, "valueTo"), num(el, "stepSize")
        rel = path.relative_to(ROOT)

        if lo is None or hi is None:
            problems.append(f"{rel}: {sid} is missing valueFrom/valueTo")
            continue
        if lo >= hi:
            problems.append(f"{rel}: {sid} has valueFrom {lo} >= valueTo {hi}")
            continue
        if step and not lands_on_tick(hi, lo, step):
            problems.append(
                f"{rel}: {sid} range {lo}..{hi} is not a whole number of "
                f"stepSize {step} ({(hi - lo) / step:.4f} steps); Material "
                f"throws on this at draw time"
            )
            continue

        v = num(el, "value")
        if v is not None:
            if not (lo <= v <= hi):
                problems.append(f"{rel}: {sid} value {v} is outside {lo}..{hi}")
            elif not lands_on_tick(v, lo, step):
                problems.append(
                    f"{rel}: {sid} value {v} does not land on a stepSize "
                    f"{step} tick from {lo}"
                )
        found[sid] = (lo, hi, step)
    return found


FLOAT = re.compile(r"(?<![\w.])(\d+\.\d+)[fF]?")

# An ARITHMETIC operand is not a value being assigned.
#
# The first draft read every float literal in the statement as the value, so
#
#     binding.resumeSlider.setSnapped(v - 0.05f)
#
# was reported as "resumeSlider is given 0.05, outside its range 0.2..1.1".
# 0.05 is the step the guard backs off by; the value is `v`, which is not
# knowable here at all. Two findings, both wrong, on the very code that fixed
# the crash -- and a checker that fails correct code cannot go in the
# workflow, which is why this one was not in it.
#
# The rule: a literal counts only when it is the whole value -- directly
# after `=`, `(` or `,` (a getFloat default) -- and never when it is an
# operand of + - * /. When the argument is computed, the value cannot be
# known statically, so this says nothing: setSnapped clamps and snaps it
# anyway, which is the entire reason that helper exists.
ARITH_BEFORE = re.compile(r"[\w)\]]\s*[-+*/]\s*$")
ARITH_AFTER = re.compile(r"^\s*[-+*/]\s*[\w(]")


def is_operand(stmt, start, end):
    """True if the literal at [start:end] is part of an arithmetic expression."""
    return bool(ARITH_BEFORE.search(stmt[:start]) or ARITH_AFTER.match(stmt[end:]))
COERCE = re.compile(r"coerceIn\(\s*(-?\d+\.?\d*)[fF]?\s*,\s*(-?\d+\.?\d*)[fF]?\s*\)")


def statement_at(src, pos):
    """The assignment text from `pos` to the end of its statement.

    Kotlin has no terminator, so a statement runs to a newline that is not
    inside brackets and does not end on an operator. Good enough: these are
    single assignments, occasionally wrapped after `=`.
    """
    out, depth = [], 0
    for ch in src[pos:pos + 400]:
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == "\n" and depth <= 0 and out and out[-1].strip().endswith(
                (")", "f", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9")):
            break
        out.append(ch)
    return "".join(out)


def check_assignments(sliders):
    for kt in sorted(KOTLIN.rglob("*.kt")):
        src = kt.read_text(encoding="utf-8")
        rel = kt.relative_to(ROOT)
        for sid, (lo, hi, step) in sliders.items():
            for m in re.finditer(
                r"\b\w+\.%s\.(value\s*=|setSnapped\()" % re.escape(sid), src
            ):
                stmt = statement_at(src, m.end())
                snapped = m.group(1).startswith("setSnapped")
                line = src[: m.start()].count("\n") + 1

                for fm in FLOAT.finditer(stmt):
                    if is_operand(stmt, fm.start(1), fm.end()):
                        continue
                    v = float(fm.group(1))
                    if not (lo <= v <= hi):
                        problems.append(
                            f"{rel}:{line}: {sid} is given {v}, outside its "
                            f"XML range {lo}..{hi}"
                        )
                    elif not snapped and not lands_on_tick(v, lo, step):
                        problems.append(
                            f"{rel}:{line}: {sid} is given {v}, which does not "
                            f"land on a stepSize {step} tick from {lo} -- this "
                            f"throws when the slider is first DRAWN, not here"
                        )

                for cm in COERCE.finditer(stmt):
                    a, b = float(cm.group(1)), float(cm.group(2))
                    if a < lo - EPS or b > hi + EPS:
                        problems.append(
                            f"{rel}:{line}: {sid} is clamped to {a}..{b} but "
                            f"its XML range is {lo}..{hi}; one of the two is "
                            f"wrong"
                        )


def main():
    sliders = {}
    for path in sorted(LAYOUTS.rglob("*.xml")) if LAYOUTS.exists() else []:
        sliders.update(sliders_in(path))

    if sliders:
        check_assignments(sliders)

    if problems:
        print("Sliders that will throw when drawn:\n")
        for p in problems:
            print(f"  FAIL  {p}")
        print(
            "\nA Material Slider checks its configuration in onDraw. A bad\n"
            "value is accepted in silence and crashes later, wherever that\n"
            "view first becomes visible -- which for a tab that starts GONE\n"
            "is the moment someone opens it."
        )
        print(f"\ncheck_sliders: {len(problems)} problem(s)")
        return 1

    print(f"check_sliders: OK ({len(sliders)} slider(s) checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
