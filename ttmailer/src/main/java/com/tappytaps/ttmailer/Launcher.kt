package com.tappytaps.ttmailer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/** Opens what a rich push or a message points to (TTM-624). Called on the main thread. Never throws. */
internal object Launcher {
    /** The system browser (a store URL goes to the store). */
    fun browser(activity: Activity, url: String) {
        if (RichPush.isStoreUrl(url)) return store(activity, url)
        view(activity, url, browsable = true)
    }

    /** Custom Tabs; the system browser when no browser provides Custom Tabs. A store URL goes to the store. */
    fun inApp(activity: Activity, url: String) {
        if (RichPush.isStoreUrl(url)) return store(activity, url)
        val provider = try { CustomTabsClient.getPackageName(activity, null) } catch (_: Exception) { null }
        if (provider != null) {
            try {
                val tab = CustomTabsIntent.Builder()
                    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                    .setShowTitle(true)
                    .build()
                tab.intent.setPackage(provider)
                tab.launchUrl(activity, Uri.parse(url))
                return
            } catch (_: Exception) {
                // The provider went away (disabled, uninstalled): the browser.
            }
        }
        view(activity, url, browsable = true)
    }

    /** The store: Google Play's app for its links, else any app that takes it (the browser). */
    fun store(activity: Activity, url: String) {
        for (l in RichPush.storeLaunches(url)) {
            if (view(activity, l.url, browsable = false, packageName = l.packageName)) return
        }
    }

    /** A deep link through the system (an app's link, mailto:, tel:…). */
    fun system(context: Context, url: String) {
        view(context, url, browsable = false)
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
