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

## AD-3. Medha owns permissioned data sources

**Decision: native connectors live in Medha; PWAs consume them over HTTP.**

A browser page cannot read SMS. Android exposes SMS only to native apps holding
`READ_SMS`/`RECEIVE_SMS`, and Play restricts those to the default SMS handler.
No API, no workaround.

So Medha reads SMS and exposes it at `/connectors/sms/*`. The PWA stays pure UI.
One APK holds the sensitive permissions instead of three.

This generalises: anything requiring a runtime permission (contacts, calendar,
notification access) becomes a Medha connector rather than a new app.

---

## AD-4. Storage lives in Medha, namespaced per client

**Decision: SQLite in Medha. PWAs get a scoped key-value + document API.**

Browser storage (IndexedDB) is quota-limited and **evictable** — Android will
silently drop it under pressure. Unacceptable for a message archive.

So there are three tiers, and the split matters:

| Data | Where | Why |
|---|---|---|
| Raw SMS | **Not stored.** Read live from the system provider | The OS already holds it; a copy is a second thing to secure and to keep in sync |
| Derived results (classifications, summaries, extracted fields) | **Medha `/store`**, namespaced per client | Expensive to recompute — that is the whole point of the batch pass |
| Chat sessions, RAG chunks | Medha, already | Needs the embedder, which lives with the model |
| Ephemeral UI state | Browser | Losing it costs nothing |

The `/store` API is deliberately generic — key-value with JSON documents and
prefix listing — so a new PWA needs no schema change in Medha.

**Storing message *IDs* plus derived labels, not message bodies**, keeps the
archive small and means revoking a client destroys derived data without
touching the user's actual messages.

---

## AD-5. Per-client tokens, not one root token

**Decision: `token -> client -> namespace + capabilities`.**

The single token was a root credential: any holder could read every session and
collection. `sms-organizer` now gets namespace `sms` and cannot see `compose:*`.

Enforced server-side on every request. **The namespace is derived from the
token, never read from the request body**, so a client cannot ask for someone
else's prefix.

Capabilities are coarse (`generate`, `memory`, `rag`, `store`, `sms.read`,
`sms.send`, `notify`, `admin`). Fine-grained scopes that nobody configures
correctly are security theatre.

Verified: prefix-confusion is blocked (`smsX:` does not match namespace `sms`),
admin passthrough works, capability checks hold. 11 assertions.

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

### On the SMS backlog

Your instinct — backlog once, then trivial steady state — is right, and it makes
gating *more* important, not less. The 500-message backlog is precisely the
sustained decode that heats the phone. Steady state never approaches a limit. So
gating exists for the backlog and then effectively disappears.

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

> Status: `/store` and the tenancy/scheduler layer are **implemented**.
> `/connectors/sms/*` and `/notify/*` are **specified but not yet built** —
> the contracts below are frozen so PWA work can proceed against them.

### `/store` — per-client persistence *(implemented)*

```
PUT    /store/{key}          body: any JSON      upsert
GET    /store/{key}                              read
DELETE /store/{key}
GET    /store?prefix=&limit=&offset=             list keys
POST   /store/query          {prefix, filter}    bulk read
```

Keys auto-namespace: client `sms-organizer` writing `msg/123` stores `sms:msg/123`.

### `/connectors/sms/*` — *specified*

Designed once, so it does not need rebuilding:

```
GET  /connectors/sms/status                  permission + default-handler state
GET  /connectors/sms/conversations           threads: id, address, snippet, count, unread
GET  /connectors/sms/messages                ?threadId= &since= &before= &limit= &offset=
GET  /connectors/sms/messages/{id}
GET  /connectors/sms/contacts/{address}      display name resolution
POST /connectors/sms/send                    {address, body}      needs sms.send
POST /connectors/sms/mark-read               {threadId|ids[]}
GET  /connectors/sms/events                  SSE: new message arrives
```

Design notes worth keeping:
- **Cursor pagination on `since`/`before` (timestamps), not page numbers.** New
  messages arriving mid-scan shift page offsets and cause duplicates or gaps.
- **Read-only by default.** `sms.send` is a separate capability, and sending
  requires being the default SMS app on modern Android.
- **Bodies are never copied into Medha's DB.** Clients store derived labels
  against message IDs in `/store`.
- **SSE for new messages** so a PWA does not poll a content provider through
  HTTP every few seconds.

### `/notify/*` — *specified*

```
POST   /notify                {id, title, text, style, progress?, actions?, ongoing?}
DELETE /notify/{id}
GET    /notify/capabilities   {liveUpdates: bool, widget: bool, sdk: int}
PUT    /widget/content        {items:[{title, text, icon}]}
```

`GET /notify/capabilities` first, then the client knows whether a Live Update
will actually surface in the Now Bar on this device.

---

## What is still open

1. **Instrumented tests** for the v2→v3 DB migration. Still the highest-
   consequence untested path.
2. **`/benchmark` endpoint** — run a fixed prompt across GPU/CPU/NPU and report
   tokens-per-second plus thermal delta, so backend choice is measured rather
   than assumed. NNAPI is deprecated in favour of LiteRT delegates, so "target
   the TPU" is not a road; measure the three that exist.
3. **Crash diagnostics** — a local ring buffer with an export action.
