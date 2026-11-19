# Medha v0.20.0 — the agent does the thinking

`versionCode 51`, `versionName 0.20.0`. Build on CI as usual and sideload.

**Extract this zip over your checkout.** It adds and overwrites but never
deletes, which is why `tools/check_stale.py` exists — run `verify.sh` (or just
`python3 tools/check_stale.py`) after extracting and delete anything it
reports as unshipped.

---

## What this release is

Medha could see the phone's notification stream and had no memory to reason
about it. The agent on the other side of the loopback has the corpus, the
embeddings and the correlation, and could not see the stream at all. So Medha
was the one guessing — a `kind` from the package name, a `dueAt` scraped out
of an email subject — and v0.19.1 shipped a home screen whose agenda was four
emails with dates invented that way. One of them, *"Re: Delayed employees for
11:30 on 17/11/2026"*, became an obligation due tomorrow from a date already
past. Another was a cancellation, drawn at the cancelled meeting's hour.

That was an architectural mistake, not a parser bug: the least reliable signal
in the system had been given decisive power over the home screen.

v0.20.0 moves the judgement to the side that has the memory.

**Medha is sensors, model and surface. The agent is memory and judgement.**

---

## 1. `GET /captures` — observations, not interpretation

```
GET /captures?since=<cursor>&limit=<n>        capability: captures
```

Returns `{items:[{id, at, source_pkg, source_label, category, title, text}],
cursor, more, head, listening, retainDays, maxRows}`.

- Ids come from `AUTOINCREMENT` and are never reused, so `?since=<last id you
  saw>` cannot skip a row even across pruning.
- `listening` distinguishes "nothing new" from "the listener is off", which
  are the same empty array otherwise — and an agent that cannot tell them
  apart will report a clear day when the phone has gone deaf.
- Bounded in both directions: 14 days and 5000 rows, whichever bites first,
  enforced on every insert rather than by a schedule that can stop running.

Full reference: `docs/BRIEF-API.md`.

### The promise that changed, stated plainly

Through v0.19.2 this project said notification text was **never** written to
storage. The reason given was that "a readable history of every notification
is a worse thing to leave on a phone than the notifications themselves", and
that judgement was right. It is kept verbatim in
`MedhaNotificationListener`'s class comment and in `docs/PLAY-PROTECT.md`,
next to what replaced it, rather than quietly disappearing.

What replaced it answers the judgement instead of overruling it:

- **Nothing is recorded unless some client holds the `captures`
  capability.** Not a preference that can drift out of step with reality: the
  same grant that allows the log to be read is the one that causes it to
  exist. A phone with no such client keeps the original promise exactly.
- **Revoking the last grant deletes what was already collected.** Enforced at
  server start, which is the one choke point every capability change passes
  through.
- **Captures pass the same `NotificationFilter` as the brief**, so the
  one-time-passcode guarantee is not weakened by this route. A notification
  that never became a brief row never becomes a capture either.

### The new capability, and the one asymmetry in it

`captures` is **not** in the default set and **not** in
`MedhaAccessContract.GRANTABLE` — so unlike every other capability, an
installed app cannot obtain it through the consent dialog it triggers.

That is a decision, not an omission. The argument that made `notify`
grantable in v0.9.0 — that a system-verified package deserves at least what a
typed pairing code gets — turns on the user being able to *see* the
consequence of saying yes. With `notify` they can: it writes to their shade.
Reading the stream has no such tell. An app granted `captures` behaves exactly
as it did before, forever, while every notification the phone shows is
readable by it. A dialog raised by that app, at a moment it chose, explaining
itself in words it wrote, is not where that gets decided.

It is granted from Medha's own screens — the per-client capability editor, or
web pairing — and the first time it is switched on for a client, a dialog
states the scope and the retention. A check in
`tools/tests/AccessContractTest.kt` fails if anyone adds it to `GRANTABLE`.

**After installing:** tick **"Read your notifications"** for your agent's
client (drawer → API clients → the client → capabilities). Nothing is
recorded until you do.

---

## 2. `PUT /widget/content` v2 — obligations, not rendered rows

Both shapes are supported and v1 is not going away. Ask
`GET /notify/capabilities` for `briefProtocol`.

```json
{"obligations": [
   {"id": "card-icici-nov", "title": "ICICI credit card", "kind": "bill",
    "due": {"at": 1762022400000, "provenance": "declared"},
    "state": "open", "links": ["https://quicksilver.local/n/4312"]}],
 "ttlSeconds": 7200}
```

