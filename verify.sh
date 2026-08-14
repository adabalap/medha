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
for f in MainActivity InferenceService LlmEngine LocalServer SystemInfo MemoryRepository; do
  need "app/src/main/java/com/adabala/medha/$f.kt"; done
for f in auth/ClientRegistry sched/InferenceScheduler connectors/SmsConnector \
         notify/NotificationHub notify/MedhaWidgetProvider data/MedhaDatabase \
         data/Entities rag/Retriever rag/Embedder rag/AiEdgeEmbedder; do
  need "app/src/main/java/com/adabala/medha/$f.kt"; done

echo; echo "Hygiene"
[ -x gradlew ] && say OK "gradlew is executable" || { say FIX "chmod +x gradlew"; }
if find . -name "*[{}]*" -not -path "./.git/*" | grep -q .; then
  say BAD "filenames with braces (some extractors fail on these)"; fail=1
else say OK "no problematic filenames"; fi
if [ -d medha ]; then say BAD "nested medha/ folder - flatten it"; fail=1; fi

echo
if [ "$fail" -eq 0 ]; then
  echo "Ready.  ./gradlew assembleCoreDebug     (clean install, no SMS)"
  echo "        ./gradlew assembleFullDebug     (adds the SMS connector)"
else
  echo "Fix the items above first."; exit 1
fi
