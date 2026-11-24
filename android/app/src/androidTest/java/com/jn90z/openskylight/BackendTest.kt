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
import org.junit.Test
import org.junit.runner.RunWith
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
        val person = data("people:create", json("name" to "Tablet Test", "color" to "#0091ff", "role" to "child")) as JSONObject
        val personId = person.getString("id")
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
                          if (window.osl && !window.__bridgeTest) {
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
