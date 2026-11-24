package com.jn90z.openskylight

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

internal object NativeNetwork {
    private const val MAX_TEXT = 8 * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun fetch(request: JSONObject): JSONObject {
        val method = request.optString("method", "GET")
        val timeout = request.optInt("timeout", 15_000).coerceIn(1_000, 30_000).toLong()
        val headers = request.optJSONObject("headers") ?: JSONObject()
        val contentType = headers.keys().asSequence().firstOrNull { it.equals("Content-Type", ignoreCase = true) }
        val body = request.optString("body").takeIf { request.has("body") }
            ?.toRequestBody(contentType?.let(headers::getString)?.toMediaTypeOrNull())
        var url = request.getString("url").toHttpUrl()
        val perCall = client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()

        repeat(6) {
            val call = Request.Builder().url(url).method(method, body).apply {
                for (key in headers.keys()) header(key, headers.getString(key))
            }.build()
            val response = perCall.newCall(call).execute()
            response.use {
                val status = it.code
                val redirect = it.header("Location")
                if (status in setOf(301, 302, 303, 307, 308) && redirect != null && method == "GET") {
                    val next = url.resolve(redirect) ?: error("Invalid redirect")
                    require(url.scheme != "https" || next.scheme == "https")
                    if (headers.keys().asSequence().any { key -> key.equals("Authorization", ignoreCase = true) }) {
                        require(url.scheme == next.scheme && url.host == next.host && url.port == next.port)
                    }
                    url = next
                } else {
                    val fields = JSONObject()
                    for (name in it.headers.names()) fields.put(name.lowercase(), it.header(name))
                    val text = it.body?.byteStream()?.use { stream -> String(readLimited(stream, MAX_TEXT), UTF_8) }.orEmpty()
                    return JSONObject().put("status", status).put("headers", fields).put("body", text)
                }
            }
        }
        error("Too many redirects")
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
