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
| **Runs reliably on your own phone** | Close. Finish the P0 list. |
| **Other people sideload it** | Not yet. P0 + P1. |
| **Public distribution (Play Store / F-Droid)** | Some distance. P0 + P1 + P2. |

---

## P0 — blockers for trusting it yourself

### 1. Zero automated tests against a real device
Everything verified so far is either a syntax pass, a pure-logic harness, or a
CI grep. **No test has ever executed against Android's SQLite, the Ktor server,
or the LiteRT engine.** The two places this hurts most:

- **`MedhaDatabase` migration v1 → v2.** If it is wrong, users lose stored
  conversations, silently, on upgrade. This is the single highest-consequence
  untested path in the codebase.
- **FTS4 sync.** Chunks and their index are kept consistent by hand across
  insert and delete. A drift bug degrades retrieval quietly rather than loudly.

Fix: an `androidTest` suite with a handful of instrumented tests. This is
maybe half a day and it removes the largest unknown in the project.

### 2. No crash reporting, and the logs are unreachable
When it dies on your phone at 2am you have nothing. There is no local log
buffer, no crash handler, no way to export a report. `adb logcat` is not an
answer once the app leaves your desk.

Fix: a bounded in-memory ring buffer written to a file on crash, plus a "share
diagnostics" action in the drawer. Deliberately local-only — no telemetry.

### 3. The engine is a single-request bottleneck with no timeout
Every request serialises on one mutex, which is correct for native safety, but
there is **no cap on how long one generation may run and no queue limit**. A
runaway prompt blocks every other consumer indefinitely, and a PWA that fires
requests in a loop builds an unbounded backlog.

Fix: a per-request timeout (`withTimeout`), a max queue depth returning `429`,
and cancellation wired to client disconnect.

### 4. ~~`applicationId` is a placeholder~~ — RESOLVED
Now `com.adabala.medha`, matching the adabala.com domain. applicationId,
namespace and the Kotlin package were moved together via
`tools/rename_package.py`.

If you already installed an earlier build, the old id is a **separate app**.
Uninstall it: it will not be upgraded in place, and its settings, API token and
conversation database stay stranded under the old identity.

---

## P1 — blockers before other people install it

### 5. The token is scrapeable by another app on the device
Documented in the README and worth restating: the bundled UI is served
unauthenticated with the token injected, so a malicious *native* app could
`GET /` and read it. A hostile *web page* cannot. Acceptable for you; not
something to ship to strangers without at least an opt-in strict mode.

### 6. No release signing key exists yet
The build now supports it (`MEDHA_KEYSTORE_*`), but until you generate a
keystore and add the secrets, CI produces only debug artefacts. **Back that
keystore up somewhere you will not lose it** — losing it means you can never
ship an upgrade to an existing install, only a fresh app.

### 7. No ProGuard/R8 rules
Minification is deliberately off. Ktor, kotlinx.serialization, and the
reflective LiteRT probe would all break under R8 without keep rules — and they
break at *runtime*, not build time. Shipping unminified is the honest choice
today, at the cost of APK size.

### 8. `dataSync` foreground service type
On Android 15+ that type carries a cumulative daily runtime cap. An always-on
inference host may hit it. Needs either a justification for `specialUse` or an
accepted, documented limit.

### 9. No storage ceiling
`/rag/ingest` will happily fill the disk. `/chat` history grows without bound.
There is no retention policy and no quota.

---

## P2 — before public distribution

10. **Privacy policy and data-handling statement.** Even though nothing leaves
    the device, stores require the statement, and users deserve it in writing.
11. **Accessibility.** No content descriptions on most controls, no TalkBack
    pass, no large-font layout testing.
12. **Localisation.** Strings are extracted now, which is the hard part, but
    only `values/` exists. The Telugu name deserves a Telugu locale.
13. **No dark theme.** The theme is `DayNight` but the cards hardcode
    `#FFFFFF` and `#333333`, so dark mode is currently broken-looking.
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
3. Add request timeout + queue cap. *(an hour)*
4. Write instrumented tests for the DB migration and FTS sync. *(half a day)*
5. Add the local diagnostics buffer. *(half a day)*
6. Then reassess — after those five, "other people can sideload it" is a fair
   claim.
