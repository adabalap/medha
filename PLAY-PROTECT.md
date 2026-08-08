# Play Protect blocked the install — what to do

```
App blocked to protect your device
This app can request access to sensitive data.
This can increase the risk of identity theft or financial fraud.
```

**Nothing is wrong with the APK.** This is Play Protect's standard response to a
**sideloaded** app that **declares SMS permissions**. It fires on the manifest
alone — before the app runs, and regardless of whether you ever enable the
connector.

Two things made it trigger, and one of them was a mistake:

1. `READ_SMS` / `SEND_SMS` were added for the connector. This is the main cause,
   and it is unavoidable for a build that reads SMS.
2. **`RECEIVE_SMS` was declared but never used.** The connector detects new
   messages with a `ContentObserver`, which needs only `READ_SMS`. That
   permission bought nothing and is the one Play Protect treats most harshly.
   **Removed in v0.7.0.**

A debug-signed APK makes it worse: the Android debug key is publicly known and
shared by every debug build in the world, so Play Protect has no reason to trust
it.

---

## The fix: two builds

From v0.7.0 CI produces **two APKs per build type**:

| APK | SMS permissions | Play Protect |
|---|---|---|
| `medha-0.7.0-core-debug-<sha>.apk` | **none** | installs cleanly |
| `medha-0.7.0-full-debug-<sha>.apk` | `READ_SMS`, `READ_CONTACTS`, `SEND_SMS` | will warn |

**Install `core` unless you actually need the SMS connector.** Everything else —
inference, chat memory, RAG, `/store`, notifications, the widget — is identical.

They carry different application ids (`com.adabala.medha` and
`com.adabala.medha.full`), so both can sit on the device at once and installing
`full` never silently replaces a clean `core` install.

`GET /connectors/sms/status` reports `supported: false` on `core`, so a PWA can
detect it and show a useful message instead of failing.

---

## Installing the `full` build anyway

### Best option: adb (no Play Protect prompt)

```bash
adb install -r medha-0.7.0-full-debug-<sha>.apk
```

The install goes through the shell rather than the package installer UI, so the
warning dialog never appears. Nothing is disabled and nothing stays weakened.

### If you only have the phone

The dialog you saw has a single **OK** button — this is the hard-block variant,
with no "install anyway". You have to turn scanning off for the moment:

1. **Play Store** → profile icon → **Play Protect** → gear icon
2. Turn off **Scan apps with Play Protect**
3. Install the APK
4. **Turn it back on.** Leaving it off removes scanning for every app you ever
   install, which is a much worse trade than one deliberate sideload.

Play Protect may still show a "harmful app" notification afterwards. Choosing to
keep the app is fine; it will not uninstall it behind you.

### Making it stop for good

Sign a release build with your own keystore (see the README section on debug vs
release). A consistently-signed release APK from a stable key attracts far less
suspicion than a debug-key build, though an SMS-permission sideload may still be
flagged.

---

## Is the warning legitimate?

Yes, as a category. An app that can read your SMS can read bank OTPs. Play
Protect cannot tell Medha apart from something malicious, so it warns about the
capability rather than the intent — which is the correct default for a system
protecting millions of people who did not read the source.

What Medha actually does with it:

- SMS is **read on demand** from the system provider. **Message bodies are never
  copied into Medha's database.** Clients store derived labels against message
  IDs.
- The connector requires the `sms.read` capability on a per-client token; a PWA
  without it gets `403`.
- Nothing leaves the device. The server binds to `127.0.0.1` only.
- Reading requires you to grant the runtime permission inside Medha, separately
  from installing.

You are choosing to trust code you can read. That is a different decision from
the one Play Protect is defending against — but it is your decision to make
consciously, not one to click past.
