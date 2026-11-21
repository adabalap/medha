# Response to the Quicksilver multimodal spec

> **SUPERSEDED — read [MULTIMODAL.md](MULTIMODAL.md) instead.**
>
> This document is from v0.10.0 and its central claim is wrong. It says the
> model work is not done and that `/health` will honestly report `false`
> until it is. In fact litertlm-android 0.13.1 — the version Medha was
> already pinned to when this was written — fully supports image and audio
> input. The probe behind that `false` matched **method names** against a
> guessed hint list, while the SDK expresses media as **types**
> (`InputData.Audio`) and **constructor parameters**
> (`EngineConfig.audioBackend`). It could not have found the binding.
> Separately, `LlmEngine` was constructing the engine with no media
> backends at all, so the capability would have failed even if advertised.
>
> Both are fixed in v0.11.0, which also ships
> `POST /v1/audio/transcriptions`. **Do not plan around whisper.cpp on the
> strength of this document.**
>
> Kept rather than deleted: the request-contract sections below are still
> accurate, and the reasoning that led to a confidently wrong answer is
> worth being able to re-read.

---

Medha v0.10.0. Point by point, with what is built, what is not, and what
cannot be answered from this side.

**Headline:** everything that is contract and plumbing is done. The model
work is not, and I will not claim otherwise — see §2. The useful consequence
is that you can turn `medha_multimodal` on **now**: `/health` reports
capabilities from an actual runtime probe, so it will read `false` until the
model genuinely supports it, and your documented fallback does the rest.

---

## The bug this found first

`OAChatMessage.content` was typed `String`. Your array-of-parts payload threw
in deserialization, and `StatusPages` turned that into **HTTP 500**.

That is the worst available answer. Your fallback reads 4xx as "this build
has no vision or audio"; a 500 reads as a broken server. So today the agent
cannot degrade — it just fails. Acceptance check 7 failed before any of the
rest mattered.

Fixed: the body is parsed by `oai/ChatRequestParser.kt`, which accepts both
the string and the content-part forms. 57 assertions, run in CI, built
against the exact payload from your spec rather than an invented one.

---

## Must have

### 1. `POST /v1/chat/completions` with image and audio parts — **accepted and validated**

Parses `text`, `image_url` and `input_audio` parts. Images must be `data:`
URLs; Medha does not fetch remote ones, because nothing is supposed to leave
the device, and it says so rather than timing out.

Validation, all returning statuses your client treats as fallback:

| Condition | Status | `code` |
|---|---|---|
| Malformed body, missing/empty `messages`, empty payload | 400 | `bad_request` |
| Remote (non-`data:`) image URL | 422 | `remote_image_unsupported` |
| Non-base64 data URL | 422 | `image_encoding_unsupported` |
| Image type other than jpeg/png/webp | 415 | `image_type_unsupported` |
| Image over 4 MB | 413 | `image_too_large` |
| Audio format other than wav/pcm16 | 415 | `audio_format_unsupported` |
| Audio over 30 s | 413 | `audio_too_long` |
| Well-formed but this build has no encoder | **501** | `multimodal_unsupported` |

Size and duration are computed from the base64 length without decoding —
allocating a megabyte to discover it is too big is backwards on a phone.
Your 28 s chunk size is explicitly tested as accepted; 35 s is refused with a
message saying to chunk.

An unknown part type is skipped rather than fatal, so a future OpenAI part
alongside usable text still gets an answer to the text.

### 2. Vision and audio encoders loaded — **NOT DONE, and not something I can do from here**

This is the honest part.

Medha binds LiteRT-LM by reflection because the Kotlin surface has changed
between builds. Whether `litertlm-android:0.13.1` exposes an image or audio
entry point **at all**, and under what name, cannot be settled from here: I
cannot build, cannot run on the device, and cannot fetch the AAR to inspect
it. That the native library does multimodal under Termux does not settle
whether the Android binding exposes it.

Writing a vision path against guessed method names and shipping it would
produce code that compiles, passes every check I can run, and fails on your
phone. I have done the adjacent version of that mistake in this project
already.

**What is built instead** is `oai/MultimodalProbe.kt`, which at runtime
reflects over the installed AAR, looks for image and audio entry points, and
reports exactly what it found — including full method signatures. `/health`
reports `vision`/`audio` from that probe, never from a flag.

**This turns an unanswerable question into one call.** On a device with the
model loaded:

```sh
curl -s -H "Authorization: Bearer $TOK" http://127.0.0.1:8001/system | jq .multimodal
```

```json
{ "vision": false, "audio": false,
  "summary": "no image or audio entry points in this build of the LiteRT-LM AAR",
  "imageMethods": [], "audioMethods": [], "sdkMethodCount": 47 }
```

