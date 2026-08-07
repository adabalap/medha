# Medha — code review findings and changes

Review of the uploaded tree against the reported v0.1.1 CI failure.

---

## 1. The build failure

**Reported:** `Unresolved reference 'room' / 'Dao' / 'Query' / 'Insert'` in
`app/src/main/java/com/example/litertservice/data/Daos.kt`.

**Root cause: the tagged commit and the uploaded tree are not the same code.**

The uploaded ZIP contains **no `Daos.kt` at all**, and no Room reference
anywhere — not in the sources, not in `app/build.gradle.kts`, not in a version
catalog (there isn't one). `MedhaDatabase.kt` in the ZIP is a hand-written
`SQLiteOpenHelper` whose header comment reads *"Deliberately no Room/KSP."*
`Entities.kt` says *"Plain data classes (no Room annotations)."* The ZIP is
`versionName = "0.2.0"`.

So the migration off Room was already done locally and never pushed. GitHub at
`v0.1.1` still carries the pre-migration `Daos.kt`, which imports
`androidx.room.*` against a module that has no Room dependency and no annotation
processor.

**Answers to the four diagnostic questions:**

| Question | Answer |
|---|---|
| Room dependency declarations missing? | **Yes — entirely.** No `room-runtime`, `room-ktx`, or `room-compiler`. |
| Room version aliases incorrect? | **N/A.** There is no `gradle/libs.versions.toml`; every dependency is a hardcoded string. |
| KSP/KAPT configured? | **No.** Neither plugin appears in the root or module build script. |
| Imports use wrong package names? | **No.** `androidx.room` is the correct package. |

That last row is the useful diagnostic signal. `Unresolved reference 'room'`
fires on the **import line** — meaning the whole `androidx.room` package is
absent from the compile classpath. A wrong *class* name would report an
unresolved `Dao`, not an unresolved `room`. Unresolved at the package segment is
always a missing-dependency symptom, never a typo.

### The fix

**Delete `Daos.kt`. Do not add Room.**

```bash
git rm app/src/main/java/com/example/litertservice/data/Daos.kt
git commit -m "fix(build): drop orphaned Room DAO left over from the SQLite migration"
```

Adding Room back would be the wrong move on this codebase:

- `MemoryRepository`, `Retriever`, `LocalServer`, and `InferenceService` already
  call `MedhaDatabase`'s hand-written SQL API. Restoring Room means rewriting
  all four, not adding three lines to a build file.
- Room requires KSP, and **KSP's version is hard-pinned to the Kotlin compiler
  version**. This module is on Kotlin 2.3.0. Every future Kotlin bump would
  become a coordinated KSP bump or a red build — the coupling the migration was
  performed to escape.

If Room is genuinely wanted later, the `NOTE ON ROOM` block at the top of
`app/build.gradle.kts` lists all three pieces that must land **together**.

### Preventing the recurrence

`.github/workflows/build-apk.yml` gains a guard step that fails in seconds with
a clear message when Room annotations exist without Room configuration, instead
of producing a wall of unresolved references. Comment lines are stripped before
matching, so the documentation block does not read as live config.

Verified against three scenarios: clean tree (pass), `Daos.kt` restored with no
Room deps (fail, correct message), `Daos.kt` restored with Room properly
configured (pass).

---

## 2. Correctness and stability bugs found

Ordered by severity.

### 2.1 Native-engine data race — could crash the process

`generate()` was `@Synchronized`; `generateStream()` was **not guarded at all**.
A streaming request concurrent with a blocking one drove the same native
`Engine` handle from two threads. That is a native-side crash, not a catchable
Kotlin exception.

*Fixed:* both paths take one coroutine `Mutex`.

### 2.2 The mutex was the wrong kind of lock, and it blocked the event loop

`@Synchronized` parks the calling thread. Ktor CIO serves requests on a small
event-loop pool, and the blocking `sendMessage()` ran directly on it. One
in-flight generation therefore stalled **every** other request — including the
`/health` poll the dashboard runs every 2 s, which is why the UI would appear to
freeze during inference.

*Fixed:* coroutine `Mutex` (waiters suspend, not park) plus
`withContext(Dispatchers.IO)` around the native call.

### 2.3 `START_STICKY` redelivery tore down a live engine

`onStartCommand` unconditionally called `engine.load()`, whose first act was
`close()`. Any redelivery — or a second tap of Start — destroyed the engine
underneath in-flight requests.

*Fixed:* `load()` is idempotent for the same `(path, backend)`; the service also
skips relaunching while a load job is active.

### 2.4 A schema bump silently destroyed all user memory

```kotlin
override fun onUpgrade(...) {
    db.execSQL("DROP TABLE IF EXISTS conversations")   // ...and the rest
    onCreate(db)
}
```

For a product whose headline feature is *retentive memory*, the first schema
change would have wiped every stored conversation on upgrade.

*Fixed:* additive migration path; v1 → v2 adds the FTS index and backfills it
without dropping anything.

### 2.5 Unbounded RAG scan — OOM on any real corpus

`chunksInCollection()` loaded **every** chunk in a collection into a `List`,
then scored it in Kotlin. Fine for the demo, fatal once a PWA ingests an inbox.

*Fixed:* FTS4 shortlist inside SQLite (bounded at 200 candidates), re-ranked in
Kotlin. Fallback scans are hard-capped at 2000 rows.

### 2.6 `/generate`'s `system` parameter was silently ignored

```kotlin
fun generate(prompt: String, systemInstruction: String? = null): Result {
    ...
    conv.sendMessage(prompt)    // systemInstruction never referenced
```

The endpoint accepted `system`, documented it, and dropped it.

*Fixed:* composed into the prompt.

### 2.7 Streaming requests were invisible to `/metrics`

`SystemInfo.record()` was called only in `generate()`. Every SSE consumer —
including the demo UI's own Stream button — contributed nothing to request
counts or tokens/sec.

*Fixed:* streaming records on completion, and prompt tokens are now tracked
separately from completion tokens.

### 2.8 The default model path could never work

`DEFAULT_MODEL_PATH` pointed at
`/sdcard/Download/models/gemma-4-E2B-it.litertlm`, but the manifest requests no
storage permission. Any code path reaching that default failed with an opaque
native error.

*Fixed:* removed. A missing model now produces an explicit
"No model selected" notification.

### 2.9 Port changes were ignored

`if (server == null) { server = LocalServer(...) }` — editing the port in the UI
and pressing Start kept serving on the old port, with no indication why.

*Fixed:* the server restarts when the port changes; the port is validated
(1024–65535) before the service is started; bind failures surface in the
notification.

### 2.10 Partially copied models became the configured model

Model import wrote straight to the destination path and recorded it on success,
but a mid-copy failure (out of space, revoked URI) left a truncated file that
the engine then tried to open.

*Fixed:* free-space precheck against the SAF-reported size, write to
`<name>.part`, atomic rename, cleanup on failure.

### 2.11 SSE framing was not spec-compliant

- Deltas were emitted as raw text with newlines hand-escaped to a literal `\n`
  sequence, so a conformant client received corrupted text. *Now JSON-encoded.*
- `/v1/chat/completions` streaming chunks carried only
  `{"choices":[{"delta":...}]}` — no `id`, `object`, `created`, or `model`.
  Strict OpenAI clients reject those outright, which directly blocks pointing
  `hf_agent` at this service. *Now spec-shaped, with a leading role chunk and a
  trailing `finish_reason: "stop"`.*
- No `Cache-Control: no-cache`, so a WebView or proxy could buffer the whole
  stream and defeat streaming entirely. *Now set.*
- The demo PWA's reader split each network chunk on newlines independently, so
  an event straddling a chunk boundary was silently corrupted. *Now buffers to
  `\n\n` event boundaries.*

### 2.12 Fixed message count was the wrong context bound

`history(conversationId, maxMessages = 20)` — twenty one-word replies and twenty
pasted emails are the same count, and only one fits a 2B model's window.

*Fixed:* character-budget trimming that keeps the newest turns that fit.

### 2.13 Chunking had no overlap

A fact spanning a chunk boundary ended up in neither chunk intact and became
unretrievable.

*Fixed:* 100-character overlap; oversized paragraphs are hard-split rather than
emitted whole.

### 2.14 Turn writes were not atomic

The user message and assistant reply were two separate inserts. A failure
between them left a half-written turn.

*Fixed:* single transaction.

### 2.15 Lexical scoring ignored term rarity

Plain overlap counting let a chunk repeating a common word outrank one
containing the actual distinctive keyword.

*Fixed:* IDF weighting plus a stopword list.

---

## 3. Security changes

### 3.1 `anyHost()` CORS on a loopback inference service

This is the significant one. Binding to `127.0.0.1` stops **network** access; it
does not stop **the browser**. Any page the user visits can `fetch()` a loopback
URL, and the browser sends it. CORS decides only whether the page may *read* the
response — so `anyHost()` let an arbitrary website both drive the model and read
its output, including anything in a `/chat` session's stored history.

*Fixed:*
- Per-install bearer token (160 bits, `SecureRandom`), required on every
  endpoint except `/health` and static UI assets.
- CORS restricted to the loopback origins Medha itself serves.
- Constant-time token comparison.
- Token displayed and copyable in the app; substituted into the bundled UI so
  same-origin pages need no setup.

The residual risk is documented in the README rather than papered over: a
malicious *native* app on the device can still fetch `/` and scrape the injected
token. A malicious *web page* cannot, because it cannot read a cross-origin
response.

### 3.2 Cloud backup of the model and the token

`android:allowBackup="true"` with no rules meant the API token, the SQLite
conversation store, and a potentially multi-GB model were all eligible for cloud
backup and device transfer.

*Fixed:* `allowBackup="false"` plus explicit `backup_rules.xml` /
`data_extraction_rules.xml` excluding `models/`, `medha.db`, and the prefs file.

### 3.3 Asset path handling

`name.replace("..", "")` is a filter, and filters get bypassed
(`....//` collapses to `//`). In practice `AssetManager` confines reads to
`assets/`, so this was **defence in depth rather than an exploitable hole** —
but a whitelist is the right shape.

*Fixed:* per-segment whitelist rejecting `.`, `..`, empty segments, and
backslashes. Verified against six traversal patterns.

### 3.4 No request size limit

A 500 MB POST body would be buffered before any handler ran.

*Fixed:* 16 MB cap enforced in the pipeline, returning `413`.

---

## 4. Functionality added

Aimed at the stated goal — decoupled PWAs consuming this service.

- `GET /sessions`, `GET /sessions/{id}/messages`, `DELETE /sessions/{id}` —
  an SMS organizer needs to list and prune threads, which was impossible before.
- `GET /rag/collections`, `DELETE /rag/collections/{name}` — same for knowledge.
- `POST /v1/embeddings` — returns `501` with an explanatory message rather than
  a fabricated vector. Storage already round-trips embeddings, so implementing
  it is a matter of wiring an embedder into `Retriever`.
- `/health` reports `busy` and `authRequired`; `/metrics` reports failures and
  in-flight depth; `/system` reports thermal status, free storage, DB size and
  whether the FTS index exists.
- Structured JSON errors everywhere (`{error, code}`) — `StatusPages` previously
  returned `text/plain` where clients expected an object.
- Notification gained **Stop** and tap-to-open actions.
- Engine released on `TRIM_MEMORY_COMPLETE` instead of being LMK-killed
  mid-request.
- Demo PWA rebuilt: chat-with-memory panel, RAG ingest/query panel, correct SSE
  reader, token-aware fetch wrapper.

---

## 5. Verification performed — and its limits

**Please read this section before merging.**

There is no Android SDK or Gradle in the environment this review ran in, and the
network allowlist excludes Google's Maven. **The project was not compiled and no
APK was produced.** What was actually done:

| Check | Method | Result |
|---|---|---|
| Kotlin syntax, all 9 sources | `kotlinc` parse pass | No syntax errors |
| Pure-logic behaviour | 40 assertions compiled and executed | 40/40 pass |
| CI Room guard | Executed against 3 scenarios | Correct in all 3 |

The 40 executed assertions cover chunking (overlap, oversized-paragraph
splitting, degenerate inputs), IDF ranking, prompt budgeting, cumulative-vs-delta
stream detection, constant-time comparison, FTS term sanitising, and the asset
path whitelist.

**Not verified, and where residual risk sits:**

1. **Ktor 2.3.12 API surface.** `intercept(ApplicationCallPipeline.Plugins)`,
   `CORS.allowHost(...)`, `respondTextWriter`, and the `delete` route builder are
   used from memory. These are the most likely source of a first-build error.
   `embeddedServer` returning `ApplicationEngine` is correct for Ktor 2.x but
   **breaks on Ktor 3.x** — do not bump that dependency casually.
2. **LiteRT-LM 0.13.1.** Every call into it (`Backend.GPU()`, `EngineConfig`,
   `createConversation`, `sendMessage`, the `sendMessageAsync` reflection probe)
   is preserved byte-for-byte from your working code. Nothing new was invented
   against that API.
3. **Android resource references.** `tokenText` / `btnCopyToken` were added to
   `activity_main.xml` and are consumed via ViewBinding; the new strings exist
   in `strings.xml`. Consistent by inspection, not by an `aapt` run.
4. **FTS4 availability.** Present in Android's bundled SQLite across the
   supported API range, but creation is wrapped in `runCatching` with a
   `hasFullTextIndex` flag and a bounded fallback scan if it ever fails.
5. **Kotlin 2.3.0 / AGP 8.7.3.** Left untouched deliberately. Your failure was a
   Kotlin *compile* error, which proves plugin resolution and configuration
   already succeed — so version churn would add risk without addressing anything.

Suggested merge order: land the one-line `Daos.kt` deletion first and confirm
CI goes green. That de-risks the build independently of everything else here.
Then land the rest and read the first CI log carefully for Ktor signature
mismatches.

---

## 6. Suggested next steps

1. **Commit the Gradle wrapper** (`gradlew`, `gradle/wrapper/gradle-wrapper.jar`).
   Regenerating it in CI means the build tool version is not pinned.
2. **Change the `applicationId`.** `com.example.litertservice` is a placeholder;
   moving off it later is disruptive because it changes app identity and loses
   installed state. Do it now.
3. **Wire an embedder.** `Retriever` takes a `suspend (String) -> FloatArray?`
   and everything downstream already handles vectors. This is the single highest
   leverage remaining change for RAG quality.
4. **Add instrumented tests** for `MedhaDatabase` — the migration path and the
   FTS sync are the two places where a bug costs user data, and they are exactly
   what a JVM-only harness cannot reach.
5. **Revisit the foreground service type.** `dataSync` faces a cumulative daily
   runtime cap on recent Android versions; an always-on inference host may need
   `specialUse` with a written justification.
