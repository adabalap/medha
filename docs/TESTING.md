# Testing the v0.8.3 changes

Three tiers, because the changes span pure logic, coroutine concurrency, and
things only a real phone can tell you the truth about. None of them requires
guesswork about whether something "should" work — each tier either ran and
printed a number, or has an exact command for you to run and see one.

---

## Tier 1 — pure logic. Already run, in this review, right now.

`SchedulerConfig.validated()` (the queue-depth clamp, the thermal hysteresis
math) and `Embedder`'s codec/normalise/dot/prefix functions have zero Android
or kotlinx.coroutines dependency. They were compiled and executed directly
against the real source files, no device, no Gradle, no mocks:

```
$ tools/tests/run.sh
== SchedulerConfigTest ==
SchedulerConfigTest: 18 checks, 0 failed

== EmbedderTest ==
EmbedderTest: 18 checks, 0 failed
```

This is now wired into `verify.sh`, so it runs every time you do your normal
pre/post-change check:

```
$ ./verify.sh
```

Needs `kotlinc` on your PATH (any recent version; this review used 2.0.21)
and nothing else — no network, no Android SDK. Grab it from
https://github.com/JetBrains/kotlin/releases if you don't have it.

**What this tier does NOT cover:** anything that touches a real coroutine
dispatcher, a real Context, or native code. That's tiers 2 and 3.

---

## Tier 2 — scheduler concurrency. Written, not yet run — run it via Gradle.

`app/src/test/java/com/adabala/medha/sched/InferenceSchedulerConcurrencyTest.kt`
exercises the actual admission-control/queue-cap/timeout logic added this
session, using `kotlinx-coroutines-test`'s virtual clock so it runs in
milliseconds rather than actually waiting out a 2-minute timeout. It covers:

- queue depth returns to 0 after a request completes, and after one that throws
- the `(maxQueueDepth+1)`th concurrent request is rejected immediately, not queued
- a request stacked behind a permanently-hung one gets `TimedOut` instead of
  hanging forever
- two `INTERACTIVE` requests never run the engine concurrently (mutex holds)
- `acquire()`/`Permit.close()` used directly, including double-close safety

Run it with:

```
./gradlew testCoreDebugUnitTest --tests "*InferenceSchedulerConcurrencyTest*"
```

**Be the first to actually run this file.** The sandbox this repo was
reviewed in has no route to a real `kotlinx-coroutines-core` jar — Maven
Central is blocked and the project doesn't publish raw jars via GitHub
Releases — so this test was written and reasoned through by hand, but never
compiled or executed anywhere. Every other file in this delivery either ran
successfully in the sandbox or is explicitly marked as unverified below; this
is the one piece of new code without either. If it doesn't compile cleanly or
a case fails, that's real signal, not sandbox noise — file it back.

Newly added `testImplementation` deps for this (in `app/build.gradle.kts`):
`junit:junit:4.13.2`, `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1`,
`io.mockk:mockk:1.13.11`. First run will need network access to Google's/Maven
Central to resolve them.

---

## Tier 3 — on-device. Nothing below this line can be faked from a sandbox.

Install a debug build (`./gradlew installCoreDebug` or `installFullDebug`),
load a model, grab the admin token from the app UI, and run these from a
machine on the same network (or `adb reverse tcp:8080 tcp:8080` and use
`localhost`).

### 3a. Streaming/OpenAI-compat now goes through admission control

This is the actual bug this session fixed — before, these two endpoints had
zero protection. Confirm the 429 fires on the endpoints that used to skip it:

```bash
TOKEN="<your admin token>"
HOST="http://127.0.0.1:8080"

# Fire more concurrent streaming requests than maxQueueDepth (default 8).
# The (N+1)th response should come back fast with HTTP 429, not hang.
for i in $(seq 1 12); do
  curl -sS -o /dev/null -w "%{http_code}\n" -X POST "$HOST/v1/chat/completions" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"model":"medha","stream":true,"messages":[{"role":"user","content":"count to 200 slowly"}]}' &
done
wait
```

Expect a mix of `200` and `429`, not 12×`200` and not any request hanging
past `requestTimeoutMs` (2 min default). Same test against `POST
/generate/stream` should show the same shape.

### 3b. Timeout actually bounds a wedged request

Hard to induce a genuinely wedged native call safely, so the practical proxy:
set a short config value in prefs before starting the service —

```bash
adb shell run-as com.adabala.medha
# inside the app's data dir, or use the in-app settings screen if it exposes
# request timeout — check app/src/main/res for the preference key
```

— then fire two overlapping requests and confirm the second returns `504`
inside roughly your configured window, not left hanging. `GET /system` while
this is happening should show `queueDepth` and `inFlight` reflecting both
requests, not silently dropping the stuck one.

### 3c. Embedder dimension probe

```
adb logcat -s AiEdgeEmbedder
```

restart Medha with an embedding model dropped in `models/embed/` (see
`docs/EMBEDDINGS.md` for exactly where and which files). You should see one
of:

- `Embedder ready: <name>@<N> (seq=<S>, measured via startup probe)` — note
  `<N>` is now measured, not assumed. If you're running a 768-dim export this
  will read `@768` same as before; the fix only changes behavior on a
  Matryoshka-truncated (256/512/128-dim) variant, where it now reports the
  *actual* dimension instead of a wrong static guess.
- `Constructed ... but a startup probe embed produced no output; RAG stays
  lexical` — the SDK loaded but the probe call itself failed. This is new:
  previously a bad model file would silently keep pretending to be a working
  768-dim embedder until the first real query quietly failed.
- `No embedding model in .../models/embed; RAG stays lexical` or `AI Edge RAG
  SDK not on the classpath` — unchanged from before, expected if you haven't
  uncommented the gradle deps / pushed model files yet.

Then confirm retrieval quality end to end:

```bash
curl -sS -X POST "$HOST/rag/reindex" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{}'
# repeat until "remaining": 0

curl -sS -X POST "$HOST/v1/embeddings" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"input":["test query"],"input_type":"query"}' | head -c 300
```

A vector of the length reported in the logcat line above (not necessarily
768) confirms the measured dimension is actually what's being stored and
served.

### 3d. Client CRUD (unchanged this session, spot-check while you're in there)

From the app UI: create a client, copy its token, curl an endpoint with it,
rotate the token and confirm the old one now 401s, try revoking the last
admin and confirm it's refused.

---

## Summary table

| What | Verified how | Confidence |
|---|---|---|
| `SchedulerConfig.validated()` clamping | Executed in sandbox, real source | High |
| `Embedder` codec/normalize/dot/prefixes | Executed in sandbox, real source | High |
| Scheduler admission/queue-cap/timeout concurrency | Written, reasoned by hand, **not executed anywhere yet** | Run tier 2 first |
| `/generate/stream`, `/v1/chat/completions` now admission-controlled | Code review + `check_symbols`/`check_overrides` clean | Needs tier 3a |
| Embedder dimension probe correctness | Code review only | Needs tier 3c, on real SDK |
| Client CRUD | Unchanged this session | Already working per prior sessions |
