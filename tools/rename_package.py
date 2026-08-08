#!/usr/bin/env python3
"""
Rename the application identity across all three places it appears.

    python3 tools/rename_package.py io.github.you.medha

What it changes:
  1. applicationId  in app/build.gradle.kts
  2. namespace      in app/build.gradle.kts
  3. Kotlin package — directory layout under app/src/main/java, plus every
     `package` and `import` statement in the sources
  4. the sharedpref filename referenced in the backup/data-extraction rules
     (Android derives it from applicationId: "<applicationId>_preferences.xml")
  5. any remaining textual references in docs and the demo PWA

Run from the repository root. Idempotent: re-running with the same name is a
no-op. Prints a summary and exits non-zero if anything looks unfinished.

WARNING: changing applicationId changes app identity. An installed build under
the old id cannot be upgraded to the new one — the user gets a second app, and
the old one keeps its own settings, API token and database. Do this before
anyone else installs, not after.
"""

import os
import re
import shutil
import subprocess
import sys

OLD = "com.example.litertservice"
SRC_ROOT = "app/src/main/java"

# Files whose *textual* occurrences of the old id should be rewritten.
TEXT_GLOBS = [
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/xml/backup_rules.xml",
    "app/src/main/res/xml/data_extraction_rules.xml",
    "README.md",
    "docs/FINDINGS.md",
    "docs/PRODUCTION-READINESS.md",
    ".github/workflows/build-apk.yml",
    "tools/check_overrides.py",
]

# This script holds OLD as a constant, so it must never rewrite or flag itself.
SELF = os.path.normpath(__file__) if "__file__" in dir() else ""

VALID = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")

# Reserved words that cannot appear as a Java/Kotlin package segment.
RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "in", "is", "object",
    "fun", "val", "var", "when", "typealias",
}


def die(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def validate(new):
    if not VALID.match(new):
        die(f"'{new}' is not a valid package name.\n"
            "       Use lowercase reverse-DNS with at least two segments, e.g.\n"
            "       io.github.yourname.medha")
    segs = new.split(".")
    if len(segs) < 2:
        die("a package name needs at least two segments")
    for s in segs:
        if s in RESERVED:
            die(f"'{s}' is a reserved word and cannot be a package segment")
        if s[0].isdigit():
            die(f"segment '{s}' cannot start with a digit")
    if new.startswith("com.example"):
        die("com.example.* is the placeholder you are trying to escape")
    return new


def move_sources(new):
    old_dir = os.path.join(SRC_ROOT, *OLD.split("."))
    new_dir = os.path.join(SRC_ROOT, *new.split("."))
    if not os.path.isdir(old_dir):
        if os.path.isdir(new_dir):
            print(f"  sources already at {new_dir}")
            return 0
        die(f"cannot find sources at {old_dir} — run this from the repo root")

    os.makedirs(os.path.dirname(new_dir), exist_ok=True)

    # Prefer `git mv` so history follows the files.
    moved_with_git = False
    if os.path.isdir(".git"):
        r = subprocess.run(["git", "mv", old_dir, new_dir],
                           capture_output=True, text=True)
        moved_with_git = r.returncode == 0
    if not moved_with_git:
        shutil.move(old_dir, new_dir)

    # Clean up now-empty ancestor directories of the old package.
    parent = os.path.dirname(old_dir)
    while parent.startswith(SRC_ROOT) and parent != SRC_ROOT:
        if os.path.isdir(parent) and not os.listdir(parent):
            os.rmdir(parent)
            parent = os.path.dirname(parent)
        else:
            break

    n = sum(len(f) for _, _, f in os.walk(new_dir))
    print(f"  moved {n} source file(s) -> {new_dir}"
          f"{' (via git mv)' if moved_with_git else ''}")
    return n


def rewrite(paths, new):
    changed = []
    for p in paths:
        if not os.path.isfile(p):
            continue
        s = open(p, encoding="utf-8").read()
        if OLD not in s:
            continue
        open(p, "w", encoding="utf-8").write(s.replace(OLD, new))
        changed.append(p)
    return changed


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    new = validate(sys.argv[1].strip())

    if not os.path.isfile("app/build.gradle.kts"):
        die("run this from the repository root")

    print(f"Renaming {OLD}  ->  {new}\n")

    print("Kotlin sources:")
    move_sources(new)

    # Every .kt under the new tree, plus the fixed text files.
    kt = []
    for root, _, files in os.walk(SRC_ROOT):
        kt += [os.path.join(root, f) for f in files if f.endswith(".kt")]
    changed = rewrite(kt, new)
    print(f"  rewrote package/import in {len(changed)} source file(s)")

    print("\nBuild + manifest + docs:")
    for p in rewrite(TEXT_GLOBS, new):
        print(f"  {p}")

    # The demo PWA and any other assets.
    extra = []
    for root, _, files in os.walk("app/src/main/assets"):
        extra += [os.path.join(root, f) for f in files]
    for p in rewrite(extra, new):
        print(f"  {p}")

    # -------------------- verify nothing was missed --------------------
    leftovers = []
    skip_dirs = {".git", "build", ".gradle", ".idea"}
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        for f in files:
            p = os.path.join(root, f)
            if p.endswith((".png", ".jks", ".zip", ".jar", ".webp")):
                continue
            if os.path.basename(p) == "rename_package.py":
                continue
            try:
                if OLD in open(p, encoding="utf-8", errors="ignore").read():
                    leftovers.append(p)
            except OSError:
                pass

    print()
    if leftovers:
        print("Remaining references to the old id (review by hand):")
        for p in sorted(set(leftovers)):
            print(f"  {p}")
        sys.exit(1)

    print(f"Done. applicationId, namespace and Kotlin package are all '{new}'.")
    print()
    print("Next:")
    print("  1. Uninstall any previously installed build — the old id is a")
    print("     separate app and will not be upgraded in place.")
    print("  2. ./gradlew clean assembleDebug")
    print("  3. Commit: git add -A && git commit -m "
          f"'chore: move off com.example placeholder to {new}'")


if __name__ == "__main__":
    main()
