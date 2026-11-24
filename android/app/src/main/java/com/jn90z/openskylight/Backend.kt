package com.jn90z.openskylight

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class Backend private constructor(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "OpenSkyLight backend") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val network = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, ArrayBlockingQueue(24)).apply {
        allowCoreThreadTimeOut(true)
    }
    private val ui = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger()
    private val requests = ConcurrentHashMap<Int, CompletableFuture<Result>>()
    private val capacity = Semaphore(48)
    private val timers = mutableMapOf<Int, Runnable>()
    private val companion = CompanionHttp(context, this)
    private val photos = PhotoLibrary(context)
    private val google = NativeGoogle(context, this)
    private val boot = CompletableFuture<Boolean>()
    private lateinit var sqlite: NativeSqlite
    private lateinit var engine: QuickJs

    @Volatile private var plugin: OpenSkyLightPlugin? = null
    @Volatile private var sleeping = false
    @Volatile private var kioskState = "active"
    @Volatile private var visible = false
    private val dirtyDomains = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            try {
                sqlite = NativeSqlite(context)
                engine = QuickJs.create(dispatcher).apply {
                    memoryLimit = 48L * 1024 * 1024
                    maxStackSize = 1024L * 1024
                    defineBinding("androidHost", FunctionBinding { args ->
                        try {
                            val payload = JSONTokener(args[1] as String).nextValue()
                            asciiJson(JSONObject().put("data", handle(args[0] as String, payload) ?: JSONObject.NULL).toString())
                        } catch (error: Exception) {
                            val message = if (error is PlatformException) error.message else "Android operation failed"
                            asciiJson(JSONObject().put("error", message).toString())
                        }
                    })
                }
                engine.evaluate<Any?>(context.assets.open("backend.js").bufferedReader().use { it.readText() }, "backend.js")
                boot.complete(true)
            } catch (error: Exception) {
                Log.e(TAG, "Backend initialization failed", error)
                boot.complete(false)
            }
        }
    }

    fun invoke(channel: String, payload: Any?, bearer: String? = null): CompletableFuture<Result> {
        if (!capacity.tryAcquire()) return CompletableFuture.completedFuture(failure("BUSY", "OpenSkyLight is busy"))
        val future = CompletableFuture<Result>()
        val id = nextId.incrementAndGet()
        requests[id] = future
        val expires = SystemClock.elapsedRealtime() + 310_000
        val timeout = Runnable {
            future.complete(failure("TIMEOUT", "The operation took too long"))
        }
        ui.postDelayed(timeout, 310_000)
        future.whenComplete { _, _ ->
            ui.removeCallbacks(timeout)
            requests.remove(id)
            capacity.release()
        }
        boot.thenAccept { ready ->
            if (!ready) {
                future.complete(failure("BACKEND_UNAVAILABLE", "OpenSkyLight could not open its Android backend"))
                return@thenAccept
            }
            scope.launch {
                if (future.isDone || SystemClock.elapsedRealtime() >= expires) return@launch
                try {
                    evaluate("oslInvoke", JSONArray().put(id).put(channel).put(payload ?: JSONObject.NULL).put(bearer ?: JSONObject.NULL))
                } catch (error: Exception) {
                    Log.e(TAG, "Could not execute backend request")
                    future.complete(failure("INTERNAL", "Could not execute backend request"))
                }
            }
        }
        return future
    }

    fun attach(next: OpenSkyLightPlugin) {
        plugin = next
        ui.post { next.setDisplaySleep(sleeping) }
    }

    fun detach(previous: OpenSkyLightPlugin) {
        if (plugin === previous) {
            plugin = null
            visible = false
        }
    }

    fun setVisible(next: Boolean) {
        visible = next
        if (next) {
            emitState()
            for (domain in dirtyDomains.toList()) {
                if (dirtyDomains.remove(domain)) ui.post {
                    plugin?.push("push:dataChanged", JSONObject().put("domain", domain))
                }
            }
        }
    }

    fun emitState() {
        ui.post {
            plugin?.push("push:sleepState", JSONObject().put("sleeping", sleeping))
            plugin?.push("push:kioskIdle", JSONObject().put("state", kioskState))
        }
    }

    fun photo(uri: String) = photos.open(uri)

    private suspend fun evaluate(name: String, args: JSONArray) {
        // Treat the arguments as data even when a string contains JavaScript source.
        val encoded = asciiJson(JSONObject.quote(args.toString()))
        engine.evaluate<Any?>("void $name(...JSON.parse($encoded))")
    }

    fun complete(id: Int, data: Any?, error: String?) {
        scope.launch {
            try {
                evaluate("oslComplete", JSONArray().put(id).put(data ?: JSONObject.NULL).put(error ?: JSONObject.NULL))
            } catch (_: Exception) {
                Log.w(TAG, "Could not deliver platform result")
            }
        }
    }

    private fun schedule(delay: Long, repeat: Boolean): Int {
        val duration = delay.coerceAtLeast(50)
        val id = nextId.incrementAndGet()
        val callback = object : Runnable {
            override fun run() {
                val task = this
                scope.launch {
                    if (timers[id] !== task) return@launch
                    if (!repeat) timers.remove(id)
                    try {
                        evaluate("oslTimer", JSONArray().put(id))
                    } catch (_: Exception) {
                        Log.w(TAG, "Backend timer failed")
                    }
                    if (repeat && timers[id] === task) ui.postDelayed(task, duration)
                }
            }
        }
        timers[id] = callback
        ui.postDelayed(callback, duration)
        return id
    }

    private fun handle(operation: String, payload: Any): Any? = when (operation) {
        "timezone" -> TimeZone.getDefault().id
        "version" -> BuildConfig.VERSION_NAME
        "sqlQuery" -> sqlite.query(payload as JSONArray)
        "sqlRun" -> sqlite.run(payload as JSONArray)
        "sqlVersion" -> sqlite.version
        "sqlBegin" -> sqlite.begin().let { null }
        "sqlCommit" -> sqlite.commit().let { null }
        "sqlEnd" -> sqlite.end().let { null }
        "random" -> NativeSecurity.random((payload as Number).toInt())
        "sha256" -> NativeSecurity.sha256(payload as String)
        "scrypt" -> (payload as JSONArray).let { NativeSecurity.scrypt(it.getString(0), it.getString(1), it.getInt(2)) }
        "encrypt" -> NativeSecurity.encrypt(payload as String)
        "decrypt" -> NativeSecurity.decrypt(payload as String)
        "log" -> Log.w(TAG, payload as String).let { null }
        "timer" -> (payload as JSONArray).let { schedule(it.getLong(0), it.getBoolean(1)) }
        "cancelTimer" -> timers.remove((payload as Number).toInt())?.let { ui.removeCallbacks(it) }
        "fetch" -> startFetch(payload as JSONObject)
        "googleAvailable" -> google.available()
        "googleToken" -> nextId.incrementAndGet().also { google.token(it, payload as JSONObject) }
        "googleConnect" -> nextId.incrementAndGet().also { id ->
            ui.post {
                val activity = plugin?.owner
                if (activity == null) complete(id, null, "Open the kiosk to sign in")
                else google.connect(id, activity, payload as JSONObject)
            }
        }
        "pickFolder" -> nextId.incrementAndGet().also { id ->
            ui.post {
                val activity = plugin
                if (activity == null) complete(id, null, "Open the kiosk to choose a folder")
                else activity.choosePhotos(id)
            }
        }
        "photos" -> JSONArray(photos.list(payload as String))
        "launchAtBoot" -> context.getSharedPreferences("android-platform", Context.MODE_PRIVATE).edit()
            .putBoolean("launchAtBoot", payload as Boolean).apply()
        "displaySleep" -> {
            sleeping = payload as Boolean
            ui.post { plugin?.setDisplaySleep(sleeping) }
            null
        }
        "cameraStart" -> (payload as JSONObject).let { input ->
            val current = plugin ?: throw PlatformException("Open the kiosk to view the camera")
            ui.post { current.startCamera(input) }
        }
        "cameraStop" -> ui.post { plugin?.stopCamera(payload as String) }
        "cameraRemove" -> ui.post { plugin?.removeCamera(payload as String) }
        "companionConfigure" -> (payload as JSONObject).let { companion.configure(it.getBoolean("enabled"), it.getInt("port")) }
        "companionStatus" -> companion.status((payload as Number).toInt())
        "reply" -> (payload as JSONArray).let { requests[it.getInt(0)]?.complete(Result(it.getInt(1), it.getJSONObject(2))) }
        "push" -> (payload as JSONArray).let {
            val event = it.getString(0)
            val data = it.getJSONObject(1)
            if (event == "push:kioskIdle") kioskState = data.getString("state")
            if (event == "push:dataChanged") {
                val domain = data.getString("domain")
                dirtyDomains.add(domain)
                if (visible) ui.post {
                    if (visible && dirtyDomains.remove(domain)) plugin?.push(event, data)
                }
            } else if (visible) ui.post { plugin?.push(event, data) }
            null
        }
        else -> error("Unknown platform operation")
    }

    private fun startFetch(payload: JSONObject): Int {
        val id = nextId.incrementAndGet()
        try {
            network.execute {
                try {
                    complete(id, NativeNetwork.fetch(payload), null)
                } catch (_: Exception) {
                    complete(id, null, "Network request failed or timed out")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw PlatformException("Too many network requests")
        }
        return id
    }

    data class Result(val status: Int, val body: JSONObject)

    private class PlatformException(message: String) : RuntimeException(message)

    companion object {
        private const val TAG = "OpenSkyLight"
        @Volatile private var instance: Backend? = null

        fun get(context: Context): Backend = instance ?: synchronized(this) {
            instance ?: Backend(context.applicationContext).also { instance = it }
        }

        private fun asciiJson(json: String): String = if (json.all { it.code < 128 }) json else buildString {
            for (char in json) {
                if (char.code < 128) append(char)
                else append("\\u").append(char.code.toString(16).padStart(4, '0'))
            }
        }

        private fun failure(code: String, message: String) = Result(
            503, JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message))
        )
    }
}
