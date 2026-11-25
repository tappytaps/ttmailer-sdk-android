package com.tappytaps.ttmailer

import java.util.Locale

/**
 * Rich push (TTM-624, docs/rich-push-contract.md in TTMailer): what a tap on a push does and where a link in an in-app
 * message goes. Pure functions (no Android classes), so they are tested on the JVM.
 */
internal object RichPush {
    /** What [TTMailer.handle] does with a tapped push. */
    sealed class Tap {
        /** No SDK action (`ttmAction` absent, `app` or unknown): the link goes to the app, as [TTMailer.opened]. */
        data class ReturnLink(val link: String?) : Tap()
        /** `browser`: the system browser. */
        data class Browser(val url: String) : Tap()
        /** A store URL (from `browser` or `inapp`): the store. */
        data class Store(val url: String) : Tap()
        /** `inapp`: Custom Tabs (the system browser without a Custom Tabs provider). */
        data class InApp(val url: String) : Tap()
        /** `message`: the SDK's full-screen message. */
        data class Message(val url: String) : Tap()
    }

    /** Where a navigation inside the message WebView goes (contract section 3). */
    sealed class Nav {
        /** A TTMailer page (https, incl. the click redirects): stays in the WebView, loaded with the SDK header. */
        data class Stay(val url: String) : Nav()
        /** `ttmailer://close`: the message closes. */
        object Close : Nav()
        /** A store URL: the store; the message stays. */
        data class Store(val url: String) : Nav()
        /** Another http(s) page: Custom Tabs over the message. */
        data class InApp(val url: String) : Nav()
        /** One of the app's own schemes: the message closes, then `onDeepLink` or the system opens it. */
        data class AppLink(val url: String) : Nav()
        /** An `intent:` URL: launched sanitised (or its fallback); the message stays. */
        data class AndroidIntent(val url: String) : Nav()
        /** Another scheme (`mailto`, `tel`, `sms`, `geo`…): the system opens it; the message stays. */
        data class System(val url: String) : Nav()
        /** Cancelled, nothing happens (other `ttmailer:` links, `javascript:`, `file:`, `about:`, a sub-frame…). */
        object Ignore : Nav()

        /** Whether the navigation leaves the message (needs a tap or a server redirect). */
        val leaves: Boolean get() = this !is Stay && this !is Ignore
    }

    /** A way to open a store URL: the URL and the app that should open it (null = any). */
    data class Launch(val url: String, val packageName: String? = null)

