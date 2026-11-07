# Medha API — handover for PWA builders

Everything an app needs to know to build against Medha. Hand this whole file to
a code-generating assistant; it is written to be read cold.

---

## 1. What Medha is

A native Android service that keeps one LLM resident on the device's GPU/NPU and
exposes it over **loopback HTTP**. Apps that consume it are plain static
HTML/JS — **no backend, no server, no build step required**.

- Base URL: `http://127.0.0.1:8080` (port configurable in the Medha app)
- Nothing leaves the device. No cloud, no telemetry.
- Medha also brokers the **notification shade**, because a browser page
  cannot post to it. That is the one platform capability it hands over.

**Do not write a Flask/Node/Python backend for your app.** Medha *is* the
backend. A second server on the phone means a second runtime, a second wake
lock, and no benefit.

---

## 2. Authentication — read this first

Every request except `/health` needs:

```
Authorization: Bearer <token>
```

There are three ways to get a token, depending on what you are:

| You are | How you get a token |
|---|---|
| A **web app on another origin** | Pair: Medha → **Pair a web app** → six digits → `POST /pair` |
| An **installed Android app** | The intent handshake — see [INTEGRATION.md](INTEGRATION.md) |
| A **script, or the bundled demo** | Medha → **API clients** → **Add client**, copy the token |

Pairing is the one to use if you are a page in a browser. You cannot use the
intent handshake: it identifies callers by `getCallingPackage()`, which for a
web page would name the browser. See [NOTIFICATIONS.md](NOTIFICATIONS.md).

A paired token is **bound to the origin that paired it** and is refused from
any other, with `origin_mismatch`. Copying it into another site's page does
not work.

### Namespacing is automatic and mandatory

A client with id `journal` operates in namespace `journal:*`.

- Session ids, RAG collections and store keys are **transparently prefixed**.
- You send `inbox`, Medha stores `journal:inbox`, and you read back `inbox`.
  The prefixing is invisible.
- **You cannot read another client's data**, and you cannot escape by sending a
  prefixed key. The namespace comes from the token, never from the request.

### Capabilities

| Capability | Grants |
|---|---|
| `generate` | `/generate`, `/generate/stream`, `/v1/chat/completions` |
| `memory` | `/chat`, `/sessions/*` |
| `rag` | `/rag/*`, `/v1/embeddings` |
| `store` | `/store/*` |
| `notify` | `/notify/*`, `/widget/content` |
| `admin` | everything, plus cross-namespace access |

New clients get `generate, memory, rag, store` by default. `notify` must be
granted deliberately — it is grantable, but off by default and never implied.
`admin` cannot be obtained by asking at all.

### Boilerplate

```js
const MEDHA = "http://127.0.0.1:8080";
const TOKEN = localStorage.getItem("medha_token");

async function api(path, opts = {}) {
  const r = await fetch(MEDHA + path, {
    ...opts,
    headers: {
      "Content-Type": "application/json",
      "Authorization": "Bearer " + TOKEN,
      ...(opts.headers || {})
    }
  });
  if (!r.ok) {
    let msg = "HTTP " + r.status;
    try { const e = await r.json(); if (e.error) msg = e.error; } catch {}
    const err = new Error(msg);
    err.status = r.status;
    err.retryAfter = Number(r.headers.get("Retry-After") || 0);
    throw err;
  }
  return r.json();
}
```

---

## 3. Priority — get this right or you will heat the phone

Send this header on any bulk/background work:

```
X-Medha-Priority: batch
```

| | no header (default) | `batch` |
|---|---|---|
| Meaning | a human is waiting | background work |
| Thermal gating | never blocked | pauses when the SoC is hot |
| Battery gating | never blocked | can require charging |
| Queue order | ahead of batch | yields to interactive |

Classifying an inbox is `batch`. A user tapping "summarise this" is not.

**Handle `429`.** The queue is bounded. On `429`, back off for `Retry-After`
seconds. Do not retry in a tight loop.

```js
async function batchCall(path, body) {
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      return await api(path, {
        method: "POST",
        headers: { "X-Medha-Priority": "batch" },
        body: JSON.stringify(body)
      });
    } catch (e) {
      if (e.status !== 429) throw e;
      await new Promise(r => setTimeout(r, (e.retryAfter || 5) * 1000));
    }
  }
  throw new Error("still rejected after 5 attempts");
}
```

