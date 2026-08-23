# Is Medha production ready?

**No. It is a solid personal-use daily driver, and it is not yet something to
put in other people's hands.**

That distinction matters more than a yes/no. Below is what actually separates
the two, sorted by what would bite first.

---

## What "production" means here

Three different bars, and Medha sits at different heights on each:

| Bar | Status |
|---|---|
| **Runs reliably on your own phone** | All P0 items addressed in code. Run the instrumented test suite on a real device first — see `docs/TESTING.md` — before fully trusting that. |
| **Other people sideload it** | Not yet. P0 + P1. |
| **Public distribution (Play Store / F-Droid)** | Some distance. P0 + P1 + P2. |

---

## P0 — blockers for trusting it yourself

### 1. ~~Zero automated tests against a real device~~ — RESOLVED
`app/src/androidTest/java/.../MedhaDatabaseMigrationTest.kt` now exercises the
real v1 -> v4 on-disk SQLite upgrade (hand-building the actual v1 schema —
no `chunks_fts`, no `kv`, embeddings as TEXT — and forcing the real
`onUpgrade` path against it) and FTS4 insert/delete sync. `MedhaDatabase`
gained a `@VisibleForTesting forTesting(context, dbName)` construction path
so tests get isolated database files instead of colliding with the
process-wide singleton `get()` uses.

Run: `./gradlew connectedCoreDebugAndroidTest --tests "*MedhaDatabaseMigrationTest*"`
(needs a connected device or emulator — see `docs/TESTING.md`).

Honesty note, consistent with how this doc treats everything else: this was
written and hand-verified against the real `onUpgrade` implementation in a
sandbox with no Android SDK, so it has never actually executed. Treat a first
green run as the real confirmation, not this paragraph.

### 2. ~~No crash reporting, and the logs are unreachable~~ — RESOLVED
`Diagnostics` is a bounded 500-line in-memory ring buffer with drop-in
`d/i/w/e` replacements for `Log`'s own methods (same signatures, same logcat
output — every existing call site in the crash-relevant files now routes
through it instead). `MedhaApplication` installs an uncaught-exception
handler that writes the buffer to a local file the moment a fatal exception
is about to kill the process, then **always** chains to whatever handler was
already installed — it must never suppress the real crash. The drawer's new
"Diagnostics" entry lists past dumps (tap to share via a scoped `FileProvider`
that only exposes the diagnostics folder, nothing else in app-private
storage) and has an "export now" action for capturing the buffer on demand.

Deliberately local-only: nothing here transmits anything on its own. A dump
is a file the person explicitly shares, the same as they'd attach a
manually-copied logcat capture — except it's actually still there after the
crash that mattered.

### 3. ~~The engine is a single-request bottleneck with no timeout~~ — RESOLVED
`InferenceScheduler` already provided admission control (bounded queue → 429)
and thermal/battery gating. What it didn't do: enforce `requestTimeoutMs` (the
field existed and was silently dead), and cover every endpoint — `/generate/
stream` and both branches of `/v1/chat/completions` called the engine directly,
bypassing the scheduler entirely. That last gap mattered most in practice:
`/v1/chat/completions` is the endpoint a standard OpenAI client actually hits,
so the one interface most third-party callers use had zero protection against
the "PWA loops requests, backlog grows without bound" failure this scheduler
exists to prevent.

Fixed: `requestTimeoutMs` is enforced via `withTimeout`, and all three
endpoints now go through the scheduler — the streaming ones via a new
`acquire()`/`Permit` API rather than reusing `submit()`, because a timeout
firing after SSE headers are already on the wire cannot cleanly turn into a
429/504 response.

Honest scope, stated plainly: cancellation is cooperative. A timeout cannot
force-interrupt a blocking native decode call already in progress — the
mutex stays held until that call naturally returns. What is guaranteed: no
caller waits longer than `requestTimeoutMs` for the engine to become
available or for its own admission to complete, so a caller stacked behind a
hung request gets a clear, bounded error instead of hanging forever. A truly
wedged native call still needs a process restart to clear.

### 4. ~~`applicationId` is a placeholder~~ — RESOLVED

Not from Gradle, but the literal package name; done at project creation.
Now `com.adabala.medha`, matching the adabala.com domain. applicationId,
namespace and the Kotlin package were moved together via
`tools/rename_package.py`.

If you already installed an earlier build, the old id is a **separate app**.
Uninstall it: it will not be upgraded in place, and its settings, API token and
conversation database stay stranded under the old identity.

### 5. ~~Two endpoints skipped authorization entirely~~ — RESOLVED

Found while investigating a separate streaming bug report: `/generate/stream`
and `/v1/chat/completions` never called `requireCap(GENERATE)`, unlike every
sibling endpoint (`/generate`, `/chat`, `/rag/*`, `/store/*`). A client
created with zero capabilities — or narrowly scoped to something unrelated,
like `NOTIFY` only — could still generate model output through either of
these two, because nothing was actually checking. `/v1/chat/completions` is
also the endpoint any standard OpenAI-compatible third-party client hits, so
this was the more exposed of the two, not a corner case.

