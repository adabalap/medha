package com.adabala.medha.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.adabala.medha.InferenceService
import com.adabala.medha.R
import com.adabala.medha.diag.Diagnostics
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows the consent screen for [MedhaAccessContract.ACTION_REQUEST_ACCESS] and,
 * on approval, mints a namespace-scoped [ClientRegistry] client for the calling
 * app.
 *
 * This is the security boundary for third-party integration, so the ordering of
 * checks below is deliberate and worth preserving:
 *
 * 1. **Identify the caller before anything else.** [getCallingPackage] is
 *    derived by the platform from the calling UID and cannot be forged, but it
 *    is only populated when the caller used `startActivityForResult`. A null
 *    here means we genuinely do not know who is asking, so we refuse rather
 *    than render a consent screen that would have to say "some app wants
 *    access" — that is not informed consent, and a user tapping Allow on it
 *    has not actually agreed to anything specific.
 * 2. **Resolve the display name from PackageManager**, never from an extra.
 *    Otherwise any app could pass `EXTRA_APP_NAME = "Your Bank"` and the
 *    consent screen would faithfully lie to the user.
 * 3. **Filter capabilities to [MedhaAccessContract.GRANTABLE]** before showing
 *    them, so the screen only ever offers what can actually be granted, and
 *    `admin` cannot be obtained by asking for it.
 * 4. **Derive the namespace from the verified package**, so an app cannot
 *    request someone else's data scope.
 *
 * `launchMode` is deliberately left at the default `standard` in the manifest:
 * `singleTask` and `singleInstance` cause `startActivityForResult` to return
 * `RESULT_CANCELED` immediately, which would break every integration in a way
 * that looks like the user instantly declined.
 */
class AccessRequestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val caller = callingPackage
        if (caller.isNullOrBlank()) {
            Diagnostics.w(TAG, "access request with no verifiable caller; refusing")
            return finishWith(MedhaAccessContract.ERROR_UNIDENTIFIED_CALLER)
        }

        val requested = intent
            .getStringArrayExtra(MedhaAccessContract.EXTRA_CAPABILITIES)
            ?.toSet()
            .orEmpty()
            .filter { it in MedhaAccessContract.GRANTABLE }
            .toSortedSet()

        if (requested.isEmpty()) {
            Diagnostics.w(TAG, "access request from $caller with no grantable capability")
            return finishWith(MedhaAccessContract.ERROR_INVALID_REQUEST)
        }

        val registry = ClientRegistry.get(this)
        val namespace = MedhaAccessContract.namespaceFor(caller)
        val existing = registry.all().firstOrNull { it.id == namespace }

        // Re-request with nothing new: hand back the existing grant without
        // prompting. An app that was killed and lost its token, or that simply
        // asks again on every launch, should not produce a consent dialog the
        // user has already answered -- that trains people to tap Allow
        // reflexively, which is exactly what makes consent screens useless.
        if (existing != null && existing.capabilities.containsAll(requested)) {
            return grant(existing)
        }

        showConsent(caller, namespace, requested, existing)
    }

    private fun showConsent(
        caller: String,
        namespace: String,
        requested: Set<String>,
        existing: ClientRegistry.Client?
    ) {
        val label = appLabel(caller)
        val reason = intent.getStringExtra(MedhaAccessContract.EXTRA_REASON)
            ?.trim()
            ?.take(MAX_REASON_CHARS)
            ?.takeIf { it.isNotEmpty() }

        val body = buildString {
            append(getString(R.string.consent_body, label))
            append("\n\n")
            requested.forEach { cap -> append("  •  ").append(capLabel(cap)).append('\n') }
            if (reason != null) {
                // Shown as the app's own claim, clearly attributed, so it can
                // never read as something Medha is asserting on its behalf.
                append('\n').append(getString(R.string.consent_reason, label, reason)).append('\n')
            }
            append('\n').append(getString(R.string.consent_scope, namespace))
            if (existing != null) {
                append("\n\n").append(getString(R.string.consent_expanding))
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.consent_title, label))
            .setIcon(R.drawable.ic_key)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_allow) { _, _ ->
                val client = if (existing != null) {
                    ClientRegistry.get(this)
                        .setCapabilities(existing.id, existing.capabilities + requested)
                } else {
                    runCatching {
                        ClientRegistry.get(this).create(
                            id = namespace,
                            name = label,
                            namespace = namespace,
                            capabilities = requested,
                            origin = ClientRegistry.Origin.APP
                        )
                    }.onFailure { Diagnostics.e(TAG, "could not create client for $caller", it) }
                        .getOrNull()
                }
                if (client == null) finishWith(MedhaAccessContract.ERROR_INVALID_REQUEST)
                else grant(client)
            }
            .setNegativeButton(R.string.consent_deny) { _, _ ->
                finishWith(MedhaAccessContract.ERROR_DENIED)
            }
            .show()
    }

    private fun grant(client: ClientRegistry.Client) {
        val port = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(InferenceService.KEY_PORT, InferenceService.DEFAULT_PORT)
            ?.toIntOrNull()
            ?.takeIf { it in 1024..65535 }
            ?: InferenceService.DEFAULT_PORT.toInt()

        Diagnostics.i(TAG, "granted ${client.capabilities.sorted()} to ${client.id}")
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(MedhaAccessContract.EXTRA_TOKEN, client.token)
                putExtra(MedhaAccessContract.EXTRA_BASE_URL, "http://127.0.0.1:$port")
                putExtra(
                    MedhaAccessContract.EXTRA_GRANTED_CAPABILITIES,
                    client.capabilities.sorted().toTypedArray()
                )
                putExtra(MedhaAccessContract.EXTRA_NAMESPACE, client.namespace)
            }
        )
        finish()
    }

    private fun finishWith(error: String) {
        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(MedhaAccessContract.EXTRA_ERROR, error)
        )
        finish()
    }

    /**
     * The caller's real, user-visible name as the launcher shows it. Falls back
     * to the raw package name rather than to anything the caller supplied — a
     * bare package name is less friendly but still honest.
     */
    private fun appLabel(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun capLabel(cap: String): String = when (cap) {
        ClientRegistry.Cap.GENERATE -> getString(R.string.cap_generate)
        ClientRegistry.Cap.MEMORY -> getString(R.string.cap_memory)
        ClientRegistry.Cap.RAG -> getString(R.string.cap_rag)
        ClientRegistry.Cap.STORE -> getString(R.string.cap_store)
        else -> cap
    }

    private companion object {
        const val TAG = "AccessRequest"
        const val MAX_REASON_CHARS = 160
    }
}