---

## 4. Endpoints

### Health and status

| Method | Path | Notes |
|---|---|---|
| `GET` | `/health` | no auth. `{status, modelLoaded, backend, busy}` |
| `GET` | `/system` | device, RAM, thermal, storage, embedding model |
| `GET` | `/metrics` | requests, tokens/sec, DB counts |
| `GET` | `/scheduler` | queue depth, batch paused, thermal headroom, battery |

Poll `/health` before your first real call so you can show "service not running"
instead of a failure.

### Generation

```
POST /generate            {prompt, system?}
  -> {text, promptTokens, tokens, ms, tokensPerSec}

POST /generate/stream     {prompt, system?}
  -> SSE. Each `data:` line is a JSON-encoded STRING delta. `data: [DONE]` ends.
```

### Chat with persistent memory

```
POST /chat  {sessionId, message, system?, collection?, ragTopK?}
  -> {text, sessionId, tokens, ms, tokensPerSec}
```

Medha stores the thread; you store only the id. Use a stable `sessionId` per
conversation. `collection` optionally injects RAG context into the turn.

```
GET    /sessions?limit=&offset=
GET    /sessions/{id}/messages?limit=
DELETE /sessions/{id}
```

### OpenAI-compatible

```
GET  /v1/models
POST /v1/chat/completions   {model?, messages[], stream?}
POST /v1/embeddings         {input[], input_type: "query"|"document"}
```

Point any OpenAI client at `http://127.0.0.1:8080/v1` with your Medha token as
the API key.

> `input_type` is a Medha extension. Retrieval models are **asymmetric** — a
> search query and an indexed passage need different prefixes. Default is
> `document`. Use `query` when embedding something you are searching *with*.
> `/v1/embeddings` returns `501` if no embedding model is loaded.

### RAG

```
POST   /rag/ingest              {collection, text, title?, source?}
                                -> {chunks, embedded, mode}
POST   /rag/query               {collection, query, topK}
                                -> {hits:[{text, score}], mode}
POST   /rag/reindex             {collection?}  backfill vectors
GET    /rag/collections
DELETE /rag/collections/{name}
```

`mode` is `hybrid`, `vector`, or `lexical`. Hybrid fuses dense similarity with
keyword search, which matters because embeddings are blurry on exact
identifiers — order numbers, OTP codes, account digits.

### Key-value store — where your app's data lives

**Browser storage is not safe for anything you care about.** IndexedDB is
quota-limited and *evictable*: Android may silently drop it under memory
pressure. Anything you paid inference time to produce belongs here.

```
PUT    /store/{key}        body: any JSON
GET    /store/{key}
DELETE /store/{key}
GET    /store?prefix=&limit=&offset=
POST   /store/bulk         {items:[{key, value}]}   up to 1000, one transaction
```

Keys may contain `/`. Value limit 512 KB.

```js
await api("/store/msg/12345", {
  method: "PUT",
  body: JSON.stringify({ label: "otp", confidence: 0.94, at: Date.now() })
});
const page = await api("/store?prefix=msg/&limit=100");
```

Use `/store/bulk` for batch results — one transaction instead of N.

### Notifications and the home screen

Full guide, with the browser rules that will cost you an hour:
[NOTIFICATIONS.md](NOTIFICATIONS.md).

```
POST   /pair     {code}   no auth, returns {token, clientId, namespace, capabilities}
GET    /notify/capabilities
         -> {sdk, liveUpdates, nowBarLikely, notifications, widget, nowBrief}
POST   /notify   {id, title, text, ongoing?, progressCurrent?, progressMax?, silent?}
DELETE /notify/{id}

PUT    /widget/content   {items:[{title, text}], ttlSeconds?}   your section of the brief
GET    /widget/content                                          read it back
DELETE /widget/content                                          withdraw it
```

**Samsung's Now Brief has no third-party API.** The One UI 9 custom cards are
authored by the user in Settings and generated by Samsung's own AI; no field
is writable by an app. `capabilities.nowBrief` is always `false` and carries a
`nowBriefReason`. Medha's brief — the home-screen widget above — is the
writable equivalent: each client owns a section, sections expire after 24h by
default, and rows fill round-robin so one app cannot crowd out the rest. It
shows three rows at a time and cycles through the rest while the screen is on;
tapping it opens every item from every app. The rotation interval is the
user's setting, not something a client can drive.

