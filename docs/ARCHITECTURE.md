# Medha — architecture decisions

Answers to the design questions, with the reasoning, so future-you can tell
which choices were considered and which were accidents.

---

## AD-1. One resident model, loopback API, thin clients

**Decision: keep it.**

Model *load* is the expensive operation — seconds of wall time, 1–2 GB resident.
If each app loaded its own, that cost repeats per app and the second one OOMs.
Centralising pays it once. Same shape as Ollama on desktop; proven.

The efficiency goal follows from this directly: one model on the accelerator,
one wake lock, one thermal budget to manage.

---

## AD-2. PWAs have no backend. Medha *is* the backend.

**Decision: PWAs are static HTML/JS. No Flask on the phone.**

Running Flask on-device means Chaquopy or Termux: a Python runtime per app, a
second wake lock, a second process to keep alive. That is the opposite of light.

Flask remains useful for *developing* PWAs on a laptop:

```bash
adb reverse tcp:8080 tcp:8080     # laptop talks to the phone's Medha
```

Ship the result as static files.

---

## AD-3. Medha brokers what a browser cannot reach

**Decision: capabilities a web page cannot have live in Medha; PWAs reach them
over HTTP.** Reversed once, in v0.9.0, and the reversal is the interesting part.

The original form of this decision was broader: Medha would own *permissioned
data sources*, starting with SMS. A browser page cannot read SMS — Android
exposes it only to native apps holding `READ_SMS`, and Play restricts those to
the default handler — so Medha read it and served it at `/connectors/sms/*`.

**That connector was removed in v0.9.0.** Two things it cost, both of which
outlived its usefulness:

- Declaring SMS permissions makes Play Protect block a sideloaded APK on the
  manifest alone. Working around that meant a `core`/`full` product-flavour
  split, which combined with the debug suffix produced four `applicationId`s.
  A consumer app cannot hardcode a package name it cannot predict, and the
  hello-medha sample duly reported "Medha is not installed" on a device where
  it was.
- The app it existed to serve was built and then scrapped. A broker with no
  consumer is a permission surface earning nothing.

What survives is the narrower, better version of the claim: **a browser page
cannot post a notification to the Android shade, and Medha can.** That is now
the one brokered capability, at `/notify/*`, and it is the shape the decision
should always have had — broker *capabilities the platform withholds from the
web*, not *data Medha could usefully hold*. The first is a real asymmetry; the
second is scope creep with a permission prompt attached.

The generalisation still holds, with a caveat earned the hard way: anything
requiring a runtime permission *can* become a Medha connector, but each one
costs install friction for every user who does not want it. Add one when
something is actually built against it, not in anticipation.

---

## AD-4. Storage lives in Medha, namespaced per client

**Decision: SQLite in Medha. PWAs get a scoped key-value + document API.**

Browser storage (IndexedDB) is quota-limited and **evictable** — Android will
silently drop it under pressure. Unacceptable for a message archive.

So there are three tiers, and the split matters:

| Data | Where | Why |
|---|---|---|
| OS-owned data (messages, contacts, calendar) | **Not stored, and no longer read.** | The OS already holds it; a copy is a second thing to secure and to keep in sync. The SMS connector that read it live was removed in v0.9.0 — see AD-3 |
| Derived results (classifications, summaries, extracted fields) | **Medha `/store`**, namespaced per client | Expensive to recompute — that is the whole point of the batch pass |
| Chat sessions, RAG chunks | Medha, already | Needs the embedder, which lives with the model |
| Ephemeral UI state | Browser | Losing it costs nothing |

The `/store` API is deliberately generic — key-value with JSON documents and
prefix listing — so a new PWA needs no schema change in Medha.

**Storing identifiers plus derived labels, not source content**, keeps the
archive small and means revoking a client destroys derived data without
touching whatever the client derived it from.

---

## AD-5. Per-client tokens, not one root token

**Decision: `token -> client -> namespace + capabilities`.**

The single token was a root credential: any holder could read every session and
collection. `journal` now gets namespace `journal` and cannot see `compose:*`.

Enforced server-side on every request. **The namespace is derived from the
token, never read from the request body**, so a client cannot ask for someone
else's prefix.

Capabilities are coarse (`generate`, `memory`, `rag`, `store`, `notify`,
`admin`). Fine-grained scopes that nobody configures correctly are security
theatre.

Verified: prefix-confusion is blocked (`journalX:` does not match namespace
`journal`), admin passthrough works, capability checks hold. 11 assertions.

v0.9.0 added a second axis for browser clients: a token issued by pairing is
bound to the **web origin** that paired, and a request carrying it from a
different origin is refused. CORS governs who may *read* a reply and says
nothing about who may hold a token, so without the binding a token copied out
of one site's `localStorage` into another page would work perfectly. Origin
matching is exact — not prefix, not suffix, not subdomain — and
`tools/tests/NetPolicyTest.kt` pins that with the suffix-attack cases, after a
mutation test showed the earlier assertions passed against a `startsWith`
implementation.

---

## AD-6. Scheduling, not threading

**Decision: one engine, one mutex, a priority queue in front.**

You cannot usefully parallelise inference against one model. The native engine
is not thread-safe, and GPU/NPU is a single shared resource — two concurrent
decodes contend for the same silicon, produce the same heat, and worsen latency
for both. Parallelism is not the lever. Ordering and pacing are.

