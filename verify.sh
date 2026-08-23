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
         ui/ClientListAdapter; do
  need "app/src/main/java/com/adabala/medha/$f.kt"; done

echo; echo "Tests"
need "app/src/test/java/com/adabala/medha/sched/InferenceSchedulerConcurrencyTest.kt"
need "app/src/androidTest/java/com/adabala/medha/data/MedhaDatabaseMigrationTest.kt"
need "tools/tests/webapp_markdown_test.js"
need "tools/tests/webapp_sse_test.js"
need "tools/tests/webapp_smoke_test.js"
need "tools/tests/package.json"

echo; echo "Bundled demo webapp"
need "app/src/main/assets/webapp/index.html"

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
