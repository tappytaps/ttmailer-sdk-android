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

    @Test fun ttmailerPagesStayInTheWebView() {
        assertEquals(Nav.Stay, RichPush.navigation("https://mailer.example.com/r/abc?u=https%3A%2F%2Fexample.com", hosts))
        assertEquals(Nav.Stay, RichPush.navigation("https://MAILER.example.com:443/p/m/tok", hosts))
        assertEquals(Nav.Stay, RichPush.navigation("about:blank", hosts))
    }

    @Test fun theMessagesOwnHostCountsAsTtmailer() {
        val h = RichPush.ttmailerHosts("https://api.example.com", "https://news.example.com/p/m/tok")
        assertEquals(setOf("api.example.com", "news.example.com"), h)
        assertEquals(Nav.Stay, RichPush.navigation("https://news.example.com/r/x", h))
    }

    @Test fun finalTargetsGoOutside() {
        assertEquals(Nav.Close, RichPush.navigation("ttmailer://close", hosts))
        assertEquals(Nav.Close, RichPush.navigation("TTMailer://close", hosts))
        assertEquals(Nav.Ignore, RichPush.navigation("ttmailer://other", hosts))
        assertEquals(Nav.Store("https://apps.apple.com/app/id1"), RichPush.navigation("https://apps.apple.com/app/id1", hosts))
        assertEquals(Nav.Store("market://details?id=x"), RichPush.navigation("market://details?id=x", hosts))
        assertEquals(Nav.InApp("https://example.com/a"), RichPush.navigation("https://example.com/a", hosts))
        assertEquals(Nav.InApp("http://mailer.example.com.evil.com/"), RichPush.navigation("http://mailer.example.com.evil.com/", hosts))
        assertEquals(Nav.DeepLink("myapp://premium?x=1"), RichPush.navigation("myapp://premium?x=1", hosts))
        assertEquals(Nav.DeepLink("mailto:a@example.com"), RichPush.navigation("mailto:a@example.com", hosts))
    }

    @Test fun dangerousOrBrokenUrlsAreIgnored() {
        listOf("javascript:alert(1)", "file:///etc/hosts", "content://x/y", "data:text/html,hi", "", "no scheme", null)
            .forEach { assertEquals(it.toString(), Nav.Ignore, RichPush.navigation(it, hosts)) }
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
