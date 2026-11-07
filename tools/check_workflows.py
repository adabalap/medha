#!/usr/bin/env python3
"""
Checks the GitHub Actions workflows for configuration that is known to break
this project. CI is the ONLY way this project gets built, so a broken workflow
means no APK at all -- and these failures happen during provisioning, before
anything is compiled, so the logs look nothing like a build problem.

Run: python3 tools/check_workflows.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WF_DIR = ROOT / ".github" / "workflows"

failures = []
checks = 0


def fail(msg):
    failures.append(msg)
    print(f"  FAIL  {msg}")


def ok(msg):
    print(f"  ok    {msg}")


def check(msg, condition):
    global checks
    checks += 1
    if condition:
        ok(msg)
    else:
        fail(msg)


def main():
    # main() increments the module-level counter directly in a few places
    # rather than only through check(), so it has to declare it.
    global checks

    if not WF_DIR.is_dir():
        print(f"check_workflows: no {WF_DIR}, nothing to check")
        return 0

    files = sorted(WF_DIR.glob("*.yml")) + sorted(WF_DIR.glob("*.yaml"))
    if not files:
        print("check_workflows: no workflow files found")
        return 0

    for f in files:
        text = f.read_text()
        rel = f.relative_to(ROOT)

        # ------------------------------------------------ setup-android
        #
        # The action's default `packages` input is "tools platform-tools".
        # Google removed the deprecated legacy `tools` package from the SDK
        # repository index, so sdkmanager exits 1 with
        # "Failed to find package 'tools'" and the job dies before compiling.
        # Every setup-android step must therefore pin `packages` to a list
        # that excludes `tools`.
        #
        # Upstream: android-actions/setup-android#537.
        for m in re.finditer(r"uses:\s*\S*setup-android@\S+", text):
            # Look at the step body that follows this `uses:` line, up to the
            # next step (a line starting with "- " at the same indent) or EOF.
            tail = text[m.end():]
            body = re.split(r"\n\s*-\s+(?:name|uses):", tail, maxsplit=1)[0]
            line_no = text[:m.start()].count("\n") + 1

            pkgs = re.search(r"^\s*packages:\s*(.+?)\s*$", body, re.MULTILINE)
            if pkgs is None:
                fail(
                    f"{rel}:{line_no} setup-android has no explicit "
                    "'packages:' input -- its default includes the removed "
                    "'tools' package and sdkmanager will exit 1 during setup"
                )
                checks += 1
                continue

            value = pkgs.group(1).strip().strip("\"'")
            checks += 1
            if not value:
                fail(
                    f"{rel}:{line_no} setup-android 'packages' is empty -- "
                    "it must be a non-empty list, e.g. \"platform-tools\""
                )
            elif re.search(r"(^|\s)tools(\s|$)", value):
                fail(
                    f"{rel}:{line_no} setup-android 'packages' still requests "
                    f"the removed legacy 'tools' package: {value!r}"
                )
            else:
                ok(f"{rel}:{line_no} setup-android pins packages={value!r}")

        # ------------------------------------------------ APK output paths
        #
        # v0.9.0 collapsed the core/full product flavours. Flavour builds land
        # in apk/<flavour>/<buildType>/, single-variant builds in
        # apk/<buildType>/. A stale flavour path copies nothing, and the
        # upload step then fails on an empty dist/ while blaming the uploader.
        if "outputs/apk" in text:
            checks += 1
            stale = re.findall(r"outputs/apk/(?:core|full)/", text)
            if stale:
                fail(f"{rel} references a product-flavour APK path; flavours were removed in v0.9.0")
            else:
                ok(f"{rel} APK paths are flavour-free")

        # ------------------------------------------------ assemble tasks
        if "gradlew" in text:
            checks += 1
            flavoured = re.findall(
                r"(?:assemble|lint|test|bundle)(?:Core|Full)[A-Za-z]*", text
            )
            if flavoured:
                fail(
                    f"{rel} invokes flavour-specific Gradle tasks "
                    f"{sorted(set(flavoured))}; there is one variant since v0.9.0"
                )
            else:
                ok(f"{rel} Gradle tasks are flavour-free")

    # ------------------------------------------------ main build reachability
    #
    # The Medha APK workflow has to run on every branch. It was once
    # restricted to main/master while the sample workflow had no branch
    # filter, so pushing a feature branch returned a `hello-medha-apk`
    # artifact and no Medha APK, with nothing explaining the absence. CI is
    # the only way this project gets built, so the main build must be the one
    # that always runs.
    main_wf = WF_DIR / "build-apk.yml"
    if main_wf.is_file():
        checks += 1
        text = main_wf.read_text()
        # The push: block, up to the next top-level trigger key.
        push = re.search(
            r"^on:\s*$.*?^\s{2}push:\s*$(.*?)(?=^\s{2}\w+:|^\w+:)",
            text, re.MULTILINE | re.DOTALL
        )
        if push is None:
            fail("build-apk.yml has no push: trigger -- it will not build on a push at all")
        else:
            branches = re.search(r"^\s*branches:\s*(.+?)\s*$", push.group(1), re.MULTILINE)
            # Compare against the quote-stripped entries, not the raw line:
            # `[ '*' ]` and `[ * ]` mean the same thing to Actions, and the
            # single-star case is the one worth naming precisely because it
            # looks like a fix.
            entries = (
                [e.strip().strip("\"'") for e in
                 re.sub(r"[\[\]]", " ", branches.group(1)).split(",")]
                if branches else []
            )
            if branches is None:
                ok("build-apk.yml builds on every branch (no branches filter)")
            elif "**" in entries:
                ok("build-apk.yml builds on every branch (branches: '**')")
            elif "*" in entries:
                # A single star does not match a '/', so it silently skips
                # every feature/... branch -- which looks like a fix and is not.
                fail(
                    "build-apk.yml uses branches: '*', which does NOT match "
                    "branch names containing '/' such as feature/x. Use '**'"
                )
            else:
                fail(
                    "build-apk.yml restricts push to "
                    f"{branches.group(1)} -- feature branches get no Medha APK, "
                    "only the sample. Use branches: [ '**' ]"
                )

    print()
    if failures:
        print(f"check_workflows: {len(failures)} of {checks} checks FAILED")
        return 1
    print(f"check_workflows: OK ({checks} checks across {len(files)} workflow files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
