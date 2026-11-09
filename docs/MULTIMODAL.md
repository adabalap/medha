# Multimodal: images and audio

Medha v0.11.0. This supersedes `MULTIMODAL-SPEC-RESPONSE.md`, which was
written against v0.10.0 and said audio and vision were not built. That was
wrong, for a reason worth recording.

---

## What was wrong before

v0.10.0 shipped a runtime probe that reported `vision: false, audio: false`
and a `/health` capability block fed from it. Both were wrong, in two
independent ways that happened to agree:

**1. The probe matched method names.** It carried a hand-written hint list
— `addAudio`, `sendAudio`, `addPcm`, `withImage` — and looked for those
substrings in the method names of five classes. All five classes resolved.
None of the names matched, because litertlm-android does not name methods
after modalities. It expresses them as **types** and **constructor
parameters**:

```kotlin
sealed class InputData {
  data class Text (val text: String)   : InputData()
  data class Audio(val bytes: ByteArray) : InputData()
  data class Image(val bytes: ByteArray) : InputData()
}

class Session {
  fun generateContent(inputData: List<InputData>): String
}

data class EngineConfig(
  val modelPath: String,
  val backend: Backend = Backend.CPU(),
  val visionBackend: Backend? = null,
  val audioBackend: Backend? = null,
  val maxNumImages: Int? = null,
  ...
)
```

`generateContent` contains neither "audio" nor "image". The probe was
structurally incapable of finding a working binding no matter which classes
it was handed.

The lesson, since it is easy to repeat: a probe written to avoid guessing
can still bake a guess into its own design. That one was "capabilities are
announced in method names."

**2. Medha switched it off anyway.** `LlmEngine` constructed
`EngineConfig(modelPath, backend, cacheDir)` and left `visionBackend` and
`audioBackend` at their `null` defaults. Even a correct probe would have
advertised a capability that every request then failed to use.

Both are fixed in v0.11.0. The probe now matches on types and config
accessors, and `tools/tests/MultimodalProbeTest.kt` replays the old hint
list against the real 0.13.1 surface and asserts it finds nothing.

---

## Two different facts, reported separately

`/health` now distinguishes what the **AAR** can do from what the **running
engine** can do:

```json
{
  "capabilities":  { "text": true, "vision": true, "audio": true },
  "aar_supports":  { "vision": true, "audio": true },
  "multimodal_mode": "media on GPU",
  "max_context": 4096,
  "max_audio_seconds": 30,
  "max_image_bytes": 4194304,
  "max_images": 4
}
```

- **`capabilities`** is what you can actually use. Advertised only when the
  AAR supports the modality *and* this engine initialized with that backend
  — which additionally proves the loaded `.litertlm` carries the encoder.
- **`aar_supports`** is the build's ceiling. When this is `true` and
  `capabilities` is `false`, the model is the limitation, not Medha.

**Clients should branch on `capabilities`.** `aar_supports` is for working
out whether to change the model or change the APK.

### `multimodal_error` vs `multimodal_note`

Only one of these appears, and the distinction is load-bearing:

- **`multimodal_error`** — a capability the AAR supports is not available.
  Something you may need to act on.
- **`multimodal_note`** — the ladder fell back a rung but everything
  advertised in `capabilities` works. Informational.

v0.11.1 got this wrong and emitted `multimodal_error` alongside
`audio: true`, because the GPU rung's failure was recorded and never
cleared when the CPU rung succeeded. Both facts were true and the pair read
as a broken server. A recovered failure is a note; only an unmet capability
is an error.

---

## The init ladder

`Engine.initialize()` is the only thing that can tell you whether a given
`.litertlm` will run its encoders on a given accelerator. Nothing readable
from the file or the AAR says so in advance. So `LlmEngine.load()` tries in
order and keeps the first that comes up:

| Rung | `multimodal_mode` | When it is used |
|---|---|---|
| 1 | `all on GPU` (or CPU/NPU) | Both encoders on the configured backend |
| 2 | `GPU, audio on CPU` | The audio tower is refused on the GPU but the vision tower is not |
| 3 | `GPU, media on CPU` | Neither tower runs on the configured backend |
| 4 | `text-only` | The model has no encoders |

**The language model stays on the configured backend in every rung except
the last.** Only the encoders move. The labels lead with the accelerator
running the LLM for exactly that reason: an earlier scheme named only what
moved, and `media on CPU` was read — reasonably — as the whole model
dropping to CPU.

**Rung 2 exists because of a real device.** The first Snapdragon SM8850 to
run this, with `gemma-4-E4B-it`, reported:

