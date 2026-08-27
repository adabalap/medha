package com.adabala.medha.auth

import java.security.MessageDigest

/**
 * The public contract another app on this device uses to request access to
 * Medha. Deliberately free of Android and Medha dependencies so a third-party
 * developer can copy this single file into their project verbatim rather than
 * taking a dependency on Medha itself.
 *
 * ## Why an Intent handshake rather than "paste a token"
 *
 * Before this existed, integrating meant: open Medha, create a client, copy a
 * 64-character token, paste it into the other app. No shipped consumer app can
 * reasonably ask that of a user, so in practice Medha was only consumable by
 * its own bundled demo and by whoever built it. This turns the same underlying
 * [ClientRegistry] grant into something an app can request at runtime with a
 * consent screen, the way it would request camera access.
 *
 * ## The flow
 *
 * ```kotlin
 * // 1. Is Medha even installed?
 * val intent = Intent(MedhaAccessContract.ACTION_REQUEST_ACCESS).apply {
 *     setPackage(MedhaAccessContract.MEDHA_PACKAGE)   // explicit: never let
 *                                                    // another app answer this
 *     putExtra(MedhaAccessContract.EXTRA_CAPABILITIES, arrayOf("generate", "rag"))
 *     putExtra(MedhaAccessContract.EXTRA_REASON, "To summarise your notes offline")
 * }
 * // Not installed -- fall back to whatever you do without Medha.
 * if (intent.resolveActivity(packageManager) == null) return
 *
 * // 2. MUST be startActivityForResult, not startActivity -- see below.
 * launcher.launch(intent)
 *
 * // 3. On RESULT_OK:
 * val token   = data.getStringExtra(MedhaAccessContract.EXTRA_TOKEN)
 * val baseUrl = data.getStringExtra(MedhaAccessContract.EXTRA_BASE_URL)
 * val granted = data.getStringArrayExtra(MedhaAccessContract.EXTRA_GRANTED_CAPABILITIES)
 * // then call the normal OpenAI-compatible HTTP API with
 * //   Authorization: Bearer $token
 * ```
 *
 * ## The trust basis, stated plainly
 *
 * Medha identifies the caller with `Activity.getCallingPackage()`, which the
 * platform derives from the calling UID — an app cannot lie about it. That is
 * *only* populated for `startActivityForResult`; a plain `startActivity` gives
 * a null calling package, and Medha rejects the request rather than guessing,
 * because a request from an unidentifiable caller cannot be shown honestly on
 * a consent screen ("an app wants access" is not informed consent).
 *
 * Two consequences worth knowing before you integrate:
 * - Always `setPackage(MEDHA_PACKAGE)` so the implicit action cannot be picked
 *   up by some other app that declared the same intent filter.
 * - The token you receive is scoped to *your* package. It cannot read another
 *   app's sessions or RAG collections, and it can never carry admin rights.
 */
object MedhaAccessContract {

    /**
     * Medha's *release* package name.
     *
     * Do not use this to target the app. Medha applies an
     * `applicationIdSuffix` per build variant, so the installed package is
     * any of `com.adabala.medha`, `com.adabala.medha.full`,
     * `com.adabala.medha.debug` or `com.adabala.medha.full.debug` — a
     * consumer has no way to know which one the user installed, and
     * hardcoding this constant makes the integration silently report "Medha
     * is not installed" against a perfectly working debug build.
     *
     * Resolve [ACTION_REQUEST_ACCESS] instead and read the package name off
     * the result. See [PACKAGE_PREFIX] and the snippet in
     * `docs/INTEGRATION.md`.
     */
    const val MEDHA_PACKAGE = "com.adabala.medha"

    /**
     * Every Medha variant's package name starts with this.
     *
     * Used to sanity-check a resolved package before trusting it: the
     * consent action is declared in an intent filter, and an implicit intent
     * can in principle be answered by any app that declares the same filter.
     * Checking the prefix means a resolver result from some unrelated app is
     * ignored rather than handed the request.
     *
     * Honest limit: a prefix check is not authentication. An app could
     * install itself as `com.adabala.medha.something` and match. The robust
     * version compares the resolved package's signing certificate against
     * Medha's known one — worth doing if you are shipping this to users
     * rather than running it on your own device.
     */
    const val PACKAGE_PREFIX = "com.adabala.medha"

