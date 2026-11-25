package com.tappytaps.ttmailer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService

/**
 * Opens what a rich push or a message points to (TTM-624). Called on the main thread. Never throws. [Context] is an
 * activity, or the application context (then the launch gets a new task) when the app's activity is already finishing.
 */
internal object Launcher {
    /** The system browser (a store URL goes to the store). */
    fun browser(context: Context, url: String) {
        if (RichPush.isStoreUrl(url)) return store(context, url)
        view(context, url, browsable = true)
    }

    /** Custom Tabs; the system browser when no browser provides Custom Tabs. A store URL goes to the store. */
    fun inApp(context: Context, url: String) {
        if (RichPush.isStoreUrl(url)) return store(context, url)
        val provider = try {
            // The browsers that offer Custom Tabs (visible through the library manifest's <queries>); the default
            // browser first when it is one of them.
            val packages = context.packageManager
                .queryIntentServices(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0)
                .map { it.serviceInfo.packageName }
            if (packages.isEmpty()) null else CustomTabsClient.getPackageName(context, packages)
        } catch (_: Exception) { null }
        if (provider != null) {
            try {
                val tab = CustomTabsIntent.Builder()
                    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                    .setShowTitle(true)
                    .build()
                tab.intent.setPackage(provider)
                if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(context, Uri.parse(url))
                return
            } catch (_: Exception) {
                // The provider went away (disabled, uninstalled): the browser.
            }
        }
        view(context, url, browsable = true)
    }

    /** The store: Google Play's app for its links, else any app that takes it (the browser). */
    fun store(context: Context, url: String) {
        for (l in RichPush.storeLaunches(url)) {
            if (view(context, l.url, browsable = false, packageName = l.packageName)) return
        }
    }

    /** A link through the system (the app's deep link, mailto:, tel:…). False when no app takes it. */
    fun system(context: Context, url: String): Boolean = view(context, url, browsable = false)

    /**
     * An `intent:` URL from a web page, sanitised as Chrome does: only a browsable activity, no explicit component or
     * selector, no URI grants. Without an app for it: its `S.browser_fallback_url`, or its package in Google Play.
     */
    fun intent(context: Context, url: String) {
        val launched = try {
            val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
        if (launched) return
        val fallback = RichPush.intentFallback(url) ?: return
        if (RichPush.isStoreUrl(fallback)) store(context, fallback) else inApp(context, fallback)
    }

    private fun view(context: Context, url: String, browsable: Boolean, packageName: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (browsable) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        if (packageName != null) intent.setPackage(packageName)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
