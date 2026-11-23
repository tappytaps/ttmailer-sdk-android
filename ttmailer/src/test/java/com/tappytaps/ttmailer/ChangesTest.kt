package com.tappytaps.ttmailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tags, the app's own language (TTM-584) and premium (TTM-585). */
class ChangesTest {
    private val key = "tta_ABCDEFGHIJKLMNOPQRSTUVWX"
    private val info = DeviceInfo(language = "cs-CZ", timeZone = "Europe/Prague", appVersion = "3.2.1", osVersion = "14")
    private var now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun engine(t: FakeTransport, s: MemoryStore) =
        Engine(key, "https://mailer.example.com", "redirectUrl", t, s, { info }, { now })

    private fun json(b: String?) = Json.parseObject(b ?: "{}")

    @Test fun theAppsLanguageReplacesTheSystemOneAndNullGoesBack() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        e.setLanguage("de")
        assertEquals("de", json(t.sent.last().body)["language"])
        val again = engine(t, s)
        t.answer(200); t.answer(200, """{"ok":true,"confirm":true}""")
        again.subscribe("a@b.cz", null)
        assertEquals("de", json(t.sent.last().body)["language"])
        again.setLanguage(null)
        assertEquals("cs-CZ", json(t.sent.last().body)["language"])
    }

    @Test fun tagsWaitOfflineMergeAndAreSentAfterTheInstallation() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        t.fail(); e.addTags(listOf("Sportovec", "beta")); assertTrue(!e.flushChanges())
        e.removeTags(listOf("beta", "old"))
        assertTrue(e.flushChanges())
        val tags = t.sent.filter { it.path == "/sdk/v1/installations/${e.installationId}/tags" }
        assertEquals(1, tags.size)
        assertEquals("""{"add":["sportovec"],"remove":["beta","old"]}""", tags[0].body)
        assertEquals(0, e.pendingTagCount)
        // A restart keeps waiting tags; a 4xx drops them; an unknown installation keeps them.
        t.fail(); e.addTags(listOf("a")); e.flushChanges()
        assertEquals(1, engine(t, s).pendingTagCount)
        t.answer(409, """{"error":"unknown_installation"}"""); val again = engine(t, s); again.flushChanges()
        assertEquals(1, again.pendingTagCount)
        // The installation is sent again first (TTMailer did not know it), then the tags.
        t.answer(200); t.answer(400, """{"error":"invalid_tags"}"""); again.flushChanges()
        assertEquals("PUT", t.sent[t.sent.size - 2].method)
        assertEquals(0, again.pendingTagCount)
    }

    @Test fun setPremiumIsSentOnceUntilItChanges() {
        val t = FakeTransport(); val s = MemoryStore()
        val e = engine(t, s)
        val r = PremiumReport(until = now + 30 * day, lifetime = false, kind = "paid", startedAt = now, products = null)
        e.setPremium(r); e.flushChanges()
        e.setPremium(r); e.flushChanges()
        val sent = t.sent.filter { it.path.endsWith("/premium") }
        assertEquals(1, sent.size)
        assertEquals("paid", json(sent[0].body)["kind"])
        assertEquals(Engine.iso(now + 30 * day), json(sent[0].body)["until"])
        e.setPremium(PremiumReport(null, true, "lifetime", null, listOf("pro"))); e.flushChanges()
        assertEquals("""{"lifetime":true,"kind":"lifetime","products":["pro"]}""", t.sent.last().body)
    }

    @Test fun anAndroidSubscriptionEndsAtItsNextRenewal() {
        val bought = now - 40 * day
        // Monthly bought 40 days ago: renews after ~2 months.
        val end = subscriptionEnd(bought, "P1M", now)!!
        assertTrue(end > now && end < now + 31 * day)
        assertEquals(bought + 7 * day * 6, subscriptionEnd(bought, "P1W", now))
        assertNull(subscriptionEnd(bought, "nonsense", now))
    }

    @Test fun reportFromTheStore() {
        val start = now - 40 * day
        val sub = Entitlement("m", Entitlement.Kind.SUBSCRIPTION, now + 20 * day, start, false)
        assertEquals(PremiumReport(now + 20 * day, false, "paid", start, emptyList()), PremiumReport.from(listOf(sub), emptySet(), null, now))
        val pro = Entitlement("pro", Entitlement.Kind.ONE_TIME, null, now, false)
        val themes = Entitlement("themes", Entitlement.Kind.ONE_TIME, null, now, false)
        assertEquals(PremiumReport(null, true, "lifetime", now, listOf("pro", "themes")), PremiumReport.from(listOf(themes, pro), setOf("pro"), null, now))
        assertEquals(PremiumReport(null, false, null, null, listOf("themes")), PremiumReport.from(listOf(themes), emptySet(), null, now))
        val prev = PremiumReport(now + 10 * day, false, "paid", null, emptyList())
        assertEquals(PremiumReport(now, false, "paid", null, emptyList()), PremiumReport.from(emptyList(), emptySet(), prev, now))
        assertNull(PremiumReport.from(emptyList(), emptySet(), null, now))
    }
}
