package com.jn90z.openskylight

import android.os.SystemClock
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

@CapacitorPlugin(name = "OpenSkyLight")
class OpenSkyLightPlugin : Plugin() {
    private lateinit var backend: Backend
    private lateinit var cameras: NativeCameras
    private var visible = false
    private var rendererReady = false
    private var lastPinAttempt = -1_000L

    internal val owner: MainActivity get() = activity as MainActivity

    override fun load() {
        backend = Backend.get(context)
        cameras = NativeCameras(owner, owner.overlay(), ::push)
        backend.attach(this)
    }

    @PluginMethod
    fun invoke(call: PluginCall) {
        val channel = call.getString("channel")
        if (channel.isNullOrEmpty() || !channel.matches(Regex("[A-Za-z]+:[A-Za-z]+"))) {
            call.reject("Invalid channel")
            return
        }
        if (call.data.toString().length > 256 * 1024) {
            call.reject("Request too large")
            return
        }
        if (channel == "auth:verifyPin") {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPinAttempt < 1_000) {
                call.resolve(JSObject("{\"ok\":false,\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Wait before retrying your PIN\"}}"))
                return
            }
            lastPinAttempt = now
        }
        backend.invoke(channel, call.data.opt("payload")).thenAccept { result ->
            call.resolve(JSObject(result.body.toString()))
        }
    }

    @PluginMethod
    fun ready(call: PluginCall) {
        owner.runOnUiThread {
            rendererReady = true
            backend.emitState()
            call.resolve()
        }
    }

    @PluginMethod
    fun cameraPosition(call: PluginCall) {
        val id = call.getString("sessionId") ?: return call.reject("Missing camera session")
        owner.runOnUiThread {
            val origin = IntArray(2)
            val overlay = IntArray(2)
            bridge.webView.getLocationInWindow(origin)
            owner.overlay().getLocationInWindow(overlay)
            val density = owner.resources.displayMetrics.density
            fun px(key: String) = ((call.getDouble(key) ?: 0.0) * density).toInt()
            val width = px("width").coerceIn(0, bridge.webView.width)
            val height = px("height").coerceIn(0, bridge.webView.height)
            cameras.position(id, origin[0] - overlay[0] + px("left"), origin[1] - overlay[1] + px("top"),
                width, height, visible && call.getBoolean("visible", false) == true)
            call.resolve()
        }
    }

    internal fun choosePhotos(id: Int) = owner.chooseFolder { folder -> backend.complete(id, folder, null) }
    internal fun setDisplaySleep(sleeping: Boolean) = owner.setDimmed(sleeping)
    internal fun startCamera(data: JSONObject) {
        if (visible) cameras.start(data)
        else push("push:cameraState", JSONObject().put("sessionId", data.getString("sessionId")).put("state", "stopped"))
    }
    internal fun stopCamera(id: String) = cameras.stop(id)
    internal fun removeCamera(id: String) = cameras.remove(id)

    internal fun push(event: String, payload: JSONObject) {
        if (rendererReady) notifyListeners(event, JSObject(payload.toString()))
    }

    override fun handleOnResume() {
        visible = true
        backend.setVisible(true)
        if (rendererReady) push("push:androidVisibility", JSONObject().put("visible", true))
    }

    override fun handleOnPause() {
        visible = false
        backend.setVisible(false)
        push("push:androidVisibility", JSONObject().put("visible", false))
        cameras.release()
    }

    override fun handleOnDestroy() {
        cameras.release()
        backend.detach(this)
    }
}
