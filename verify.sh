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
for f in app/build.gradle.kts app/src/main/AndroidManifest.xml \
         app/src/full/AndroidManifest.xml; do need "$f"; done

echo; echo "Sources"
for f in MainActivity InferenceService LlmEngine LocalServer SystemInfo MemoryRepository \
         MedhaApplication; do
  need "app/src/main/java/com/adabala/medha/$f.kt"; done
for f in auth/ClientRegistry sched/InferenceScheduler sched/SchedulerConfig \
         connectors/SmsConnector \
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
# Visibility must be declared by intent action, not a fixed package: Medha's
# applicationIdSuffix means the installed package varies by variant.
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

echo
if [ "$fail" -eq 0 ]; then
  echo "Ready.  ./gradlew assembleCoreDebug     (clean install, no SMS)"
  echo "        ./gradlew assembleFullDebug     (adds the SMS connector)"
else
  echo "Fix the items above first."; exit 1
fi

echo
echo "Pure-logic tests (SchedulerConfig, Embedder — no Android SDK needed)"
./tools/tests/run.sh || { echo "Pure-logic tests failed."; exit 1; }

echo
python3 tools/check_resources.py || { echo "Resource reference check failed."; exit 1; }
