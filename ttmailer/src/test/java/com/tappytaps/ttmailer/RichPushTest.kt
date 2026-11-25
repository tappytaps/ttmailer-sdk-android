package com.tappytaps.ttmailer

import com.tappytaps.ttmailer.RichPush.Launch
import com.tappytaps.ttmailer.RichPush.Nav
import com.tappytaps.ttmailer.RichPush.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RichPushTest {
    private val hosts = RichPush.ttmailerHosts("https://mailer.example.com", "https://mailer.example.com/p/m/tok")

    // Store URLs

    @Test fun storeUrlsByHostAndScheme() {
        listOf(
            "https://apps.apple.com/cz/app/bibino/id123",
            "https://itunes.apple.com/app/id123",
            "http://APPS.apple.com/app/id1",
            "https://play.google.com/store/apps/details?id=com.example",
            "https://play.google.com/store",
            "market://details?id=com.example",
            "itms-apps://apps.apple.com/app/id123",
            "itms-appss://apps.apple.com/app/id123",
            "MARKET://details?id=x",
        ).forEach { assertTrue(it, RichPush.isStoreUrl(it)) }
    }

    @Test fun otherUrlsAreNotStores() {
        listOf(
            "https://play.google.com/about",
            "https://play.google.com/",
            "https://example.com/store",
            "https://apple.com/app",
            "https://apps.apple.com.evil.com/app",
            "https://evil.com/?u=https://apps.apple.com",
            "myapp://premium",
            "",
            "not a url",
            null,
        ).forEach { assertFalse(it.toString(), RichPush.isStoreUrl(it)) }
    }

    // The payload

    private fun tap(vararg kv: Pair<String, String?>) = RichPush.tap(mapOf(*kv), "redirectUrl")

    @Test fun withoutAnActionTheLinkGoesToTheApp() {
        assertEquals(Tap.ReturnLink("myapp://x"), tap("redirectUrl" to "myapp://x", "ttmToken" to "t"))
        assertEquals(Tap.ReturnLink("myapp://x"), tap("redirectUrl" to "myapp://x", "ttmAction" to "app"))
        assertEquals(Tap.ReturnLink("myapp://x"), tap("redirectUrl" to "myapp://x", "ttmAction" to "teleport", "ttmUrl" to "https://a.com"))
        assertEquals(Tap.ReturnLink(null), tap("ttmToken" to "t"))
    }

    @Test fun browserAndInAppOpenTheRawUrl() {
        val link = "https://mailer.example.com/p/c/abc"
        assertEquals(Tap.Browser("https://example.com/a"), tap("redirectUrl" to link, "ttmAction" to "browser", "ttmUrl" to "https://example.com/a"))
        assertEquals(Tap.InApp("https://example.com/a"), tap("redirectUrl" to link, "ttmAction" to "inapp", "ttmUrl" to " https://example.com/a "))
        assertEquals(Tap.InApp("https://example.com/a"), tap("redirectUrl" to link, "ttmAction" to "InApp", "ttmUrl" to "https://example.com/a"))
    }

    @Test fun storeLinksOpenTheStoreForBrowserAndInApp() {
        val play = "https://play.google.com/store/apps/details?id=com.example"
        assertEquals(Tap.Store(play), tap("ttmAction" to "inapp", "ttmUrl" to play))
        assertEquals(Tap.Store(play), tap("ttmAction" to "browser", "ttmUrl" to play))
        assertEquals(Tap.Store("market://details?id=x"), tap("ttmAction" to "inapp", "ttmUrl" to "market://details?id=x"))
    }

    @Test fun withoutTtmUrlTheLinkIsUsedAndWithoutAnyTheLinkGoesToTheApp() {
        assertEquals(Tap.InApp("https://mailer.example.com/p/c/abc"), tap("redirectUrl" to "https://mailer.example.com/p/c/abc", "ttmAction" to "inapp"))
        assertEquals(Tap.ReturnLink("myapp://x"), tap("redirectUrl" to "myapp://x", "ttmAction" to "browser", "ttmUrl" to "bad url"))
    }

    @Test fun messageOpensTheMessageUrl() {
        val m = "https://mailer.example.com/p/m/tok"
        assertEquals(Tap.Message(m), tap("redirectUrl" to m, "ttmAction" to "message", "ttmUrl" to m))
        assertEquals(Tap.Message(m), tap("redirectUrl" to m, "ttmAction" to "message"))
        assertEquals(Tap.ReturnLink("myapp://x"), tap("redirectUrl" to "myapp://x", "ttmAction" to "message", "ttmUrl" to "myapp://y"))
    }

    @Test fun theProjectsLinkKeyIsUsed() {
        assertEquals(Tap.ReturnLink("https://a.com"), RichPush.tap(mapOf("url" to "https://a.com", "redirectUrl" to "https://b.com"), "url"))
    }

    // Navigation in the message

    private val appSchemes = setOf("myapp")
    private fun nav(url: String?) = RichPush.navigation(url, hosts) { it in appSchemes }
    private fun decide(url: String?, main: Boolean = true, gesture: Boolean = true, redirect: Boolean = false) =
        RichPush.decide(url, main, gesture, redirect, hosts) { it in appSchemes }

    @Test fun ttmailerHttpsPagesStayInTheWebView() {
        val r = "https://mailer.example.com/r/abc?u=https%3A%2F%2Fexample.com"
        assertEquals(Nav.Stay(r), nav(r))
        assertEquals(Nav.Stay("https://MAILER.example.com:443/p/m/tok"), nav("https://MAILER.example.com:443/p/m/tok"))
    }

    @Test fun plainHttpToTheTtmailerHostIsNotTtmailer() {
        assertEquals(Nav.InApp("http://mailer.example.com/r/abc"), nav("http://mailer.example.com/r/abc"))
        assertFalse(RichPush.isTtmailerPage("http://mailer.example.com/", hosts))
        assertFalse(RichPush.isTtmailerPage("https://sub.mailer.example.com/", hosts))
    }

    @Test fun theMessagesOwnHostCountsAsTtmailer() {
        val h = RichPush.ttmailerHosts("https://api.example.com", "https://news.example.com/p/m/tok")
        assertEquals(setOf("api.example.com", "news.example.com"), h)
        assertEquals(Nav.Stay("https://news.example.com/r/x"), RichPush.navigation("https://news.example.com/r/x", h) { false })
    }

    @Test fun finalTargetsGoOutside() {
        assertEquals(Nav.Close, nav("ttmailer://close"))
        assertEquals(Nav.Close, nav("TTMailer://close"))
        assertEquals(Nav.Ignore, nav("ttmailer://other"))
        assertEquals(Nav.Store("https://apps.apple.com/app/id1"), nav("https://apps.apple.com/app/id1"))
        assertEquals(Nav.Store("market://details?id=x"), nav("market://details?id=x"))
        assertEquals(Nav.InApp("https://example.com/a"), nav("https://example.com/a"))
        assertEquals(Nav.InApp("https://mailer.example.com.evil.com/"), nav("https://mailer.example.com.evil.com/"))
    }

    @Test fun onlyTheAppsOwnSchemesAreDeepLinks() {
        assertEquals(Nav.AppLink("myapp://premium?x=1"), nav("myapp://premium?x=1"))
        assertEquals(Nav.AppLink("MyApp://premium"), nav("MyApp://premium"))
        assertEquals(Nav.System("mailto:a@example.com"), nav("mailto:a@example.com"))
        assertEquals(Nav.System("tel:+420123"), nav("tel:+420123"))
        assertEquals(Nav.System("geo:50,14"), nav("geo:50,14"))
        assertEquals(Nav.System("otherapp://x"), nav("otherapp://x"))
        val intent = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        assertEquals(Nav.AndroidIntent(intent), nav(intent))
    }

    @Test fun dangerousOrBrokenUrlsAreIgnored() {
        listOf("javascript:alert(1)", "file:///etc/hosts", "content://x/y", "data:text/html,hi", "blob:https://a/b",
            "about:blank", "about:srcdoc", "", "no scheme", null)
            .forEach { assertEquals(it.toString(), Nav.Ignore, nav(it)) }
    }

    @Test fun leavingNeedsATapOrARedirect() {
        assertEquals(Nav.InApp("https://example.com/"), decide("https://example.com/", gesture = true))
        assertEquals(Nav.InApp("https://example.com/"), decide("https://example.com/", gesture = false, redirect = true))
        assertEquals(Nav.Ignore, decide("https://example.com/", gesture = false))
        assertEquals(Nav.Ignore, decide("myapp://x", gesture = false))
        assertEquals(Nav.Ignore, decide("ttmailer://close", gesture = false))
        // TTMailer pages need neither (they stay).
        assertEquals(Nav.Stay("https://mailer.example.com/p/m/tok"), decide("https://mailer.example.com/p/m/tok", gesture = false))
    }

    @Test fun subFramesNeverLeaveOrClose() {
        assertEquals(Nav.Ignore, decide("ttmailer://close", main = false))
        assertEquals(Nav.Ignore, decide("myapp://x", main = false))
        assertEquals(Nav.Ignore, decide("https://example.com/", main = false))
        assertEquals(Nav.Stay("https://mailer.example.com/x"), decide("https://mailer.example.com/x", main = false))
    }

    @Test fun onlyTtmailerDocumentsLoadInFrames() {
        val iframe = mapOf("Sec-Fetch-Dest" to "iframe")
        assertTrue(RichPush.blocksSubframe("https://example.com/", false, iframe, hosts))
        assertTrue(RichPush.blocksSubframe("https://example.com/", false, mapOf("Accept" to "text/html,application/xhtml+xml"), hosts))
        assertFalse(RichPush.blocksSubframe("https://mailer.example.com/x", false, iframe, hosts))
        assertFalse(RichPush.blocksSubframe("https://cdn.example.com/a.png", false, mapOf("Accept" to "image/webp,*/*"), hosts))
        assertFalse(RichPush.blocksSubframe("https://cdn.example.com/a.png", false, mapOf("Sec-Fetch-Dest" to "image"), hosts))
        assertFalse(RichPush.blocksSubframe("https://cdn.example.com/a.css", false, null, hosts))
        assertFalse(RichPush.blocksSubframe("https://example.com/", true, iframe, hosts))
    }

    @Test fun intentUrlFallbacks() {
        assertEquals("https://example.com/a?x=1",
            RichPush.intentFallback("intent://x#Intent;scheme=z;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1;end"))
        assertEquals("https://play.google.com/store/apps/details?id=com.google.zxing.client.android",
            RichPush.intentFallback("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"))
        assertEquals("https://play.google.com/store/apps/details?id=com.a.b",
            RichPush.intentFallback("intent://x#Intent;package=com.a.b;S.browser_fallback_url=javascript%3Aalert(1);end"))
        assertEquals(null, RichPush.intentFallback("intent://x#Intent;scheme=z;end"))
        assertEquals(null, RichPush.intentFallback("intent://x"))
    }

    // Opening a store

    @Test fun storeLaunchesPreferThePlayStoreApp() {
        val play = "https://play.google.com/store/apps/details?id=x"
        assertEquals(listOf(Launch(play, "com.android.vending"), Launch(play)), RichPush.storeLaunches(play))
        assertEquals(listOf(Launch("market://details?id=x"), Launch("https://play.google.com/store/apps/details?id=x")),
            RichPush.storeLaunches("market://details?id=x"))
        assertEquals(listOf(Launch("market://search?q=x"), Launch("https://play.google.com/store/search?q=x")),
            RichPush.storeLaunches("market://search?q=x"))
        assertEquals(listOf(Launch("https://apps.apple.com/app/id1")), RichPush.storeLaunches("itms-apps://apps.apple.com/app/id1"))
        assertEquals(listOf(Launch("https://apps.apple.com/app/id1")), RichPush.storeLaunches("https://apps.apple.com/app/id1"))
    }

    // The version

    @Test fun theVersionConstantMatchesTheGradleVersion() {
        val gradle = File("build.gradle.kts").takeIf { it.exists() } ?: File("ttmailer/build.gradle.kts")
        val v = Regex("""(?m)^version = "([^"]+)"""").find(gradle.readText())!!.groupValues[1]
        assertEquals(v, TTMailer.SDK_VERSION)
    }
}
