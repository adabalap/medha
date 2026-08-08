# Medha · మేధా

On-device LLM inference service for Android. Loads a `.litertlm` model once via
the LiteRT-LM runtime and serves it over a loopback HTTP API so any app or PWA
on the device can consume it. Named for *medhā* (मेधा / మేధా) — intelligence
with retentive memory.

Medha is a **substrate**, not an app. The intended pattern is: run Medha once,
then build thin PWAs (SMS organizer, note triage, an `hf_agent` pointed at
`127.0.0.1`) that hold no model and no inference code of their own.

---

## Quick start

1. Install the APK (sideload).
2. Open Medha → **Browse for .litertlm model** → pick your file (imported once).
3. Choose backend (GPU default) and port.
4. **Start Medha.** Watch the status card: loading → running.
5. Tap **Allow to run in background** so the OS doesn't kill it.
6. Tap **Copy API token** — your PWAs need it.

```bash
TOKEN=<paste from the app>
curl -s http://127.0.0.1:8080/health
curl -s -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"prompt":"Summarise this SMS: your parcel arrives Tuesday"}' \
     http://127.0.0.1:8080/generate
```

---

## Authentication

Every endpoint except `/health` and the bundled UI assets requires:

```
Authorization: Bearer <token>
```

`X-Medha-Token: <token>` is accepted as an alternative for clients that cannot
set `Authorization`.

**Why this exists.** Binding to `127.0.0.1` keeps the service off the network,
but it does *not* keep it away from the browser. Any web page the user visits
can issue `fetch("http://127.0.0.1:8080/generate", {method:"POST"})`, and the
browser will send it. CORS governs whether the page may *read* the reply, not
whether the request happens — so the previous `anyHost()` policy meant an
arbitrary site could both drive the model and read its output.

The token closes that. CORS is additionally restricted to the loopback origins
Medha itself serves.

**Residual risk, stated plainly:** the bundled demo UI is served unauthenticated
with the token substituted into the HTML, so same-origin pages work with no
setup. Another *native app* on the device could therefore fetch `/` and scrape
the token. A hostile *web page* cannot, because it cannot read a cross-origin
response. If you want the stricter posture, serve your own UI that prompts for
the token instead of relying on injection.

To disable auth entirely (not recommended), set the `require_auth` preference to
`false`.

---

## Endpoints (`http://127.0.0.1:<port>`)

### Status
| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| GET | `/health` | – | status, model loaded, backend, busy, last error |
| GET | `/system` | yes | device, SoC, RAM, thermal, storage, DB size, load time |
| GET | `/metrics` | yes | request/failure counts, tokens/sec, DB counts, in-flight |

### Generation
| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| POST | `/generate` | yes | `{prompt, system?}` → `{text, promptTokens, tokens, ms, tokensPerSec}` |
| POST | `/generate/stream` | yes | SSE; each `data:` payload is a JSON-encoded delta string |
| POST | `/chat` | yes | `{sessionId, message, system?, collection?, ragTopK?}` — multi-turn, SQLite-backed, optional RAG |

### Sessions
| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| GET | `/sessions?limit=&offset=` | yes | list threads with message counts |
| GET | `/sessions/{id}/messages?limit=` | yes | replay a thread |
| DELETE | `/sessions/{id}` | yes | delete thread + messages (CASCADE) |

### OpenAI-compatible
| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| GET | `/v1/models` | yes | model list |
| POST | `/v1/chat/completions` | yes | streaming and non-streaming, spec-shaped chunks |
| POST | `/v1/embeddings` | yes | `{input[], input_type}` — `501` when no embedder is loaded |

### RAG
| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| POST | `/rag/ingest` | yes | `{collection, text, title?, source?}` → chunk + store |
| POST | `/rag/query` | yes | `{collection, query, topK}` → ranked chunks + retrieval mode |
| POST | `/rag/reindex` | yes | backfill vectors for chunks lacking them |
| GET | `/rag/collections` | yes | list collections with doc/chunk/embedded counts |
| DELETE | `/rag/collections/{name}` | yes | drop a collection |

