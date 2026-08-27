package com.example.hellomedha

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pulls plain text out of the document formats people actually have, so it
 * can be pushed into a Medha RAG collection.
 *
 * Medha's `/rag/ingest` takes text, not files, and deliberately so: the
 * server has no business growing a parser for every format on earth, and
 * doing extraction client-side means the phone never has to hand a whole
 * binary to another process. That puts the parsing burden here.
 *
 * ## What each format costs
 *
 * - **txt / md / csv / json** — already text. Read and go.
 * - **docx / xlsx** — these are ZIP archives containing XML. Unzipping and
 *   stripping tags needs nothing beyond `java.util.zip`, which is in the
 *   platform. Crude compared to a real Office parser, and it will not
 *   reproduce table structure or formatting — but for RAG the target is
 *   *retrievable prose*, not fidelity, so crude is genuinely fine here.
 * - **pdf** — the one format with no cheap path. PDF stores glyph positions,
 *   not paragraphs, so reconstructing reading order is a real algorithm and
 *   not something to hand-roll. Hence the one third-party dependency in this
 *   sample.
 * - **doc / xls (legacy binary)** — not supported. These are undocumented
 *   binary formats where a half-working parser produces plausible-looking
 *   garbage, which is worse than a clear "unsupported" for a RAG corpus.
 */
object DocumentExtractor {

    class UnsupportedFormat(val name: String) : Exception("Cannot read $name")

    data class Extracted(val displayName: String, val text: String, val chunks: Int)

    /** Roughly 600 characters, matching the chunk size Medha itself targets. */
    private const val CHUNK_CHARS = 600

    fun extract(context: Context, uri: Uri): Extracted {
        val name = displayName(context, uri)
        val lower = name.lowercase()
        val text = context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw UnsupportedFormat(name)
            when {
                lower.endsWith(".pdf") -> fromPdf(context, input)
                lower.endsWith(".docx") -> fromDocx(input)
                lower.endsWith(".xlsx") -> fromXlsx(input)
                lower.endsWith(".txt") || lower.endsWith(".md") ||
                    lower.endsWith(".csv") || lower.endsWith(".tsv") ||
                    lower.endsWith(".json") || lower.endsWith(".log") ->
                    input.readBytes().toString(Charsets.UTF_8)
                lower.endsWith(".doc") || lower.endsWith(".xls") ->
                    throw UnsupportedFormat("$name (legacy binary Office format)")
                else -> throw UnsupportedFormat(name)
            }
        }
        val cleaned = text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (cleaned.isBlank()) throw UnsupportedFormat("$name (no extractable text)")
        return Extracted(name, cleaned, (cleaned.length + CHUNK_CHARS - 1) / CHUNK_CHARS)
    }

    /**
     * Splits on paragraph boundaries where possible rather than every N
     * characters, because a chunk cut mid-sentence retrieves badly: the
     * fragment matches a query but reads as nonsense once it lands in the
     * model's context.
     */
    fun chunk(text: String, target: Int = CHUNK_CHARS): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (para in text.split(Regex("\n\\s*\n"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (buf.isNotEmpty() && buf.length + p.length > target) {
                out.add(buf.toString()); buf.setLength(0)
            }
            // A single paragraph longer than the target still has to be split,
            // otherwise one wall-of-text section becomes one huge chunk that
            // crowds everything else out of the prompt.
            if (p.length > target * 2) {
                p.chunked(target).forEach { out.add(it) }
            } else {
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(p)
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out.filter { it.isNotBlank() }
    }

    private fun fromPdf(context: Context, input: InputStream): String {
        // PdfBox-Android needs its resource loader primed before any parsing;
        // skipping this throws deep inside the library with an unhelpful
        // message about missing fonts.
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input).use { doc ->
            return PDFTextStripper().apply { sortByPosition = true }.getText(doc)
        }
    }

    /** word/document.xml holds the body; <w:p> are paragraphs, <w:t> the runs. */
    private fun fromDocx(input: InputStream): String =
        unzipEntry(input, setOf("word/document.xml"))
            .joinToString("\n") { xml ->
                xml.replace(Regex("</w:p>"), "\n")
                    .let { stripTags(it) }
            }

    /**
     * xlsx keeps most cell text in a shared-strings table, with the sheets
     * holding indices into it — so both parts are needed, and the shared
     * strings alone are usually the readable content.
     */
    private fun fromXlsx(input: InputStream): String =
        unzipEntry(input, setOf("xl/sharedStrings.xml"))
            .joinToString("\n") { xml ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(xml)
                    .map { unescape(it.groupValues[1]) }
                    .joinToString("\n")
            }

    private fun unzipEntry(input: InputStream, wanted: Set<String>): List<String> {
        val found = mutableListOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in wanted) found.add(zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return found
    }

    private fun stripTags(xml: String): String =
        unescape(xml.replace(Regex("<[^>]+>"), ""))

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment ?: "document"
    }
}
