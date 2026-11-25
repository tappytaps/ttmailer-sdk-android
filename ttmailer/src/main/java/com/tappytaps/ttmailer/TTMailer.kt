package com.tappytaps.ttmailer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * TTMailer SDK: registers the installation and its push token, signs the user up to the newsletter (with a
 * confirmation e-mail) and reports opened pushes. Call [configure] once in `Application.onCreate`; everything else may
 * be called from any thread. Calls that only update the installation never fail: the SDK keeps the state and sends it
 * when it can (on the next change, when the app comes to the foreground, or after a growing pause).
 */
object TTMailer {
    /** This SDK's version; TTMailer learns it with every installation (`sdkVersion`). */
    const val SDK_VERSION = "0.4.0"

    @Volatile private var engine: Engine? = null
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ttmailer-sdk").apply { isDaemon = true }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var retryDelaySec = 30L
    @Volatile private var retryScheduled = false
    @Volatile private var callbacksRegistered = false
    @Volatile private var appContext: Context? = null
    @Volatile private var automaticPurchases = false
    @Volatile private var lifetimeProducts: Set<String>? = null
    @Volatile private var lastStoreReadAt = 0L
    @Volatile private var baseUrl = "https://mailer.tappytaps.com"
    @Volatile private var linkKey = "redirectUrl"

    /**
     * Deep links from an in-app message (a link to another scheme, e.g. `myapp://premium`): the message closes first,
     * then this runs on the main thread. Null (the default): the system opens the link (the app's own intent filters).
     */
    @JvmStatic @Volatile var onDeepLink: ((String) -> Unit)? = null

