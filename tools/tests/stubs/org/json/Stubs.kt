// Stand-ins for the org.json API Android ships, with the SAME signatures and
// a small real parser, so BriefJson can be EXECUTED here rather than only
// type-checked.
//
// WHY: BriefJson holds the v0.9.0 -> v0.9.1 brief migration. If the new
// parser does not recognise the old single-blob shape, every existing
// install's home-screen widget goes blank on upgrade -- and a blank widget is
// indistinguishable from "nothing posted yet", so nobody would report it.
// That is not something to reason about and ship.
//
// org.json is a plain Java library, not an Android one, which is the whole
// reason BriefJson was split out of NotificationHub: it can be compiled and
// run off-device.
//
// Signatures mirrored from Android's org.json:
//   JSONObject(String)                      throws JSONException
//   JSONObject.optJSONArray(String)      -> JSONArray or null
//   JSONObject.optJSONObject(String)     -> JSONObject or null
//   JSONObject.optLong(String, long)     -> long
//   JSONObject.optString(String, String) -> String (non-null)
//   JSONArray.length()                   -> int
//   JSONArray.optJSONObject(int)         -> JSONObject or null
//
// These are stubs, not a conformant implementation: they cover the shapes the
// brief actually stores. toString() emits JSON so encode/parse round trips.
package org.json

class JSONException(message: String) : Exception(message)

class JSONObject {
    internal val map = LinkedHashMap<String, Any?>()

    constructor()

    constructor(source: String) {
        val p = JsonReader(source)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != source.length) throw JSONException("trailing input at " + p.i)
        if (v !is JSONObject) throw JSONException("not an object")
        map.putAll(v.map)
    }

    fun put(name: String, value: Any?): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Long): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Int): JSONObject {
        map[name] = value
        return this
    }

    fun put(name: String, value: Boolean): JSONObject {
        map[name] = value
        return this
    }

    fun opt(name: String): Any? = map[name]

    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray

    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject

    fun optLong(name: String, fallback: Long): Long {
        val v = map[name]
        return when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            else -> fallback
        }
    }

    /**
     * Mirrors the real one's surprising behaviour: a JSON null does NOT
     * return the fallback, it stringifies to the four characters "null".
     * That exact quirk caused a real bug in ClientRegistry, so the stub
     * reproduces it rather than being helpfully saner than Android.
     */
    fun optString(name: String, fallback: String): String {
        if (!map.containsKey(name)) return fallback
        val v = map[name] ?: return "null"
        return if (v is String) v else v.toString()
    }

    fun optInt(name: String, fallback: Int): Int {
        val v = map[name]
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            else -> fallback
        }
    }

    fun optBoolean(name: String, fallback: Boolean): Boolean {
        val v = map[name]
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> fallback
        }
    }

    fun isNull(name: String): Boolean = !map.containsKey(name) || map[name] == null

    override fun toString(): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(",")
            first = false
            sb.append(jsonQuote(k)).append(":").append(jsonRender(v))
        }
        return sb.append("}").toString()
    }
}

class JSONArray {
    internal val list = ArrayList<Any?>()

    constructor()

    fun put(value: Any?): JSONArray {
        list.add(value)
        return this
    }

    fun length(): Int = list.size

    fun optJSONObject(index: Int): JSONObject? = list.getOrNull(index) as? JSONObject

    override fun toString(): String {
        val sb = StringBuilder("[")
        for ((n, v) in list.withIndex()) {
            if (n > 0) sb.append(",")
            sb.append(jsonRender(v))
        }
        return sb.append("]").toString()
    }
}

internal fun jsonQuote(s: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

internal fun jsonRender(v: Any?): String = when (v) {
    null -> "null"
    is String -> jsonQuote(v)
    is JSONObject -> v.toString()
    is JSONArray -> v.toString()
    is Number -> v.toString()
    is Boolean -> v.toString()
    else -> jsonQuote(v.toString())
}

/** Minimal recursive-descent reader; enough for the shapes the brief stores. */
internal class JsonReader(private val s: String) {
    var i = 0

    fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    fun value(): Any? {
        ws()
        if (i >= s.length) throw JSONException("unexpected end")
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> num()
        }
    }

    private fun obj(): JSONObject {
        val o = JSONObject()
        i++
        ws()
        if (i < s.length && s[i] == '}') {
            i++
            return o
        }
        while (true) {
            ws()
            val k = str()
            ws()
            if (i >= s.length || s[i] != ':') throw JSONException("expected colon at " + i)
            i++
            o.map[k] = value()
            ws()
            if (i < s.length && s[i] == ',') {
                i++
            } else if (i < s.length && s[i] == '}') {
                i++
                return o
            } else {
                throw JSONException("expected comma or close-brace at " + i)
            }
        }
    }

    private fun arr(): JSONArray {
        val a = JSONArray()
        i++
        ws()
        if (i < s.length && s[i] == ']') {
            i++
            return a
        }
        while (true) {
            a.list.add(value())
            ws()
            if (i < s.length && s[i] == ',') {
                i++
            } else if (i < s.length && s[i] == ']') {
                i++
                return a
            } else {
                throw JSONException("expected comma or close-bracket at " + i)
            }
        }
    }

    private fun str(): String {
        if (i >= s.length || s[i] != '"') throw JSONException("expected string at " + i)
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw JSONException("unterminated string")
            val c = s[i]
            if (c == '"') break
            if (c == '\\') {
                i++
                if (i >= s.length) throw JSONException("unterminated escape")
                when (s[i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 > s.length) throw JSONException("short unicode escape")
                        sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                        i += 4
                    }
                    else -> throw JSONException("bad escape at " + i)
                }
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        i++
        return sb.toString()
    }

    private fun num(): Any {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' ||
                s[i] == '.' || s[i] == 'e' || s[i] == 'E')) {
            i++
        }
        val raw = s.substring(start, i)
        val asLong = raw.toLongOrNull()
        if (asLong != null) return asLong
        val asDouble = raw.toDoubleOrNull()
        if (asDouble != null) return asDouble
        throw JSONException("bad number '" + raw + "'")
    }

    private fun expect(lit: String) {
        if (!s.startsWith(lit, i)) throw JSONException("expected " + lit + " at " + i)
        i += lit.length
    }
}