### UI
| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| GET | `/` | – | bundled demo PWA (same origin — no CORS/cert issues) |

---

## Building a consumer PWA

```js
const MEDHA = "http://127.0.0.1:8080";
const TOKEN = localStorage.getItem("medha_token");   // paste once from the app

const h = {
  "Content-Type": "application/json",
  "Authorization": "Bearer " + TOKEN
};

// Stateless
await fetch(`${MEDHA}/generate`, {
  method: "POST", headers: h,
  body: JSON.stringify({
    prompt: "Classify this SMS: ...",
    system: "Reply with one word."
  })
}).then(r => r.json());

// Stateful — Medha keeps the thread, your app keeps only the id
await fetch(`${MEDHA}/chat`, {
  method: "POST", headers: h,
  body: JSON.stringify({ sessionId: "sms-organizer-v1", message: "..." })
}).then(r => r.json());
```

Use a **distinct, stable `sessionId` per consumer app** (e.g.
`sms-organizer-v1`) so threads do not collide across PWAs.

Pointing an OpenAI-compatible client (`hf_agent`, LangChain, the `openai` SDK)
at Medha:

```
base_url = http://127.0.0.1:8080/v1
api_key  = <your Medha token>
model    = <anything; the loaded model is used>
```

---

## Architecture

```
MainActivity --starts--> InferenceService (foreground, wake lock, START_STICKY)
                              |
                              +-- LlmEngine     one native Engine, one Mutex
                              |                 serialising every request
                              |
                              +-- LocalServer   Ktor CIO on 127.0.0.1
                                     +-- MemoryRepository --+
                                     +-- Retriever ---------+--> MedhaDatabase
                                                                 (SQLite + FTS4)
```

**Concurrency.** One native engine cannot be driven from two threads. Both
`generate` and `generateStream` take the same coroutine `Mutex`, so requests
queue rather than corrupting native state, and waiting callers suspend instead
of parking a thread on the Ktor event loop.

**Persistence.** Plain SQLite, hand-written SQL, no Room. Room needs KSP, whose
version is pinned to the Kotlin compiler version; that coupling is a standing CI
hazard for a project this small. Migrations are additive — a schema bump no
longer wipes stored conversations. See `docs/FINDINGS.md`.

**Retrieval.** Hybrid. Lexical mode uses an FTS4 index to shortlist candidates
inside SQLite, then re-ranks with IDF weighting. With an embedder loaded, dense
vector search runs alongside it and the two are fused by Reciprocal Rank Fusion
— dense is strong on paraphrase but blurry on exact identifiers (order numbers,
OTP codes), which lexical catches. Vectors are float32 BLOBs tagged with the
embedding space that produced them, so a model swap can never silently compare
across spaces. See `docs/EMBEDDINGS.md`.

---

## Features

- **Persistent memory** — conversations, messages, and RAG chunks survive
  restarts. Pass a stable `sessionId` per consumer app.
- **Bounded context** — history is trimmed by character budget, not message
  count, so twenty pasted emails do not overflow a 2B model's window.
- **RAG** — hybrid dense + lexical retrieval with overlapping chunks; overlap
  keeps facts that straddle a boundary retrievable. Embeddings are optional and
  off by default (`docs/EMBEDDINGS.md`).
- **Metrics** — per-request timing, tokens/sec, failures, in-flight depth.
  Streaming requests are counted (they previously were not).
- **Backend selection** — GPU / CPU / NPU. LiteRT-LM has no "which backend am I
  on" getter, so the UI reports the *configured* backend plus whether init
  succeeded, rather than claiming an unverifiable value.
- **Hardened service** — foreground + wake lock + START_STICKY, idempotent model
  load, memory-pressure release, battery-exemption prompt for OEM killers.
- **Browsable model** — SAF picker, free-space check, atomic `.part` → final
  rename so a truncated copy never becomes the configured model.

---

## Two builds: core and full

CI produces **two APKs per build type**:

| APK | SMS connector | Play Protect |
|---|---|---|
| `medha-<v>-core-<type>-<sha>.apk` | no | installs cleanly |
| `medha-<v>-full-<type>-<sha>.apk` | yes | will warn |

