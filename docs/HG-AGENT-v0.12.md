# Quicksilver → Medha v0.12.2: what to change

Everything here is additive. A client that sends nothing new keeps working
exactly as it does today — so none of this has to land in one go. The order
below is roughly by value.

Full reference for the brief and notification surfaces is in
`docs/BRIEF-API.md`; this file is only the delta and the traps.

| # | Change | Endpoint | Breaking? |
|---|--------|----------|-----------|
| 1 | Audio transcription moves to Medha; whisper comes out | `POST /v1/audio/transcriptions` | no |
| 2 | Images are JPEG/PNG only — **WebP is refused** | `POST /v1/chat/completions` | yes, if you send WebP |
| 3 | Send `dueAt`, stop sending date prose | `PUT /widget/content` | no |
| 4 | Send `kind` to pick the row icon | `PUT /widget/content` | no |
| 5 | Notifications can carry a tap target | `POST /notify` | no |
| 6 | Reindex now needs the RAG capability | `POST /rag/reindex` | yes, if you reindex |

---

## 1 · Transcription: drop whisper

`POST /v1/audio/transcriptions`, `multipart/form-data`, file part named `file`.
Needs the **GENERATE** capability. OpenAI-shaped, so an existing Whisper client
mostly just changes its base URL.

**The audio must be 16000 Hz, mono.** This is not a preference — the engine's
preprocessor is built for exactly that rate and channel count, and anything
else is refused with a 400 that names the ffmpeg command. Record at 16k mono
and there is no conversion step at all:

```
ffmpeg -i in.wav -ar 16000 -ac 1 -c:a pcm_s16le out.wav
```

| Limit | Value |
|---|---|
| Format | WAV, or headerless 16-bit PCM (what `AudioRecord` gives you) |
| Sample rate | 16000 Hz exactly |
| Channels | 1 |
| Length | 30 s |
| Upload | 10 MB (the request is refused above 20 MB before a byte is read) |

Optional form fields: `language` (a hint only), `prompt`, `response_format`
(`json` — the default, `text`, or `verbose_json`).

### The one thing Medha cannot replace

`verbose_json` returns `"segments": []` and `"segments_supported": false`,
always. The engine exposes no per-segment timings, and Medha will not
fabricate a single whole-clip segment that would read as real alignment data.

**If anything downstream uses whisper's segment timings, that code has no
Medha equivalent.** Check before removing whisper, not after. Everything else
in `verbose_json` is real: `text`, `duration`, `model`, and `ms` (actual
inference time — useful for your own baselines).

### Check first, don't assume

Audio depends on the model and the AAR, so ask before you rely on it:

```
GET /health  →  capabilities.audio: true|false
                multimodal_mode: "all on GPU" | "GPU, audio on CPU" | ...
```

At runtime the answer is **501 `audio_unsupported`**, and the message says
why. Other errors: **413 `file_too_large`**, **400** for rate/channels/length.
Keep whisper as a fallback for one release if you like, but gate it on
`capabilities.audio` at startup rather than on a per-clip failure.

---

## 2 · Images: JPEG and PNG, never WebP

Images go through `POST /v1/chat/completions` as typed content parts.

`image/webp` was accepted until v0.11.0 and was never going to work:
LiteRT-LM's image decoder reads PNG and JPG only, so a WebP passed the type
check and then died inside the native decoder with a message about neither
WebP nor images. It is now refused up front, with the fix in the message.

**This is the one genuinely breaking item.** `Bitmap.CompressFormat.WEBP` is a
common default on Android, and if the agent screenshots or re-encodes to WebP
anywhere, those calls start failing on upgrade. Use `PNG` for screenshots and
sketches, `JPEG` for photos.

Limit: 4 MB per image.

---

## 3 · Send `dueAt`, not prose

This is the highest-value change and it is two lines.

`PUT /widget/content`, each item may carry:

```json
{
  "title": "ICICI credit card",
  "text":  "payment missed",
  "dueAt": 1763337600000
}
```

`dueAt` is **epoch milliseconds**. Medha derives from it, on every render, in
the device's current timezone:

- the **band** — `overdue`, `soon` (within 7 days), or `later`
- the **relative string** — `"today"`, `"tomorrow"`, `"yesterday"`, `"10d over"`, `"in 6d"`
- **where the row sits.** From v0.12.2 rows are ordered by band: everything
  overdue, then this week, then later, then undated. Before that, position was
  decided by which app had posted most recently, so an overdue bill could sit
  under a note due in three weeks.

