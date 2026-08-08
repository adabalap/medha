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
- Medha also owns the permissioned data sources (SMS, contacts) and the
  notification surfaces, because a browser page cannot reach those.

**Do not write a Flask/Node/Python backend for your app.** Medha *is* the
backend. A second server on the phone means a second runtime, a second wake
lock, and no benefit.

---

## 2. Authentication — read this first

Every request except `/health` needs:

```
Authorization: Bearer <token>
```

Each app gets **its own token** from the Medha app: burger menu → **API
clients** → **Add client**. Give it an id like `sms-organizer`. The token is
shown once and copied to the clipboard.

### Namespacing is automatic and mandatory

A client with id `sms-organizer` operates in namespace `sms-organizer:*`.

- Session ids, RAG collections and store keys are **transparently prefixed**.
- You send `inbox`, Medha stores `sms-organizer:inbox`, and you read back
  `inbox`. The prefixing is invisible.
- **You cannot read another client's data**, and you cannot escape by sending a
  prefixed key. The namespace comes from the token, never from the request.

### Capabilities

| Capability | Grants |
|---|---|
| `generate` | `/generate`, `/generate/stream`, `/v1/chat/completions` |
| `memory` | `/chat`, `/sessions/*` |
| `rag` | `/rag/*`, `/v1/embeddings` |
| `store` | `/store/*` |
| `sms.read` | `/connectors/sms/*` reads |
| `sms.send` | `/connectors/sms/send` |
| `notify` | `/notify/*`, `/widget/content` |
| `admin` | everything, plus cross-namespace access |

New clients get `generate, memory, rag, store` by default. SMS and notify must
be granted deliberately.

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

### SMS connector

Requires `sms.read`. The user must also grant Medha the Android SMS permission
(Medha app → burger → SMS connector → Grant). Endpoints return `403` with code
`sms_denied` until then — surface that clearly rather than looking broken.

```
GET  /connectors/sms/status
       -> {canRead, canSend, isDefaultSmsApp, totalMessages}

GET  /connectors/sms/conversations?limit=&offset=
       -> {conversations:[{threadId, address, displayName, snippet,
                           messageCount, unreadCount, lastAt}]}

GET  /connectors/sms/messages?threadId=&since=&before=&unreadOnly=&limit=
       -> {messages:[{id, threadId, address, body, date, read, inbound}],
           nextBefore, count}

GET  /connectors/sms/messages/{id}
GET  /connectors/sms/contacts/{address}
POST /connectors/sms/mark-read      {threadId} or {ids:[...]}
POST /connectors/sms/send           {address, body}      needs sms.send
GET  /connectors/sms/events         SSE, fires on any change
```

**Paginate with `before`, never with `offset`.** Pass the `nextBefore` you got
back as the next `before`. New messages arriving during a backlog scan shift
every offset and cause duplicates or gaps; timestamps are stable.

```js
let before = null;
for (;;) {
  const q = new URLSearchParams({ limit: "100" });
  if (before) q.set("before", before);
  const page = await api("/connectors/sms/messages?" + q);
  if (!page.messages.length) break;
  await handle(page.messages);
  before = page.nextBefore;
}
```

**Do not copy message bodies into `/store`.** Store the message `id` plus what
you derived. The system SMS provider is the source of truth; a second copy is
another thing to secure and to keep in sync.

### Notifications and the home screen

```
GET    /notify/capabilities
         -> {sdk, liveUpdates, nowBarLikely, notifications, widget, nowBrief}
POST   /notify   {id, title, text, ongoing?, progressCurrent?, progressMax?, silent?}
DELETE /notify/{id}
PUT    /widget/content   {items:[{title, text}]}   up to 5
```

Call `/notify/capabilities` first — it tells you what this device can actually
do rather than making you branch on OS version.

- `ongoing: true` with progress asks the system to promote the notification into
  the status-bar chip and, on One UI 8+, Samsung's **Now Bar**.
- **Samsung's Now Brief cannot be targeted.** It has no public API.
  `capabilities.nowBrief` is always `false`. Do not design around it.
- `PUT /widget/content` fills Medha's home-screen widget. The user must place
  the widget once; after that any client with `notify` can update it.

---

## 5. Worked example: SMS organizer

```js
// 1. wait for the service
const h = await fetch(MEDHA + "/health").then(r => r.json());
if (!h.modelLoaded) return showBanner("Start Medha and load a model");

// 2. check SMS access
const sms = await api("/connectors/sms/status");
if (!sms.canRead) return showBanner("Grant SMS permission in the Medha app");

// 3. backlog pass — BATCH, gated and resumable
let before = null, done = 0;
for (;;) {
  const q = new URLSearchParams({ limit: "50" });
  if (before) q.set("before", before);
  const page = await api("/connectors/sms/messages?" + q);
  if (!page.messages.length) break;

  const results = [];
  for (const m of page.messages) {
    // skip anything already classified — makes the pass resumable
    const seen = await api("/store/msg/" + m.id).catch(() => null);
    if (seen) continue;

    const r = await batchCall("/generate", {
      system: "Reply with exactly one word: otp, bank, promo, personal, or other.",
      prompt: m.body.slice(0, 400)
    });
    results.push({
      key: "msg/" + m.id,
      value: JSON.stringify({ label: r.text.trim().toLowerCase(), at: m.date })
    });
  }
  if (results.length) await api("/store/bulk", {
    method: "POST", body: JSON.stringify({ items: results })
  });

  done += page.messages.length;
  before = page.nextBefore;

  await api("/notify", {                       // progress in the Now Bar
    method: "POST",
    body: JSON.stringify({
      id: "backlog", title: "Organising messages",
      text: done + " processed", ongoing: true,
      progressCurrent: done, progressMax: sms.totalMessages
    })
  });
}
await api("/notify/backlog", { method: "DELETE" });

// 4. steady state — new messages, INTERACTIVE (no header)
const es = new EventSource(MEDHA + "/connectors/sms/events");  // see note below
es.onmessage = () => refreshInbox();
```

> `EventSource` cannot set an `Authorization` header. Either use `fetch` +
> `ReadableStream` for SSE, or poll `/connectors/sms/messages?since=<lastDate>`
> every 30s. Polling is fine at that interval and much simpler.

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
6. **Truncate prompts.** Long SMS threads overflow a small model's context.
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
| 403 | `sms_denied` | Medha lacks the Android SMS permission |
| 404 | `not_found` | no such key/session/message |
| 413 | `too_large` | body over 16 MB, or value over 512 KB |
| 429 | `rejected` | queue full or batch gated — honour `Retry-After` |
| 500 | `internal_error` | unexpected |
| 501 | `not_implemented` | no embedding model loaded |
| 503 | `model_not_loaded` | model still loading, or none selected |

All errors are `{"error": "...", "code": "..."}`.
