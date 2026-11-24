package com.jn90z.openskylight

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackendTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backend get() = Backend.get(context)

    private fun json(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().also { obj -> pairs.forEach { (key, value) -> obj.put(key, value ?: JSONObject.NULL) } }

    private fun call(channel: String, payload: JSONObject? = null, token: String? = null) =
        backend.invoke(channel, payload, token).get(40, TimeUnit.SECONDS)

    private fun data(channel: String, payload: JSONObject? = null): Any? {
        val result = call(channel, payload).body
        assertTrue("$channel: $result", result.getBoolean("ok"))
        return result.opt("data")
    }

    @Test fun nativeSqliteServicesAndAuthorization() {
        val info = data("app:getInfo") as JSONObject
        assertEquals("android", info.getString("platform"))
        val calendars = data("calendars:list") as JSONArray
        assertTrue(calendars.length() >= 1)
        val calendar = calendars.getJSONObject(0).getString("id")
        val person = data("people:create", json("name" to "Tablet Test 🌤", "color" to "#0091ff", "role" to "child")) as JSONObject
        val personId = person.getString("id")
        assertEquals("Tablet Test 🌤", person.getString("name"))
        data("events:create", json("calendarId" to calendar, "title" to "DST recurrence", "allDay" to false,
            "start" to "2026-03-07T14:00:00.000Z", "end" to "2026-03-07T15:00:00.000Z",
            "tz" to "America/New_York", "personIds" to JSONArray().put(personId),
            "recurrence" to json("freq" to "daily", "count" to 3)))
        val occurrences = data("events:getOccurrences", json("start" to "2026-03-07T00:00:00Z",
            "end" to "2026-03-11T00:00:00Z")) as JSONArray
        val starts = (0 until occurrences.length()).map { occurrences.getJSONObject(it) }
            .filter { it.getString("title") == "DST recurrence" }.map { it.getString("start") }.toSet()
        assertTrue(starts.any { it.startsWith("2026-03-07T14:00:00") })
        assertTrue(starts.any { it.startsWith("2026-03-08T13:00:00") })

        val chore = data("chores:create", json("title" to "Dishes", "personId" to personId, "starsValue" to 5,
            "anchorDate" to "2026-11-24")) as JSONObject
        data("chores:complete", json("choreId" to chore.getString("id"), "date" to "2026-11-24"))
        val reward = data("rewards:create", json("title" to "Choose dessert", "costStars" to 4)) as JSONObject
        data("rewards:redeem", json("rewardId" to reward.getString("id"), "personId" to personId))

        val list = data("lists:create", json("name" to "Shopping", "color" to "#ffffff", "kind" to "grocery")) as JSONObject
        val item = data("listItems:add", json("text" to "Milk", "listId" to list.getString("id"))) as JSONObject
        data("listItems:toggle", json("id" to item.getString("id")))
        val lists = data("lists:getAll") as JSONArray
        assertTrue(lists.toString().contains("\"checked\":true"))
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("openskylight.db").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { disk ->
            disk.rawQuery("SELECT text, checked FROM list_items WHERE id = ?", arrayOf(item.getString("id"))).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Milk", row.getString(0))
                assertEquals(1, row.getInt(1))
            }
        }
        data("meals:set", json("date" to "2026-11-24", "slot" to "dinner", "text" to "Pasta"))
        assertTrue(data("meals:getRange", json("start" to "2026-11-24", "end" to "2026-11-25")).toString().contains("Pasta"))

        assertEquals(401, call("lists:getAll", token = "invalid").status)
        data("auth:setPin", json("pin" to "2468"))
        try {
            data("auth:lock")
            val denied = call("people:delete", json("id" to personId)).body
            assertFalse(denied.getBoolean("ok"))
            assertEquals("LOCKED", denied.getJSONObject("error").getString("code"))
            assertTrue((data("auth:verifyPin", json("pin" to "2468")) as JSONObject).getBoolean("valid"))
            data("people:delete", json("id" to personId))
        } finally {
            data("auth:setPin", json("pin" to null))
        }
    }

    @Test fun nativeFeedParserPreservesAtomLinksAndRssTitles() {
        val rss = FeedXml.parse("<rss><channel><title><![CDATA[Local & regional]]></title>" +
            "<item><title>First &amp; second</title></item></channel></rss>")
        val channel = rss.getJSONObject("rss").getJSONObject("channel")
        assertEquals("Local & regional", channel.getString("title"))
        assertEquals("First & second", channel.getJSONObject("item").getString("title"))
        val atom = FeedXml.parse("<feed xmlns='http://www.w3.org/2005/Atom'>" +
            "<entry><title>Local</title><link rel='alternate' href='https://example.com/story'/></entry></feed>")
        val link = atom.getJSONObject("feed").getJSONObject("entry").getJSONObject("link")
        assertEquals("https://example.com/story", link.getString("@_href"))
        assertEquals("alternate", link.getString("@_rel"))
    }

    @Test fun pullsIcsThroughTheAndroidNetworkAdapter() {
        val feed = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//OpenSkyLight//Android test//EN",
            "BEGIN:VEVENT", "UID:android-test-1", "DTSTAMP:20261124T120000Z",
            "DTSTART:20261124T150000Z", "DTEND:20261124T160000Z", "SUMMARY:Android ICS integration",
            "END:VEVENT", "END:VCALENDAR", "").joinToString("\r\n")
        LocalHttpServer(InetAddress.getByName("127.0.0.1"), 0, 1) { _, res ->
            res.text(200, feed, "text/calendar; charset=utf-8")
        }.use { server ->
            val binding = data("ics:add", json("url" to "http://127.0.0.1:${server.port}/calendar.ics",
                "name" to "Android ICS", "color" to "#225588")) as JSONObject
            try {
                val range = json("start" to "2026-11-24T00:00:00Z", "end" to "2026-11-25T00:00:00Z")
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                while (System.nanoTime() < end) {
                    if (data("events:getOccurrences", range).toString().contains("Android ICS integration")) return
                    data("sync:now")
                    Thread.sleep(500)
                }
                throw AssertionError("ICS event was not imported. " + data("sync:getStatus"))
            } finally {
                data("calendars:delete", json("id" to binding.getString("id")))
            }
        }
    }

    @Test fun companionAuthenticationAndSharedWrites() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val port = java.net.ServerSocket(0).use { socket -> socket.localPort }
            fun configure(enabled: Boolean) =
                data("settings:set", json("patch" to json("companion" to json("enabled" to enabled, "port" to port))))

            configure(true)
            try {
                val status = data("companion:getStatus") as JSONObject
                assertTrue(status.toString(), status.getBoolean("running"))
                val urls = status.getJSONArray("urls")
                assumeTrue("Emulator has no private Wi-Fi or Ethernet address", urls.length() > 0)
                val root = urls.getString(0)
                val pairing = (data("companion:issueToken") as JSONObject).getString("url").substringAfter("#t=")
                assertTrue(request(root, null).second.contains("<html"))
                val route = root + "api/rpc/lists:create"
                val list = json("name" to "Companion grocery test", "color" to "#112233", "kind" to "grocery")
                assertEquals(401, request(route, null, list).first)
                val response = request(route, pairing, list)
                assertEquals(200, response.first)
                assertTrue(JSONObject(response.second).getBoolean("ok"))
                assertTrue(data("lists:getAll").toString().contains("Companion grocery test"))
                data("companion:unpairAll")
                assertEquals(401, request(route, pairing, list).first)
            } finally {
                configure(false)
            }
        }
    }

    private fun request(url: String, token: String?, payload: JSONObject? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (payload != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = payload.toString().toByteArray()
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return status to stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    @Test fun webViewCallsTheInstalledNativeBridge() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var last = ""
            while (System.nanoTime() < deadline) {
                val evaluated = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val webView: WebView = activity.bridge.webView
                    webView.evaluateJavascript("""
                        (() => {
                          if (window.osl && document.querySelector('[data-app-ready=true]') && !window.__bridgeTest) {
                            window.__bridgeTest = 'pending';
                            window.osl.invoke('app:getInfo').then(r => {
                              window.__bridgeTest = r.ok ? r.data.platform : JSON.stringify(r);
                            });
                          }
                          return window.__bridgeTest || document.title;
                        })()
                    """.trimIndent()) { result ->
                        last = result.orEmpty()
                        evaluated.countDown()
                    }
                }
                assertTrue(evaluated.await(5, TimeUnit.SECONDS))
                if (last == "\"android\"") return
                Thread.sleep(500)
            }
            throw AssertionError("WebView bridge did not respond: $last")
        }
    }
}
