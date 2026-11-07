# Play Protect

## As of v0.9.0, the warning this document was written about no longer applies

The warning was:

```
App blocked to protect your device
This app can request access to sensitive data.
This can increase the risk of identity theft or financial fraud.
```

Play Protect shows that on a **sideloaded** app whose manifest declares SMS
permissions. It fires on the manifest alone — before the app runs, and
regardless of whether the feature is ever used.

Medha declared `READ_SMS`, `SEND_SMS` and `READ_CONTACTS` for its SMS
connector, which tripped it. The whole `core`/`full` product-flavour split
existed to quarantine those permissions into a build you only installed if
you wanted the connector.

**The SMS connector was removed in v0.9.0, along with the flavour split.**
Medha's manifest now declares no sensitive-data permissions at all:

```
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS
WAKE_LOCK
INTERNET
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

No SMS, no contacts, no location, no camera, no external storage. There is
nothing left for that particular warning to fire on, and `verify.sh` has an
absence guard that fails the build if an SMS permission or capability is
reintroduced.

## What you may still see

Sideloading an APK is still sideloading. Android may show either of these,
and neither is the warning above:

- **"Unknown app"** / install-from-this-source prompts, the first time you
  install from a browser or file manager. Normal for any APK not from Play.
- **"Play Protect doesn't recognise this app"**, with Install anyway. This is
  about the signature being unknown to Google, not about permissions. It
  appears for every self-signed build and there is no way to avoid it short
  of publishing through Play.

If you ever see the *sensitive data* wording again on a Medha build, something
has been added back that should not have been — check the manifest and run
`./verify.sh`.

## Why `INTERNET` is declared

It looks wrong on an app whose entire point is that nothing leaves the device.
It is required anyway: Android gates the `socket()` syscall on
`android.permission.INTERNET`, and **loopback is not exempt**. Without it,
binding to `127.0.0.1` fails at runtime with `socket failed: EPERM`.

This cost real debugging time once, in the hello-medha sample, where a code
comment had confidently asserted the opposite. The server binds to
`127.0.0.1` and nothing else; `verify.sh` checks the permission is present
for exactly this reason.
