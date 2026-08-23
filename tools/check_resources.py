#!/usr/bin/env python3
"""
Cross-checks resource references against resource declarations, in both
directions, without needing a real Android build:

  1. Every @string/xxx and @color/xxx used in layout/menu/xml resources
     resolves to a <string>/<color> declared somewhere under res/values*/.
  2. Every R.string.xxx, R.color.xxx, R.drawable.xxx, R.id.xxx used in Kotlin
     resolves to a declared string/color, a drawable file, or an
     android:id="@+id/..." declared in some layout or menu.

Why this exists: aapt2 would catch a missing resource at build time, but this
sandbox has no Android SDK to run aapt2 at all. Editing layout XML and
strings.xml by hand across several files (as the dark-theme and string-
externalization pass did) is exactly the kind of change where a typo'd
resource name compiles fine at the Kotlin level and only fails much later,
at actual app startup, with an unhelpful Resources.NotFoundException.

Usage: tools/check_resources.py
Exit code 0 and a summary line on success; a listing and exit 1 on any gap.
"""
import glob
import os
import re
import sys
import xml.dom.minidom


def check_xml_well_formed():
    """
    Parses every XML file under app/src with a real XML parser.

    Why this earns its place here rather than being "obviously already
    covered": a real bug shipped past ad-hoc `minidom.parse()` calls made by
    hand on some-but-not-all edited files in one session, because it's easy
    to create a new file, validate three others, and never circle back to
    the one that actually broke. A blanket sweep over every file removes
    "did I remember to check this one" as a failure mode entirely.

    Concretely, this catches things like a literal "--" inside an XML
    comment (illegal anywhere in a comment body per the XML spec, not just
    adjacent to the closing "-->") — invisible to a human skim, invisible to
    Kotlin-side tooling, and something aapt2 only reports once a real Gradle
    build runs far enough to parse resources.
    """
    failed = []
    files = sorted(glob.glob("app/src/**/*.xml", recursive=True))
    for f in files:
        try:
            xml.dom.minidom.parse(f)
        except Exception as e:
            failed.append(f"{f}: {e}")
    return files, failed


def declared_values():
    declared = {"string": set(), "color": set()}
    for f in glob.glob("app/src/main/res/values*/strings.xml") + glob.glob(
        "app/src/main/res/values*/colors.xml"
    ):
        txt = open(f, encoding="utf-8").read()
        for m in re.finditer(r'<(string|color)\s+name="([^"]+)"', txt):
            declared[m.group(1)].add(m.group(2))
    return declared


def declared_drawables():
    names = set()
    for f in glob.glob("app/src/main/res/drawable*/*") + glob.glob(
        "app/src/main/res/mipmap*/*"
    ):
        if os.path.isfile(f):
            names.add(os.path.splitext(os.path.basename(f))[0])
    return names


def declared_ids():
    ids = set()
    for f in glob.glob("app/src/main/res/layout/*.xml") + glob.glob(
        "app/src/main/res/menu/*.xml"
    ):
        txt = open(f, encoding="utf-8").read()
        for m in re.finditer(r'android:id="@\+id/([A-Za-z0-9_]+)"', txt):
            ids.add(m.group(1))
    return ids


def main():
    xml_files, xml_failures = check_xml_well_formed()
    if xml_failures:
        print("XML files that do not parse:\n")
        for line in xml_failures:
            print(" ", line)
        return 1

    declared = declared_values()
    drawables = declared_drawables()
    ids = declared_ids()
    missing = []

    for f in (
        glob.glob("app/src/main/res/layout/*.xml")
        + glob.glob("app/src/main/res/menu/*.xml")
        + glob.glob("app/src/main/res/xml/*.xml")
    ):
        txt = open(f, encoding="utf-8").read()
        for kind in ("string", "color"):
            for m in re.finditer(r"@" + kind + r"/([A-Za-z0-9_]+)", txt):
                if m.group(1) not in declared[kind]:
                    missing.append(f"{f}: @{kind}/{m.group(1)} not declared")

    for f in glob.glob("app/src/main/java/com/adabala/medha/**/*.kt", recursive=True):
        txt = open(f, encoding="utf-8").read()
        for kind, pool in (
            ("string", declared["string"]),
            ("color", declared["color"]),
            ("drawable", drawables),
            ("id", ids),
        ):
            for m in re.finditer(r"R\." + kind + r"\.([A-Za-z0-9_]+)", txt):
                if m.group(1) not in pool:
                    missing.append(f"{f}: R.{kind}.{m.group(1)} not declared")

    if missing:
        print("Resource references with no matching declaration:\n")
        for line in missing:
            print(" ", line)
        return 1

    print(
        f"check_resources: OK ({len(xml_files)} XML files well-formed, "
        f"{len(declared['string'])} strings, "
        f"{len(declared['color'])} colors, {len(drawables)} drawables, "
        f"{len(ids)} ids)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
