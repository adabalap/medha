# Integrating with Medha

> **Working example:** `samples/hello-medha/` is a complete, runnable app that
> does everything described here — handshake, token persistence, streaming,
> and every error path. It builds to a sideloadable APK via the **Build
> sample APK** GitHub Actions workflow — no desktop IDE needed. See
> `samples/hello-medha/README.md`.

Medha runs a local, OpenAI-compatible inference server on the device. Another
app on the same device can request scoped access to it at runtime, get a bearer
token, and then talk to it over plain HTTP.

Nothing here requires a network, an account, or a cloud round trip. The model
runs on the phone.

---

## 1. The handshake

Integration is a runtime consent flow, not a copy-pasted API key. The user sees
a dialog naming your app and exactly what it is asking for, and approves or
denies — the same shape as a runtime permission.

Copy `MedhaAccessContract.kt` into your project (it has no dependencies beyond
the JDK), or just inline the string constants below.

```kotlin
private val medha = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        val data = result.data ?: return@registerForActivityResult
        val token   = data.getStringExtra("com.adabala.medha.extra.TOKEN")
        val baseUrl = data.getStringExtra("com.adabala.medha.extra.BASE_URL")
        val granted = data.getStringArrayExtra("com.adabala.medha.extra.GRANTED_CAPABILITIES")
        // Persist the token. Do not request again on every launch.
    } else {
        when (result.data?.getStringExtra("com.adabala.medha.extra.ERROR")) {
            "denied" -> { /* user said no; degrade gracefully */ }
            else -> { /* see the error table below */ }
        }
    }
}

fun requestMedhaAccess() {
    val intent = Intent("com.adabala.medha.action.REQUEST_ACCESS").apply {
        // Explicit package: never let another app claim this action.
        setPackage("com.adabala.medha")
        putExtra("com.adabala.medha.extra.CAPABILITIES", arrayOf("generate", "rag"))
        putExtra("com.adabala.medha.extra.REASON", "To summarise your notes offline")
    }
    if (intent.resolveActivity(packageManager) == null) {
        // Medha is not installed. Send the user to install it, or fall back.
        return
    }
    medha.launch(intent)
}
```

**`startActivityForResult` is mandatory.** Medha identifies you by
`getCallingPackage()`, which the platform derives from your UID and which you
cannot forge — but it is only populated for a result-returning launch. A plain
`startActivity` produces a null calling package and Medha refuses the request
with `unidentified_caller`, because a consent screen that has to say "some app
wants access" is not informed consent.

### Capabilities

| Capability | Grants access to |
|---|---|
| `generate` | `/v1/chat/completions`, `/generate`, `/generate/stream`, `/chat` |
| `memory` | `/sessions/*` — server-side conversation history |
| `rag` | `/rag/*`, `/v1/embeddings`, and the `collection` field on chat calls |
| `store` | `/store/*` — a small key-value scratch space |

Ask for the narrowest set you actually need; the consent screen shows the user
every item, and a long list is a good reason for them to decline.

`admin` cannot be obtained through this flow, and neither can SMS or
notification access. Those are filtered out even if requested — admin bypasses
every namespace check server-side, and SMS/notifications are personal enough
that granting them should mean a deliberate trip through Medha's own UI, not a
dialog your app triggered.

### Errors

| `EXTRA_ERROR` | Meaning |
|---|---|
| `denied` | The user declined. Respect it; don't re-prompt in a loop. |
| `unidentified_caller` | You used `startActivity` instead of `startActivityForResult`. |
| `invalid_request` | No grantable capability was requested. |

### Re-requesting

Asking again with the same (or a subset of the same) capabilities returns the
existing grant **without prompting** — safe to call on launch if you lost your
token. Asking for something *new* re-prompts, and the dialog says plainly that
the app already has access and this would add to it.

---

## 2. Using the API

Once you have a token, it is an ordinary OpenAI-compatible endpoint.

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"model":"medha","messages":[{"role":"user","content":"Hello"}]}'
```

Streaming is standard SSE (`"stream": true`), terminated by `data: [DONE]`.
Buffer until a complete `\n\n`-delimited event before parsing — events can and
do split across network chunks.

### RAG

`collection` and `ragTopK` are a Medha extension on the OpenAI request shape.
Standard clients that never set them are unaffected.

```json
{
  "model": "medha",
  "messages": [{"role": "user", "content": "What did the doc say about refunds?"}],
  "collection": "notes",
  "ragTopK": 3
}
```

Ingest with `POST /rag/ingest`. Both require the `rag` capability.

### Useful endpoints

| Endpoint | Purpose |
|---|---|
| `GET /health` | Is a model loaded, is it busy |
| `GET /system` | Backend, memory, thermal, whether vector search is active |
| `GET /v1/models` | OpenAI-shaped model list |

Check `/health` before your first call: the user may have Medha installed but
not running, or no model loaded yet.

---

## 3. What your token can and cannot do

Every grant is scoped to a namespace derived from your package name. Your
sessions, RAG collections and key-value entries live under it, and the
namespace is taken from the token server-side — never from the request body —
so you cannot read another app's data by asking for a different prefix, and
they cannot read yours.

The namespace is `<readable-package>-<8 hex>`, where the hex is a digest of
your full package name. The digest is not decoration: sanitising a package name
into the allowed id character set collapses distinct packages onto the same
string (`com.foo.bar` and `com.foo-bar` both become `com-foo-bar`), and two
apps sharing a namespace would read each other's data.

The user can revoke you at any time from Medha's drawer → API clients. Handle
`401` by treating your stored token as dead and re-running the handshake.

---

## 4. Rate limits and back-pressure

One model, one device. Medha serialises inference and applies admission
control, so expect:

- `429` with a `Retry-After` header when the queue is full. Honour it.
- `503` when no model is loaded.
- `504` if a request waited longer than the configured timeout.

Under thermal or battery pressure, background-priority work is paused. Design
for a request that might take a while, and don't retry aggressively into a
`429` — you are competing with the foreground app the user is actually looking
at, possibly your own.
