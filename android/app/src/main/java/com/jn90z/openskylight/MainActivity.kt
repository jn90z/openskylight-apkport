package com.jn90z.openskylight

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebViewClient
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : BridgeActivity() {
    private var pendingFolder: ((String?) -> Unit)? = null
    private val selectFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val folder = uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                it.toString()
            }.getOrNull()
        }
        pendingFolder?.invoke(folder)
        pendingFolder = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(OpenSkyLightPlugin::class.java)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        immersive()
        val backend = Backend.get(this)
        bridge.webView.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
        }
        bridge.setWebViewClient(object : BridgeWebViewClient(bridge) {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "localhost") {
                    if (uri.pathSegments.take(2) == listOf("_osl", "photo")) {
                        return uri.pathSegments.getOrNull(2)?.let(backend::photo) ?: missing()
                    }
                    if (uri.path == "/_osl/bird-image") return birdImage(uri.getQueryParameter("base"), uri.getQueryParameter("sci"))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "localhost" && uri.port == -1) return false
                if (request.isForMainFrame && uri.scheme in listOf("https", "http")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                if (!isFinishing && !isDestroyed) window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) recreate() }, 1000)
                return true
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        pendingFolder?.invoke(null)
        pendingFolder = null
        super.onDestroy()
    }

    internal fun chooseFolder(callback: (String?) -> Unit) {
        if (pendingFolder != null) {
            callback(null)
            return
        }
        pendingFolder = callback
        try {
            selectFolder.launch(null)
        } catch (_: Exception) {
            pendingFolder = null
            callback(null)
        }
    }

    internal fun overlay(): FrameLayout = findViewById(android.R.id.content)

    internal fun setDimmed(dimmed: Boolean) {
        val attrs = window.attributes
        attrs.screenBrightness = if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }

    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
        emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun birdImage(base: String?, species: String?): WebResourceResponse {
        if (base.isNullOrBlank() || species.isNullOrBlank() || species.length > 120) return missing()
        return try {
            val origin = URL(base)
            require(origin.protocol in listOf("http", "https") && origin.userInfo == null && origin.host.isNotEmpty())
            require(origin.path.isEmpty() && origin.query == null && origin.ref == null)
            val uri = URL(origin, "/media/image/" + android.net.Uri.encode(species))
            val connection = (uri.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
            }
            try {
                val mime = connection.contentType?.substringBefore(';')
                if (connection.responseCode != 200 || mime !in listOf("image/jpeg", "image/png", "image/webp", "image/gif")) return missing()
                val bytes = connection.inputStream.use { NativeNetwork.readLimited(it, 2 * 1024 * 1024) }
                WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "private, max-age=900"), ByteArrayInputStream(bytes))
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            missing()
        }
    }
}
