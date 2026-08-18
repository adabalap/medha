/**
 * Standalone JVM test for [com.adabala.medha.rag.Embedder]'s pure functions:
 * the float32 LE codec, L2 normalisation, dot product, and prefix strings.
 *
 * Compiles and runs against the REAL source file
 * (app/src/main/java/com/adabala/medha/rag/Embedder.kt) — Embedder.kt has no
 * Android or kotlinx.coroutines dependency, so unlike AiEdgeEmbedder.kt (which
 * needs Context, reflection, and a real device to mean anything) this can be
 * verified on a bare JVM.
 *
 * Run with: tools/tests/run.sh
 */
import com.adabala.medha.rag.Embedder
import com.adabala.medha.rag.NoEmbedder
import kotlin.math.sqrt

private var failures = 0
private var total = 0

private fun check(label: String, condition: Boolean) {
    total++
    if (!condition) {
        failures++
        println("FAIL: $label")
    }
}

private fun approx(a: Double, b: Double, eps: Double = 1e-5) = kotlin.math.abs(a - b) < eps
private fun approx(a: Float, b: Float, eps: Float = 1e-5f) = kotlin.math.abs(a - b) < eps

fun main() {
    // --- Prefix strings: exact, including the documented trailing space ---
    // These are load-bearing: EmbeddingGemma is asymmetric and was trained on
    // this literal instruction format. A silently "tidied" space changes the
    // model's input distribution.
    check(
        "query prefix exact including trailing space",
        Embedder.QUERY_PREFIX == "task: search result | query: "
    )
    check(
        "document prefix with title",
        Embedder.documentPrefix("My Title") == "title: My Title | text: "
    )
    check(
        "document prefix with null title falls back to 'none'",
        Embedder.documentPrefix(null) == "title: none | text: "
    )
    check(
        "document prefix with blank title falls back to 'none'",
        Embedder.documentPrefix("   ") == "title: none | text: "
    )

    // --- L2 normalisation ---
    run {
        val v = floatArrayOf(3f, 4f)
        val n = Embedder.normalize(v)
        check("3-4-5 triangle normalises to unit length", approx(sqrt((n[0] * n[0] + n[1] * n[1]).toDouble()), 1.0))
        check("normalize(3,4) -> (0.6, 0.8)", approx(n[0], 0.6f) && approx(n[1], 0.8f))
    }
    run {
        // The documented special case: a zero vector must not produce NaN.
        val v = floatArrayOf(0f, 0f, 0f)
        val n = Embedder.normalize(v)
        check("zero vector returns unchanged, not NaN", n.all { it == 0f })
    }
    run {
        val v = floatArrayOf(-1f, -1f)
        val n = Embedder.normalize(v)
        check("negative components normalise correctly", approx(n[0], -0.70710677f) && approx(n[1], -0.70710677f))
    }

    // --- Dot product ---
    run {
        val a = Embedder.normalize(floatArrayOf(1f, 0f))
        val b = Embedder.normalize(floatArrayOf(1f, 0f))
        check("identical unit vectors dot to 1.0", approx(Embedder.dot(a, b), 1.0))
    }
    run {
        val a = Embedder.normalize(floatArrayOf(1f, 0f))
        val b = Embedder.normalize(floatArrayOf(0f, 1f))
        check("orthogonal unit vectors dot to 0.0", approx(Embedder.dot(a, b), 0.0))
    }
    run {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(1f, 2f)
        check("mismatched-length vectors dot to 0.0 rather than throwing", Embedder.dot(a, b) == 0.0)
    }

    // --- float32 LE codec round-trip ---
    run {
        val v = FloatArray(768) { i -> (i - 384) * 0.001234f }
        val encoded = Embedder.encode(v)
        check("768-dim vector encodes to exactly 3072 bytes", encoded.size == 3072)
        val decoded = Embedder.decode(encoded)
        check("decode(encode(v)) round-trips exactly at 768 dims", v.indices.all { approx(v[it], decoded[it], 1e-6f) })
    }
    run {
        // A Matryoshka-truncated variant must round-trip too — this is
        // exactly the case the AiEdgeEmbedder dimension-probe fix now
        // measures correctly instead of assuming 768.
        val v = FloatArray(256) { i -> i * 0.01f - 1f }
        val decoded = Embedder.decode(Embedder.encode(v))
        check("256-dim (Matryoshka) vector round-trips", v.indices.all { approx(v[it], decoded[it], 1e-6f) })
    }
    run {
        val v = FloatArray(0)
        check("empty vector encodes to 0 bytes", Embedder.encode(v).isEmpty())
        check("empty byte array decodes to empty vector", Embedder.decode(ByteArray(0)).isEmpty())
    }

    // --- NoEmbedder: the lexical-only fallback must be inert, not throw ---
    run {
        check("NoEmbedder.dimensions is 0", NoEmbedder.dimensions == 0)
        check("NoEmbedder.isReady is false", !NoEmbedder.isReady)
    }

    println("EmbedderTest: $total checks, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
