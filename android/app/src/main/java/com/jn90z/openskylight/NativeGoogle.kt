package com.jn90z.openskylight

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.security.MessageDigest

internal class NativeGoogle(private val context: Context, private val backend: Backend) {
    private val main = Handler(Looper.getMainLooper())

    fun available(): Boolean = GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    fun connect(id: Int, activity: MainActivity, request: JSONObject) {
        val scope = request.getString("scope")
        val intent = AccountPicker.newChooseAccountIntent(
            AccountPicker.AccountChooserOptions.Builder()
                .setAllowableAccountsTypes(listOf("com.google"))
                .setAlwaysShowAccountPicker(true)
                .build()
        )
        try {
            activity.pickGoogleAccount(intent) { result ->
                val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (result.resultCode != Activity.RESULT_OK || email.isNullOrBlank()) {
                    backend.complete(id, null, "Google account selection was cancelled")
                } else {
                    authorize(id, scope, email, activity, null)
                }
            }
        } catch (_: Exception) {
            backend.complete(id, null, "Could not open the Google account picker")
        }
    }

    fun token(id: Int, request: JSONObject) {
        main.post {
            authorize(id, request.getString("scope"), request.getString("email"), null,
                request.optString("expiredToken").takeIf(String::isNotEmpty))
        }
    }

    private fun authorize(id: Int, scope: String, email: String, activity: MainActivity?, expired: String?) {
        var finished = false
        fun finish(data: JSONObject?, error: String?) {
            if (finished) return
            finished = true
            backend.complete(id, data, error)
        }
        fun finishGrant(result: AuthorizationResult) {
            val token = result.accessToken
            if (token.isNullOrBlank() || scope !in result.grantedScopes.orEmpty()) {
                finish(null, "Google Calendar access was not granted")
            } else {
                finish(JSONObject().put("email", email).put("token", token), null)
            }
        }
        val timeout = Runnable { finish(null, "Google authorization timed out") }
        main.postDelayed(timeout, if (activity == null) 30_000 else 280_000)
        val client = if (activity == null) Identity.getAuthorizationClient(context) else Identity.getAuthorizationClient(activity)
        val request = AuthorizationRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setRequestedScopes(listOf(Scope(scope)))
            .build()

        fun runAuthorization() {
            client.authorize(request).addOnSuccessListener { result ->
                if (finished) return@addOnSuccessListener
                val resolution = result.pendingIntent
                if (!result.hasResolution()) {
                    main.removeCallbacks(timeout)
                    finishGrant(result)
                } else if (activity == null || resolution == null) {
                    main.removeCallbacks(timeout)
                    finish(null, "Reconnect this Google account in Settings")
                } else {
                    try {
                        activity.resolveGoogleAuthorization(resolution) { response ->
                            if (finished) return@resolveGoogleAuthorization
                            val failure = try {
                                val grant = client.getAuthorizationResultFromIntent(response.data)
                                main.removeCallbacks(timeout)
                                finishGrant(grant)
                                return@resolveGoogleAuthorization
                            } catch (error: ApiException) {
                                error
                            }
                            if (response.resultCode == Activity.RESULT_OK || failure.statusCode in setOf(7, 10)) {
                                main.removeCallbacks(timeout)
                                finish(null, errorMessage(failure))
                                return@resolveGoogleAuthorization
                            }

                            // A consent UI can close without returning a usable result Intent.
                            // Check the grant for the selected account once, without reopening consent.
                            client.authorize(request).addOnSuccessListener { retry ->
                                main.removeCallbacks(timeout)
                                if (retry.hasResolution()) finish(null, incompleteAuthorization(failure))
                                else finishGrant(retry)
                            }.addOnFailureListener { error ->
                                main.removeCallbacks(timeout)
                                finish(null, errorMessage(error))
                            }
                        }
                    } catch (_: Exception) {
                        main.removeCallbacks(timeout)
                        finish(null, "Could not open Google authorization")
                    }
                }
            }.addOnFailureListener { error ->
                main.removeCallbacks(timeout)
                finish(null, errorMessage(error))
            }
        }
        try {
            if (expired.isNullOrEmpty()) runAuthorization()
            else client.clearToken(ClearTokenRequest.builder().setToken(expired).build())
                .addOnCompleteListener { if (!finished) runAuthorization() }
        } catch (_: Exception) {
            main.removeCallbacks(timeout)
            finish(null, "Google Play services could not authorize Calendar")
        }
    }

    private fun incompleteAuthorization(error: ApiException): String {
        return "Google did not return a Calendar grant (status ${error.statusCode}). " +
            "If you approved access, check the Android OAuth client for ${context.packageName} " +
            "and this APK’s signing SHA-1${signingFingerprint()}."
    }

    @Suppress("DEPRECATION")
    private fun signingFingerprint(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
            ?: return@runCatching ""
        val digest = MessageDigest.getInstance("SHA-1").digest(signer)
        ": " + digest.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }.getOrDefault("")

    private fun errorMessage(error: Exception): String {
        val code = (error as? ApiException)?.statusCode
        return when (code) {
            10 -> "Google rejected this Android OAuth configuration. Register ${context.packageName} " +
                "with this APK’s signing SHA-1${signingFingerprint()}."
            7 -> "Could not reach Google. Check the tablet's internet connection."
            else -> "Google could not authorize Calendar${code?.let { " ($it)" }.orEmpty()}. Reconnect the account."
        }
    }
}
