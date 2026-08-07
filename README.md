# Medha · మేధా

On-device LLM inference service for Android. Loads a `.litertlm` model once via
the LiteRT-LM runtime and serves it over a loopback HTTP API so any app or PWA on
the device can consume it. Named for *medhā* (मेधा / మేధా) — intelligence with
retentive memory.

## Endpoints (http://127.0.0.1:<port>)

| Method | Path | Purpose |
|---|---|---|
| GET  | `/health` | status, model loaded, backend, last error |
| GET  | `/system` | device, SoC, configured backend, RAM, load time |
| GET  | `/metrics` | request count, avg/last tokens-per-sec, DB counts |
| POST | `/generate` | `{prompt, system?}` → `{text, tokens, ms, tokensPerSec}` |
| POST | `/generate/stream` | SSE token stream |
| POST | `/chat` | `{sessionId, message, system?, collection?, ragTopK?}` — multi-turn, SQLite-backed, optional RAG |
| POST | `/v1/chat/completions` | OpenAI-compatible (stream + non-stream) |
| GET  | `/v1/models` | OpenAI-compatible model list |
| POST | `/rag/ingest` | `{collection, text, title?, source?}` — chunk + store |
| POST | `/rag/query` | `{collection, query, topK}` → ranked chunks |
| GET  | `/` | bundled demo PWA (same origin — no CORS/cert issues) |

## Features

- **Persistent memory** — Room/SQLite stores conversations, messages, and RAG
  chunks; survives restarts. Pass a stable `sessionId` per consumer app.
- **RAG** — ingest/query with keyword retrieval today; drop in an embedding model
  (e.g. EmbeddingGemma) later and the retriever switches to cosine similarity
  automatically (chunks store embeddings when an embedder is wired in `Retriever`).
- **Metrics** — per-request timing + tokens/sec, aggregate averages, live in the app.
- **Backend selection** — GPU / CPU / NPU. Note: LiteRT-LM has no "which backend
  am I on" getter, so the UI reports the *configured* backend + whether init
  succeeded, rather than claiming an unverifiable value.
- **Hardened service** — foreground + wake lock + START_STICKY; battery-exemption
  prompt for OEM killers (Samsung etc).
- **Browsable model** — Storage Access Framework picker; the file is imported into
  app storage so the native runtime opens it by a stable path.

## Usage

1. Install the APK (sideload).
2. Open Medha → **Browse for .litertlm model** → pick your file (imported once).
3. Choose backend (GPU default) and port.
4. **Start Medha.** Watch the status card: loading → running.
5. Tap **Allow to run in background** so the OS doesn't kill it.
6. Any PWA/app can now call `http://127.0.0.1:<port>`.

## Build

Push to GitHub → the **Build Medha APK** workflow produces a debug APK in the run
Artifacts. Tag a release to also attach it as a Release asset. Local install only.

## Notes / limits
- Memory: E2B + long threads pressure RAM on mid-range devices; watch `/system`
  `lowMemory`. The `/chat` history is bounded to recent messages for this reason.
- NPU needs vendor libs (Qualcomm QAIRT / MediaTek NeuroPilot) bundled per device;
  exposed as an option but GPU/CPU are the safe defaults.
- Streaming uses the AAR's `sendMessageAsync` Flow when present, else falls back
  to a single emission.
