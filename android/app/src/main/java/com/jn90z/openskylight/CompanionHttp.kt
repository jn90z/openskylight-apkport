package com.jn90z.openskylight

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

internal class CompanionHttp(private val context: Context, private val backend: Backend) {
    private var server: LocalHttpServer? = null
    private var lastError: String? = null
    private val failures = LinkedHashMap<InetAddress, Pair<Long, Int>>()

    fun configure(enabled: Boolean, port: Int) {
        if (enabled && server?.port == port) return
        server?.close()
        server = null
        lastError = null
        if (!enabled) {
            CompanionService.stop(context)
            return
        }
        try {
            server = LocalHttpServer(InetAddress.getByName("0.0.0.0"), port, 3, ::serve)
            CompanionService.start(context)
        } catch (_: Exception) {
            server?.close()
            server = null
            lastError = "Could not open companion port $port"
        }
    }

    fun status(port: Int): JSONObject {
        val urls = if (server != null) lanAddresses().map { "http://${it.hostAddress}:$port/" } else emptyList()
        return JSONObject().put("running", server != null).put("port", port)
            .put("urls", org.json.JSONArray(urls))
            .put("lastError", lastError ?: JSONObject.NULL)
    }

    private fun lanAddresses(): List<Inet4Address> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.flatMap { network ->
            val info = connectivity.getNetworkCapabilities(network)
            if (info?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true &&
                info?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true
            ) return@flatMap emptyList()
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map { it.address }.filterIsInstance<Inet4Address>()
        }.filter { it.isSiteLocalAddress }.distinct()
    }

    @Synchronized
    private fun authLimit(ip: InetAddress, failed: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = failures[ip]?.takeIf { now - it.first < 60_000 }
        if (failed) {
            failures[ip] = (previous?.first ?: now) to ((previous?.second ?: 0) + 1)
            if (failures.size > 256) failures.remove(failures.keys.first())
        }
        return (previous?.second ?: 0) >= 30
    }

    private fun serve(req: LocalHttpServer.Request, res: LocalHttpServer.Response) {
        // Companion is opt-in and accepts only private LAN clients. Check Host
        // against the tablet's current IPs before serving a page or any API.
        val host = req.headers["host"]?.substringBefore(':')
        if (!req.remote.isSiteLocalAddress || lanAddresses().none { it.hostAddress == host }) {
            res.text(403, "Open this app using the tablet's companion address")
            return
        }
        val path = Uri.decode(req.target.substringBefore('?'))
        if (path == "/api/health" && req.method == "GET") {
            res.text(200, JSONObject().put("app", "openskylight").put("version", BuildConfig.VERSION_NAME).toString(), JSON)
            return
        }
        if (path.startsWith("/api/rpc/")) {
            if (req.method != "POST") return error(res, 405, "METHOD", "POST only")
            if (authLimit(req.remote, false)) return error(res, 429, "RATE_LIMITED", "Too many attempts — wait a minute")
            val channel = path.removePrefix("/api/rpc/")
            if (!channel.matches(Regex("[A-Za-z]+:[A-Za-z]+"))) return error(res, 404, "NOT_FOUND", "Unknown channel")
            val authorization = req.headers["authorization"].orEmpty()
            val token = if (authorization.startsWith("Bearer ")) authorization.removePrefix("Bearer ") else ""
            if (token.isEmpty() || token.length > 128) {
                authLimit(req.remote, true)
                return error(res, 401, "UNAUTHORIZED", "Not paired — scan the QR code on the kiosk")
            }
            val payload = req.body().trim()
            val data = try {
                if (payload.isEmpty()) JSONObject.NULL else org.json.JSONTokener(payload).let { parser ->
                    parser.nextValue().also { require(parser.nextClean() == '\u0000') }
                }
            } catch (_: Exception) {
                return error(res, 400, "INVALID", "Body must be JSON")
            }
            val future = backend.invoke(channel, data, token)
            val result = try { future.get(20, TimeUnit.SECONDS) } finally { future.cancel(false) }
            if (result.status == 401) authLimit(req.remote, true)
            res.text(result.status, result.body.toString(), JSON)
            return
        }
        if (path.startsWith("/api/")) return error(res, 404, "NOT_FOUND", "Unknown endpoint")
        if (req.method != "GET") return res.text(405, "GET only")
        if (!path.matches(Regex("/[A-Za-z0-9_/.-]*")) || path.split('/').any { it == ".." || it == "." }) {
            return res.text(404, "Not found")
        }

        val file = if (path == "/" || '.' !in path.substringAfterLast('/')) "/index.html" else path
        val mime = when (file.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "webmanifest" -> "application/manifest+json"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            else -> return res.text(404, "Not found")
        }
        val asset = try { context.assets.open("companion$file") } catch (_: Exception) { return res.text(404, "Not found") }
        val cache = if (file.startsWith("/assets/")) "public, max-age=31536000, immutable" else "no-cache"
        asset.use { res.send(200, mime, cache) { output -> asset.copyTo(output) } }
    }

    private fun error(res: LocalHttpServer.Response, status: Int, code: String, message: String) {
        res.text(status, JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message)).toString(), JSON)
    }

    companion object {
        private const val JSON = "application/json; charset=utf-8"
    }
}