    private val APPLE_STORE_HOSTS = setOf("apps.apple.com", "itunes.apple.com")
    private val STORE_SCHEMES = setOf("itms-apps", "itms-appss", "market")
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "data", "blob", "about")
    const val PLAY_STORE_PACKAGE = "com.android.vending"

    /** The parts of a URL the SDK looks at (tolerant: no exceptions on odd characters, unlike java.net.URI). */
    internal class Parts(val scheme: String, val host: String?, val path: String)

    internal fun parse(url: String?): Parts? {
        val s = url?.trim() ?: return null
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(s) ?: return null
        val scheme = m.groupValues[1].lowercase(Locale.ROOT)
        val rest = s.substring(m.value.length)
        if (!rest.startsWith("//")) return Parts(scheme, null, rest.substringBefore('?').substringBefore('#'))
        val afterSlashes = rest.substring(2)
        val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) afterSlashes.length else it }
        val authority = afterSlashes.substring(0, end).substringAfterLast('@')
        val host = (if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':'))
            .lowercase(Locale.ROOT).trimEnd('.')
        val tail = afterSlashes.substring(end)
        val path = if (tail.startsWith("/")) tail.substringBefore('?').substringBefore('#') else ""
        return Parts(scheme, host.ifEmpty { null }, path)
    }

    /** An absolute http(s) URL with a host. */
    fun isWebUrl(url: String?): Boolean {
        val p = parse(url) ?: return false
        return (p.scheme == "http" || p.scheme == "https") && p.host != null
    }

    /**
     * App Store and Google Play links: hosts `apps.apple.com`, `itunes.apple.com`, `play.google.com` (path `/store…`),
     * schemes `itms-apps`, `itms-appss`, `market`.
     */
    fun isStoreUrl(url: String?): Boolean {
        val p = parse(url) ?: return false
        if (p.scheme in STORE_SCHEMES) return true
        if (p.scheme != "http" && p.scheme != "https") return false
        return p.host in APPLE_STORE_HOSTS || (p.host == "play.google.com" && p.path.startsWith("/store"))
    }

    /** The push's payload → what the tap does. [linkKey] is the project's link key (`redirectUrl` by default). */
    fun tap(data: Map<String, String?>, linkKey: String): Tap {
        val link = linkOf(data, linkKey)
        val action = data["ttmAction"]?.trim()?.lowercase(Locale.ROOT)
        // ttmUrl is the raw target; a push without it (should not happen) falls back to the link.
        fun target(): String? = listOf(data["ttmUrl"]?.trim(), link).firstOrNull { isWebUrl(it) || isStoreUrl(it) }
        return when (action) {
            "browser" -> target()?.let { if (isStoreUrl(it)) Tap.Store(it) else Tap.Browser(it) } ?: Tap.ReturnLink(link)
            "inapp" -> target()?.let { if (isStoreUrl(it)) Tap.Store(it) else Tap.InApp(it) } ?: Tap.ReturnLink(link)
            "message" -> listOf(data["ttmUrl"]?.trim(), link).firstOrNull { isWebUrl(it) }?.let { Tap.Message(it) }
                ?: Tap.ReturnLink(link)
            else -> Tap.ReturnLink(link)
        }
    }

    /**
     * The TTMailer hosts whose https pages stay in the message WebView: the SDK's base URL host, and the host the
     * message itself came from (the same one, unless TTMailer's public URL differs from the API's).
     */
    fun ttmailerHosts(baseUrl: String, messageUrl: String?): Set<String> =
        listOfNotNull(parse(baseUrl)?.host, parse(messageUrl)?.host).toSet()

    /** An https URL on one of [ttmailerHosts] (an exact host match; http never counts). */
    fun isTtmailerPage(url: String?, ttmailerHosts: Set<String>): Boolean {
        val p = parse(url) ?: return false
        return p.scheme == "https" && p.host != null && p.host in ttmailerHosts
    }

    /**
     * A main-frame navigation inside the message WebView → where it goes. [isAppScheme] tells whether a scheme is
     * one of the app's own (its intent filters).
     */
    fun navigation(url: String?, ttmailerHosts: Set<String>, isAppScheme: (String) -> Boolean): Nav {
        val p = parse(url) ?: return Nav.Ignore
        val u = url!!.trim()
        return when {
            p.scheme == "ttmailer" -> if (p.host == "close") Nav.Close else Nav.Ignore
            p.scheme == "http" || p.scheme == "https" -> when {
                p.host == null -> Nav.Ignore
                isTtmailerPage(u, ttmailerHosts) -> Nav.Stay(u)
                isStoreUrl(u) -> Nav.Store(u)
                else -> Nav.InApp(u)
            }
            p.scheme in BLOCKED_SCHEMES -> Nav.Ignore
            isStoreUrl(u) -> Nav.Store(u)
            p.scheme == "intent" -> Nav.AndroidIntent(u)
            isAppScheme(p.scheme) -> Nav.AppLink(u)
            else -> Nav.System(u)
        }
    }

    /**
     * What the WebView does with a navigation: sub-frames never leave the message (only TTMailer pages load in them),
     * and the main frame leaves it only after a tap or a server redirect (never on its own, e.g. a meta refresh).
     */
    fun decide(
        url: String?, mainFrame: Boolean, hasGesture: Boolean, isRedirect: Boolean,
        ttmailerHosts: Set<String>, isAppScheme: (String) -> Boolean,
    ): Nav {
        if (!mainFrame) return if (isTtmailerPage(url, ttmailerHosts)) Nav.Stay(url!!.trim()) else Nav.Ignore
        val nav = navigation(url, ttmailerHosts, isAppScheme)
        return if (nav.leaves && !hasGesture && !isRedirect) Nav.Ignore else nav
    }

    /**
     * Whether a request of a sub-frame document (an iframe) is blocked: only TTMailer pages load in frames. Images,
     * styles and fonts of the message load from anywhere.
     */
    fun blocksSubframe(url: String?, mainFrame: Boolean, headers: Map<String, String>?, ttmailerHosts: Set<String>): Boolean {
        if (mainFrame) return false
        val h = headers.orEmpty().mapKeys { it.key.lowercase(Locale.ROOT) }
        val dest = h["sec-fetch-dest"]?.lowercase(Locale.ROOT)
        val document = dest == "iframe" || dest == "frame" || dest == "document" ||
            (dest == null && h["accept"]?.trimStart()?.startsWith("text/html", ignoreCase = true) == true)
        if (!document) return false
        return !isTtmailerPage(url, ttmailerHosts)
    }

    /**
     * The fallback of an `intent:` URL that no app takes: its `S.browser_fallback_url` (http(s) only), else the
     * Google Play page of its `package`. Null when it has neither.
     */
    fun intentFallback(url: String): String? {
        val fragment = url.substringAfter("#Intent;", "").substringBefore(";end")
        if (fragment.isEmpty()) return null
        val params = fragment.split(';').mapNotNull { kv ->
            val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()
        params["S.browser_fallback_url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { isWebUrl(it) }?.let { return it }
        val pkg = params["package"]?.takeIf { Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(it) } ?: return null
        return "https://play.google.com/store/apps/details?id=$pkg"
    }

    /** How to open a store URL on Android, in order: the Play Store app, then anything (the browser). */
    fun storeLaunches(url: String): List<Launch> {
        val p = parse(url) ?: return emptyList()
        val u = url.trim()
        return when (p.scheme) {
            "market" -> {
                val rest = u.substring("market:".length).trimStart('/')
                val web = if (rest.startsWith("details") || rest.startsWith("dev")) "https://play.google.com/store/apps/$rest"
                else "https://play.google.com/store/$rest"
                listOf(Launch(u), Launch(web))
            }
            // Apple's links on Android: the web page.
            "itms-apps", "itms-appss" -> listOf(Launch("https://" + u.substringAfter(':').trimStart('/')))
            else -> if (p.host == "play.google.com") listOf(Launch(u, PLAY_STORE_PACKAGE), Launch(u)) else listOf(Launch(u))
        }
    }
}