Install **core** unless you need SMS. Everything else is identical. Play Protect
blocks sideloaded APKs that merely *declare* SMS permissions, so keeping them out
of the default build is what makes a clean install possible. See
`docs/PLAY-PROTECT.md`.

## Debug vs release builds

The workflow produced `app-debug.apk` because it ran `assembleDebug`, and AGP
names outputs `app-<buildType>.apk` regardless of the app.

A debug APK is not just "the same thing with a different name":

| | debug | release |
|---|---|---|
| Signing key | shared public Android debug key | your keystore |
| `debuggable` | true — any app with USB debugging can attach and read memory | false |
| Upgradeable over the other | no (different key) | no (different key) |
| `applicationId` | `com.adabala.medha.debug` | `com.adabala.medha` |

For a service that holds an API token and your conversation history,
`debuggable=true` is the part that matters. Use release for anything you
actually rely on.

CI now builds **both**, and names them
`medha-<version>-<buildType>-<shortsha>.apk`. The release build only happens
when signing secrets are present:

```
MEDHA_KEYSTORE_BASE64      base64 of your .jks
MEDHA_KEYSTORE_PASSWORD
MEDHA_KEY_ALIAS
MEDHA_KEY_PASSWORD
```

Generate a keystore once, and **back it up** — losing it means you can never
upgrade an existing install:

```bash
keytool -genkeypair -v -keystore medha.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias medha
base64 -w0 medha.jks    # paste into the MEDHA_KEYSTORE_BASE64 secret
```

The debug build carries a `.debug` applicationId suffix, so debug and release
can sit side by side on the same phone.

## Build

Push to GitHub → the **Build Medha APK** workflow produces a debug APK in the
run artifacts. Tag `v*` to also attach it as a Release asset.

The workflow runs a **Room guard** first: if any source file carries Room
annotations while the Room dependency or KSP plugin is absent, it fails
immediately with an actionable message instead of a wall of
`Unresolved reference 'room'`. That is the exact failure that broke v0.1.1.

The Gradle wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`) is **committed on purpose**. It pins the
build tool, so CI and every machine use the same Gradle.

Without it, CI has to fall back to `gradle wrapper` using whatever Gradle the
runner ships — and that task still has to *configure* the project before it can
run, so a runner-Gradle/AGP mismatch surfaces as:

```
Plugin [id: 'com.android.application'] was not found in any of the following sources
```

which looks like a broken build file but is nothing of the sort. The fallback
now generates the wrapper in an empty temp directory, where there is no build
script to configure, and copies the files in.

After cloning, if `./gradlew` is not executable:

```bash
chmod +x gradlew
git update-index --chmod=+x gradlew   # persist the bit in git
```

---

## Is it production ready?

Short answer: it is a good personal daily driver, not yet something to hand to
other people. See `docs/PRODUCTION-READINESS.md` for the blocker list — the
top items are instrumented tests for the DB migration, a request timeout, and
crash diagnostics.

## Notes / limits

- **Memory** — a 2B-class model plus long threads pressures RAM on mid-range
  devices. Watch `/system` → `lowMemory`. The service releases the engine on
  `TRIM_MEMORY_COMPLETE` rather than being killed mid-request.
- **Thermal** — sustained inference throttles the SoC and roughly halves
  tokens/sec. `/system` reports `thermal` so the slowdown is legible.
- **Foreground service type** — declared `dataSync`. On Android 15+ that type is
  subject to a cumulative daily runtime cap; if you need true always-on,
  investigate `specialUse` with a documented justification.
- **NPU** — needs vendor libs (Qualcomm QAIRT / MediaTek NeuroPilot) bundled per
  device. Exposed as an option; GPU/CPU are the safe defaults.
- **Token counts** — estimated at ~4 chars/token, not tokenizer-exact. Labelled
  as estimates everywhere they surface.
- **Streaming** — uses the AAR's `sendMessageAsync` Flow when present, else falls
  back to a single emission. Cumulative-vs-delta emission is auto-detected.