Fixed: both now require `GENERATE`, matching `/generate`. While in the same
code, also closed a related gap — `/chat`'s `collection` parameter let any
`MEMORY`-capable client read back RAG collections without the separate `RAG`
capability `/rag/query` itself requires; that inconsistency is gone too, and
the new `collection`/`ragTopK` fields added to `/v1/chat/completions` (see
`docs/TESTING.md` tier 3i) enforce the same `RAG` check from the start.

Not yet verified on a device — see `docs/TESTING.md` tier 3j for the exact
check (create a capability-limited client, confirm both endpoints 403 it).

---

## P1 — blockers before other people install it

### 6. The token is scrapeable by another app on the device
Documented in the README and worth restating: the bundled UI is served
unauthenticated with the token injected, so a malicious *native* app could
`GET /` and read it. A hostile *web page* cannot. Acceptable for you; not
something to ship to strangers without at least an opt-in strict mode.

### 7. No release signing key exists yet
The build now supports it (`MEDHA_KEYSTORE_*`), but until you generate a
keystore and add the secrets, CI produces only debug artefacts. **Back that
keystore up somewhere you will not lose it** — losing it means you can never
ship an upgrade to an existing install, only a fresh app.

### 8. No ProGuard/R8 rules
Minification is deliberately off. Ktor, kotlinx.serialization, and the
reflective LiteRT probe would all break under R8 without keep rules — and they
break at *runtime*, not build time. Shipping unminified is the honest choice
today, at the cost of APK size.

### 9. `dataSync` foreground service type
On Android 15+ that type carries a cumulative daily runtime cap. An always-on
inference host may hit it. Needs either a justification for `specialUse` or an
accepted, documented limit.

### 10. No storage ceiling
`/rag/ingest` will happily fill the disk. `/chat` history grows without bound.
There is no retention policy and no quota.

---

## P2 — before public distribution

10. **Privacy policy and data-handling statement.** Even though nothing leaves
    the device, stores require the statement, and users deserve it in writing.
11. **Accessibility — partially addressed.** An audit found the main screen
    already clean (every `ImageView` has a `contentDescription`, the drawer's
    nav icon does too, no undersized touch targets). Not yet audited: the
    dialog-heavy secondary flows (SMS, thermal, scheduler, about) and no
    TalkBack pass or large-font layout test has actually been run on device.
12. **Localisation.** Strings are extracted now, which is the hard part, but
    only `values/` exists. The Telugu name deserves a Telugu locale. A
    meaningful chunk of the main screen's remaining hardcoded strings were
    externalized this session (capability labels, primary status text, all
    static button/section labels); the technical diagnostic-panel text
    (thermal, scheduler, SMS, about dialogs) deliberately was not, as a lower
    ROI / higher-risk-of-invisible-mistake tradeoff given no device to
    visually verify against — flagged here rather than silently left undone.
13. **~~No dark theme~~ — RESOLVED.** The four non-brand cards hardcoded
    `#FFFFFF` cardBackgroundColor and `#333333`/`#555555` text with nothing in
    `values-night/` to override them, so dark mode stayed stark white with
    dark text regardless of system theme. `card_surface`/`text_primary`/
    `text_secondary` are now named resources with real dark-mode values in
    `values-night/colors.xml`, contrast-checked against WCAG 2.1 (text pairs
    9–14:1, the card's border color retuned from an initial 1.6:1 up to 3.2:1
    to actually meet the non-text contrast guideline). The brand-colored
    surfaces (status card, toolbar, nav header, widget) are deliberately left
    theme-invariant — that's a design choice already in the app, not a bug.
    Not yet verified on an actual device with dark mode toggled on.
14. **targetSdk 34.** Play requires newer for new submissions; irrelevant for
    sideload, relevant the moment you list it.
15. **Battery behaviour is unmeasured.** A wake lock plus a resident multi-GB
    model has a real cost that has never been quantified.

---

## What is genuinely solid

Worth stating, because the list above is long and one-sided:

- The **HTTP surface is well shaped** — authenticated, loopback-bound,
  OpenAI-compatible, with structured errors. That is the part other apps
  depend on, and it is the part most likely to stay stable.
- **Persistence is non-destructive** and migrations are additive.
- **Concurrency is correct** at the native boundary, which is the failure mode
  that produces unexplainable crashes.
- **Failure paths are handled**: memory pressure, thermal, bind failures,
  truncated model imports, missing FTS.
- **CI catches two whole classes of regression** before they reach a compiler.

---

## Suggested order

1. ~~Change `applicationId`.~~ Done — `com.adabala.medha`.
2. Generate and back up a release keystore; add the CI secrets. *(30 min)*
3. ~~Add request timeout + queue cap.~~ Done — see item 3 above.
4. ~~Write instrumented tests for the DB migration and FTS sync.~~ Written —
   see item 1 above. Run them on a real device before treating this as closed.
5. ~~Add the local diagnostics buffer.~~ Done — see item 2 above.
6. Then reassess — after those five, "other people can sideload it" is a fair
   claim.
