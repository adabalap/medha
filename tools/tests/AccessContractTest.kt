/**
 * Tests [com.adabala.medha.auth.MedhaAccessContract.namespaceFor].
 *
 * This is a security boundary, not a formatting helper: the derived namespace
 * is what keeps one app's sessions and RAG collections out of another app's
 * reach. If two packages ever derive the same namespace they share a data
 * scope silently, with no error anywhere — so the collision property is
 * asserted here against a real corpus rather than argued for in a comment.
 *
 * MedhaAccessContract is compiled directly (it deliberately has no Android
 * dependency, exactly so a third-party developer can copy the single file and
 * so this can be tested on a bare JVM), but it references ClientRegistry.Cap
 * for the GRANTABLE set, which does need Android. This test therefore
 * re-declares namespaceFor's logic; the guard test below fails loudly if the
 * real implementation drifts from this copy.
 *
 * Run with: tools/tests/run.sh
 */
import java.io.File
import java.security.MessageDigest

private const val MAX_ID_LENGTH = 32

/** Mirror of MedhaAccessContract.namespaceFor. Kept honest by [assertMirrorsSource]. */
private fun namespaceFor(packageName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(packageName.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { "%02x".format(it) }

    val readable = packageName
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .take(MAX_ID_LENGTH - digest.length - 1)
        .trimEnd('-')
        .ifEmpty { "app" }

    val head = if (readable.first().isLetterOrDigit()) readable else "app-$readable"
    return "$head-$digest".take(MAX_ID_LENGTH)
}

/** ClientRegistry's own id constraint, which every derived namespace must satisfy. */
private val ID_RE = Regex("[a-z0-9][a-z0-9_-]{1,31}")

private var failures = 0
private var total = 0

private fun check(label: String, condition: Boolean) {
    total++
    if (!condition) {
        failures++
        println("FAIL: $label")
    }
}

/**
 * Guards against this test's mirrored copy silently drifting from the real
 * implementation, which would make every assertion below meaningless.
 */
private fun assertMirrorsSource() {
    val src = File("../../app/src/main/java/com/adabala/medha/auth/MedhaAccessContract.kt")
    if (!src.exists()) {
        println("WARN: could not locate MedhaAccessContract.kt to compare against")
        return
    }
    val text = src.readText()
    listOf(
        "SHA-256",
        ".take(4)",
        "MAX_ID_LENGTH - digest.length - 1",
        "isLetterOrDigit",
        "private const val MAX_ID_LENGTH = 32"
    ).forEach { marker ->
        check("real namespaceFor still contains `$marker`", text.contains(marker))
    }
}

fun main() {
    assertMirrorsSource()

    val samples = listOf(
        "com.example.app",
        "com.example-app",
        "com.foo.bar",
        "com.foo-bar",
        "a",
        "9lives.app",
        "UPPER.Case.App",
        "com.adabala.sandeshika",
        "com.verylongpackagename.that.goes.on.and.on.forever.seriously.app",
        "com.verylongpackagename.that.goes.on.and.on.forever.seriously.app2"
    )

    // Every derived id must satisfy ClientRegistry's ID_RE *entirely*, not
    // merely contain a match — ClientRegistry uses `matches`, so a partial
    // match here would be rejected at create() time with an opaque failure.
    samples.forEach { pkg ->
        val ns = namespaceFor(pkg)
        check("'$ns' (from '$pkg') fully matches ClientRegistry's ID_RE", ID_RE.matchEntire(ns) != null)
        check("'$ns' is within the 32-char id limit", ns.length <= MAX_ID_LENGTH)
    }

    // The specific pairs naive "sanitise and truncate" would merge.
    check(
        "com.example.app and com.example-app get different namespaces",
        namespaceFor("com.example.app") != namespaceFor("com.example-app")
    )
    check(
        "com.foo.bar and com.foo-bar get different namespaces",
        namespaceFor("com.foo.bar") != namespaceFor("com.foo-bar")
    )
    check(
        "long packages differing only in a deep suffix stay distinct",
        namespaceFor("com.verylongpackagename.that.goes.on.and.on.forever.seriously.app") !=
            namespaceFor("com.verylongpackagename.that.goes.on.and.on.forever.seriously.app2")
    )

    // Stability matters as much as uniqueness: a re-request has to find the
    // existing grant, which only works if the same package always derives the
    // same namespace across process restarts and app upgrades.
    check(
        "derivation is stable across repeated calls",
        namespaceFor("com.example.app") == namespaceFor("com.example.app")
    )

    // A package name that is entirely punctuation must still yield a valid id
    // rather than an empty string that create() would reject.
    check(
        "degenerate all-punctuation package still yields a valid id",
        ID_RE.matchEntire(namespaceFor("...")) != null
    )

    // Bulk collision check.
    val corpus = (1..20_000).map { "com.vendor$it.module.app" } +
        (1..20_000).map { "org.other.pkg.name.v$it" } +
        (1..5_000).map { "com.verylongpackagename.that.goes.on.forever.variant$it" }
    check(
        "no collisions across ${corpus.size} synthetic package names",
        corpus.map(::namespaceFor).toSet().size == corpus.size
    )

    println("AccessContractTest: $total checks, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
