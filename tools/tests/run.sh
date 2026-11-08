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

echo
echo "== AccessContractTest =="
kotlinc AccessContractTest.kt -include-runtime -d "$WORK/access.jar"
java -jar "$WORK/access.jar"

echo
echo "== BriefPolicyTest =="
# Medha's own brief: the multi-client home-screen digest that exists because
# Samsung's Now Brief has no third-party API. Merge, expiry, ordering, bounds.
kotlinc "$SRC/notify/BriefPolicy.kt" BriefPolicyTest.kt \
  -include-runtime -d "$WORK/brief.jar"
java -jar "$WORK/brief.jar"

echo
echo "== BriefJsonTest =="
# The brief's on-disk format, INCLUDING the v0.9.0 -> v0.9.1 migration. Runs
# against the real BriefJson.kt plus stubs/org/json, which mirror Android's
# org.json signatures closely enough to execute the migration rather than
# merely type-check it. A migration that is only reasoned about is a
# migration that silently wipes someone's widget.
kotlinc stubs/org/json/Stubs.kt "$SRC/notify/BriefPolicy.kt" "$SRC/notify/BriefJson.kt" \
  BriefJsonTest.kt -include-runtime -d "$WORK/briefjson.jar"
java -jar "$WORK/briefjson.jar"

echo
echo "== ChatRequestParserTest =="
# The OpenAI-shaped request parser, including the multimodal content-part
# form. Before it, an image part threw and became a 500 -- the one answer a
# client built to fall back on 4xx cannot act on.
kotlinc stubs/org/json/Stubs.kt "$SRC/oai/ChatRequestParser.kt" ChatRequestParserTest.kt \
  -include-runtime -d "$WORK/chatreq.jar"
java -jar "$WORK/chatreq.jar"

echo
echo "== MultimodalProbeTest =="
# Decides what /health advertises. Claiming a capability this build does not
# have would stop clients falling back and make them fail instead.
kotlinc "$SRC/oai/MultimodalProbe.kt" MultimodalProbeTest.kt \
  -include-runtime -d "$WORK/probe.jar"
java -jar "$WORK/probe.jar"

echo
echo "== NetPolicyTest =="
# Origin matching, the CORS decision, the Private Network Access preflight,
# the notify rate limiter and the pairing-code state machine. This is the
# access-control boundary for every web app that pairs with Medha, so it runs
# against the real sources rather than a copy.
kotlinc "$SRC/net/WebOrigin.kt" "$SRC/net/CorsPolicy.kt" "$SRC/net/RateLimiter.kt" \
  "$SRC/auth/PairingManager.kt" NetPolicyTest.kt \
  -include-runtime -d "$WORK/net.jar"
java -jar "$WORK/net.jar"

if command -v node >/dev/null 2>&1; then
  echo
  echo "== webapp_markdown_test (Node) =="
  node webapp_markdown_test.js

  echo
  echo "== webapp_sse_test (Node) =="
  node webapp_sse_test.js

  echo
  echo "== medha_brief_client_test (Node) =="
  # samples/medha-brief.js is the file every PWA copies, so a bug in it is a
  # bug in all of them at once. Needs no jsdom -- it stubs fetch instead.
  node medha_brief_client_test.js

  echo
  echo "== webapp_smoke_test (Node + jsdom) =="
  if [ -d node_modules/jsdom ]; then
    node webapp_smoke_test.js
    echo
    echo "== sample_pwa_smoke_test (Node + jsdom) =="
    node sample_pwa_smoke_test.js
  else
    echo "jsdom not installed — run 'npm install' in tools/tests/ first, then re-run this script."
    echo "This is the test that catches a script-level bug (like a variable"
    echo "colliding with a built-in window property) silently halting the"
    echo "whole page with no visible error — worth the one-time npm install."
  fi
else
  echo
  echo "node not found on PATH — skipping the three webapp JS tests."
  echo "Get it from https://nodejs.org/, then re-run this script."
fi
