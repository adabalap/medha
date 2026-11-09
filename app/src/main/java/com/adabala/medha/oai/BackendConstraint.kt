package com.adabala.medha.oai

/**
 * Reads a LiteRT-LM backend-constraint rejection out of a JNI error
 * message.
 *
 * ## Why this distinction is worth code
 *
 * `LlmEngine`'s init ladder cannot tell, from the fact that a rung failed,
 * *why* it failed — and two reasons mean opposite things to a caller:
 *
 *  - **The model declares a requirement.** `.litertlm` containers carry a
 *    `backend_constraint` key per modality (parsed in LiteRT-LM's
 *    `schema/model_info/model_info.cc`, enforced by
 *    `ValidateBackendConstraint` in `runtime/engine/engine_settings.cc`).
 *    `gemma-4-E4B-it` declares `cpu` for audio. Running the audio encoder
 *    on CPU is then not a fallback at all — it is the only configuration
 *    these weights permit, and reporting it as a degradation describes a
 *    non-problem that can never be resolved.
 *  - **Something actually went wrong.** Out of memory, a missing kernel, a
 *    corrupt file. Worth surfacing, worth acting on.
 *
 * v0.11.3 reported both the same way, as "running on X rather than the
 * configured backend". For a constraint that wording is simply false:
 * there was no more capable configuration to lose.
 *
 * Parsing an error string is ordinarily a bad idea, and it is used here
 * only to *downgrade* a warning — never to claim a capability. A message
 * this does not recognise falls through to the pessimistic path, which is
 * the existing behaviour. The format is pinned by
 * tools/tests/MultimodalLadderTest.kt against the exact string a real
 * device produced.
 *
 * No Android types; every branch runs off device.
 */
object BackendConstraint {

    /**
     * A refusal by declared constraint.
     *
     * @param modality as LiteRT-LM names it: "Audio", "Vision", "Text".
     * @param allowed the backends the model permits, lowercased.
     * @param requested what was asked for.
     */
    data class Mismatch(
        val modality: String,
        val allowed: List<String>,
        val requested: String
    ) {
        /** True when [backend] is one the model permits. */
        fun permits(backend: String): Boolean =
            allowed.any { it.equals(backend, ignoreCase = true) }

        /** A sentence for /health, stating whose decision this is. */
        fun explain(): String =
            "the model's ${modality.lowercase()} encoder declares it requires " +
                "${allowed.joinToString(" or ") { it.uppercase() }}; " +
                "this is the configuration the weights ask for, not a fallback. " +
                "The constraint is stored in the .litertlm file by whoever packaged it, " +
                "so no setting on this side can change it."
    }

    // "Audio backend constraint mismatch. Model requires one of [cpu] but
    //  Audio backend is GPU"
    //
    // Deliberately tolerant of surrounding text: the real message arrives
    // wrapped in "LiteRtLmJniException: Failed to create engine:
    // INVALID_ARGUMENT: ...", and the wrapper has already changed shape
    // once between LiteRT-LM versions.
    private val RE = Regex(
        """(\w+) backend constraint mismatch\.\s*Model requires one of \[([^\]]*)]\s*but\s+\w+\s+backend is\s+(\w+)""",
        RegexOption.IGNORE_CASE
    )

    /** Returns the mismatch [message] describes, or null. */
    fun parse(message: String?): Mismatch? {
        if (message.isNullOrBlank()) return null
        val m = RE.find(message) ?: return null
        val allowed = m.groupValues[2]
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        // An empty allow-list is not a constraint anyone can satisfy, and
        // treating it as one would produce "requires ; this is the
        // configuration the weights ask for". Fall through to the generic
        // path instead.
        if (allowed.isEmpty()) return null
        return Mismatch(
            modality = m.groupValues[1].replaceFirstChar { it.uppercase() },
            allowed = allowed,
            requested = m.groupValues[3]
        )
    }
}