```
LiteRtLmJniException: Model requires one of [cpu] but Audio backend is GPU
```

### That constraint comes from the model file, not from the GPU

Worth being exact, because it determines who can fix it. In LiteRT-LM's
source, `schema/model_info/model_info.cc` parses a `backend_constraint`
key/value pair out of each section of the `.litertlm` container — one per
modality — and `ValidateBackendConstraint` in
`runtime/engine/engine_settings.cc` splits it on commas and rejects any
backend not in the list:

```cpp
return absl::InvalidArgumentError(absl::StrCat(
    modality_name, " backend constraint mismatch. Model requires one of [",
    backend_constraint_str, "] but ", modality_name, " backend is ", backend));
```

So whoever packaged `gemma-4-E4B-it.litertlm` wrote `cpu` into the audio
section's constraint. It is a declaration baked into the weights file by
its publisher — not a limitation of the Snapdragon, not of Android, and not
of Medha. No setting on this side can override it; the check runs before
any kernel is ever selected. A differently packaged build of the same model
could lift it.

That model supports audio perfectly well — just not on the GPU. A single
attempt would have fallen back to text-only, and the obvious conclusion
would have been "these weights have no audio encoder", which is wrong and
would have sent a team back to shipping whisper.cpp. The error names
**Audio** specifically, so demoting only audio is the targeted response and
keeps image encoding — the heavier of the two — on the GPU.

### The winning rung is cached

Each rung costs a full model load, about 8.6s on an SM8850, and which rung
a given (model, device) pair accepts does not change between starts. So the
label that worked is stored, keyed by model path and backend, and tried
first next time. Steady state is one load, not three.

The hint only reorders — it never prunes. Swap the model or change the
backend and every rung is still reachable, so an engine that once fell back
can climb back up. `text-only` is deliberately never cached: hinted first it
would succeed immediately, and the media rungs would never run again.

If you would rather skip the ladder entirely — on a model you know has no
encoders — **drawer → Multimodal support → Turn off**.

---

## `POST /v1/audio/transcriptions`

OpenAI-compatible speech-to-text. Change a base URL on an existing client
and nothing else.

```
POST /v1/audio/transcriptions
Authorization: Bearer <token>
Content-Type: multipart/form-data

file             required.  WAV, or raw 16-bit PCM
model            accepted and ignored (Medha serves the loaded model)
language         optional.  ISO-639: "te", "en", "hi"… or omit for autodetect
prompt           optional.  spelling/name hints
response_format  optional.  json (default) | text | verbose_json
temperature      optional.  0–2
```

```bash
curl -s http://127.0.0.1:8001/v1/audio/transcriptions \
  -H "Authorization: Bearer $MEDHA_TOKEN" \
  -F file=@clip.wav \
  -F model=whisper-1 \
  -F language=te
# {"text":"నమస్కారం, ఎలా ఉన్నారు?"}
```

**Raw PCM is accepted**, because Android's `AudioRecord` produces headerless
PCM and that is what any on-device client actually has. Medha sniffs the
bytes and wraps them in a WAV header — sniffs rather than trusting the
declared format, so a client that mislabels a WAV as `pcm16` does not get a
second header stapled on.

**Compressed formats are refused**, not decoded. Shipping an mp3 decoder to
save a caller one ffmpeg call is not a trade worth making on a phone. You
get a 415 that says so.

### Errors

| Condition | Status | `code` |
|---|---|---|
| No audio capability on this build | 501 | `audio_unsupported` |
| No `file` part | 400 | `bad_request` |
| Unreadable / compressed audio | 415 | `audio_format_unsupported` |
| Longer than 30s | 413 | `audio_too_long` |
| Larger than 10 MB | 413 | `file_too_large` |
| `response_format` srt or vtt | 422 | `response_format_unsupported` |
| Unknown `response_format` | 400 | `bad_request` |

`srt` and `vtt` are a deliberate 422 rather than a 400: both need
per-segment timestamps, which the engine does not expose. A client asking
for subtitles made a valid request of a real API and deserves better than
being told its spelling is wrong. `verbose_json` returns an empty
`segments` array and `segments_supported: false` rather than fabricating
one whole-clip segment that would read as real alignment data.

### Duration is parsed, not estimated

The WAV header is read to get the real length. This matters: a 44.1 kHz
stereo clip is 5.5x shorter than a byte-count estimate assuming 16 kHz mono
would say, so an estimate would wave through nearly three minutes against a
30-second limit. (`/v1/chat/completions` still estimates, because it only
has base64 and decoding a megabyte to measure it would be backwards.)

---

## `POST /v1/chat/completions` with media

The standard OpenAI content-part form:

