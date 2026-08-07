#!/usr/bin/env python3
"""
Catch Kotlin lifecycle-override mistakes before the Kotlin compiler does.

Two failure modes, both of which have bitten this repo:

  1. A method named like a platform callback (onStart, onResume, ...) declared
     WITHOUT `override`. Kotlin rejects this with
     "'onStart' hides member of supertype ... and needs an 'override' modifier".

  2. The tempting-but-wrong fix for (1): adding `override` to a method that was
     never meant to be a lifecycle callback. That compiles and then misbehaves
     at runtime -- e.g. a Start button handler renamed to override onStart()
     fires every time the Activity becomes visible, including on return from a
     file picker.

This script only detects (1). It cannot detect (2), which is why the correct
remedy is spelled out in the failure message: rename, don't override, unless
you actually mean to hook the lifecycle.

Usage:  python3 tools/check_overrides.py [source_root]
Exit:   0 clean, 1 problems found.
"""

import glob
import os
import re
import sys

CALLBACKS = {
    "Activity": {
        "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
        "onRestart", "onSaveInstanceState", "onRestoreInstanceState", "onNewIntent",
        "onBackPressed", "onActivityResult", "onRequestPermissionsResult",
        "onConfigurationChanged", "onLowMemory", "onTrimMemory", "onWindowFocusChanged",
        "onCreateOptionsMenu", "onOptionsItemSelected", "onPrepareOptionsMenu",
        "onAttachedToWindow", "onDetachedFromWindow", "onPostCreate", "onPostResume",
        "onUserLeaveHint", "onKeyDown", "onKeyUp", "onTouchEvent", "attachBaseContext",
    },
    "Service": {
        "onCreate", "onStart", "onStartCommand", "onDestroy", "onBind", "onUnbind",
        "onRebind", "onLowMemory", "onTrimMemory", "onConfigurationChanged",
        "onTaskRemoved", "onTimeout", "attachBaseContext",
    },
    "SQLiteOpenHelper": {
        "onCreate", "onUpgrade", "onDowngrade", "onOpen", "onConfigure",
    },
    "Fragment": {
        "onCreate", "onCreateView", "onViewCreated", "onStart", "onResume", "onPause",
        "onStop", "onDestroyView", "onDestroy", "onAttach", "onDetach",
        "onSaveInstanceState",
    },
    "Application": {
        "onCreate", "onTerminate", "onLowMemory", "onTrimMemory",
        "onConfigurationChanged", "attachBaseContext",
    },
    "BroadcastReceiver": {"onReceive"},
    "ContentProvider": {
        "onCreate", "query", "insert", "update", "delete", "getType",
    },
    "Runnable": {"run"},
    "ViewModel": {"onCleared"},
}

# Map a supertype token appearing after `:` to a callback category.
SUPERTYPES = [
    (r"\bAppCompatActivity\b", "Activity"),
    (r"\bComponentActivity\b", "Activity"),
    (r"\bFragmentActivity\b", "Activity"),
    (r"\bActivity\b", "Activity"),
    (r"\bLifecycleService\b", "Service"),
    (r"\bIntentService\b", "Service"),
    (r"\bService\b", "Service"),
    (r"\bSQLiteOpenHelper\b", "SQLiteOpenHelper"),
    (r"\bFragment\b", "Fragment"),
    (r"\bApplication\b", "Application"),
    (r"\bBroadcastReceiver\b", "BroadcastReceiver"),
    (r"\bContentProvider\b", "ContentProvider"),
    (r"\bViewModel\b", "ViewModel"),
    (r"\bRunnable\b", "Runnable"),
]

CLASS_RE = re.compile(
    r"^[ \t]*(?:private |internal |public |abstract |open |sealed )*"
    r"(?:class|object)\s+(\w+)[^\n{:]*:\s*([^\n{]+)",
    re.M,
)
FUN_RE = re.compile(
    r"^([ \t]*)((?:private |protected |public |internal |open |final |suspend )*)"
    r"(override\s+)?fun\s+(\w+)",
    re.M,
)
ANON_RE = re.compile(r"object\s*:\s*([\w.]+)\s*\{")


def categories_for(supers: str):
    cats = set()
    for pattern, cat in SUPERTYPES:
        if re.search(pattern, supers):
            cats.add(cat)
    return cats


def scan(path: str):
    src = open(path, encoding="utf-8").read()
    problems = []

    # Named classes/objects with a supertype list.
    matches = list(CLASS_RE.finditer(src))
    for i, m in enumerate(matches):
        cats = categories_for(m.group(2))
        if not cats:
            continue
        names = set()
        for c in cats:
            names |= CALLBACKS[c]
        end = matches[i + 1].start() if i + 1 < len(matches) else len(src)
        body = src[m.end():end]
        for fm in FUN_RE.finditer(body):
            _, _, override, fname = fm.groups()
            if fname in names and not override:
                line = src[: m.end() + fm.start()].count("\n") + 1
                problems.append((line, fname, m.group(1), "/".join(sorted(cats))))

    # Anonymous objects, e.g. `object : Runnable { fun run() }`.
    for m in ANON_RE.finditer(src):
        cats = categories_for(m.group(1))
        if not cats:
            continue
        names = set()
        for c in cats:
            names |= CALLBACKS[c]
        body = src[m.end(): m.end() + 600]
        for fm in FUN_RE.finditer(body):
            _, _, override, fname = fm.groups()
            if fname in names and not override:
                line = src[: m.end() + fm.start()].count("\n") + 1
                problems.append((line, fname, "<anonymous>", m.group(1)))

    return problems


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app/src"
    if not os.path.isdir(root):
        print(f"check_overrides: no such directory: {root}")
        return 0

    found = []
    for path in sorted(glob.glob(os.path.join(root, "**", "*.kt"), recursive=True)):
        for line, fname, cls, cat in scan(path):
            found.append((path, line, fname, cls, cat))

    if not found:
        print("check_overrides: OK (no lifecycle methods missing 'override').")
        return 0

    for path, line, fname, cls, cat in found:
        print(f"::error file={path},line={line}::"
              f"'{fname}' in {cls} matches a {cat} callback but has no 'override'.")
        print(f"  {path}:{line}  fun {fname}()   [{cls} : {cat}]")

    print()
    print("Decide which of these two you meant -- they are NOT interchangeable:")
    print()
    print("  (a) You intended to hook the lifecycle:")
    print("        add 'override', call super.<name>(...), and drop 'private'")
    print("        (platform callbacks are protected or public, never private).")
    print()
    print("  (b) You did NOT intend to hook the lifecycle -- e.g. it is a click")
    print("      handler that happens to be called onStart():")
    print("        RENAME it (startMedha(), onStartClicked(), ...).")
    print("        Adding 'override' here compiles but is a runtime bug: the body")
    print("        would then run on every lifecycle transition, including on")
    print("        return from a file picker or a settings screen.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
