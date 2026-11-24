package com.jn90z.openskylight

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import org.json.JSONObject

/** Owns one decoder for the low-memory target. The web renderer supplies the tile
 * rectangle. RTSP addresses never cross into the WebView. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NativeCameras(
    private val context: Context,
    private val container: FrameLayout,
    private val emit: (String, JSONObject) -> Unit
) {
    private data class Session(
        val cameraId: String, val id: String, val player: ExoPlayer, val view: TextureView
    )
    private var active: Session? = null

    fun start(request: JSONObject) {
        val id = request.getString("sessionId")
        if (active != null) {
            status(id, "busy")
            return
        }
        val view = TextureView(context).apply {
            isOpaque = true
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        container.addView(view, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        val player = ExoPlayer.Builder(context).build()
        active = Session(request.getString("cameraId"), id, player, view)
        player.volume = 0f
        player.setVideoTextureView(view)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (active?.id == id) status(id, "live")
            }
            override fun onPlayerError(error: PlaybackException) {
                if (active?.id == id) {
                    status(id, "error")
                    stop(id)
                }
            }
        })
        try {
            val item = MediaItem.fromUri(Uri.parse(request.getString("url")))
            player.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(10_000).createMediaSource(item))
            player.prepare()
            player.play()
        } catch (_: Exception) {
            status(id, "error")
            stop(id)
        }
    }

    fun position(id: String, left: Int, top: Int, width: Int, height: Int, visible: Boolean) {
        val session = active?.takeIf { it.id == id } ?: return
        if (width <= 0 || height <= 0 || !visible) {
            session.view.visibility = View.INVISIBLE
            return
        }
        val frame = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT)
        frame.leftMargin = left
        frame.topMargin = top
        session.view.layoutParams = frame
        session.view.visibility = View.VISIBLE
    }

    fun stop(id: String) {
        val session = active?.takeIf { it.id == id } ?: return
        active = null
        session.player.release()
        container.removeView(session.view)
    }

    fun remove(cameraId: String) { active?.takeIf { it.cameraId == cameraId }?.let { stop(it.id) } }

    fun release() {
        active?.let {
            status(it.id, "stopped")
            stop(it.id)
        }
    }

    private fun status(id: String, state: String) {
        emit("push:cameraState", JSONObject().put("sessionId", id).put("state", state))
    }
}