| Control | Behaviour |
|---|---|
| Priority | `INTERACTIVE` (human waiting) is admitted ahead of `BATCH` |
| Thermal gating | `BATCH` pauses at high headroom, resumes at a lower one |
| Battery gating | `BATCH` optionally requires charging / a minimum level |
| Admission control | Bounded queue; excess gets `429` with `Retry-After` |
| Timeout | Hard ceiling per generation |

**Interactive work is never thermally blocked.** A user tapping a button should
get an answer on a warm phone; it is sustained batch decode that cooks the SoC.

### Why two watermarks

A single threshold makes the queue oscillate on and off at the boundary,
which is worse than either state. Pause at `0.85`, resume at `0.70`, both
user-configurable. The gap is enforced in code — equal watermarks reintroduce
exactly the oscillation they were meant to prevent.

### Why headroom, not degrees Celsius

You asked for a range like 30–50 °C. **Android exposes no temperature reading to
normal apps.** `getThermalHeadroom` returns a normalised forecast — 0.0 cool,
1.0 throttling now — and it is the only signal available without root. Showing
a °C figure would mean inventing one. The UI therefore reads:

```
Thermal limit    [====|=====]  pause 0.85 / resume 0.70
```

A bug the exhaustive config sweep caught: clamping both watermarks inside one
`copy()` evaluates the second expression against the *unclamped* receiver, so a
pause value of 0.0 produced an empty coercion range and threw. Now clamped in
sequence, and verified across 1296 configurations including negatives.

### On bulk backlogs

The instinct — backlog once, then trivial steady state — is right, and it makes
gating *more* important, not less. A few hundred queued classifications, or a
RAG corpus being re-embedded, is precisely the sustained decode that heats the
phone. Steady state never approaches a limit. So gating exists for the backlog
and then effectively disappears.

---

## AD-7. Notifications: Now Bar yes, Now Brief no

These are different systems and the distinction decides what is buildable.

| Surface | Third-party access | Path |
|---|---|---|
| **Now Brief** (AI daily digest) | **No public API** | Not available. Do not design for it |
| **Now Bar** (lock-screen pill) | **Yes**, One UI 8+ | Android 16 Live Updates API |
| Standard notifications | Yes, everywhere | `NotificationCompat` |
| Home-screen widget | Yes | `AppWidgetProvider` owned by Medha |

One UI 8 adopted Android 16's Live Updates, which lets any app create live
ongoing notifications — previously limited to mostly first-party Samsung apps.
So the Now Bar is reachable, but only on One UI 8+ / Android 16+, and it is
designed for *ongoing activities with progress*, not arbitrary cards.

**Decision: one `/notify` API in Medha, three back-ends, degrading by device.**
Live Update where supported, plain notification otherwise, plus an optional
Medha-owned home-screen widget that any client can push content to. Same
endpoint for all three — the PWA does not branch on OS version.

Keeping it in Medha rather than a separate app: the notification permission and
the widget provider are already there, and a second APK would fragment both.

---

## Planned API surface

> Status: `/store`, the tenancy/scheduler layer and `/notify/*` are all
> **implemented** as of v0.9.0. `/connectors/sms/*` was specified here, built
> in v0.8.x, and removed in v0.9.0 — see AD-3 for why. Its contract is left
> out rather than kept as a ghost.

### `/store` — per-client persistence *(implemented)*

```
PUT    /store/{key}          body: any JSON      upsert
GET    /store/{key}                              read
DELETE /store/{key}
GET    /store?prefix=&limit=&offset=             list keys
POST   /store/query          {prefix, filter}    bulk read
```

Keys auto-namespace: client `journal` writing `note/123` stores `journal:note/123`.

### `/notify/*` — *implemented in v0.9.0*

```
POST   /pair                  {code}               no auth; returns a token
POST   /notify                {id, title, text, ongoing?, progressCurrent?, progressMax?, silent?}
DELETE /notify/{id}
GET    /notify/capabilities   {sdk, liveUpdates, nowBarLikely, notifications, widget, nowBrief}
PUT    /widget/content        {items:[{title, text}]}
```

`GET /notify/capabilities` first, then the client knows whether a Live Update
will actually surface in the Now Bar on this device rather than branching on
OS version itself. `nowBrief` is always `false`: Samsung's digest has no
public API and this reports that honestly instead of failing silently.

Two things the built version added that the spec did not anticipate:

- **`POST /pair`.** The intent handshake identifies a caller by
  `getCallingPackage()`, which a web page does not have — it would report the
  browser. So web apps pair with a six-digit code the user carries from
  Medha's screen, and the token is bound to the origin that redeemed it.
- **A rate limit.** This is the first endpoint where a client makes the
  *device* do something, and it is cheap to call. Every other endpoint is
  self-limiting because inference is slow and the scheduler queues it. Ten in
  a burst, thirty a minute, per client.

See [NOTIFICATIONS.md](NOTIFICATIONS.md).

---

## What is still open

1. **Instrumented tests** for the v2→v3 DB migration. Still the highest-
   consequence untested path.
2. **`/benchmark` endpoint** — run a fixed prompt across GPU/CPU/NPU and report
   tokens-per-second plus thermal delta, so backend choice is measured rather
   than assumed. NNAPI is deprecated in favour of LiteRT delegates, so "target
   the TPU" is not a road; measure the three that exist.
3. **Crash diagnostics** — a local ring buffer with an export action.
