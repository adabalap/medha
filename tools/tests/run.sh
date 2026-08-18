#!/usr/bin/env bash
# Compiles and runs the pure-logic test harness in this directory against the
# REAL source files under app/src/main/java — not copies. This covers exactly
# the two files with zero Android/kotlinx.coroutines dependencies:
# SchedulerConfig.kt and Embedder.kt.
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
# Requires: kotlinc, java. Neither requires network access or an Android SDK.
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
