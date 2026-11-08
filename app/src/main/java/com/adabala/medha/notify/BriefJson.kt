package com.adabala.medha.notify

import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk form of the brief: `org.json` in, [BriefPolicy.Section] out,
 * and back.
 *
 * ## Why this is its own file
 *
 * It started inside [NotificationHub], which also touches `Context`,
 * `NotificationManager` and `NotificationCompat` — so nothing in it could be
 * compiled, let alone run, without the Android SDK. That matters because this
 * file contains the **v0.9.0 → v0.9.1 migration**, and a migration that is
 * only reasoned about is a migration that silently wipes someone's data.
 *
 * `org.json` is not Android-only: it is a plain Java library with a published
 * API, so this file compiles against a stub with the same signatures and the
 * migration can be *executed* in a test. See tools/tests/BriefJsonTest.kt.
 *
 * Nothing here decides anything. Merge, expiry, ordering and bounds all live
 * in [BriefPolicy]. This only translates.
 */
object BriefJson {

    /**
     * Reads the stored brief, accepting both shapes.
     *
     * **The legacy branch is a migration, not defensive padding.** Up to
     * v0.9.0 the widget key held a single last-writer-wins blob:
     *
     * ```json
     * {"clientId":"journal","updatedAt":1699123,"items":[{"title":"…"}]}
     * ```
     *
     * Anyone upgrading has exactly that on disk. Parsing only the new
     * `{"sections":[…]}` shape would return an empty list, so the home-screen
     * widget would go blank on upgrade — and the user could not tell that
     * from "no app has posted yet", which is the worst kind of data loss:
     * invisible and indistinguishable from normal.
     *
     * A legacy blob carries no `expiresAt`, so it is given the default TTL
     * measured from when it is read. It survives the upgrade and then ages
     * out normally. Dating it from its original `updatedAt` instead would
     * expire a blob older than a day the instant the user upgraded, which is
     * the same blank widget by a different route.
     *
     * Returns an empty list rather than throwing on malformed input: a
     * corrupt preference value should cost the brief, not crash the widget
     * host on every home-screen draw.
     */
    fun parse(raw: String, now: Long): List<BriefPolicy.Section> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()

        val arr = root.optJSONArray("sections")
            ?: return legacy(root, now)

        val out = mutableListOf<BriefPolicy.Section>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val items = items(o.optJSONArray("items"))
            if (items.isEmpty()) continue
            out += BriefPolicy.Section(
                clientId = o.optString("clientId", "").ifBlank { UNKNOWN },
                updatedAt = o.optLong("updatedAt", now),
                expiresAt = o.optLong("expiresAt", now + BriefPolicy.DEFAULT_TTL_MS),
                items = items.take(BriefPolicy.MAX_ITEMS_PER_SECTION)
            )
        }
        return out
    }

    /** The pre-v0.9.1 single-blob shape. See [parse]. */
    private fun legacy(root: JSONObject, now: Long): List<BriefPolicy.Section> {
        val items = items(root.optJSONArray("items"))
        if (items.isEmpty()) return emptyList()
        return listOf(
            BriefPolicy.Section(
                clientId = root.optString("clientId", "").ifBlank { UNKNOWN },
                updatedAt = root.optLong("updatedAt", now),
                expiresAt = now + BriefPolicy.DEFAULT_TTL_MS,
                items = items.take(BriefPolicy.MAX_ITEMS_PER_SECTION)
            )
        )
    }

    private fun items(arr: JSONArray?): List<BriefPolicy.Item> {
        if (arr == null) return emptyList()
        val out = mutableListOf<BriefPolicy.Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "")
            if (title.isBlank()) continue
            // Only http(s) links are kept. An item is written by a remote
            // client and opened by a tap on the user's home screen, so a
            // `intent:` or `file:` URL here would be a way for one app to
            // aim the user at something Medha never intended to launch.
            val link = o.optString("link", "").takeIf { isSafeLink(it) }
            out += BriefPolicy.Item(title, o.optString("text", ""), link)
        }
        return out
    }

    /** The current shape. Always `{"sections":[…]}`. */
    fun encode(sections: List<BriefPolicy.Section>): String {
        val arr = JSONArray()
        sections.forEach { s ->
            val items = JSONArray()
            s.items.forEach { i ->
                val o = JSONObject().put("title", i.title).put("text", i.text)
                if (i.link != null) o.put("link", i.link)
                items.put(o)
            }
            arr.put(
                JSONObject()
                    .put("clientId", s.clientId)
                    .put("updatedAt", s.updatedAt)
                    .put("expiresAt", s.expiresAt)
                    .put("items", items)
            )
        }
        return JSONObject().put("sections", arr).toString()
    }

    /**
     * True for a link the brief is willing to open.
     *
     * http and https only, and nothing else -- not `intent:`, which can
     * target an arbitrary component, nor `file:`, `content:` or any custom
     * scheme. The brief is written by remote clients and tapped on the
     * user's home screen, so the set of things a tap can launch has to be
     * the boring one. Case-insensitive because a client may send `HTTPS:`.
     */
    fun isSafeLink(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > MAX_LINK_LENGTH) return false
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // Reject anything with whitespace or control characters: a URL with
        // a newline in it is not a URL, and some parsers will silently take
        // only the first line.
        return s.none { it.isWhitespace() || it.code < 0x20 }
    }

    const val MAX_LINK_LENGTH = 2000

    /**
     * Shown when a stored section has no client id — only reachable from a
     * legacy blob written before ids were recorded, or a corrupt value.
     */
    const val UNKNOWN = "unknown"
}
