package com.jn90z.openskylight

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class LocalHttpServer(
    address: InetAddress,
    port: Int,
    workers: Int,
    private val handler: (Request, Response) -> Unit
) : AutoCloseable {
    private val listener = ServerSocket().apply {
        reuseAddress = true
        try {
            bind(InetSocketAddress(address, port), 16)
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8)).apply {
        allowCoreThreadTimeOut(true)
    }
    private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    val port: Int get() = listener.localPort

    init {
        thread(isDaemon = true, name = "OpenSkyLight HTTP accept") {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                socket.soTimeout = 5_000
                connections.add(socket)
                try {
                    pool.execute {
                        try {
                            val deadline = deadlines.schedule({ runCatching { socket.close() } }, 25, TimeUnit.SECONDS)
                            try { socket.use(::serve) } finally { deadline.cancel(false) }
                        } catch (_: Exception) {
                            runCatching { socket.close() }
                        } finally {
                            connections.remove(socket)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    connections.remove(socket)
                    socket.close()
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = Response(BufferedOutputStream(socket.getOutputStream()))
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val first = readLine(input, deadline, 4096).split(' ')
            require(first.size == 3 && first[1].startsWith('/') && first[2] in listOf("HTTP/1.0", "HTTP/1.1"))
            val headers = linkedMapOf<String, String>()
            var remaining = 8192
            while (true) {
                val line = readLine(input, deadline, remaining)
                if (line.isEmpty()) break
                remaining -= line.length + 2
                val colon = line.indexOf(':')
                require(colon > 0)
                val key = line.substring(0, colon).lowercase()
                require(key !in headers)
                headers[key] = line.substring(colon + 1).trim()
            }
            require("transfer-encoding" !in headers)
            val length = headers["content-length"]?.toInt() ?: 0
            require(length >= 0)
            if (length > MAX_BODY) {
                output.text(413, "Request too large")
                return
            }
            handler(Request(first[0], first[1], headers, socket.inetAddress, length, input, deadline), output)
        } catch (_: IllegalArgumentException) {
            if (!output.sent) output.text(400, "Bad request")
        } catch (_: Exception) {
            if (!output.sent) output.text(503, "Service unavailable")
        }
    }

    override fun close() {
        listener.close()
        connections.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        deadlines.shutdownNow()
    }

    data class Request(
        val method: String, val target: String, val headers: Map<String, String>,
        val remote: InetAddress, private val length: Int, private val input: InputStream, private val deadline: Long
    ) {
        fun body(): String {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                check(System.nanoTime() < deadline)
                val read = input.read(bytes, offset, minOf(length - offset, 8192))
                require(read > 0)
                offset += read
            }
            return String(bytes, UTF_8)
        }
    }

    class Response(private val output: OutputStream) {
        var sent: Boolean = false
            private set

        fun text(status: Int, text: String, mime: String = "text/plain; charset=utf-8") {
            val bytes = text.toByteArray(UTF_8)
            send(status, mime, "no-store", bytes.size) { it.write(bytes) }
        }

        fun send(status: Int, mime: String, cache: String, length: Int? = null, write: (OutputStream) -> Unit) {
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                413 -> "Content Too Large"
                429 -> "Too Many Requests"
                503 -> "Service Unavailable"
                else -> "Internal Server Error"
            }
            val header = buildString {
                append("HTTP/1.1 $status $reason\r\nConnection: close\r\n")
                append("Content-Type: $mime\r\nCache-Control: $cache\r\nX-Content-Type-Options: nosniff\r\n")
                append("Referrer-Policy: no-referrer\r\n")
                append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'\r\n")
                if (length != null) append("Content-Length: $length\r\n")
                append("\r\n")
            }
            sent = true
            output.write(header.toByteArray(UTF_8))
            write(output)
            output.flush()
        }
    }

    companion object {
        private const val MAX_BODY = 64 * 1024

        private fun readLine(input: InputStream, deadline: Long, limit: Int): String {
            val line = StringBuilder()
            while (line.length < limit) {
                check(System.nanoTime() < deadline)
                val value = input.read()
                require(value >= 0)
                if (value == 13) {
                    require(input.read() == 10)
                    return line.toString()
                }
                require(value == 9 || value in 32..126)
                line.append(value.toChar())
            }
            throw IllegalArgumentException("Header too large")
        }
    }
}
