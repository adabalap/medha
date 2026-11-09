#!/usr/bin/env python3
"""
Checks that every app-widget layout uses only view classes RemoteViews can
actually inflate.

WHY THIS EXISTS: v0.9.7 added `<View>` elements as accent bars to
widget_medha.xml. `android.view.View` is not annotated @RemoteView, so
RemoteViews' inflation filter rejects it and the launcher refuses the widget
with "Couldn't add widget." -- no stack trace in the app, nothing in logcat
from Medha, and the layout is perfectly valid XML that passes every other
check here. It compiles, it lints, and it cannot be placed.

This is invisible to kotlinc, to check_resources.py and to a code review by
someone who does not already know the allowlist. So it is checked.

Run: python3 tools/check_widget_layouts.py
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Classes annotated @android.widget.RemoteView, i.e. the only ones a widget
# layout may contain. Anything else throws InflateException in the host.
#
# Layouts and widgets, per the RemoteViews documentation.
ALLOWED = {
    # layouts
    "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
    # widgets
    "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView",
    "ProgressBar", "TextView", "ViewFlipper", "ListView", "GridView",
    "StackView", "AdapterViewFlipper", "ViewStub", "TextClock",
    # API 31+
    "CheckBox", "RadioButton", "RadioGroup", "Switch",
    # structural, not a view
    "merge", "requestFocus", "include", "tag",
}

# Named so the error can say what to use instead of the thing that failed.
SUGGEST = {
    "View": "ImageView with android:background (View is NOT @RemoteView)",
    "Space": "ImageView with android:background=\"@android:color/transparent\"",
    "ConstraintLayout": "LinearLayout or RelativeLayout",
    "androidx.constraintlayout.widget.ConstraintLayout": "LinearLayout or RelativeLayout",
    "com.google.android.material.card.MaterialCardView": "LinearLayout with a shape drawable background",
    "ScrollView": "ListView via a RemoteViewsService, or fewer rows",
    "RecyclerView": "ListView or AdapterViewFlipper via a RemoteViewsService",
    "androidx.recyclerview.widget.RecyclerView": "ListView or AdapterViewFlipper via a RemoteViewsService",
}

failures = []
checks = 0


def fail(msg):
    failures.append(msg)
    print(f"  FAIL  {msg}")


def widget_layouts():
    """Layouts named as initialLayout by an appwidget-provider."""
    out = []
    xml_dir = RES / "xml"
    if not xml_dir.is_dir():
        return out
    for f in sorted(xml_dir.glob("*.xml")):
        text = f.read_text()
        if "appwidget-provider" not in text:
            continue
        for attr in ("initialLayout", "previewLayout"):
            m = re.search(rf'android:{attr}\s*=\s*"@layout/([A-Za-z0-9_]+)"', text)
            if m:
                out.append((f.name, attr, RES / "layout" / f"{m.group(1)}.xml"))
    return out


def walk(elem, found):
    tag = elem.tag
    if not isinstance(tag, str):
        return  # a comment or processing instruction
    found.append(tag)
    for child in elem:
        walk(child, found)


def main():
    global checks

    layouts = widget_layouts()
    if not layouts:
        print("check_widget_layouts: no appwidget-provider found, nothing to check")
        return 0

    for provider, attr, path in layouts:
        checks += 1
        if not path.is_file():
            fail(f"{provider} {attr} points at {path.name}, which does not exist")
            continue

        try:
            tree = ET.parse(path)
        except ET.ParseError as e:
            fail(f"{path.name} is not parseable: {e}")
            continue

        found = []
        walk(tree.getroot(), found)
        bad = [t for t in dict.fromkeys(found) if t not in ALLOWED]

        if bad:
            for t in bad:
                hint = SUGGEST.get(t)
                extra = f" — use {hint}" if hint else " — not a @RemoteView class"
                fail(
                    f"{path.name} contains <{t}>, which RemoteViews cannot inflate{extra}"
                )
            print(f"         the launcher will refuse this widget with "
                  f'"Couldn\'t add widget." and log nothing in the app')
        else:
            print(f"  ok    {path.name} uses only RemoteViews-inflatable classes "
                  f"({len(set(found))} distinct)")

    print()
    if failures:
        print(f"check_widget_layouts: {len(failures)} problem(s) across {checks} layout(s)")
        return 1
    print(f"check_widget_layouts: OK ({checks} widget layout(s) checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
