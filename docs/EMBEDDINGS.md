# Vector RAG — wiring an embedder

Medha now supports dense retrieval. It is **off by default** and the app behaves
exactly as before until you enable it.

---

## What changed, and why the interface had to move

The old hook was:

```kotlin
Retriever(db, embedder = { text -> floatArrayOf(...) })
```

That cannot express a modern retrieval model. EmbeddingGemma is **asymmetric** —
it wants a different instruction prefix depending on which side of the search
the text is on:

| side | prefix |
|---|---|
| query | `task: search result \| query: ` |
| document | `title: <title\|none> \| text: ` |

Using the wrong prefix, or none, does not throw. It quietly costs recall. Silent
quality loss is the worst failure mode a retrieval system has, so the asymmetry
is now in the type:

```kotlin
interface Embedder {
    val id: String          // "embeddinggemma-300m@768" — the embedding SPACE
    val dimensions: Int
    suspend fun embedQuery(text: String): FloatArray?
    suspend fun embedDocument(text: String, title: String? = null): FloatArray?
}
```

The trailing space in both prefixes is part of the spec. Do not tidy it away.

---

## Retrieval is hybrid, not "vector instead of lexical"

Dense retrieval is strong on paraphrase and weak on **exact tokens**. Embeddings
of `order AX-99213` and `order AX-99871` sit almost on top of each other,
because the model encodes *"an order reference"* and not the digits.

For your SMS organizer that is the common case, not an edge case: OTP codes,
tracking numbers, account digits, short names. So Medha runs both and fuses them
with **Reciprocal Rank Fusion**:

```
score(d) = Σ  1 / (60 + rank_in_list(d))
```

RRF reads only *ranks*, never scores. That matters because cosine similarity and
IDF live on incomparable scales, and any fixed weighting between them is a magic
number that silently stops being correct when the corpus changes.

`/rag/query` reports which mode ran: `hybrid`, `vector`, or `lexical`.

---

## Storage changes (schema v3)

| | v2 | v3 |
|---|---|---|
| vector format | comma-separated TEXT | float32 LE **BLOB** |
| size per 768-dim vector | ~9 KB + parse per query | 3 KB, no parsing |
| model provenance | none | `embeddingModel`, `embeddingDim` |

**The provenance column is the important one.** Without it, swapping embedders
or changing Matryoshka dimensions leaves old vectors that still decode fine and
still produce a similarity number — computed in the wrong space. Plausible
nonsense. Queries are now scoped with `embeddingModel = ?`, so stale vectors are
simply invisible until reindexed.

Byte format is little-endian float32, matching the AI Edge RAG SDK's SQLite
vector store, so the two are binary-compatible.

**Migration v2 → v3 drops old vectors but keeps every chunk of text.** Their
provenance was unknowable, so re-embedding is the only honest option. Nothing
you ingested is lost; run `POST /rag/reindex` to refill.

---

## Enabling it

**1. Uncomment two lines in `app/build.gradle.kts`:**

```kotlin
implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
implementation("com.google.mediapipe:tasks-genai:0.10.27")
```

The `packaging { jniLibs { pickFirsts } }` block is already there and is
required: `localagents-rag` ships its own copies of the LiteRT native libraries
and so does `litertlm-android`. Two AARs contributing `libLiteRt.so` is a merge
conflict without it.

**2. Push the model and tokenizer** to the app's files dir under `models/embed/`:

```bash
adb push embeddinggemma-300m_seq256_f32.tflite \
  /sdcard/Android/data/com.adabala.medha/files/models/embed/
adb push sentencepiece.model \
  /sdcard/Android/data/com.adabala.medha/files/models/embed/
```

Models: `litert-community/embeddinggemma-300m` on Hugging Face. Gecko variants
also work — smaller and ~2.6× faster, less accurate.

**3. Restart Medha.** `/system` will report `vectorSearch: true` and
`embeddingModel: "<name>@768"`.

**4. Backfill existing chunks:**

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{}' \
     http://127.0.0.1:8080/rag/reindex
# {"embedded":64,"remaining":112,"status":"more remaining","model":"..."}
```

Batched at 64 per call so a large collection never monopolises the device.
Repeat until `remaining` is 0.

---

## Sequence length vs chunk size

Medha chunks to ~600 characters ≈ 150 tokens, which fits the 256-token model
variants. If you raise `TARGET_CHARS` without also moving to a 512/1024-token
model, the embedder **silently truncates** and indexes only the head of each
chunk. That is invisible until retrieval quality drops.

---

## Memory — the real constraint

EmbeddingGemma-300M in fp32 is well over a gigabyte. Alongside a resident
2B-class generation model, that will OOM most mid-range phones. Use a quantized
variant and expect to trade some accuracy.

The service releases **both** models on `TRIM_MEMORY_COMPLETE`; releasing only
the LLM would have accomplished much less than it appeared to.

---

## New endpoints

```
POST /v1/embeddings   {"input":["..."], "input_type":"query"|"document"}
POST /rag/reindex     {"collection":"optional"}
```

`input_type` is a Medha extension. OpenAI's schema has no field for retrieval
asymmetry, and defaulting silently to the query prefix would corrupt an indexing
pipeline — so the default is `document`.

---

## What is verified, and what is not

**Executed and passing (26 assertions):** prefix strings exact including
trailing spaces; L2 normalisation including the zero-vector case; cosine via dot
product; float32 LE round-trip at 768 dims; the 3072-byte size claim;
little-endian byte order; the model-identity guard excluding stale and
unembedded rows; RRF fusion — items in both lists rank first, single-sided
fusion, topK, no duplicates, and specifically that an exact-identifier chunk
survives fusion.

**Not verified:** the AI Edge RAG SDK binding itself. `AiEdgeEmbedder` reaches
the SDK by reflection and tries several known method and constructor shapes,
because that surface has changed between releases. If none match it logs and
returns `NoEmbedder` — RAG stays lexical rather than the app failing. **This is
the part to test first on device**, and the native-library conflict is the most
likely problem.

Watch `logcat -s AiEdgeEmbedder` on startup: it says explicitly whether it found
the model files, found the SDK, and constructed the delegate.
