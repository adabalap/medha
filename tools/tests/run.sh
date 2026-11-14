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
echo "== BriefRenderTest =="
# What the widget decides to draw. Lived inside the provider until v0.13.0,
# where nothing short of Gradle could compile it and so nothing tested it --
# which is how dueAt went two releases being stored and ignored.
kotlinc "$SRC/notify/BriefPolicy.kt" "$SRC/notify/BriefRender.kt" \
  BriefRenderTest.kt -include-runtime -d "$WORK/render.jar"
java -jar "$WORK/render.jar"

echo
echo "== WidgetCallShapesTest =="
# The BriefPolicy and BriefRender call shapes used by BriefWidgets.kt and
# LocalServer. Those three need an Android classpath, so no stage before
# Gradle type-checks them; this compiles the same argument shapes against the
# real BriefPolicy so a signature change fails here in seconds instead of in
# CI minutes later.
kotlinc "$SRC/notify/BriefPolicy.kt" "$SRC/notify/BriefRender.kt" \
  WidgetCallShapesTest.kt -include-runtime -d "$WORK/shapes.jar"
java -jar "$WORK/shapes.jar"

echo
echo "== NotificationFilterTest =="
# What a notification is allowed to become. Medha asks for the one Android
# switch that reads EVERY notification, because a work calendar inside
# Outlook or Teams never reaches CalendarContract. This is the evidence for
# the promise that comes with that -- above all, that a one-time passcode
# can never reach a home screen.
kotlinc "$SRC/connect/NotificationFilter.kt" NotificationFilterTest.kt \
  -include-runtime -d "$WORK/nfilter.jar"
java -jar "$WORK/nfilter.jar"

echo
echo "== NotificationItemTest =="
# And what it becomes: which glyph, what time the words mean, and keeping
# the same meeting from appearing four times as its countdown ticks down.
kotlinc "$SRC/notify/BriefPolicy.kt" "$SRC/connect/NotificationFilter.kt" \
  "$SRC/connect/NotificationItem.kt" NotificationItemTest.kt \
  -include-runtime -d "$WORK/nitem.jar"
java -jar "$WORK/nitem.jar"

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
# have would stop clients falling back and make them fail instead -- and
# HIDING one, which is what v1 of the probe did for three releases, means
# shipping a whisper.cpp sidecar to do what the model already does. The
# first test in here replays v1's hint list against the real 0.13.1 surface
# and asserts it matches nothing.
kotlinc "$SRC/oai/MultimodalProbe.kt" MultimodalProbeTest.kt \
  -include-runtime -d "$WORK/probe.jar"
java -jar "$WORK/probe.jar"

echo
echo "== MultimodalLadderTest =="
# The order the engine tries vision/audio backends in. Every rung costs a
# full model load (~8.6s on an SM8850), and the first real device to run
# this refused audio-on-GPU while accepting audio-on-CPU -- so the ladder
# is the difference between "this model has no audio encoder" and audio
# working. Ordering, de-duplication, the cached-rung hint, and the
# constraint parser that tells "the model declares it requires CPU" apart
# from "something went wrong" -- opposite meanings, reported identically
# until v0.11.4.
kotlinc "$SRC/oai/MultimodalLadder.kt" "$SRC/oai/BackendConstraint.kt" \
  MultimodalLadderTest.kt -include-runtime -d "$WORK/ladder.jar"
java -jar "$WORK/ladder.jar"

echo
echo "== WavTest =="
# InputData.Audio takes WAV only, and AudioRecord produces headerless PCM,
# so every audio request goes through this file. Two silent killers: the
# RIFF size field is total-8 and not total, and chunk walking has to honour
# the word-align pad byte or a perfectly valid ffmpeg WAV reports a wrong
# duration and gets rejected against the 30s limit.
kotlinc "$SRC/audio/Wav.kt" WavTest.kt -include-runtime -d "$WORK/wav.jar"
java -jar "$WORK/wav.jar"

echo
echo "== ResampleTest =="
# Getting a video's audio to the 16 kHz mono the engine demands. The alias
# check is the one that matters: naive decimation folds 12 kHz down onto
# speech at full strength and nothing about it looks broken, so the test
# drives real tones through and measures what survives.
kotlinc "$SRC/audio/Resample.kt" ResampleTest.kt -include-runtime -d "$WORK/resample.jar"
java -jar "$WORK/resample.jar"

echo
echo "== TranscriptionRequestTest =="
# POST /v1/audio/transcriptions -- the endpoint that replaces whisper.cpp
# for hg_agent. Validation, the Telugu script instruction, and the cleanup
# that strips the model's conversational scaffolding without eating real
# transcript text.
kotlinc "$SRC/audio/Wav.kt" "$SRC/oai/TranscriptionRequest.kt" \
  TranscriptionRequestTest.kt -include-runtime -d "$WORK/trans.jar"
java -jar "$WORK/trans.jar"

echo
echo "== TokenCapTest =="
# max_tokens on the multimodal path is a truncation, not a cap. Getting the
# character budget wrong empties every capped response rather than
# shortening it.
kotlinc "$SRC/oai/TokenCap.kt" TokenCapTest.kt -include-runtime -d "$WORK/tokencap.jar"
java -jar "$WORK/tokencap.jar"

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

echo
echo "== hg_agent_transcribe_test (Python) =="
# samples/hg-agent-transcribe.py is what replaces whisper.cpp for hg_agent,
# and its multipart encoder is hand-rolled. Real HTTP round trips against a
# stub server, parsed back with the stdlib's email parser -- a multipart bug
# produces a truncated upload that looks like a corrupt audio file.
if command -v python3 >/dev/null 2>&1; then
  python3 -I hg_agent_transcribe_test.py
else
  echo "python3 not found on PATH — skipping."
fi

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
