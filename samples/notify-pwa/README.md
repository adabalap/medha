# notify-pwa

A web app that pairs with Medha and posts to the Android notification shade.
One HTML file, no build step, no SDK, no dependencies.

It exists because the other sample, `hello-medha`, is an installed Android app
and gets its token through the intent handshake — a path a web page cannot
use, since the system would report the *browser* as the caller. Web apps pair
instead, and this shows how.

## Running it

You need to serve it over `http` or `https`. **A `file://` page cannot pair**,
and the sample disables the button and says so rather than letting you find
out at step 2. The reason: browsers send `Origin: null` for `file://` pages,
and so does every sandboxed iframe on the device — binding a token to that
origin would make it usable from any of them.

The simplest host is GitHub Pages: commit this folder to a `gh-pages` branch
and open the published URL on the phone running Medha. Any static host works.
So does a laptop on the same network, as long as the phone loads the page from
that laptop's address rather than from a file.

Then:

1. Start Medha and load a model.
2. In Medha: drawer → **Pair a web app** → tick **Post notifications** → tap
   **Create**. Six digits appear.
3. In this page: set the port (Medha's status card shows it), tap
   **Check connection**, then type the six digits and tap **Pair**.
4. Tap **Post** and pull down the shade.

The token is kept in `localStorage`, so the page stays paired across reloads
on that browser. It is per-browser and per-origin: clearing site data means
pairing again, which is correct for a credential.

## The whole API

Three calls. That is the entire integration surface.

```js
// 1. Pair, once. No Authorization header -- getting a token is the point.
const r = await fetch("http://127.0.0.1:8001/pair", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ code: "418902" })
});
const { token } = await r.json();

// 2. Post. Same shape every time after that.
await fetch("http://127.0.0.1:8001/notify", {
  method: "POST",
  headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` },
  body: JSON.stringify({ id: "build", title: "Build finished", text: "main → green" })
});

// 3. Cancel, when the thing it was about is over.
await fetch("http://127.0.0.1:8001/notify/build", {
  method: "DELETE",
  headers: { "Authorization": `Bearer ${token}` }
});
```

Add `ongoing: true` with `progressCurrent` / `progressMax` and the platform
may promote it to the status-bar chip — the Now Bar on One UI 8. Ask
`GET /notify/capabilities` what this particular device can actually do rather
than branching on OS version yourself.

## Things that will trip you up

**An HTTPS page calling `http://127.0.0.1` is not mixed content.** Both
`127.0.0.1` and `localhost` are "potentially trustworthy" origins per the
Secure Contexts spec, so the browser allows it. This surprises people.

**Private Network Access is the real gate.** Chrome treats a public page
calling a private address as a cross-boundary request and sends a preflight
asking permission. Medha answers it — but only for an origin that has already
paired, and only from v0.9.0. Against an older build the fetch fails with a
bare "Failed to fetch".

**Your token only works from your origin.** Medha binds it to the origin that
paired, and refuses the token if it arrives from a different one. Copying it
into another site's page gets a 403, not a working integration.

**`posted: false` is not an error.** It means Medha made the call and Android
refused, which on API 33+ almost always means the user has not granted Medha
the notification permission. The response says so in `reason`.

**There is a rate limit.** Ten in a burst, thirty a minute, per client. A
runaway `setInterval` gets 429s with a `Retry-After` header rather than
filling the shade.

**Now Brief cannot be targeted.** Samsung's daily digest has no public API.
Nothing here reaches it and nothing pretends to. The Now Bar is a different
surface and is reachable.
