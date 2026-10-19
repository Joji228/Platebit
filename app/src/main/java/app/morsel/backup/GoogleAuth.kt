package app.morsel.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Access to the user's own Google account through Google Play services. Platebit asks only for
 * its private app folder in Drive plus the account's name and email: it can't see any other file.
 * Nothing here needs a client secret; Google recognizes the app by its package name and signing key.
 */
class GoogleAuth(context: Context) {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    sealed interface Result {
        data class Token(val accessToken: String) : Result

        /** Google needs the user: pick an account or allow access. Launch [intent] from the screen. */
        data class NeedsUser(val intent: PendingIntent) : Result
    }

    private fun request(email: String?) = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES)
        .apply { if (email != null) setAccount(account(email)) }
        .build()

    /** With an [email], tries that account without showing anything when access was given before. */
    suspend fun authorize(email: String?): Result {
        val result = client.authorize(request(email)).await()
        val intent = result.pendingIntent
        if (result.hasResolution() && intent != null) return Result.NeedsUser(intent)
        return Result.Token(result.accessToken ?: throw BackupException("Google didn't give access"))
    }

    /** The answer after the user went through Google's screen. */
    fun tokenFrom(data: Intent?): String =
        client.getAuthorizationResultFromIntent(data).accessToken ?: throw BackupException("Google didn't give access")

    /** Drops a token Google no longer accepts, so the next [authorize] fetches a fresh one. */
    suspend fun forget(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    /** Signing out: Platebit loses access to the account until the user signs in again. */
    suspend fun revoke(email: String) {
        runCatching {
            client.revokeAccess(RevokeAccessRequest.builder().setAccount(account(email)).setScopes(SCOPES).build()).await()
        }
    }

    private fun account(email: String) = Account(email, "com.google")

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private val SCOPES = listOf(Scope(DRIVE_APPDATA), Scope(Scopes.EMAIL), Scope(Scopes.PROFILE))
    }
}

class BackupException(message: String) : Exception(message)
