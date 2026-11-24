package com.jn90z.openskylight

import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8

internal object NativeNetwork {
    private const val MAX_TEXT = 8 * 1024 * 1024

    fun fetch(request: JSONObject): JSONObject {
        val method = request.optString("method", "GET")
        val timeout = request.optInt("timeout", 15_000).coerceIn(1_000, 30_000)
        val headers = request.optJSONObject("headers") ?: JSONObject()
        val body = request.optString("body").takeIf { request.has("body") }
        var url = URL(request.getString("url"))
        repeat(6) {
            val conn = connection(url, timeout)
            try {
                conn.requestMethod = method
                for (key in headers.keys()) conn.setRequestProperty(key, headers.getString(key))
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(UTF_8)) }
                }
                val status = conn.responseCode
                val redirect = conn.getHeaderField("Location")
                if (status in setOf(301, 302, 303, 307, 308) && redirect != null && method == "GET") {
                    val next = URL(url, redirect)
                    require(url.protocol != "https" || next.protocol == "https")
                    require(!headers.has("Authorization") || url.host == next.host)
                    url = next
                } else {
                    val fields = JSONObject()
                    conn.headerFields.forEach { (key, values) ->
                        if (key != null && !values.isNullOrEmpty()) fields.put(key.lowercase(), values.first())
                    }
                    val input = if (status >= 400) conn.errorStream else conn.inputStream
                    val text = input?.use { String(readLimited(it, MAX_TEXT), UTF_8) }.orEmpty()
                    return JSONObject().put("status", status).put("headers", fields).put("body", text)
                }
            } finally {
                conn.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun connection(url: URL, timeout: Int): HttpURLConnection {
        require(url.protocol == "http" || url.protocol == "https")
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = timeout
            readTimeout = timeout
        }
    }

    fun readLimited(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return output.toByteArray()
            require(output.size() + size <= max) { "Response too large" }
            output.write(buffer, 0, size)
        }
    }
}