Three fields, each for something that went wrong:

**`id`** — a stable handle. Identity used to be `(client, title, dueAt)`, so a
dismissal dissolved the moment a title was reworded: rewriting "Pay ICICI
card" as "ICICI card payment" resurrected something the user had put away, and
it looked like the agent's fault rather than the key's. `BriefDismiss` now
prefers the id, and keeps the old encoding byte for byte so no existing
dismissal is invalidated.

**`due.provenance`** — `declared` or `inferred`. A date is only a proxy for
consequence when somebody *declared* it. An inferred date still sorts, still
persists and still shows on the full brief; it cannot promote to the widget.

**`state`** — `open`, `done` or `cancelled`. Only ever demotes. `open` does
not rescue an item whose own title begins "Cancelled:", because the two
mistakes cost different amounts: a wrongly demoted item is one tap away on the
full brief, and a wrongly promoted one is a cancelled meeting on the home
screen at its old hour.

`GET /widget/content` now echoes the **`lane`** Medha concluded — `agenda` or
`open` — which is the one thing a client could not previously find out. Send
`state: "open"` on something titled "Cancelled: standup" and you get
`"state": "open"` beside `"lane": "open"`, which is Medha telling you it read
the title and demoted it anyway.

The `PUT` response gains `protocol`, `linksDropped` and `onAgenda` — the last
being how many of your items reach the home screen rather than only the full
brief.

---

## 3. Three bugs found on the way

**`MAX_ITEMS = 5` in both sample clients.** `samples/medha_brief.py` and
`samples/medha-brief.js` — the files every local client and PWA copies — kept
trimming to five after `BriefPolicy.MAX_ITEMS_PER_SECTION` was raised to 20,
*client-side, before the request*, and returned success. The tests missed it
because they asserted "capped at five" against literals, so they kept passing
for the wrong reason after the cap moved. Fixed; fixtures now derive from the
constant; and `tools/check_mirrored_caps.py` fails CI if any mirrored limit
drifts again.

**`tools/tests/medha_brief_py_test.py` was never in `run.sh`.** 83 checks
against the client file everything copies, and CI had never run them (only
`verify.sh` did). Now wired in, and `check_workflows.py` fails if any test
file exists that `run.sh` does not name.

**Dismiss-from-the-screen would have broken silently.** `BriefActivity` built
its key from a row while `BriefDismiss.apply` built it from an item, so
tapping Dismiss on a v2 item would animate away and return on the next render
with no error anywhere. One line from shipping.

---

## 4. New files

| File | For |
|---|---|
| `app/.../connect/CapturePolicy.kt` | the capture log's bounds and cursor arithmetic, pure |
| `app/.../notify/BriefIntake.kt` | the `PUT` protocol boundary, pure |
| `tools/check_mirrored_caps.py` | the sample clients' caps against the server's |
| `tools/tests/CapturePolicyTest.kt` | 47 checks, incl. a simulated cursor walk |
| `tools/tests/BriefIntakeTest.kt` | 41 checks |
| `docs/RELEASE-v0.20.0.md` | this file |

Both new Kotlin files are pure and the harness executes them. That is
deliberate: every decision this project has left in `LocalServer.kt` has
eventually been wrong in a way nobody could run a test against — the `dueAt`
stored and ignored for two releases, the widget request codes that collided,
the triage rule that promoted four emails.

---

## 5. Verification

```
tools/tests/run.sh          37 suites green, exit 0
tools/check_*.py            13 checkers green
*_mutations.sh              86 mutation checks across 4 suites
```

Nothing Android-specific was compiled here — this project only ever builds on
CI. What ran: the pure-Kotlin harness, the Python checkers, the mutation
suites, and a filtered syntax pass over every file touched.

Mutation probes caught two defects in my own work before they shipped: a
private function-typed val leaking across files in the `check_symbols` fix,
and a key-collision test that used a dated item and so never exercised the
case it was guarding.

---

## 6. Database

`DB_VERSION` 4 → 5, adding the `captures` table. `onUpgrade` creates it for
`oldV < 5`; nothing existing is touched, and downgrade behaviour is unchanged.

The `AUTOINCREMENT` on `captures.id` is load-bearing twice over: without it
SQLite reuses rowids after deletes and `?since=` would silently skip rows, and
clearing the table on revocation would restart ids from 1 — letting a revoked
client's stale cursor replay against new rows.