    /**
     * Starts the SDK with the app key from TTMailer (Nastavení → Aplikace (SDK)).
     * @param appKey `tta_…`, the app's public key.
     * @param baseUrl the TTMailer server.
     * @param linkKey the push data key that holds the push's link (the project's push format, `redirectUrl` by default).
     */
    @JvmStatic @JvmOverloads
    fun configure(context: Context, appKey: String, baseUrl: String = "https://mailer.tappytaps.com", linkKey: String = "redirectUrl") {
        val app = context.applicationContext
        appContext = app
        this.baseUrl = baseUrl
        this.linkKey = linkKey
        engine = Engine(appKey, baseUrl, linkKey, UrlConnectionTransport(), PrefsStore(app), { DeviceInfo.current(app) })
        onWorker { it.syncAndFlush() }
        if (!callbacksRegistered && app is Application) {
            callbacksRegistered = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) = onWorker { it.readStore(); it.syncAndFlush() }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    /** The id of this installation in TTMailer (changes after [reset] or [deleteContact]); null before [configure]. */
    @JvmStatic val installationId: String? get() = engine?.installationId

    /** The FCM registration token (`FirebaseMessagingService.onNewToken`, or `FirebaseMessaging.getInstance().token`). */
    @JvmStatic fun setPushToken(token: String) = onWorker { it.setPushToken(token); it.syncAndFlush() }

    /** Whether the user wants notifications (the app's own switch, or `NotificationManagerCompat.areNotificationsEnabled()`). */
    @JvmStatic fun setPushEnabled(enabled: Boolean) = onWorker { it.setPushEnabled(enabled); it.syncAndFlush() }

    /**
     * The app's own account id when the user signs in, null when they sign out. Use an unguessable id (an account
     * UUID): it joins this installation with the user's other devices.
     */
    @JvmStatic fun setUserId(userId: String?) = onWorker { it.setUserId(userId); it.syncAndFlush() }

    /**
     * Whether TTMailer learns when the user last used the app (on by default): the SDK reports it at launch and when the
     * app comes to the foreground, at most once a day, for audiences like "used the app in the last 30 days". Off: the
     * installation is still sent when it changes, but never counts as activity. Kept between launches.
     */
    @JvmStatic fun setActivityTracking(enabled: Boolean) = onWorker { it.setActivityTracking(enabled); it.syncAndFlush() }

    /**
     * The language the user chose in the app, when it differs from the system's (e.g. "de" or "pt-BR"); null goes back
     * to the system language. TTMailer writes to the user in it. Kept between launches.
     */
    @JvmStatic fun setLanguage(language: String?) = onWorker { it.setLanguage(language); it.syncAndFlush() }

    /** Adds tags to the user's contact in TTMailer (for audiences), e.g. `listOf("sportovec")`. Keys: a-z, 0-9, _ and -,
     * 40 at most (uppercase is lowered). Kept and sent when the device is online. */
    @JvmStatic fun addTags(tags: List<String>) = onWorker { it.addTags(tags); it.syncAndFlush() }

    /** Removes tags from the user's contact in TTMailer. */
    @JvmStatic fun removeTags(tags: List<String>) = onWorker { it.removeTags(tags); it.syncAndFlush() }

    /**
     * The user's premium as the app knows it (e.g. from RevenueCat): until when (ms), its kind (`paid`, `trial`,
     * `offer`…) and when it started. A time in the past says it ended. Sent once, again only when it changes.
     */
    @JvmStatic @JvmOverloads
    fun setPremium(untilMillis: Long, kind: String? = null, startedAtMillis: Long? = null) =
        onWorker { it.setPremium(PremiumReport(untilMillis, false, kind, startedAtMillis, null)); it.syncAndFlush() }

    /** A lifetime premium (a one-time purchase that unlocks the app for good). */
    @JvmStatic @JvmOverloads
    fun setLifetimePremium(kind: String = "lifetime", startedAtMillis: Long? = null) =
        onWorker { it.setPremium(PremiumReport(null, true, kind, startedAtMillis, null)); it.syncAndFlush() }

    /**
     * Turns on the automatic premium (off by default): at launch and whenever the app comes to the foreground the SDK
     * reads the user's purchases from Play Billing (the app's own Play Billing library, 7 or newer) and tells TTMailer
     * the active subscription (premium until its next renewal, kind `paid`) and the one-time products owned (tags
     * `koupeno_<product>`). Every one-time product makes a lifetime premium; an app that also sells add-ons (e.g. a
     * theme pack) names the ones that unlock the app in [lifetimeProductIds], the others are only tags. Consumables are
     * not reported. Call it after [configure] on every launch. Use it or [setPremium], not both. Without Play Billing in
     * the app it does nothing.
     */
    @JvmStatic @JvmOverloads
    fun enableAutomaticPurchases(lifetimeProductIds: Set<String>? = null) {
        lifetimeProducts = lifetimeProductIds
        automaticPurchases = true
        lastStoreReadAt = 0
        onWorker { it.readStore(); it.syncAndFlush() }
    }

    /**
     * Call when the user opens a notification: `RemoteMessage.data`, or the extras of the launch intent (FCM puts the
     * data there when the app was in the background). Reports the open and returns the push's link, if it has one.
     */
    @JvmStatic fun opened(data: Map<String, String?>): String? {
        val e = engine ?: return null
        val link = linkOf(data, e.linkKey)
        val token = data["ttmToken"]
        if (!token.isNullOrEmpty()) onWorker { it.opened(mapOf("ttmToken" to token)); it.syncAndFlush() }
        return link
    }

    /** [opened] with the extras of the activity's intent. Prefer [handle], which also does the push's tap action. */
    @JvmStatic fun opened(extras: Bundle?): String? {
        if (extras == null) return null
        return opened(extrasMap(extras))
    }

    /**
     * Call when the user opens a notification (instead of [opened]): reports the open, then does what the push says
     * on a tap (TTMailer: Po klepnutí):
     * - open the app (the default): returns the push's link (a deep link or URL) for the app to open, as [opened];
     * - the browser, or the in-app browser (Custom Tabs): opens the link and returns null;
     * - an in-app message: shows it full screen over [activity] and returns null.
     *
     * Links to App Store or Google Play always open the store. Call it on the main thread, once per tap (e.g. in
     * `onCreate` only when `savedInstanceState == null`, and in `onNewIntent`).
     * @param data `RemoteMessage.data`, or the extras of the launch intent (see the other overload).
     */
    @JvmStatic fun handle(activity: Activity, data: Map<String, String?>): String? {
        val link = opened(data)
        return when (val tap = RichPush.tap(data, engine?.linkKey ?: linkKey)) {
            is RichPush.Tap.ReturnLink -> link ?: tap.link
            else -> { onMain { perform(activity, tap) }; null }
        }
    }

    /** [handle] with the extras of the activity's intent (FCM puts the push's data there when the app was in the background). */
    @JvmStatic fun handle(activity: Activity, extras: Bundle?): String? {
        if (extras == null) return null
        return handle(activity, extrasMap(extras))
    }

    /**
     * Signs the address up to the newsletter. TTMailer sends a confirmation e-mail; after the confirmation the address
     * joins this installation. [callback] runs on the main thread with `Result.success(true)`, or a [TTMailerException].
     */
    @JvmStatic @JvmOverloads
    fun subscribe(email: String, language: String? = null, callback: (Result<Boolean>) -> Unit) =
        call(callback) { it.subscribe(email, language) }

    /** [subscribe] as a suspend function; throws [TTMailerException]. */
    suspend fun subscribe(email: String, language: String? = null): Boolean = await { cb -> subscribe(email, language, cb) }

    /** Removes this installation from TTMailer (its device; its contact too when it has no e-mail) and starts a new one. */
    @JvmStatic fun reset(callback: (Result<Unit>) -> Unit) = call(callback) { it.reset() }
    suspend fun reset(): Unit = await { cb -> reset(cb) }

    /** Erases the user's contact in TTMailer (they deleted their account) and starts a new installation. */
    @JvmStatic fun deleteContact(callback: (Result<Unit>) -> Unit) = call(callback) { it.deleteContact() }
    suspend fun deleteContact(): Unit = await { cb -> deleteContact(cb) }

    // Internals

    private fun extrasMap(extras: Bundle): Map<String, String?> {
        val map = HashMap<String, String?>()
        for (k in extras.keySet()) map[k] = extras.getString(k)
        return map
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun perform(activity: Activity, tap: RichPush.Tap) {
        // A trampoline or splash activity that already finishes: the action still runs, from the application
        // context in a new task (call handle from the activity that stays, after routing, to keep the app's task).
        val context: Context = if (activity.isFinishing || activity.isDestroyed) {
            Log.w("TTMailer", "handle: ${activity.javaClass.simpleName} is finishing, the push's action starts from the application context")
            activity.applicationContext
        } else activity
        try {
            when (tap) {
                is RichPush.Tap.Browser -> Launcher.browser(context, tap.url)
                is RichPush.Tap.InApp -> Launcher.inApp(context, tap.url)
                is RichPush.Tap.Store -> Launcher.store(context, tap.url)
                is RichPush.Tap.Message -> TTMailerMessageActivity.start(context, tap.url, RichPush.ttmailerHosts(engine?.baseUrl ?: baseUrl, tap.url))
                is RichPush.Tap.ReturnLink -> {}
            }
        } catch (e: Exception) {
            Log.w("TTMailer", "handle: the push's action failed", e)
            // A tap must never crash the app.
        }
    }

    private fun onWorker(block: (Engine) -> Unit) {
        val e = engine ?: return
        // Throwable: an error of the app's own libraries (e.g. another Play Billing) must never kill the worker or the app.
        worker.execute { try { block(e) } catch (_: Throwable) { scheduleRetry() } }
    }

    /** The store's purchases, at most every 5 s (a resume follows every screen; Play answers from its local cache). */
    private fun Engine.readStore() {
        if (!automaticPurchases) return
        val products = lifetimeProducts
        val ctx = appContext ?: return
        val t = System.currentTimeMillis()
        if (t - lastStoreReadAt < 5_000) return
        lastStoreReadAt = t
        BillingReader.entitlements(ctx, t)?.let { storeEntitlements(it, products) }
    }

    private fun Engine.syncAndFlush() {
        val ok = sync() and flushOpens() and flushChanges()
        if (ok) retryDelaySec = 30 else scheduleRetry()
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        val delay = retryDelaySec
        retryDelaySec = minOf(retryDelaySec * 2, 3600)
        worker.schedule({ retryScheduled = false; engine?.let { e -> try { e.syncAndFlush() } catch (_: Throwable) {} } }, delay, TimeUnit.SECONDS)
    }

    private fun <T> call(callback: (Result<T>) -> Unit, block: (Engine) -> T) {
        val e = engine
        if (e == null) { main.post { callback(Result.failure(TTMailerException(TTMailerException.Reason.NOT_CONFIGURED))) }; return }
        worker.execute {
            val r = try { Result.success(block(e)) } catch (x: TTMailerException) { Result.failure(x) } catch (x: Exception) {
                Result.failure(TTMailerException(TTMailerException.Reason.NETWORK))
            }
            main.post { callback(r) }
        }
    }

    private suspend fun <T> await(start: ((Result<T>) -> Unit) -> Unit): T = suspendCoroutine { cont ->
        start { r -> r.fold({ cont.resume(it) }, { cont.resumeWithException(it) }) }
    }
}