One thing this does *not* do is let you push your way up the widget. Inside
each band the apps still take turns, one row each per pass, so posting five
items never costs another app its line. Being urgent gets you the top of the
list; being noisy does not.

**So stop putting the date arithmetic in `text`.** A string like
`"overdue 10 days"` is correct at the moment you write it and wrong a few
hours later; the widget then shows a five-hour-old sentence and presents it
as today's truth. `dueAt` is computed at render time and cannot go stale.

Rules: it must be a positive number. Omitted, `null`, or `0` means undated —
the item still renders, it just carries no band and no countdown. Values are
honest about pre-epoch and DST; you do not need to pre-adjust anything.

Read it back with `GET /widget/content` and you get the derived values too, so
you can see exactly what Medha made of what you sent:

```json
{ "dueAt": 1763337600000, "band": "later", "due": "in 10d" }
```

That response also carries `"overdue": <count>` across all contributors, and
`"kinds"`: the vocabulary Medha will accept — see below.

---

## 4 · Send `kind`

One of exactly these six:

```
bill   date   event   alert   done   note
```

It will pick the row's icon. Case and surrounding whitespace are normalised,
so `"Bill"` and `" bill "` both land. An unrecognised value is **dropped
silently, not rejected** — the item still posts, it just loses the kind, so a
typo degrades rather than failing. Validate against the `kinds` array from
`GET /widget/content` if you want to catch that in your own tests.

**Today `kind` is stored and echoed back, and nothing draws it yet** — the
widget layout has no icon slot until the rebuild lands. Send it anyway: it
costs one field, it is validated now, and the rows that carry it will light up
on upgrade without you touching anything.

---

## 5 · Notification tap target

`POST /notify` now accepts `link`:

```json
{ "title": "Bill due tomorrow", "body": "ICICI credit card",
  "link": "https://quicksilver.local/items/4821" }
```

Tapping opens it with `ACTION_VIEW` + `CATEGORY_BROWSABLE`. Without `link`,
the tap opens Medha, exactly as before.

**A link that cannot be used is dropped, not an error.** It must be `http://`
or `https://`, with no whitespace or control characters, and at most 2000
characters.
Anything else is discarded and the notification still posts. The response
tells you which happened — check it rather than assuming:

```json
{ "tapOpens": "link", "linkDropped": false }
```

`tapOpens` is `"link"` or `"medha"`.

If you want the tap to land in the Quicksilver app rather than a browser, the
intent filter needs `CATEGORY_BROWSABLE` — without it Android will not match
the intent and the tap goes nowhere. The snippet is in `docs/BRIEF-API.md`
under *Making your own app receive the tap*.

---

## 6 · `POST /rag/reindex` now needs the RAG capability

Previously this was the only RAG route with no capability check and no
namespace scoping: any authenticated client — including one paired purely for
notifications — could name another client's collection in the body, read back
its chunk counts, and spend the phone's battery embedding them.

It now requires **RAG** and scopes to the calling client's own namespace, like
every other RAG route already did. If Quicksilver is paired without RAG, this
call starts returning 403. Nothing else about it changed.

---

## What renders today, and what is stored for later

Worth being exact about, because "the API accepts it" and "the user sees it"
are not the same sentence and an earlier draft of this file ran them together.

| You send | Stored & echoed | Visible on the widget today |
|---|---|---|
| `dueAt` | yes | **yes** — sets the row's position, and the countdown on the row |
| `kind` | yes | not yet — no icon slot until the layout rebuild |
| `link` | yes | yes — on notifications; brief rows open Medha |
| band colour | derived | not yet — see below |

The countdown is the part that pays off immediately: the row now reads
`10d over` where it used to read `2h`, which was how long ago your app
happened to write rather than anything about the bill.

Band colour is derived and deliberately not drawn yet. The rows already carry
the accent bar it belongs on; what is missing is doing it in a way that keeps
dark mode correct, since the obvious `RemoteViews` calls bake in a colour
resolved against Medha's configuration rather than the launcher's. It arrives
with the rebuilt layout. None of this affects what you send.

## What is not changing

- Every field above is optional. Items without `dueAt` or `kind` render fine.
- No endpoint was removed or renamed.
- Existing brief posts keep working untouched. A client that sends no `dueAt`
  at all gets back exactly the ordering it got before — not similar, identical;
  there is a test that says so.
