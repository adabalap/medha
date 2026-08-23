#!/usr/bin/env bash
# Compiles and runs the pure-logic test harness in this directory against the
# REAL source files under app/src/main/java — not copies — plus the demo
# webapp's own pure JS functions, extracted from the real
# app/src/main/assets/webapp/index.html rather than duplicated.
#
# What this does NOT cover, and why: everything else that changed in the
# scheduler/streaming/embedder work (InferenceScheduler's admission control,
# AiEdgeEmbedder's reflection probe) needs a real Android classpath and a real
# kotlinx.coroutines jar to even compile, let alone run meaningfully — a
# thermal-headroom reading or a LiteRT native call can't be faked into
# something worth trusting. Those live as instrumented/Robolectric-backed
# tests under app/src/test and app/src/androidTest instead, run via
# `./gradlew testCoreDebugUnitTest` / `connectedCoreDebugAndroidTest`, and as
# the manual on-device checklist in docs/TESTING.md.
#
# Requires: kotlinc, java for the Kotlin tests; node for the webapp tests
# (skipped with a note if node isn't on PATH). None require network access or
# an Android SDK.
set -euo pipefail
cd "$(dirname "$0")"
SRC="../../app/src/main/java/com/adabala/medha"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "kotlinc not found on PATH — skipping pure-logic test run."
  echo "Get it from https://github.com/JetBrains/kotlin/releases (kotlin-compiler-*.zip)."
  exit 0
fi

echo "== SchedulerConfigTest =="
kotlinc "$SRC/sched/SchedulerConfig.kt" SchedulerConfigTest.kt \
  -include-runtime -d "$WORK/sched.jar"
java -jar "$WORK/sched.jar"

echo
echo "== EmbedderTest =="
kotlinc "$SRC/rag/Embedder.kt" EmbedderTest.kt \
  -include-runtime -d "$WORK/embed.jar"
java -jar "$WORK/embed.jar"

if command -v node >/dev/null 2>&1; then
  echo
  echo "== webapp_markdown_test (Node) =="
  node webapp_markdown_test.js

  echo
  echo "== webapp_sse_test (Node) =="
  node webapp_sse_test.js
else
  echo
  echo "node not found on PATH — skipping the two webapp JS tests."
  echo "Get it from https://nodejs.org/, then re-run this script."
fi
