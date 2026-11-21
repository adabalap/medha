# Hello Medha — a minimal integration sample

A complete, working example of another app using Medha's on-device model:
request access, get a token, stream an answer. Two Kotlin files, no
dependencies beyond `appcompat`, and no dependency on Medha's own code.

## Building it — no desktop needed

This is a **standalone Gradle project**, not a module of the Medha build.
Keeping it separate means a mistake in a demo app can never stop Medha itself
from building — which matters a lot when CI is the only build path you have.

**From your phone, via GitHub Actions:**

1. Push this repo. `.github/workflows/build-sample.yml` triggers on any change
   under `samples/hello-medha/`, or run it manually from the Actions tab
   (**Build sample APK** → *Run workflow*).
2. Download the **`hello-medha-apk`** artifact from the finished run.
3. Unzip it on the phone and tap `hello-medha-debug.apk` to install. You may
   need to allow installs from your browser or file manager the first time.

It uses the same Gradle wrapper, AGP and Kotlin versions as Medha itself, so
if Medha builds, the toolchain is already known to work here.

**On a desktop, if you have one:** open `samples/hello-medha/` directly (not
the repo root) in Android Studio, or run `./gradlew assembleDebug` in that
folder.

## Running it

1. Install and start Medha, and load a model.
2. Install this sample on the same device.
3. Tap **Connect to Medha** → the consent dialog appears → Allow.
4. Type a question and tap **Ask**.

Both apps must be on the same device — Medha binds to `127.0.0.1`, so nothing
off-device can reach it.

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

Written and reviewed, never built or run anywhere. The first CI run is the
real test. If it fails, likely culprits in order:

| Symptom | Probable cause |
|---|---|
| Build fails at configuration | AGP/Kotlin/Gradle mismatch — these are pinned to the same versions Medha uses, so compare against the root `build.gradle.kts` if they've since diverged |
| "Medha is not installed" on device | `<queries>` package visibility, or Medha genuinely isn't installed |
| Consent dialog never appears, instant cancel | The consent activity's `launchMode` must stay `standard`; `singleTask` makes `startActivityForResult` return `RESULT_CANCELED` immediately |
| 403 after allowing | A capability was filtered out — check what `GRANTED_CAPABILITIES` actually came back with |
