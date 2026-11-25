package com.tappytaps.ttmailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FakeTransport : HttpTransport {
    data class Sent(val method: String, val path: String, val body: String?, val headers: Map<String, String>)
    val sent = mutableListOf<Sent>()
    private val answers = ArrayDeque<Any>()
    fun answer(code: Int, body: String = """{"ok":true}""") { answers.add(HttpResponse(code, body)) }
    fun fail() { answers.add(IOException("offline")) }
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        sent.add(Sent(method, url.substringAfter("https://mailer.example.com"), body, headers))
        val next = answers.removeFirstOrNull() ?: HttpResponse(200, """{"ok":true}""")
        if (next is IOException) throw next
        return next as HttpResponse
    }
}

class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

class EngineTest {
    private val key = "tta_ABCDEFGHIJKLMNOPQRSTUVWX"
    private val info = DeviceInfo(language = "cs-CZ", timeZone = "Europe/Prague", appVersion = "3.2.1", osVersion = "14")
    private var now = 1_800_000_000_000L

    private fun engine(t: FakeTransport, s: MemoryStore) =
        Engine(key, "https://mailer.example.com", "redirectUrl", t, s, { info }, { now })

    private fun json(b: String?) = Json.parseObject(b ?: "{}")

    @Test fun firstSyncSendsTheInstallationWithTheKeyAndDeviceInfo() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        assertTrue(e.sync())
        assertEquals(1, t.sent.size)
        assertEquals("PUT", t.sent[0].method)
        assertEquals("/sdk/v1/installations/${e.installationId}", t.sent[0].path)
        assertEquals(key, t.sent[0].headers["X-TTMailer-App-Key"])
        val b = json(t.sent[0].body)
        assertEquals("cs-CZ", b["language"]); assertEquals("Europe/Prague", b["timeZone"])
        assertEquals("3.2.1", b["appVersion"]); assertEquals("14", b["osVersion"])
        assertEquals(TTMailer.SDK_VERSION, b["sdkVersion"])
        assertFalse(b.containsKey("pushToken")); assertFalse(b.containsKey("userId"))
    }

    @Test fun theInstallationIdSurvivesARestart() {
        val t = FakeTransport(); val s = MemoryStore()
        val a = engine(t, s).installationId
        assertEquals(a, engine(t, s).installationId)
        assertEquals(36, a.length)
    }

    @Test fun nothingIsResentWhenNothingChangedWithinADay() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        e.sync(); e.sync()
        assertEquals(1, t.sent.size)
        now += 25L * 3600 * 1000
        e.sync()
        assertEquals(2, t.sent.size)
    }

    @Test fun tokenSwitchAndUserIdAreSentAndKeptOverARestart() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        e.setPushToken("fcm-token")
        e.setPushEnabled(true)
        e.setUserId("acc-1")
        var b = json(t.sent.last().body)
        assertEquals("fcm-token", b["pushToken"]); assertEquals(true, b["pushEnabled"]); assertEquals("acc-1", b["userId"])
        e.setUserId(null)
        b = json(t.sent.last().body)
        assertTrue(b.containsKey("userId")); assertNull(b["userId"])
        val again = engine(t, s)
        now += 25L * 3600 * 1000
        again.sync()
        b = json(t.sent.last().body)
        assertEquals("fcm-token", b["pushToken"]); assertTrue(b.containsKey("userId")); assertNull(b["userId"])
    }

    @Test fun aFailedSyncIsRetriedNextTime() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        t.fail(); assertFalse(e.sync())
        t.answer(503, "{}"); assertFalse(e.sync())
        assertTrue(e.sync())
        assertEquals(3, t.sent.size)
    }

    @Test fun aRejectedKeyIsNotHammered() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        t.answer(404, """{"error":"not_found"}""")
        e.sync(); e.sync()
        assertEquals(1, t.sent.size)
    }

    @Test fun openedReportsTheTokenAndReturnsTheLink() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        assertEquals("https://bibino.com/x", e.opened(mapOf("ttmToken" to "abc.def", "redirectUrl" to "https://bibino.com/x")))
        e.flushOpens()
        val open = t.sent.first { it.path == "/p/open" }
        assertEquals("POST", open.method)
        assertEquals("abc.def", json(open.body)["t"])
        assertTrue((json(open.body)["at"] as String).endsWith("Z"))
    }

    @Test fun opensWaitOfflineAndAreDroppedOnA4xx() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        t.fail()
        e.opened(mapOf("ttmToken" to "a.b"))
        e.flushOpens()
        assertEquals(1, e.pendingOpens)
        val again = engine(t, s)
        t.answer(204, "")
        again.flushOpens()
        assertEquals(0, again.pendingOpens)
        t.answer(400, "{}")
        again.opened(mapOf("ttmToken" to "c.d"))
        again.flushOpens()
        assertEquals(0, again.pendingOpens)
        assertEquals(3, t.sent.count { it.path == "/p/open" })
    }

    @Test fun aPushWithoutTheTokenIsNotReported() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        assertNull(e.opened(mapOf("redirectUrl" to "not a url")))
        e.flushOpens()
        assertTrue(t.sent.isEmpty())
    }

    @Test fun subscribeSyncsFirstThenSignsUp() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        t.answer(200); t.answer(200, """{"ok":true,"confirm":true}""")
        assertTrue(e.subscribe("jana@example.com", "cs"))
        assertEquals(listOf("PUT", "POST"), t.sent.map { it.method })
        assertEquals("/sdk/v1/installations/${e.installationId}/subscribe", t.sent[1].path)
        assertEquals("jana@example.com", json(t.sent[1].body)["email"])
        assertEquals("cs", json(t.sent[1].body)["language"])
    }

    @Test fun subscribeErrors() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        e.sync()
        fun expect(reason: TTMailerException.Reason) {
            try { e.subscribe("a@b.cz", null); fail() } catch (x: TTMailerException) { assertEquals(reason, x.reason) }
        }
        t.answer(400, """{"error":"invalid_email"}"""); expect(TTMailerException.Reason.INVALID_EMAIL)
        t.answer(409, """{"error":"no_form"}"""); expect(TTMailerException.Reason.NO_FORM)
        t.answer(429, """{"error":"rate_limited"}"""); expect(TTMailerException.Reason.RATE_LIMITED)
        t.fail(); expect(TTMailerException.Reason.NETWORK)
    }

    @Test fun resetDeletesTheInstallationAndStartsANewOne() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        e.setPushToken("tok")
        val before = e.installationId
        e.reset()
        assertEquals("DELETE", t.sent.last().method)
        assertEquals("/sdk/v1/installations/$before", t.sent.last().path)
        assertNotEquals(before, e.installationId)
        e.sync()
        assertFalse(json(t.sent.last().body).containsKey("pushToken"))
    }

    @Test fun deleteContactErasesAndStartsANewInstallation() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        e.setUserId("acc-9")
        val id = e.installationId
        e.deleteContact()
        assertEquals("DELETE", t.sent.last().method)
        assertEquals("/sdk/v1/installations/$id/contact", t.sent.last().path)
        assertNotEquals(id, e.installationId)
    }

    @Test fun jsonRoundTrip() {
        val o = Json.parseObject(Json.encode(linkedMapOf("a" to "x\"y\\z\n", "b" to true, "c" to null, "d" to "č")))
        assertEquals("x\"y\\z\n", o["a"]); assertEquals(true, o["b"]); assertTrue(o.containsKey("c")); assertNull(o["c"]); assertEquals("č", o["d"])
    }
}
