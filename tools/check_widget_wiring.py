#!/usr/bin/env python3
"""
Cross-checks the four places a home-screen widget is defined, which nothing
else in this repo compares.

An app widget is wired through a manifest <receiver>, an appwidget-provider
XML, a layout, and a provider class -- and the pieces reference each other by
name across three different languages. Get one wrong and the failure is the
worst kind available on Android: the launcher shows "Couldn't add widget" with
no stack trace in the app, nothing in logcat from Medha, and every file
individually valid. check_widget_layouts.py catches a disallowed VIEW; this
catches a disallowed WIRING.

What it enforces:

  1. Every receiver declaring android.appwidget.provider names a provider XML
     that exists, and a class that exists in Kotlin.
  2. Every provider XML's initialLayout and previewLayout name layouts that
     exist.
  3. Each provider class's `override val layout` is the same layout its
     receiver's provider XML declares as initialLayout. A class drawing into
     a different layout than the one the launcher inflates produces a widget
     whose views are all missing.
  4. Every R.id a provider binds exists in the layout that provider draws
     into. This is the one that bites: R.id.widgetBar4 compiles fine against
     a layout with three rows, because R.id is global across all layouts.
  5. The row count in each layout equals the matching BriefRender.ROWS_*
     constant, and equals the number of row slots the class declares. Three
     independent statements of the same number; v0.9.0 shipped with two of
     them disagreeing and silently dropped two items per brief.
  6. Every provider XML declares both targetCellWidth/Height (API 31+, the
     only honest sizing unit) and minWidth/minHeight (the API 27-30 floor).

Usage: tools/check_widget_wiring.py
Exit 0 and a summary on success; a listing and exit 1 on any gap.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java/com/adabala/medha"
ANDROID = "{http://schemas.android.com/apk/res/android}"

problems = []


def fail(msg):
    problems.append(msg)


def attr(el, name):
    return el.get(ANDROID + name)


def receivers():
    """(class name, provider-xml stem) for every app-widget receiver."""
    root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    out = []
    for r in root.iter("receiver"):
        for m in r.findall("meta-data"):
            if attr(m, "name") == "android.appwidget.provider":
                res = (attr(m, "resource") or "").removeprefix("@xml/")
                out.append((attr(r, "name") or "", res))
    return out


def layout_ids(stem):
    """Every android:id declared in a layout, plus its widgetRow count."""
    p = RES / "layout" / f"{stem}.xml"
    if not p.exists():
        return None, 0
    text = p.read_text()
    ids = set(re.findall(r'android:id="@\+id/(\w+)"', text))
    rows = len([i for i in ids if re.fullmatch(r"widgetRow\d+", i)])
    return ids, rows


def kotlin_classes():
    """class name -> (layout stem, set of bound R.id names)."""
    src = (JAVA / "notify/BriefWidgets.kt").read_text()
    # Split on top-level declarations; a class body runs to the next one.
    marks = [(m.start(), m.group(1)) for m in
             re.finditer(r"^(?:abstract )?(?:class|object) (\w+)", src, re.M)]
    out = {}
    for i, (pos, name) in enumerate(marks):
        end = marks[i + 1][0] if i + 1 < len(marks) else len(src)
        body = src[pos:end]
        lay = re.search(r"override val layout = R\.layout\.(\w+)", body)
        if not lay:
            continue
        out[name] = (lay.group(1), set(re.findall(r"R\.id\.(\w+)", body)))
    return out


def render_capacities():
    """ROWS_2X2 / ROWS_4X2 / ROWS_4X4 as declared in BriefRender."""
    src = (JAVA / "notify/BriefRender.kt").read_text()
    return {m.group(1).lower(): int(m.group(2))
            for m in re.finditer(r"const val ROWS_(\w+) = (\d+)", src)}


def main():
    caps = render_capacities()
    classes = kotlin_classes()
    seen_layouts = []

    rx = receivers()
    if not rx:
        fail("AndroidManifest declares no app-widget receivers at all")

    for cls, info_stem in rx:
        short = cls.rsplit(".", 1)[-1]

        if short not in classes:
            fail(f"{cls}: no class in BriefWidgets.kt overrides `val layout`")
            continue
        declared_layout, bound_ids = classes[short]

        info = RES / "xml" / f"{info_stem}.xml"
        if not info.exists():
            fail(f"{cls}: provider xml @xml/{info_stem} does not exist")
            continue

        el = ET.parse(info).getroot()
        initial = (attr(el, "initialLayout") or "").removeprefix("@layout/")
        preview = (attr(el, "previewLayout") or "").removeprefix("@layout/")

        for role, stem in (("initialLayout", initial), ("previewLayout", preview)):
            if stem and not (RES / "layout" / f"{stem}.xml").exists():
                fail(f"{info_stem}.xml: {role} points at {stem}.xml, which does not exist")

        if initial != declared_layout:
            fail(f"{cls} draws into R.layout.{declared_layout} but "
                 f"{info_stem}.xml inflates @layout/{initial}")
            continue

        for a in ("targetCellWidth", "targetCellHeight"):
            if attr(el, a) is None:
                fail(f"{info_stem}.xml: no android:{a} (API 31+ sizing)")
        for a in ("minWidth", "minHeight"):
            if attr(el, a) is None:
                fail(f"{info_stem}.xml: no android:{a} (the API 27-30 floor)")

        ids, rows = layout_ids(initial)
        seen_layouts.append(initial)
        missing = sorted(i for i in bound_ids if i not in ids)
        if missing:
            fail(f"{cls} binds {', '.join(missing)}, which {initial}.xml does "
                 f"not declare (R.id is global, so this compiles)")

        slots = len([i for i in bound_ids if re.fullmatch(r"widgetRow\d+", i)])
        if slots != rows:
            fail(f"{cls} declares {slots} row slot(s) but {initial}.xml has {rows}")

        key = initial.replace("widget_brief_", "")
        if key not in caps:
            fail(f"BriefRender has no ROWS_{key.upper()} for {initial}.xml")
        elif caps[key] != rows:
            fail(f"{initial}.xml has {rows} rows but BriefRender.ROWS_"
                 f"{key.upper()} is {caps[key]}")

    for p in sorted((RES / "layout").glob("widget_*.xml")):
        if p.stem not in seen_layouts:
            fail(f"{p.name} is a widget layout no receiver inflates")

    if problems:
        print("Widget wiring problems:\n")
        for p in problems:
            print(f"  FAIL  {p}")
        print(f"\ncheck_widget_wiring: {len(problems)} problem(s)")
        return 1

    print(f"check_widget_wiring: {len(rx)} widget(s) wired consistently "
          f"across manifest, provider xml, layout and provider class")
    return 0


if __name__ == "__main__":
    sys.exit(main())