Rate limited to **10 in a burst, 30 a minute, per client** — `429` with
`Retry-After` over that. This is the only endpoint where you make the device
do something, and it is cheap to call, so a runaway interval is a bug the
shade has to survive.

`{"posted": false}` is not an error: the call worked and Android refused it,
which on API 33+ means the user has not granted Medha `POST_NOTIFICATIONS`.
The `reason` field says so.

Call `/notify/capabilities` first — it tells you what this device can actually
do rather than making you branch on OS version.

- `ongoing: true` with progress asks the system to promote the notification into
  the status-bar chip and, on One UI 8+, Samsung's **Now Bar**.
- **Samsung's Now Brief cannot be targeted.** It has no public API.
  `capabilities.nowBrief` is always `false`. Do not design around it.
- `PUT /widget/content` fills Medha's home-screen widget. The user must place
  the widget once; after that any client with `notify` can update it.

---

## 5. Worked example: a batch classifier

The shape below is the one to copy for any bulk pass: **BATCH priority, and
resumable by checking `/store` before spending inference**, because the user
will close the tab halfway through.

```js
// 1. wait for the service
const h = await fetch(MEDHA + "/health").then(r => r.json());
if (!h.modelLoaded) return showBanner("Start Medha and load a model");

// 2. backlog pass -- BATCH, gated and resumable
for (const item of itemsToClassify) {
  // Skip anything already done. This single line is what makes the pass
  // resumable, and it is the difference between a pass that survives being
  // interrupted and one that starts from zero every time.
  const seen = await api("/store/label/" + item.id).catch(() => null);
  if (seen) continue;

  const r = await api("/generate", {
    method: "POST",
    headers: { "X-Medha-Priority": "batch" },   // <- without this you heat the phone
    body: JSON.stringify({
      // Truncate. A long document overflows a small model's context and
      // 400-800 characters is plenty for a label.
      prompt: `Classify in one word: ${item.text.slice(0, 600)}`,
      maxTokens: 8
    })
  });

  await api("/store/label/" + item.id, {
    method: "PUT",
    body: JSON.stringify({ label: r.text.trim(), at: Date.now() })
  });
}

// 3. tell the user it finished, even if they switched away
await api("/notify", {
  method: "POST",
  body: JSON.stringify({ id: "backlog", title: "Classification finished",
                         text: `${itemsToClassify.length} items labelled` })
});
```

Handle `429` by honouring `Retry-After` rather than retrying immediately: it
means either the inference queue is full or batch work is thermally gated, and
both resolve on their own.

---

## 6. Rules of thumb

1. **One client id per app.** Do not share tokens between apps; it defeats
   namespacing.
2. **Header `X-Medha-Priority: batch` on anything bulk.** Without it you will
   heat the phone and block interactive requests.
3. **Handle `429` with `Retry-After`.** Never retry in a tight loop.
4. **Persist to `/store`, not IndexedDB.**
5. **Idempotent batch passes.** Check `/store` before spending inference; the
   user will close the tab mid-run.
6. **Truncate prompts.** Long documents overflow a small model's context.
   400–800 characters per classification is plenty.
7. **Degrade visibly.** If `/health` fails, say "Medha isn't running" rather
   than showing an empty screen.
8. **Timestamps, not offsets**, for any paginated list that grows.

---

## 7. Error codes

| Status | `code` | Meaning |
|---|---|---|
| 401 | `unauthorized` | missing/unknown token |
| 403 | `forbidden` | client lacks the capability |
| 403 | `origin_mismatch` | a paired token used from a different web origin |
| 403 | `wrong_code` / `code_burned` / `code_expired` / `no_pending_code` | pairing — see NOTIFICATIONS.md |
| 400 | `no_origin` | pairing attempted with no usable `Origin` (a `file://` page) |
| 404 | `not_found` | no such key/session/message |
| 413 | `too_large` | body over 16 MB, or value over 512 KB |
| 429 | `rejected` | queue full or batch gated — honour `Retry-After` |
| 429 | `rate_limited` | `/notify` budget spent — honour `Retry-After` |
| 500 | `internal_error` | unexpected |
| 501 | `not_implemented` | no embedding model loaded |
| 503 | `model_not_loaded` | model still loading, or none selected |

All errors are `{"error": "...", "code": "..."}`.
