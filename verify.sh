#!/usr/bin/env bash
# Confirms Medha extracted correctly and is buildable. Run from the extract dir.
set -uo pipefail
fail=0
say() { printf "  %-8s %s\n" "$1" "$2"; }
need() { if [ -e "$1" ]; then say OK "$1"; else say MISSING "$1"; fail=1; fi }

echo "Gradle build"
for f in settings.gradle.kts build.gradle.kts gradle.properties gradlew \
         gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties; do need "$f"; done

echo; echo "App module"
for f in app/build.gradle.kts app/src/main/AndroidManifest.xml; do need "$f"; done

echo; echo "Sources"
for f in MainActivity InferenceService LlmEngine LocalServer SystemInfo MemoryRepository \
         MedhaApplication; do
  need "app/src/main/java/com/adabala/medha/$f.kt"; done
for f in auth/ClientRegistry sched/InferenceScheduler sched/SchedulerConfig \
         notify/NotificationHub notify/MedhaWidgetProvider data/MedhaDatabase \
         data/Entities rag/Retriever rag/Embedder rag/AiEdgeEmbedder diag/Diagnostics \
         ui/ClientListAdapter auth/MedhaAccessContract auth/AccessRequestActivity; do
  need "app/src/main/java/com/adabala/medha/$f.kt"; done

echo; echo "Tests"
need "app/src/test/java/com/adabala/medha/sched/InferenceSchedulerConcurrencyTest.kt"
need "app/src/androidTest/java/com/adabala/medha/data/MedhaDatabaseMigrationTest.kt"
need "tools/tests/webapp_markdown_test.js"
need "tools/tests/webapp_sse_test.js"
need "tools/tests/webapp_smoke_test.js"
need "tools/tests/AccessContractTest.kt"
need "tools/tests/package.json"

echo; echo "Bundled demo webapp"
need "app/src/main/assets/webapp/index.html"

# Standalone Gradle project, deliberately not part of this build (see its
# settings.gradle.kts). Checked for presence only -- building it is a separate
# "open samples/hello-medha in Android Studio" step.
echo; echo "Integration sample"
need "samples/notify-pwa/index.html"
need "samples/notify-pwa/README.md"
need "docs/NOTIFICATIONS.md"
need "tools/tests/NetPolicyTest.kt"
need "tools/tests/BriefPolicyTest.kt"
need "tools/tests/BriefJsonTest.kt"
need "app/src/main/java/com/adabala/medha/BriefActivity.kt"
need "app/src/main/java/com/adabala/medha/notify/BriefRotator.kt"
need "app/src/main/res/layout/activity_brief.xml"
need "tools/tests/stubs/org/json/Stubs.kt"
need "tools/check_workflows.py"
need "samples/medha-brief.js"
need "samples/INTEGRATING.md"
need "samples/medha_brief.py"
need "samples/hg-agent-brief.py"
need "tools/tests/medha_brief_py_test.py"
need "tools/tests/medha_brief_client_test.js"
need "tools/tests/sample_pwa_smoke_test.js"
need "samples/hello-medha/README.md"
need "samples/hello-medha/app/src/main/java/com/example/hellomedha/MainActivity.kt"
need "samples/hello-medha/app/src/main/java/com/example/hellomedha/MedhaClient.kt"
# Build scaffolding. Checked explicitly because a missing gradle.properties
# (no android.useAndroidX) already cost one CI round-trip, and every one of
# these fails only after a push, a queue wait and a Gradle download.
need "samples/hello-medha/settings.gradle.kts"
need "samples/hello-medha/build.gradle.kts"
need "samples/hello-medha/gradle.properties"
need "samples/hello-medha/app/build.gradle.kts"
need "samples/hello-medha/app/src/main/AndroidManifest.xml"
need "samples/hello-medha/gradlew"
need "samples/hello-medha/gradle/wrapper/gradle-wrapper.jar"
need "samples/hello-medha/gradle/wrapper/gradle-wrapper.properties"
if grep -q "^android.useAndroidX=true" samples/hello-medha/gradle.properties 2>/dev/null; then
  say OK "sample sets android.useAndroidX"
else
  say BAD "samples/hello-medha/gradle.properties must set android.useAndroidX=true"; fail=1
fi
# INTERNET is required even for 127.0.0.1: Android gates socket() on it and
# loopback is not exempt. Its absence fails at runtime with an opaque
# "socket failed: EPERM", not at build time -- so it has to be checked here.
if grep -q "android.permission.INTERNET" samples/hello-medha/app/src/main/AndroidManifest.xml 2>/dev/null; then
  say OK "sample declares INTERNET (needed even for loopback)"
else
  say BAD "sample manifest must declare android.permission.INTERNET"; fail=1
fi
# Visibility is still declared by intent action rather than a fixed package.
# v0.9.0 pinned the applicationId to com.adabala.medha for every variant, so a
# literal package name would now work -- but resolving the action keeps working
# if that ever changes again, and costs nothing.
if grep -q "com.adabala.medha.action.REQUEST_ACCESS" samples/hello-medha/app/src/main/AndroidManifest.xml 2>/dev/null; then
  say OK "sample queries Medha by intent action, not fixed package"
else
  say BAD "sample manifest must declare <queries><intent> for REQUEST_ACCESS"; fail=1
