package com.tappytaps.ttmailer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.ByteArrayInputStream

/**
 * The in-app message of a rich push (`ttmAction` = `message`, TTM-624): TTMailer's page in a WebView, full screen, with
 * a close button. JavaScript is off. TTMailer's https pages (the click redirects) stay here, always loaded with the SDK
 * header; the final target opens outside (the store, Custom Tabs, the app's deep link or the system). Started by
 * [TTMailer.handle]; the app does not start it itself.
 */
class TTMailerMessageActivity : Activity() {
    companion object {
        private const val TAG = "TTMailer"
        private const val EXTRA_URL = "com.tappytaps.ttmailer.MESSAGE_URL"
        private const val EXTRA_HOSTS = "com.tappytaps.ttmailer.TTMAILER_HOSTS"
        /** The first load shows the error view when the page is not there by then. */
        private const val LOAD_TIMEOUT_MS = 25_000L

        /** [context] is the app's activity, or the application context when that activity is finishing. */
        internal fun start(context: Context, url: String, ttmailerHosts: Set<String>) {
            val i = Intent(context, TTMailerMessageActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_HOSTS, ttmailerHosts.toTypedArray())
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    private lateinit var url: String
    private var hosts: Set<String> = emptySet()
    private var webView: WebView? = null
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    /** The error view is shown (only for the first load). */
    private var failed = false
    /** The message has been shown once: later errors never replace it. */
    private var loadedOnce = false
    /** The navigation the SDK sent outside: its cancellation is not an error of the page. */
    private var handedOut: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (!loadedOnce && !failed) showError(offline = true) }
    private var backCallback: Any? = null
    private var backRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u = intent.getStringExtra(EXTRA_URL)
        if (u == null || !RichPush.isWebUrl(u)) { finish(); return }
        url = u
        hosts = intent.getStringArrayExtra(EXTRA_HOSTS)?.toSet() ?: RichPush.ttmailerHosts(u, null)

        val web = try { WebView(this) } catch (_: Throwable) {
            // No WebView on the device (being updated, disabled): the page in Custom Tabs instead.
            Launcher.inApp(this, u); finish(); return
        }
        webView = web
        setContentView(layout(web))
        configure(web)
        // Always a fresh load (also after a recreation or process death): every TTMailer load carries the header,
        // which a restored WebView state would not.
        load()
    }

