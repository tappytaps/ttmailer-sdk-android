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

    /** Where a navigation inside the message WebView goes. */
    sealed class Nav {
        /** TTMailer's own page (incl. the click redirects): stays in the WebView. */
        object Stay : Nav()
        /** `ttmailer://close`: the message closes. */
        object Close : Nav()
        /** A store URL: the store; the message stays. */
        data class Store(val url: String) : Nav()
        /** Another http(s) page: Custom Tabs; the message stays. */
        data class InApp(val url: String) : Nav()
        /** Another scheme (a deep link): the message closes, then `onDeepLink` or the system opens it. */
        data class DeepLink(val url: String) : Nav()
        /** Nothing happens (an unknown `ttmailer:` link, `file:`, `javascript:`…). */
        object Ignore : Nav()
    }

    /** A way to open a store URL: the URL and the app that should open it (null = any). */
    data class Launch(val url: String, val packageName: String? = null)

    private val APPLE_STORE_HOSTS = setOf("apps.apple.com", "itunes.apple.com")
    private val STORE_SCHEMES = setOf("itms-apps", "itms-appss", "market")
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "data", "blob")
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
     * The TTMailer hosts whose pages stay in the message WebView: the SDK's base URL host, and the host the message
     * itself came from (the same one, unless TTMailer's public URL differs from the API's).
     */
    fun ttmailerHosts(baseUrl: String, messageUrl: String?): Set<String> =
        listOfNotNull(parse(baseUrl)?.host, parse(messageUrl)?.host).toSet()

    /** A navigation inside the message WebView → where it goes. */
    fun navigation(url: String?, ttmailerHosts: Set<String>): Nav {
        val p = parse(url) ?: return Nav.Ignore
        val u = url!!.trim()
        return when {
            p.scheme == "ttmailer" -> if (p.host == "close") Nav.Close else Nav.Ignore
            p.scheme == "http" || p.scheme == "https" -> when {
                p.host == null -> Nav.Ignore
                p.host in ttmailerHosts -> Nav.Stay
                isStoreUrl(u) -> Nav.Store(u)
                else -> Nav.InApp(u)
            }
            p.scheme == "about" -> Nav.Stay
            p.scheme in BLOCKED_SCHEMES -> Nav.Ignore
            isStoreUrl(u) -> Nav.Store(u)
            else -> Nav.DeepLink(u)
        }
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
