package com.tappytaps.ttmailer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    @Volatile private var engine: Engine? = null
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ttmailer-sdk").apply { isDaemon = true }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var retryDelaySec = 30L
    @Volatile private var retryScheduled = false
    @Volatile private var callbacksRegistered = false
    @Volatile private var appContext: Context? = null
    @Volatile private var lifetimeProducts: Set<String>? = null
    @Volatile private var lastStoreReadAt = 0L

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
     * `koupeno_<product>`). Products in [lifetimeProductIds] make a lifetime premium. Call it after [configure] on every
     * launch. Use it or [setPremium], not both. Without Play Billing in the app it does nothing.
     */
    @JvmStatic @JvmOverloads
    fun enableAutomaticPurchases(lifetimeProductIds: Set<String> = emptySet()) {
        lifetimeProducts = lifetimeProductIds
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

    /** [opened] with the extras of the activity's intent. */
    @JvmStatic fun opened(extras: Bundle?): String? {
        if (extras == null) return null
        val map = HashMap<String, String?>()
        for (k in extras.keySet()) map[k] = extras.getString(k)
        return opened(map)
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

    private fun onWorker(block: (Engine) -> Unit) {
        val e = engine ?: return
        // Throwable: an error of the app's own libraries (e.g. another Play Billing) must never kill the worker or the app.
        worker.execute { try { block(e) } catch (_: Throwable) { scheduleRetry() } }
    }

    /** The store's purchases, at most every 5 s (a resume follows every screen; Play answers from its local cache). */
    private fun Engine.readStore() {
        val products = lifetimeProducts ?: return
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