```json
{"messages":[{"role":"user","content":[
  {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}},
  {"type":"input_audio","input_audio":{"data":"<base64 wav>","format":"wav"}},
  {"type":"text","text":"What is in this picture?"}]}]}
```

- Images: **JPEG and PNG only**, as `data:` URLs, up to 4 MB, at most 4 per
  request. WebP was advertised until v0.11.0 and could never have worked —
  `InputData.Image` documents PNG and JPG, so a WebP passed validation and
  then failed inside the native decoder with a message about neither.
- Remote `http(s)` image URLs are refused with 422. Medha makes no outbound
  requests; that is the point of it.
- Audio: `wav` or `pcm16`, up to 30s.

Media is placed **before** the text in the input list. These models are
trained with media tokens preceding the question, and the reverse order
measurably degrades the answer.

### Two differences from the text path

**Streaming is not token-by-token.** `Session` exposes
`generateContentStream`, but it is a callback API, and bridging a native
callback into a Flow correctly — cancellation, backpressure, errors — is a
second concurrency design that cannot be tested off device. The text path's
bridge took three attempts. So `stream: true` with media generates fully and
then emits valid SSE: the wire format is exactly what an OpenAI client
expects, it simply sees the answer in one content chunk.

**`max_tokens` truncates rather than caps.** The text path uses
`generateCapped`, which stops the model mid-stream so the phone never spends
the compute. That needs the same streaming bridge. Until then the multimodal
path generates in full and cuts, reporting `finish_reason: "length"` when it
does.

Both are real costs, written down rather than left to be discovered.

### Usage reporting

`usage` counts text tokens only, and says so with
`"media_tokens_counted": false`. Image and audio tokens are counted by the
native encoder and not surfaced to the Kotlin layer. Inventing a number
would corrupt every cost and throughput figure downstream.

---

## Why this replaces whisper.cpp

For hg_agent specifically. The previous arrangement ran whisper.cpp on CPU
inside a proot'd Fedora beside Medha, at roughly **3.5x realtime** — 85s for
a 24s clip.

That number was never a whisper limit. For reference, an RK3588 — a 2022
SBC, weaker than any phone running Medha — does `small` at RTF 0.47 and even
`medium` at 1.51. The likely cause was core parking: a proot'd process is
not a foreground Android app, so the scheduler parks it on the little cores,
which on a big.LITTLE SoC is a 3–4x hit by itself.

But the fix is not tuning whisper. The decisive constraint is memory. With
Gemma resident the device has about **1.7 GB free**, and whisper's peak RSS
is ~889 MB for `small` and ~2.14 GB for `medium`. `medium` does not fit at
all; `small` barely does. A whisper-in-Medha design would therefore have
meant either no concurrent STT and inference, or staying on `base`/`small`
forever and accepting their modest Telugu accuracy.

The encoder behind `/v1/audio/transcriptions` is already in memory. There is
no second model, no container, and no arbitration problem — audio requests
go through the same `InferenceScheduler` as everything else, so transcription
and generation queue against one engine instead of fighting for the SoC.

---

## Telugu

The transcription instruction names the language and the script explicitly
when `language` is set. Left to autodetect, the model will sometimes
transliterate Telugu into Latin characters, which is a different and much
less useful output than a Telugu-script transcript. Pass `language=te`.

Medha also strips the conversational scaffolding a chat-tuned model wraps
around a transcript — `Transcript: "…"`, `Sure, here is the transcript:` —
so every client does not write that cleanup slightly differently.

---

## What is still unverified

Honest list, as of this writing.

**Settled on a real device** (SM8850, `gemma-4-E4B-it.litertlm`, v0.11.1):

- The weights **do** carry both encoders. `/health` reported
  `vision: true, audio: true`.
- The audio tower will not run on the GPU on this model; it runs on CPU.
  Vision was demoted along with it in v0.11.1, which is what rung 2 now
  exists to avoid.

**Still open:**

- **Whether vision runs on the GPU here.** Rung 2 is new and untested on
  hardware. If it takes, `multimodal_mode` reads
  `vision on GPU, audio on CPU` and image encoding gets the GPU back.
- **Real transcription quality in Telugu.** Unknown, and not something this
  side can estimate.
- **Encoder throughput.** No measurement of how long a 30s clip actually
  takes through the CPU audio path, which is the number that decides
  whether this beats the 3.5x-realtime whisper setup it replaces. It
  should, comfortably, but "should" is not a measurement.
- **Thermal behaviour under sustained transcription.** The scheduler gates
  batch work on thermal headroom, but no one has run a long transcription
  session on a warm device.
