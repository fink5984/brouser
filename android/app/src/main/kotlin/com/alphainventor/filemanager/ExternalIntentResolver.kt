package com.alphainventor.filemanager

import android.content.Context
import android.content.Intent
import android.net.Uri

data class ExternalIntentRequest(val uri: Uri, val intent: Intent, val description: String)

/**
 * Turns a non-http(s) URI the WebView tried to navigate to (tel:, mailto:,
 * geo:, intent:, market:, ...) into a safe, explicit Intent to hand to the
 * user for confirmation -- never launched automatically, and never for a
 * scheme/action we don't recognize.
 */
object ExternalIntentResolver {
    private val KNOWN_SCHEMES = setOf("tel", "mailto", "sms", "smsto", "geo", "market")

    fun resolve(context: Context, uri: Uri): ExternalIntentRequest? {
        val scheme = uri.scheme?.lowercase() ?: return null

        val intent = when {
            scheme == "intent" -> parseIntentScheme(uri)
            scheme in KNOWN_SCHEMES -> Intent(Intent.ACTION_VIEW, uri)
            else -> null
        } ?: return null

        // Never allow a resolved intent to target a specific component --
        // only ever a generic action+data that the system resolves itself.
        intent.component = null
        intent.setPackage(null)

        val resolvable = intent.resolveActivity(context.packageManager) != null
        if (!resolvable) return null

        return ExternalIntentRequest(uri, intent, describe(scheme, uri))
    }

    private fun parseIntentScheme(uri: Uri): Intent? = try {
        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).takeIf { it.action == Intent.ACTION_VIEW }
    } catch (_: Exception) {
        null
    }

    private fun describe(scheme: String, uri: Uri): String = when (scheme) {
        "tel" -> "Call ${uri.schemeSpecificPart}"
        "mailto" -> "Email ${uri.schemeSpecificPart}"
        "sms", "smsto" -> "Message ${uri.schemeSpecificPart}"
        "geo" -> "Open location in Maps"
        "market" -> "Open in Play Store"
        else -> "Open in another app"
    }
}
