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
| POST | `/v1/embeddings` | yes | `501` until an embedder is wired (honest, not a fake vector) |

### RAG
| Method | Path | Auth | Purpose |
|---|---|:--:|---|
| POST | `/rag/ingest` | yes | `{collection, text, title?, source?}` → chunk + store |
| POST | `/rag/query` | yes | `{collection, query, topK}` → ranked chunks + retrieval mode |
| GET | `/rag/collections` | yes | list collections with doc/chunk counts |
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

**Retrieval.** Lexical mode uses an FTS4 index to shortlist candidates inside
SQLite, then re-ranks that bounded set with IDF weighting in Kotlin. Supply an
`embedder` to `Retriever` (e.g. EmbeddingGemma) and it switches to cosine
similarity automatically; chunk storage already round-trips embeddings.

---

## Features

- **Persistent memory** — conversations, messages, and RAG chunks survive
  restarts. Pass a stable `sessionId` per consumer app.
- **Bounded context** — history is trimmed by character budget, not message
  count, so twenty pasted emails do not overflow a 2B model's window.
- **RAG** — ingest/query with overlapping chunks; overlap keeps facts that
  straddle a boundary retrievable.
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

## Build

Push to GitHub → the **Build Medha APK** workflow produces a debug APK in the
run artifacts. Tag `v*` to also attach it as a Release asset.

The workflow runs a **Room guard** first: if any source file carries Room
annotations while the Room dependency or KSP plugin is absent, it fails
immediately with an actionable message instead of a wall of
`Unresolved reference 'room'`. That is the exact failure that broke v0.1.1.

> Commit `gradlew` and `gradle/wrapper/gradle-wrapper.jar`. The workflow
> regenerates them if missing, but an uncommitted wrapper means CI is not
> building with a pinned tool version.

---

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
