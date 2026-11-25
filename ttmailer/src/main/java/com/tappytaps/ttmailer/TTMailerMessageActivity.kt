package com.tappytaps.ttmailer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The in-app message of a rich push (`ttmAction` = `message`, TTM-624): TTMailer's page in a WebView, full screen, with
 * a close button. JavaScript is off. Links to TTMailer (the click redirects) stay here; the final target opens outside
 * (the store, Custom Tabs, or the app's deep link). Started by [TTMailer.handle]; the app does not start it itself.
 */
class TTMailerMessageActivity : Activity() {
    companion object {
        private const val EXTRA_URL = "com.tappytaps.ttmailer.MESSAGE_URL"
        private const val EXTRA_HOSTS = "com.tappytaps.ttmailer.TTMAILER_HOSTS"

        internal fun start(activity: Activity, url: String, ttmailerHosts: Set<String>) {
            val i = Intent(activity, TTMailerMessageActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_HOSTS, ttmailerHosts.toTypedArray())
            activity.startActivity(i)
        }
    }

    private lateinit var url: String
    private var hosts: Set<String> = emptySet()
    private var webView: WebView? = null
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private var failed = false
    /** The navigation the SDK sent outside: its cancellation is not an error of the page. */
    private var handedOut: String? = null
    private var backCallback: Any? = null

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
        registerBack()
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) load()
        else showPage()
    }

    // Layout (built in code: the library brings no layouts or AppCompat)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun color(id: Int) = getColor(id)

    private fun layout(web: WebView): View {
        val background = color(R.color.ttmailer_background)
        val textColor = color(R.color.ttmailer_text)

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
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
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

    private fun load() {
        val web = webView ?: return
        failed = false
        handedOut = null
        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
        // The header tells TTMailer the page is shown in the SDK (it then shows the close links, `ttmailer://close`).
        web.loadUrl(url, mapOf("X-TTMailer-SDK" to TTMailer.SDK_VERSION))
    }

    private fun showPage() {
        progress.visibility = View.GONE
        errorView.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    private fun showError(offline: Boolean) {
        failed = true
        webView?.stopLoading()
        webView?.visibility = View.INVISIBLE
        progress.visibility = View.GONE
        errorText.text = getString(if (offline) R.string.ttmailer_message_offline else R.string.ttmailer_message_failed)
        errorView.visibility = View.VISIBLE
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val target = request.url.toString()
            return when (val nav = RichPush.navigation(target, hosts)) {
                RichPush.Nav.Stay -> false
                RichPush.Nav.Ignore -> true
                RichPush.Nav.Close -> { finish(); true }
                is RichPush.Nav.Store -> { handedOut = target; Launcher.store(this@TTMailerMessageActivity, nav.url); true }
                is RichPush.Nav.InApp -> { handedOut = target; Launcher.inApp(this@TTMailerMessageActivity, nav.url); true }
                is RichPush.Nav.DeepLink -> { handedOut = target; deepLink(nav.url); true }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view.visibility != View.VISIBLE && !failed) progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!failed) showPage()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || request.url.toString() == handedOut) return
            val offline = when (error.errorCode) {
                ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO, ERROR_PROXY_AUTHENTICATION -> true
                else -> false
            }
            // ERROR_UNKNOWN covers a cancelled navigation (ERR_ABORTED): not an error of the page.
            if (error.errorCode == ERROR_UNKNOWN && view.visibility == View.VISIBLE) return
            showError(offline)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) showError(offline = false)
        }
    }

    /** A deep link from the message: the message closes first, then the app's handler, or the system. */
    private fun deepLink(link: String) {
        finish()
        val handler = TTMailer.onDeepLink
        if (handler != null) {
            try { handler(link) } catch (_: Exception) {}
        } else {
            Launcher.system(this, link)
        }
    }

    // Back: the page's own history first, then close.

    private fun goBack() {
        val web = webView
        if (web != null && web.canGoBack() && !failed) web.goBack() else finish()
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    @Deprecated("Below Android 13; OnBackInvokedCallback above.")
    override fun onBackPressed() = goBack()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!failed) webView?.saveState(outState)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
