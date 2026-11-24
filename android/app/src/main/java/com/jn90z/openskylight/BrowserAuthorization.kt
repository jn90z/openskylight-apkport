package com.jn90z.openskylight

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.InetAddress

/** PKCE and state are generated inside the trusted backend. The browser only
 * receives the public client ID, a challenge and a loopback redirect. */
internal class BrowserAuthorization(private val backend: Backend) {
    private val main = Handler(Looper.getMainLooper())

    fun start(id: Int, activity: Activity, data: JSONObject) {
        val state = data.getString("state")
        val deadline = SystemClock.elapsedRealtime() + 300_000
        var server: LocalHttpServer? = null
        var finished = false
        fun finish(result: JSONObject?, error: String?) {
            if (finished) return
            finished = true
            server?.close()
            backend.complete(id, result, error)
        }
        val timeout = Runnable { finish(null, "Google sign-in timed out") }
        try {
            val callback = LocalHttpServer(InetAddress.getByName("127.0.0.1"), 0, 1) { req, res ->
                val callbackUri = Uri.parse(req.target)
                if (req.method != "GET" || callbackUri.path != "/" || callbackUri.getQueryParameter("state") != state ||
                    SystemClock.elapsedRealtime() >= deadline) {
                    res.text(400, "This sign-in link is invalid or has expired.")
                    return@LocalHttpServer
                }
                val code = callbackUri.getQueryParameter("code")
                val error = callbackUri.getQueryParameter("error")
                if (code.isNullOrBlank() && error.isNullOrBlank()) {
                    res.text(400, "Google did not return a sign-in response.")
                    return@LocalHttpServer
                }
                val redirect = "http://127.0.0.1:${server?.port}"
                res.text(200, if (!code.isNullOrBlank()) "Return to OpenSkyLight to finish connecting." else "Google sign-in was cancelled. Return to OpenSkyLight.")
                main.post {
                    main.removeCallbacks(timeout)
                    if (code.isNullOrBlank()) finish(null, "Google sign-in was cancelled")
                    else finish(JSONObject().put("code", code).put("redirectUri", redirect), null)
                }
            }
            server = callback
            val redirect = "http://127.0.0.1:${callback.port}"
            val authorization = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
                .appendQueryParameter("client_id", data.getString("clientId"))
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", data.getString("scope"))
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", data.getString("challenge"))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .build()
            activity.startActivity(Intent(Intent.ACTION_VIEW, authorization))
            main.postDelayed(timeout, 300_000)
        } catch (_: Exception) {
            finish(null, "Could not open Google sign-in in the system browser")
        }
    }
}
