# Hello Medha — a minimal integration sample

A complete, working example of another app using Medha's on-device model:
request access, get a token, stream an answer. Two Kotlin files, no
dependencies beyond `appcompat`, and no dependency on Medha's own code.

## Running it

This is a **standalone Gradle project**, not a module of the Medha build —
open `samples/hello-medha/` directly in Android Studio rather than the repo
root. (Keeping it separate means a broken sample can never fail Medha's own
build, and CI never compiles a demo on every push.)

1. Build and install Medha, start the service, load a model.
2. Open this folder in Android Studio and run it on the same device.
3. Tap **Connect to Medha** → the consent dialog appears → Allow.
4. Type a question and tap **Ask**.

## What each part demonstrates

| File | Shows |
|---|---|
| `MainActivity.kt` | The handshake, token persistence, and every error path worth handling |
| `MedhaClient.kt` | Chat, streaming SSE, and RAG ingest over plain `HttpURLConnection` |

Both are meant to be copied. `MedhaClient.kt` in particular is written to be
lifted into a real project with nothing but a package rename.

## Three things that will bite you if you skip them

**`<queries>` in the manifest.** On Android 11+, `resolveActivity()` returns
null for a package you haven't declared visibility for — so the sample would
report "Medha is not installed" on any modern device even when it is. This is
the single most common way a working integration breaks on upgrade.

**`startActivityForResult`, not `startActivity`.** Medha identifies the caller
via `getCallingPackage()`, which the platform only populates for a
result-returning launch. A plain `startActivity` gets refused with
`unidentified_caller`, because Medha would otherwise have to show a consent
screen reading "some app wants access", which isn't informed consent.

**Buffer SSE until a blank line.** A socket read is under no obligation to
align with event boundaries — one read can carry half an event or two and a
half. Parsing whatever each read returns produces corruption that only appears
under load, which is the worst time to find it. `MedhaClient.chatStream` uses
`readLine()` and treats a blank line as the terminator.

## Error handling

`MedhaClient.ApiException` classifies what came back, because the right
response differs a lot:

| Condition | What it means | What to do |
|---|---|---|
| `isUnauthorized` (401) | Token revoked or rotated | Drop the stored token, re-run the handshake |
| `isForbidden` (403) | Missing a capability | Don't retry — request the capability instead |
| `retryAfterSeconds` (429) | Queue full | Wait the stated time |
| `isTransient` (503/504) | No model loaded, or timed out | Retry later, back off |

The sample handles all four. The 401 path in particular clears the saved
token — otherwise the app would retry forever with a credential that will
never work again.

## Not verified on a device

Written and reviewed, never run. If something doesn't work, the most likely
culprits in order: the Gradle plugin versions above not matching your
installed Android Studio, `<queries>` visibility, and the consent activity's
`launchMode` (it must stay `standard` — `singleTask` makes
`startActivityForResult` return `RESULT_CANCELED` instantly).