Send me that block. If `imageMethods` is non-empty, the signatures are there
and I can write the binding from evidence. If it is empty, the AAR does not
expose it and the answer is a newer `litertlm-android`, or MediaPipe
`tasks-genai`, which is a dependency change worth deciding deliberately —
note the comment already in `app/build.gradle.kts` about it shipping
duplicate native libraries.

Load-on-first-use and unload-after-idle: agreed, and the right shape. Not
built, because there is nothing to load yet.

### 3. `/health` reports capabilities — **done**

```json
{"status":"ok","modelLoaded":true,"backend":"GPU","busy":false,
 "capabilities":{"text":true,"vision":false,"audio":false},
 "max_context":4096,"max_audio_seconds":30,"max_image_bytes":4194304,
 "queue_depth":0,"authRequired":true,"error":""}
```

`max_context` is an estimate: LiteRT-LM exposes no context length and the
real figure depends on the model file. It is stated so you can size prompts
and so the 413 has a threshold, not as a number to trust to the token.

### 4. `/generate` honours `max_tokens` — **done, and it is a real cap**

`max_tokens` on `/generate`, and `max_tokens` / `max_completion_tokens` on
`/v1/chat/completions`.

Enforced by generating through the **streaming path and stopping early**, not
by truncating after the fact — the tokens past the cap are never produced, so
the phone does not spend the compute. That is the difference that matters for
Telugu at ~2.2× the tokens.

It overruns by at most one chunk, deliberately: cutting mid-chunk would
truncate a word. `finish_reason` is `"length"` only when the cap is what
ended it, reported from what happened rather than assumed.

---

## Should have

- **Telugu text generation** — nothing to do in Medha; it is whatever the
  model does. Tokenizer speed is measurable with your `medha_check`.
- **Telugu speech-to-text accuracy over 10 clips** — **I cannot measure
  this.** No device, no model, no clips. This needs your harness; see below.
- **`language` hint** — parsed and carried (`"te"`, `"en"`, `"auto"`,
  lowercased). Inert until there is an audio path to hand it to, and it would
  be dishonest to pretend otherwise.
- **`POST /v1/audio/transcriptions` with whisper.cpp** — not built. It is a
  native dependency, a model download and a chunking pipeline, and it is the
  right answer *only if* Gemma's Telugu audio turns out weak. That is
  question-first work: measure, then build. Worth noting it would also make
  Medha useful with no vision support at all.
- **`response_format: {"type":"json_object"}`** — parsed and reported, not
  enforced. Real constrained decoding needs grammar support the binding does
  not expose; prompt-level coaxing is not the same thing and should not share
  the name. Treat it as not implemented.
- **Telugu text in photos** — same dependency as §2.
- **Overflow and busy** — 413 `context_overflow` when the prompt exceeds
  `max_context`. 429 with `Retry-After` when the queue is full or batch work
  is thermally gated; that already existed via `InferenceScheduler`.
- **Priority hint** — already exists: `X-Medha-Priority: interactive|batch`.
  Map Ask to `interactive` and enrichment/media to `batch`.

## Nice to have

- **Usage counts** — already present on `/v1/chat/completions`; `/generate`
  now carries `finish_reason` too. Counts are estimates, not tokenizer
  output; the binding exposes no real count.
- **Streaming** — already works, SSE, on both `/generate/stream` and
  `/v1/chat/completions`.
- **Bearer auth** — already works, and is per-client with capabilities and
  namespacing rather than one shared token.

---

## Acceptance checks

| # | Check | Status |
|---|---|---|
| 1 | Car interior photo → description | **Blocked on §2** |
| 2 | Sketch described, labels read | **Blocked on §2** |
| 3 | 25 s English clip transcribed | **Blocked on §2** |
| 4 | 25 s Telugu clip, accuracy measured | **Blocked on §2**, and not measurable from my side regardless |
| 5 | Telugu printed text in a photo | **Blocked on §2** |
| 6 | Telugu story within the token cap | **Ready** — the cap is real now |
| 7 | Unsupported input returns 4xx, not a crash | **Done** — was a 500, now 400/413/415/422/501 by cause |

**Yes, please build `medha_check`.** It is the right tool and I cannot
substitute for it: every check above needs a device, the model and real
clips. A pass/fail table per Medha build is worth more than any amount of
reasoning from here. If it prints the `/system` `multimodal` block on
failure, check 1 becomes self-diagnosing.

---

## What to do on your side now

1. Read `/health` `capabilities` and switch `medha_multimodal` on
   automatically. It will correctly resolve to false until the probe says
   otherwise, so you can ship that logic today.
2. Keep Tesseract as the vision fallback. It is going to be the live path for
   now.
3. Send me `/system` → `.multimodal` from a device with the model loaded.
   That is the one piece of information that unblocks §2.