fi

echo; echo "Hygiene"
[ -x gradlew ] && say OK "gradlew is executable" || { say FIX "chmod +x gradlew"; }
if find . -name "*[{}]*" -not -path "./.git/*" | grep -q .; then
  say BAD "filenames with braces (some extractors fail on these)"; fail=1
else say OK "no problematic filenames"; fi
if [ -d medha ]; then say BAD "nested medha/ folder - flatten it"; fail=1; fi

# BuildInfo.VERSION drifted from app/build.gradle.kts's versionName once
# already (shipped 0.8.3 with the About page and /system still reporting
# 0.8.2) because it was a separately hand-maintained literal. It now reads
# BuildConfig.VERSION_NAME instead, so this just confirms nobody reverts that.
if grep -q 'const val VERSION = "' app/src/main/java/com/adabala/medha/LocalServer.kt 2>/dev/null; then
  say BAD "BuildInfo.VERSION is hardcoded again instead of reading BuildConfig.VERSION_NAME"
  fail=1
else say OK "BuildInfo.VERSION sources from BuildConfig, not a literal"; fi

# --- absence guards -------------------------------------------------------
# These check that things are GONE. A presence check cannot catch a feature
# being reintroduced, and both of these were removed for reasons that still
# hold: SMS permissions trip Play Protect on a sideloaded APK, and a variable
# applicationId breaks every consumer that has to name the package.
#
# These match CODE TOKENS, not the word "sms". A blanket word match also hits
# the comments that explain why SMS was removed, and a guard that cries wolf
# on its own rationale is a guard somebody deletes.
SMS_TOKENS='SmsConnector|READ_SMS|SEND_SMS|RECEIVE_SMS|READ_CONTACTS|"sms\.read"|"sms\.send"|/connectors/sms|R\.id\.nav_sms|R\.string\.sms_|R\.drawable\.ic_sms'
if grep -rqE "$SMS_TOKENS" app/src 2>/dev/null; then
  say BAD "SMS code is back in app/src -- removed in v0.9.0 (Play Protect)"
  grep -rlE "$SMS_TOKENS" app/src 2>/dev/null | sed 's/^/           /'
  fail=1
else say OK "no SMS code or permissions in app/src"; fi

# Strip // and # comments before matching, same reason.
gradle_code() { sed 's|//.*||' app/build.gradle.kts; }
if gradle_code | grep -q "applicationIdSuffix"; then
  say BAD "applicationIdSuffix is back -- the package must stay com.adabala.medha"
  fail=1
else say OK "applicationId is com.adabala.medha for every variant"; fi

# The widget layout's row count has to equal BriefPolicy.PAGE_ROWS. v0.9.0
# shipped a three-row layout against a server that accepted five items and
# dropped two in silence, so this is checked rather than trusted.
PAGE_ROWS=$(grep -oE 'const val PAGE_ROWS = [0-9]+' \
  app/src/main/java/com/adabala/medha/notify/BriefPolicy.kt 2>/dev/null \
  | grep -oE '[0-9]+$')
LAYOUT_ROWS=$(grep -cE 'android:id="@\+id/widgetRow[0-9]+"' \
  app/src/main/res/layout/widget_medha.xml 2>/dev/null || echo 0)
SLOT_ROWS=$(grep -cE 'Slot\(R\.id\.widgetRow[0-9]+' \
  app/src/main/java/com/adabala/medha/notify/MedhaWidgetProvider.kt 2>/dev/null || echo 0)
if [ -n "$PAGE_ROWS" ] && [ "$PAGE_ROWS" = "$LAYOUT_ROWS" ] && [ "$PAGE_ROWS" = "$SLOT_ROWS" ]; then
  say OK "widget rows agree ($PAGE_ROWS): PAGE_ROWS, layout, provider slots"
else
  say BAD "widget row mismatch - PAGE_ROWS=$PAGE_ROWS layout=$LAYOUT_ROWS slots=$SLOT_ROWS"
  fail=1
fi

if gradle_code | grep -q "productFlavors"; then
  say BAD "product flavours are back -- v0.9.0 collapsed them to one variant"
  fail=1
else say OK "single build variant, no flavour dimension"; fi

echo
if [ "$fail" -eq 0 ]; then
  echo "Ready.  ./gradlew assembleDebug"
  echo "        ./gradlew assembleRelease"
else
  echo "Fix the items above first."; exit 1
fi

echo
echo "Pure-logic tests (SchedulerConfig, Embedder — no Android SDK needed)"
./tools/tests/run.sh || { echo "Pure-logic tests failed."; exit 1; }

echo
python3 tools/check_resources.py || { echo "Resource reference check failed."; exit 1; }

# CI is the only way this project gets built, so a broken workflow means no
# APK at all -- and these failures happen during provisioning, before
# anything compiles, so the logs look nothing like a build problem.
python3 tools/check_workflows.py || { echo "Workflow check failed."; exit 1; }

# The Python client is the only thing here that can be exercised over a
# REAL HTTP round trip -- a stdlib http.server stands in for Medha -- so
# it runs unconditionally rather than behind a toolchain check.
echo
python3 tools/tests/medha_brief_py_test.py || { echo "Python client tests failed."; exit 1; }
