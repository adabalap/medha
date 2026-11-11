# Notifications from a web app

> **Writing a daemon or script rather than a browser app?** Read
> [BRIEF-API.md](BRIEF-API.md) instead — the exact request and response
> shapes, every limit as a number, and none of the pairing-code or CORS
> material below, which applies only to browsers.

How a web app — hosted anywhere — posts to this phone's notification shade
through Medha, and why the design is shaped the way it is.

**Adding this to one of your PWAs: [`samples/INTEGRATING.md`](../samples/INTEGRATING.md)**,
with a drop-in client at [`samples/medha-brief.js`](../samples/medha-brief.js).
A complete working page: [`samples/notify-pwa/`](../samples/notify-pwa/).

This document is the reference; INTEGRATING.md is the how-to.

---

## 1. The surfaces, honestly

| Surface | Reachable by a third party? |
|---|---|
| Standard notification | **Yes**, every device |
| **Now Bar** (One UI 8 status-bar pill) | **Yes**, via Android 16 Live Updates |
| **Medha's brief** (home-screen widget) | **Yes**, `PUT /widget/content` |
| **Now Brief** (Samsung's daily digest) | **No.** No public API exists |

### Now Brief cannot be targeted

There is no API, no intent, no content provider and no SDK. This is worth
stating carefully, because "post to Now Brief" is a natural thing to want and
because One UI 9 added a feature whose name makes it sound possible.

One UI 9 lets you create **custom Now Brief cards**. They are authored by the
*user*, not by an app: in Settings → Galaxy AI → Now brief → Custom cards you
pick a subject such as Weather or Health, optionally describe what you want in
free text, set a schedule, and tap Generate — and Samsung's own AI produces
the content. There is a limit of three cards and a daily cap on generating or
editing them. No field in that flow is writable by another app.
([Samsung's own how-to](https://www.samsung.com/in/support/mobile-devices/how-to-create-now-brief-custom-cards-on-galaxy-devices/))

So `capabilities.nowBrief` is always `false`, and it now carries a
`nowBriefReason` saying why, so an integrator reading `false` does not go
hunting for a flag they got wrong.

What Medha offers instead is **its own brief** — a home-screen widget that any
paired app can write to, described in section 5. It is not Samsung's surface
and does not pretend to be. It is the one digest surface on the device that
accepts third-party content at all.

The Now Bar *is* reachable, because One UI 8 adopted Android 16's Live Updates
API: an **ongoing notification carrying progress** gets promoted into the pill.
It is designed for ongoing activities with a measurable state — a download, a
build, a journey — not arbitrary cards. A request without progress posts as an
ordinary notification instead of failing.

Do not branch on OS version yourself. Ask:

```
GET /notify/capabilities
```

```json
{ "sdk": 36, "liveUpdates": true, "nowBarLikely": true,
  "notifications": true, "widget": true, "nowBrief": false }
```

`notifications: false` means the user has not granted Medha the notification
permission, and nothing you post will appear. Surface that rather than
retrying.

---

## 2. Getting a token

An **installed Android app** uses the intent handshake: it fires
`com.adabala.medha.action.REQUEST_ACCESS`, Medha reads `getCallingPackage()`,
shows the user which package is asking, and hands back a token. The whole
thing rests on `getCallingPackage()` being filled in by the system and
unforgeable by the caller. See [INTEGRATION.md](INTEGRATION.md).

A **web app has no package.** If it fired that intent, the caller would be
Chrome. There is nothing to identify and nothing to trust, so the intent flow
cannot be stretched to cover it. Web apps pair instead, with the *user*
vouching for who is asking:

```
   user, in Medha              your web app                   Medha
   --------------              ------------                   -----
   drawer → Pair a web app
   ticks "Post notifications"
   sees   418 902
                               user types 418902
                               POST /pair {"code":"418902"}  → verifies
                               ← {token, clientId, …}          binds token
                                                               to your Origin
```

```js
const r = await fetch("http://127.0.0.1:8001/pair", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ code: "418902" })
});
const { token, clientId, capabilities } = await r.json();
```

Store the token. Send `Authorization: Bearer <token>` on everything after.

### Why six digits is enough

A million combinations is not much on its own. Three properties together are
what bound it:

- a code exists only for **five minutes**, and only while the user is looking
  at Medha's screen;
- **five wrong guesses destroy it** — an attacker gets five tries at one in a
  million, not a million tries; and
- **only one code is ever live**, so guesses cannot be spread across many.

The code is short because a human retypes it on another device. A
32-character token would be entropy nobody can transcribe, and the
brute-force ceiling above is what actually bounds the risk.

### Pairing errors

| `code` | Meaning |
|---|---|
| `no_pending_code` | Nobody has tapped "Pair a web app", or the code was already used |
| `wrong_code` | Digits do not match; the reply says how many tries are left |
| `code_burned` | Five wrong tries; generate a new one in Medha |
| `code_expired` | Older than five minutes |
| `no_origin` | No usable `Origin` header — see below |

---

## 3. Posting

```js
await fetch("http://127.0.0.1:8001/notify", {
  method: "POST",
  headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` },
  body: JSON.stringify({
    id: "build-4821",          // your handle for this notification
    title: "Build finished",
    text: "main → green in 4m 12s",
    silent: true
  })
});
```

For something ongoing, which is what may reach the Now Bar:

```js
body: JSON.stringify({
  id: "sync", title: "Syncing", text: "412 of 900 documents",
  ongoing: true, progressCurrent: 412, progressMax: 900
})
```

Cancel when the thing it describes is over. An ongoing notification with no
cancel is a notification the user has to swipe away themselves:

```js
await fetch(`http://127.0.0.1:8001/notify/sync`, {
  method: "DELETE", headers: { "Authorization": `Bearer ${token}` }
});
```

Notification ids are namespaced per client, so your `"sync"` and another app's
`"sync"` are different notifications.

### `posted: false` is not an error

```json
{ "posted": false, "reason": "notifications are disabled for Medha on this device" }
```

The call succeeded; Android refused it. On API 33+ this nearly always means
Medha lacks `POST_NOTIFICATIONS`. Tell the user to grant it to Medha — your
own app's notification permission is irrelevant here, because the
notification is posted by Medha.

### Limits

- `title` 200 characters, `text` 2000. Android truncates well before either.
- **10 in a burst, 30 a minute, per client.** Over that you get `429` with a
  `Retry-After` header. The limit exists because this is the one endpoint
  where a client makes the *device* do something and it is cheap to call — a
  stray `setInterval` is a bug, not malice, and the shade should survive it.

---

## 4. Medha's brief

A home-screen widget several paired apps write to at once. Each app owns a
**section**; writing replaces only your own.

```js
await fetch("http://127.0.0.1:8001/widget/content", {
  method: "PUT",
  headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` },
  body: JSON.stringify({
    items: [
      { title: "Standup at 10", text: "with the platform team",
        link: "https://notes.example.com/today" },   // optional
      { title: "Rent due",      text: "Friday" }
    ],
    ttlSeconds: 21600          // optional; 24h default, clamped 60s..7d
  })
});
```

**`link` is optional and http(s) only.** Tapping that line in the brief
opens it; an item without one opens a detail sheet showing the full text,
which app posted it and when. Anything that is not http(s) — `intent:`,
`file:`, a custom scheme — is dropped rather than rejected, so one bad link
does not fail a whole post. The restriction is not cosmetic: items are
written by remote clients and tapped on the user's home screen, so the set
of things a tap can launch has to be the boring one. Medha filters on write
and again on read, and `GET /widget/content` echoes back what it kept.

Medha cannot open the posting app itself for you: it stores a hashed
namespace rather than the caller's package name, deliberately, so it has
nothing to launch and will not guess. A paired web app is the exception —
its origin is known, and the detail sheet offers it.

```
GET    /widget/content   -> your section (admin sees every section)
DELETE /widget/content   -> withdraw your section
```

A `PUT` with an empty `items` array withdraws too, so clearing yourself needs
no second call.

The user has to **place the Medha widget on a home screen once.** Until they
do, writes succeed and store fine but there is nothing drawing them. That is
not an error anyone can detect from the API, so say it in your setup copy.

### It rotates, and tapping opens the whole thing

The widget draws **three rows at a time** and holds up to twelve. When there
are more than three it cycles through pages while the screen is on, and the
footer shows `2/4 · 11 items · tap for all`. Tapping opens a screen with every
item from every app, grouped and timestamped — the same gesture Samsung's own
digest uses, and the reason overflow is kept rather than discarded at write
time.

Interval is the user's choice (Medha → drawer → **Brief rotation**): off, 3s
(default), 5s, 10s or 30s. Nothing in the API controls it; a client cannot
make the user's home screen flicker faster.

#### Why the timer is Medha's and not the widget framework's

Three mechanisms were available and two of them cannot do this:

- **`updatePeriodMillis`** in the appwidget metadata is clamped to a
  **30-minute minimum**, silently. Asking for 3000 gets you half an hour.
- **`AlarmManager`** has been inexact for repeating alarms since API 19, and
  Doze and App Standby throttle frequent ones. A three-second repeating alarm
  is how an app earns a battery-drain reputation, deservedly.
- **`AdapterViewFlipper` with `android:autoStart`** would let the *launcher*
  animate at no cost to Medha at all, which is genuinely the most efficient
  option available. It is not used yet for two reasons: the flip interval is a
  layout attribute, so a user-configurable interval would mean shipping one
  layout per choice; and it needs a `RemoteViewsService` plus a
  `RemoteViewsFactory`, which is a lot of API surface in a project that cannot
  run a device test. Worth revisiting — this is a known trade, not an
  oversight.

So the tick comes from Medha's foreground service, which is already running
with a wake lock whenever the brief is being written to. Three gates must all
hold before a single tick fires:

1. a widget is actually placed;
2. **the screen is on** — this is the one that matters, because it ties the
   cost to screen-on time rather than uptime. Android gives no way to know
   whether a widget is *visible* (the user may be in another app), so screen
   state is honestly just a proxy; and
3. the brief overflows one page.

When any gate closes the handler is unscheduled rather than skipped, so an
idle rotator posts no messages at all. Gates are re-checked on every tick, so
it stops on its own if a broadcast is missed.

### How it behaves, and why

- **Per client, not last-writer-wins.** Up to v0.9.0 there was one shared
  blob: the second app to write erased the first, and neither could tell why
  its content vanished. With one consumer that is indistinguishable from
  working, which is why it shipped.
- **Sections expire**, 24 hours by default. A card from Tuesday sitting there
  on Friday is worse than no card, because the user cannot tell stale from
  current and stops trusting the whole surface. `ttlSeconds` is clamped to
  60s–7d so no client can pin content indefinitely.
- **Expiry is evaluated on read.** No timer, no worker, no wakeup to delete
  text nobody is looking at.
- **One noisy app cannot crowd the others out.** Rows are filled round-robin,
  so every contributing app gets its first line before any app gets its
  second.
- **Rows carry attribution** — which app, how long ago — but only when more
  than one app is contributing. With a single writer that is noise the user
  already knows.
- **Limits:** 5 items per client, 8 clients, 12 rows total, 3 drawn at a
  time, 80-character titles, 200-character text. The `PUT` response reports
  what was *kept*, not what you sent — v0.9.0 echoed the request size while
  silently rendering three of five items.
- **Only the user can clear everything.** There is no API for it: one client
  wiping another's section is exactly the last-writer-wins behaviour the
  per-client split removed. The full-brief screen has a Clear everything
  action, because the user owning their own home screen is a different thing.

## 5. Browser rules that will cost you an hour

**An HTTPS page calling `http://127.0.0.1` is not mixed content.** Both
`127.0.0.1` and `localhost` are "potentially trustworthy" origins per the
Secure Contexts spec, so the browser permits it. People expect this to be
blocked and it is not.

**Private Network Access is the actual gate.** Chrome treats a public page
calling a private address as crossing a boundary, and sends a preflight
carrying `Access-Control-Request-Private-Network: true`. Medha answers with
`Access-Control-Allow-Private-Network: true` — but **only for an origin that
has already paired**, and only from v0.9.0. Against an older build, or before
pairing, the fetch fails with a bare `Failed to fetch` and no detail.

**`Origin: null` cannot pair.** A `file://` page and a sandboxed iframe both
send it. Since every sandboxed frame on the device sends the same value, a
token bound to it would be usable from any of them, so Medha refuses. Serve
your page over http(s).

**Your token only works from your origin.** Medha records the origin that
paired and rejects the token when it arrives from a different one, with
`origin_mismatch`. A token copied into another site's page is useless from a
browser. A request with *no* Origin header is allowed — curl and native
clients send none — so the token still works from a script if you copy it
out deliberately.

**CORS never returns `*`.** The allow header echoes the single requesting
origin, and `Vary: Origin` is set on every response, including denied ones.

---

## 6. Managing what you granted

Medha's drawer → **API clients** lists every credential, what it can do, when
it was last used, and where it came from — `Granted to an app`, `Created by
you`, or a paired web origin. Revoking takes effect on the next request: the
allowed-origin set is read live, not cached, so a revoked web app stops being
able to read replies immediately rather than at the next restart.

Re-pairing the same origin **rotates** that origin's token rather than adding
a second client. Losing a token is recoverable; accumulating live credentials
for one app is not a thing you want.

---

## 7. What is tested, and what is not

`tools/tests/NetPolicyTest.kt` runs against the real sources with no Android
SDK and covers origin canonicalisation, exact-match origin comparison
(including the suffix-attack cases), the CORS decision, the PNA preflight, the
rate-limiter refill maths and the full pairing state machine — 114 assertions,
run in CI.

`tools/tests/BriefPolicyTest.kt` covers the brief's merge, expiry, ordering,
bounds and paging — including that two clients cannot erase each other, that
one noisy client cannot fill the widget, that one full rotation cycle shows
every row exactly once in order, and that a negative or stale tick cannot
index off the end of the list. 84 assertions.

`verify.sh` additionally checks that `BriefPolicy.PAGE_ROWS`, the row ids in
`widget_medha.xml` and the slot list in `MedhaWidgetProvider` all agree. Three
independent places, because when they disagreed in v0.9.0 the overflow was
dropped in silence.

`tools/tests/BriefJsonTest.kt` runs the **v0.9.0 → v0.9.1 migration** against
the real `BriefJson.kt`, using `tools/tests/stubs/org/json`, which mirrors
Android's `org.json` signatures closely enough to execute it. The old
single-blob shape is parsed and must survive the upgrade: without that branch
every existing install's widget would go blank, and a blank widget is
indistinguishable from "nothing posted yet", so nobody would report it as data
loss. All three of those branches were mutation-tested.

`tools/tests/sample_pwa_smoke_test.js` executes `samples/notify-pwa/` in a
real DOM, including with `localStorage` throwing and on a `file://` origin.

**Not verified:** anything that needs the device. Whether a promoted ongoing
notification actually appears in the Now Bar on a given One UI build, whether
Chrome's PNA preflight is satisfied end to end, and how the five-row widget
lays out at a given home-screen size, can only be confirmed by running it. See
[TESTING.md](TESTING.md).
