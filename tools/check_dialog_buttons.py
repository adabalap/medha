#!/usr/bin/env python3
"""
Asserts no dialog button label is long enough to make Material stack the row.

WHY THIS EXISTS

A MaterialAlertDialog lays its buttons out in a horizontal row and silently
falls back to a VERTICAL stack when their combined measured width does not
fit. Nothing warns you. The dialog still works, still looks deliberate, and
reads as a little scrolling list of actions instead of a row of buttons --
which is exactly how it was reported: "shouldn't they be horizontal capsules
with no scroll?"

Six labels were over the line at once, so this was never one careless string;
it is what happens when every label is written on its own, in isolation, with
no sense of the row it has to share.

HOW THE LIMIT IS DERIVED

A text button is roughly `text width + 2 x 8dp` padding, with a 64dp minimum
and 8dp between buttons. A Material dialog on a 360dp-wide phone leaves about
280dp for the row. At ~7dp per character for 14sp medium that gives, for the
worst case of three buttons:

    3 x (7c + 16) + 16  <=  280     ->   c <= ~12 characters each

So MAX_CHARS is 12, and it is a proxy rather than a measurement: a real
layout pass needs a device. It is deliberately a little strict, because the
failure is invisible on the machine that writes the string and obvious on the
phone that shows it.

WHAT IT DOES NOT CHECK

Only `setPositiveButton` / `setNegativeButton` / `setNeutralButton` with an
`R.string.` reference. A `setItems` list entry is a list row and may be as
long as it likes -- `copy_token` ("Copy API token") is one, and shortening it
would be the wrong fix. Literal strings in buttons are reported separately:
they cannot be translated, which is its own problem.

Run: python3 -I tools/check_dialog_buttons.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main" / "java"
STRINGS = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"

MAX_CHARS = 12

# The POSITIVE button is a tonal capsule (Widget.Medha.DialogButton.Tonal),
# and a capsule is wider than its label: Material3's tonal padding is 24dp a
# side against a text button's 12dp, so the same word costs ~24dp more. The
# row's budget did not grow, so this one's share of it shrank.
MAX_CHARS_POSITIVE = 10

BUTTON = re.compile(r"\.set(Positive|Negative|Neutral)Button\(\s*R\.string\.(\w+)")
LITERAL = re.compile(r"\.set(?:Positive|Negative|Neutral)Button\(\s*\"([^\"]*)\"")

failures = []
checks = 0


def fail(msg):
    failures.append(msg)
    print(f"  FAIL  {msg}")


def main():
    if not STRINGS.is_file():
        print(f"check_dialog_buttons: no {STRINGS}, nothing to check", file=sys.stderr)
        return 1
    raw = STRINGS.read_text(encoding="utf-8")
    values = {
        name: " ".join(body.split())
        for name, body in re.findall(r'<string name="([^"]+)">(.*?)</string>', raw, re.S)
    }

    used = {}
    literals = []
    for f in sorted(SRC.rglob("*.kt")):
        text = f.read_text(encoding="utf-8")
        rel = f.relative_to(ROOT)
        for m in BUTTON.finditer(text):
            slot, name = m.group(1), m.group(2)
            entry = used.setdefault(name, {"files": set(), "slots": set()})
            entry["files"].add(str(rel))
            entry["slots"].add(slot)
        for m in LITERAL.finditer(text):
            literals.append((str(rel), m.group(1)))

    if not used:
        print("check_dialog_buttons: no dialog buttons found -- has the idiom changed?",
              file=sys.stderr)
        return 1

    global checks
    for name in sorted(used):
        checks += 1
        # A string used in several dialogs takes the tightest cap that
        # applies to it. `close` sits in the negative slot almost
        # everywhere and in the positive slot once; the capsule is the one
        # that has to fit.
        positive = "Positive" in used[name]["slots"]
        cap = MAX_CHARS_POSITIVE if positive else MAX_CHARS
        label = values.get(name)
        if label is None:
            fail(f"R.string.{name} is used as a dialog button but is not in strings.xml")
            continue
        # A format placeholder stands in for runtime text, so its length here
        # says nothing about the width on screen. Reported rather than
        # silently skipped: a button whose label is substituted at runtime
        # cannot be checked by this file at all.
        if "%" in label:
            print(f"  ??    {name} is formatted at runtime; length not checkable here")
            continue
        if len(label) > cap:
            where = "positive (a tonal capsule, so wider)" if positive else "text button"
            fail(
                f"{name} = {label!r} is {len(label)} characters (max {cap} for a "
                f"{where}). Material will stack the button row instead of laying "
                f"it out horizontally. Used in: {', '.join(sorted(used[name]['files']))}"
            )
        else:
            print(f"  ok    {len(label):2} {name}{'  (capsule)' if positive else ''}")

    for rel, lit in literals:
        checks += 1
        fail(f"{rel} sets a dialog button from the literal {lit!r}; use an R.string so "
             f"it can be translated and so this check can see it")

    print()
    if failures:
        print(f"check_dialog_buttons: {len(failures)} of {checks} checks FAILED")
        return 1
    print(f"check_dialog_buttons: OK ({checks} dialog button labels, all <= {MAX_CHARS} chars)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
