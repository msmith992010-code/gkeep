package com.gkeep.app.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gkeep.app.data.Prefs
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** Thrown when Google needs the user to pick an account / approve Gmail access on screen. */
class NeedsConsentException(val pendingIntent: PendingIntent) :
    Exception("Gmail access needs your approval")

/**
 * Gets OAuth access tokens for the Gmail API using Google Play services.
 * Tokens are short-lived (~1h); Play services refreshes them silently once access is granted.
 */
class GmailAuth(context: Context, private val prefs: Prefs) {

    private val client = Identity.getAuthorizationClient(context.applicationContext)

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt = 0L

    suspend fun token(): String {
        cachedToken?.let { if (System.currentTimeMillis() - cachedAt < TOKEN_TTL_MS) return it }
        val builder = AuthorizationRequest.builder().setRequestedScopes(SCOPES)
        prefs.accountEmail?.let { builder.setAccount(Account(it, "com.google")) }
        val result = client.authorize(builder.build()).await()
        if (result.hasResolution()) {
            throw NeedsConsentException(result.pendingIntent!!)
        }
        return remember(result.accessToken ?: error("Google returned no access token"))
    }

    /** Call with the Intent returned from the consent screen. */
    fun handleConsentResult(data: Intent?) {
        val result = client.getAuthorizationResultFromIntent(data)
        result.accessToken?.let { remember(it) }
    }

    fun clear() {
        cachedToken = null
        cachedAt = 0L
    }

    private fun remember(token: String): String {
        cachedToken = token
        cachedAt = System.currentTimeMillis()
        return token
    }

    companion object {
        private const val TOKEN_TTL_MS = 40 * 60 * 1000L
        val SCOPES = listOf(
            // Read message metadata + move messages to Trash
            Scope("https://www.googleapis.com/auth/gmail.modify"),
            // Create/delete Gmail filters for auto-deleted senders
            Scope("https://www.googleapis.com/auth/gmail.settings.basic"),
        )
    }
}