    // Layout (built in code: the library brings no layouts or AppCompat)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun layout(web: WebView): View {
        val background = getColor(R.color.ttmailer_background)
        val textColor = getColor(R.color.ttmailer_text)

        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ttmailer_ic_close)
            imageTintList = ColorStateList.valueOf(textColor)
            contentDescription = getString(R.string.ttmailer_close)
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { finish() }
        }
        val bar = FrameLayout(this).apply {
            addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) })
        }

        web.setBackgroundColor(background)
        progress = ProgressBar(this).apply { isIndeterminate = true }
        errorText = TextView(this).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        val retry = Button(this).apply {
            text = getString(R.string.ttmailer_try_again)
            setOnClickListener { load() }
        }
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(errorText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            visibility = View.GONE
        }
        val content = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(errorView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Edge-to-edge (forced from Android 15): keep the bar and the page out of the system bars and the cutout.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        return root
    }

    private fun configure(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.webViewClient = Client()
    }

    // Loading

    private val sdkHeader get() = mapOf("X-TTMailer-SDK" to TTMailer.SDK_VERSION)

    /** The first load, or Try again. */
    private fun load() {
        val web = webView ?: return
        failed = false
        loadedOnce = false
        handedOut = null
        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, LOAD_TIMEOUT_MS)
        // The header tells TTMailer the page is shown in the SDK (it then shows the close links, `ttmailer://close`).
        web.loadUrl(url, sdkHeader)
    }

    private fun showPage() {
        handler.removeCallbacks(timeout)
        progress.visibility = View.GONE
        errorView.visibility = View.GONE
        webView?.visibility = View.VISIBLE
        if (!loadedOnce) {
            loadedOnce = true
            // Back walks only the pages opened from the message, never back into a failed attempt.
            webView?.clearHistory()
        }
        updateBack()
    }

    /** A failure: the error view before the message was shown, later only a short notice (the message stays). */
    private fun failure(offline: Boolean) {
        if (loadedOnce) {
            val web = webView ?: return
            web.stopLoading()
            if (web.canGoBack()) web.goBack()
            Toast.makeText(this, R.string.ttmailer_link_failed, Toast.LENGTH_SHORT).show()
        } else {
            showError(offline)
        }
    }

    private fun showError(offline: Boolean) {
        handler.removeCallbacks(timeout)
        failed = true
        webView?.stopLoading()
        webView?.visibility = View.INVISIBLE
        progress.visibility = View.GONE
        errorText.text = getString(if (offline) R.string.ttmailer_message_offline else R.string.ttmailer_message_failed)
        errorView.visibility = View.VISIBLE
        updateBack()
    }

    /** Whether a scheme is the app's own: an activity of this app takes the link. */
    private fun appTakes(link: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(packageName)
        packageManager.queryIntentActivities(i, 0).isNotEmpty()
    } catch (_: Exception) { false }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            val nav = RichPush.decide(target, request.isForMainFrame, request.hasGesture(), request.isRedirect, hosts) { appTakes(target) }
            if (nav.leaves) handedOut = target
            val activity = this@TTMailerMessageActivity
            when (nav) {
                is RichPush.Nav.Stay -> {
                    // Sub-frames load TTMailer's pages as they are; the main frame always with the header (a link or a
                    // redirect would go without it): cancelled and loaded again with it.
                    if (!request.isForMainFrame) return false
                    view.loadUrl(nav.url, sdkHeader)
                }
                RichPush.Nav.Ignore -> {}
                RichPush.Nav.Close -> finish()
                is RichPush.Nav.Store -> Launcher.store(activity, nav.url)
                is RichPush.Nav.InApp -> Launcher.inApp(activity, nav.url)
                is RichPush.Nav.AndroidIntent -> Launcher.intent(activity, nav.url)
                is RichPush.Nav.System -> Launcher.system(activity, nav.url)
                is RichPush.Nav.AppLink -> deepLink(nav.url)
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Frames show only TTMailer's pages; images, styles and fonts load from anywhere.
            if (RichPush.blocksSubframe(request.url.toString(), request.isForMainFrame, request.requestHeaders, hosts)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!loadedOnce && !failed) progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!failed && url != null && url != "about:blank") showPage()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateBack()

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || request.url.toString() == handedOut) return
            val offline = when (error.errorCode) {
                ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO, ERROR_PROXY_AUTHENTICATION -> true
                else -> false
            }
            failure(offline)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) failure(offline = false)
        }

        override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
            sslHandler.cancel()
            failure(offline = false)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The WebView is unusable now: drop it and close the message (the app keeps running).
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            if (webView === view) webView = null
            finish()
            return true
        }
    }

    /** One of the app's own links from the message: the message closes first, then the app's handler, or the system. */
    private fun deepLink(link: String) {
        finish()
        val handler = TTMailer.onDeepLink
        if (handler != null) {
            try { handler(link) } catch (e: Exception) { Log.w(TAG, "onDeepLink failed", e) }
        } else {
            Launcher.system(this, link)
        }
    }

    // Back: the pages opened from the message first, then close. The callback is on only while there is a page to go
    // back to, so the predictive back animation shows closing otherwise.

    private fun canGoBack(): Boolean = !failed && webView?.canGoBack() == true

    private fun goBack() {
        if (canGoBack()) webView?.goBack() else finish()
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = canGoBack()
        if (want == backRegistered) return
        val cb = (backCallback as? OnBackInvokedCallback) ?: OnBackInvokedCallback { goBack() }.also { backCallback = it }
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        backRegistered = want
    }

    @Deprecated("Below Android 13; OnBackInvokedCallback above.")
    override fun onBackPressed() = goBack()

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        if (Build.VERSION.SDK_INT >= 33 && backRegistered) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