    const val ACTION_REQUEST_ACCESS = "com.adabala.medha.action.REQUEST_ACCESS"

    /** `String[]` of requested capability strings. See [GRANTABLE]. */
    const val EXTRA_CAPABILITIES = "com.adabala.medha.extra.CAPABILITIES"

    /**
     * Optional `String`, one short sentence shown on the consent screen.
     * Treated as untrusted display text: it is the calling app's own claim
     * about why it wants access, shown alongside — never instead of — the
     * verified package name and the concrete capability list.
     */
    const val EXTRA_REASON = "com.adabala.medha.extra.REASON"

    /** `String` bearer token, on RESULT_OK. */
    const val EXTRA_TOKEN = "com.adabala.medha.extra.TOKEN"

    /** `String` e.g. `http://127.0.0.1:8080`, on RESULT_OK. */
    const val EXTRA_BASE_URL = "com.adabala.medha.extra.BASE_URL"

    /**
     * `String[]` actually granted, on RESULT_OK. May be a subset of what was
     * requested, so check it rather than assuming you got everything.
     */
    const val EXTRA_GRANTED_CAPABILITIES = "com.adabala.medha.extra.GRANTED_CAPABILITIES"

    /** `String` namespace this grant is scoped to, on RESULT_OK. Informational. */
    const val EXTRA_NAMESPACE = "com.adabala.medha.extra.NAMESPACE"

    /** `String` machine-readable failure reason, on RESULT_CANCELED. */
    const val EXTRA_ERROR = "com.adabala.medha.extra.ERROR"

    /** The user declined. */
    const val ERROR_DENIED = "denied"

    /** No verifiable caller — the request did not use startActivityForResult. */
    const val ERROR_UNIDENTIFIED_CALLER = "unidentified_caller"

    /** No usable capability was requested. */
    const val ERROR_INVALID_REQUEST = "invalid_request"

    /**
     * Capability wire values.
     *
     * Declared here as plain string literals rather than referencing
     * [ClientRegistry.Cap], because this file is meant to be copied verbatim
     * into a third-party project and must therefore compile with nothing but
     * the JDK. These are the same strings the server matches on; a test
     * (`tools/tests/AccessContractTest.kt`) asserts the two declarations
     * cannot drift apart.
     */
    const val CAP_GENERATE = "generate"
    const val CAP_MEMORY = "memory"
    const val CAP_RAG = "rag"
    const val CAP_STORE = "store"

    /**
     * Capabilities obtainable through this flow.
     *
     * `admin` is deliberately absent and is filtered out even if requested:
     * admin bypasses every namespace check in the server, so a third-party
     * app must never be able to obtain it by asking, no matter how the
     * consent screen is worded. SMS and notification access are likewise
     * excluded — those are the user's messages and their notification shade,
     * and granting them deserves a deliberate trip through Medha's own UI
     * rather than a generic consent dialog another app triggered.
     */
    val GRANTABLE: Set<String> = setOf(CAP_GENERATE, CAP_MEMORY, CAP_RAG, CAP_STORE)

    /**
     * Derives a stable, collision-resistant client id / namespace from a
     * verified package name.
     *
     * [ClientRegistry] ids must match `[a-z0-9][a-z0-9_-]{1,31}`, which a
     * package name never does — dots are illegal and the length rarely fits.
     * The obvious fix, "replace illegal characters and truncate", is unsafe
     * here: `com.foo.bar` and `com.foo-bar` both sanitise to `com-foo-bar`,
     * and two apps sharing a namespace can read each other's sessions and RAG
     * collections. Truncation makes it worse, since long package names that
     * differ only in a deep suffix collapse onto the same prefix.
     *
     * So the readable prefix is only for the human reading the client list;
     * uniqueness comes from an 8-hex-character SHA-256 digest of the *full*
     * original package name appended to it. Same package always derives the
     * same namespace (so a re-request finds the existing grant), and two
     * different packages effectively never share one.
     */
    fun namespaceFor(packageName: String): String {
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

        // The leading character must be alphanumeric per ID_RE; a package name
        // starting with a digit is fine, one starting with punctuation is not.
        val head = if (readable.first().isLetterOrDigit()) readable else "app-$readable"
        return "$head-$digest".take(MAX_ID_LENGTH)
    }

    private const val MAX_ID_LENGTH = 32
}
