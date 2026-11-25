package com.tappytaps.ttmailer

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The SDK's work: the installation sync, open reports and the calls the app waits for. Blocking (network on the
 * calling thread); the public TTMailer object runs it on its own background thread. Thread-safe.
 */
internal class Engine(
    private val appKey: String,
    val baseUrl: String,
    val linkKey: String,
    private val transport: HttpTransport,
    private val store: KeyValueStore,
    private val deviceInfo: () -> DeviceInfo,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private object K {
        const val INSTALLATION = "installationId"
        const val TOKEN = "pushToken"
        const val ENABLED = "pushEnabled"
        const val USER_SET = "userIdSet"
        const val USER = "userId"
        const val LAST_BODY = "lastSentBody"
        const val LAST_AT = "lastSentAt"
        const val DEAD_KEY_AT = "deadKeyAt"
        const val OPENS = "pendingOpens"
        const val LANGUAGE = "language"
        const val TRACK = "trackActivity"
        const val TAGS = "pendingTags"
        const val PREMIUM = "pendingPremium"
        const val SENT_PREMIUM = "sentPremium"
    }

    companion object {
        /** An unchanged installation is still sent once a day (the contact's "last active in the app"). */
        const val RESEND_AFTER_MS = 24L * 3600 * 1000
        const val MAX_PENDING_OPENS = 100
        /** Tags per request (the server takes 20 in each list). */
        const val TAGS_PER_CALL = 20
        private val TAG = Regex("^[a-z0-9_-]{1,40}$")

        internal fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }

    @Volatile var installationId: String = store.get(K.INSTALLATION)?.takeIf { it.isNotEmpty() } ?: newId()
        private set

    private fun newId(): String = UUID.randomUUID().toString().also { store.set(K.INSTALLATION, it) }

    val pendingOpens: Int @Synchronized get() = loadOpens().size

    // The installation

    @Synchronized fun setPushToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) return
        change(K.TOKEN, t)
    }

    @Synchronized fun setPushEnabled(enabled: Boolean) = change(K.ENABLED, enabled.toString())

    @Synchronized fun setUserId(userId: String?) {
        val id = userId?.trim()?.takeIf { it.isNotEmpty() }
        if (store.get(K.USER_SET) == "true" && store.get(K.USER) == id) return
        store.set(K.USER_SET, "true")
        store.set(K.USER, id)
        sync()
    }

    /** TTM-588: activity reporting on (default) or off; off is stored as "false". */
    @Synchronized fun setActivityTracking(enabled: Boolean) {
        val v = if (enabled) null else "false"
        if (store.get(K.TRACK) == v) return
        store.set(K.TRACK, v)
        sync()
    }

    /** The app's own language (TTM-584); null = the system's. */
    @Synchronized fun setLanguage(language: String?) {
        val l = language?.trim()?.takeIf { it.isNotEmpty() }
        if (store.get(K.LANGUAGE) == l) return
        store.set(K.LANGUAGE, l)
        sync()
    }

    private fun change(key: String, value: String) {
        if (store.get(key) == value) return
        store.set(key, value)
        sync()
    }

    private fun body(): String {
        val info = deviceInfo()
        val b = LinkedHashMap<String, Any?>()
        (store.get(K.LANGUAGE) ?: info.language)?.let { b["language"] = it }
        info.timeZone?.let { b["timeZone"] = it }
        info.appVersion?.let { b["appVersion"] = it }
        info.osVersion?.let { b["osVersion"] = it }
        store.get(K.TOKEN)?.let { b["pushToken"] = it }
        store.get(K.ENABLED)?.let { b["pushEnabled"] = it == "true" }
        if (store.get(K.USER_SET) == "true") b["userId"] = store.get(K.USER)
        if (store.get(K.TRACK) == "false") b["trackActivity"] = false
        // TTM-624: TTMailer knows which devices can show rich pushes.
        b["sdkVersion"] = TTMailer.SDK_VERSION
        return Json.encode(b)
    }

    /** Sends the installation when it changed since the last success, or a day passed. True when TTMailer has it. */
    @Synchronized fun sync(): Boolean {
        val b = body()
        val t = now()
        // A key TTMailer does not know (rotated, disabled, a typo): tried again after a day, not on every call.
        store.get(K.DEAD_KEY_AT)?.toLongOrNull()?.let { if (t - it < RESEND_AFTER_MS) return false }
        val lastAt = store.get(K.LAST_AT)?.toLongOrNull()
        // Unchanged: sent again once a day ("last active in the app"; with activity reporting off it only keeps the
        // installation alive, TTM-588).
        if (store.get(K.LAST_BODY) == b && lastAt != null && t - lastAt < RESEND_AFTER_MS) return true
        val r = request("PUT", "/sdk/v1/installations/$installationId", b) ?: return false
        return when {
            r.code in 200..299 -> { store.set(K.LAST_BODY, b); store.set(K.LAST_AT, t.toString()); true }
            r.code == 404 -> { store.set(K.DEAD_KEY_AT, t.toString()); false }
            // The body itself is wrong: the next change or day tries again.
            r.code == 400 -> { store.set(K.LAST_BODY, b); store.set(K.LAST_AT, t.toString()); false }
            else -> false
        }
    }

    // Tags and premium (TTM-584, TTM-585)

    val pendingTagCount: Int @Synchronized get() = loadTags().size

    @Synchronized fun addTags(tags: List<String>) = changeTags(tags, true)
    @Synchronized fun removeTags(tags: List<String>) = changeTags(tags, false)

    private fun changeTags(tags: List<String>, add: Boolean) {
        // What TTMailer takes (a-z 0-9 _ -, 40 at most): one bad tag must not lose the others of its request.
        val clean = tags.map { it.trim().lowercase(Locale.ROOT) }.filter { TAG.matches(it) }
        if (clean.isEmpty()) return
        saveTags(loadTags() + clean.associateWith { add })
    }

    /** The premium the app or the store reading reports; sent once, again only when it changes. */
    @Synchronized fun setPremium(report: PremiumReport) {
        val pending = PremiumReport.parse(store.get(K.PREMIUM))
        if (pending == null && report == PremiumReport.parse(store.get(K.SENT_PREMIUM))) return
        store.set(K.PREMIUM, report.line())
    }

    /** What the store says now (automatic purchases): a report when it differs from the last one sent. */
    @Synchronized internal fun storeEntitlements(entitlements: List<Entitlement>, lifetimeProducts: Set<String>?) {
        val previous = PremiumReport.parse(store.get(K.PREMIUM)) ?: PremiumReport.parse(store.get(K.SENT_PREMIUM))
        PremiumReport.from(entitlements, lifetimeProducts, previous, now())?.let { setPremium(it) }
    }

    /** Sends waiting tag changes and the premium after the installation. False: something waits for a retry. */
    @Synchronized fun flushChanges(): Boolean {
        if (loadTags().isEmpty() && store.get(K.PREMIUM) == null) return true
        if (!sync()) return false
        while (true) {
            val tags = loadTags()
            if (tags.isEmpty()) break
            val add = tags.filterValues { it }.keys.sorted().take(TAGS_PER_CALL)
            val remove = tags.filterValues { !it }.keys.sorted().take(TAGS_PER_CALL)
            val r = request("POST", "/sdk/v1/installations/$installationId/tags", Json.encode(linkedMapOf("add" to add, "remove" to remove)))
            if (!handled(r)) return false
            saveTags(tags - add.toSet() - remove.toSet())
        }
        val line = store.get(K.PREMIUM) ?: return true
        val p = PremiumReport.parse(line) ?: run { store.set(K.PREMIUM, null); return true }
        val r = request("POST", "/sdk/v1/installations/$installationId/premium", p.json())
        if (!handled(r)) return false
        if (r!!.code in 200..299) store.set(K.SENT_PREMIUM, line)
        store.set(K.PREMIUM, null)
        return true
    }

    /** True when the change is done with: sent, or refused for good (a 4xx). False: kept for later (network, 5xx,
     * 408/429, or TTMailer does not know the installation: it is sent again first). */
    private fun handled(r: HttpResponse?): Boolean {
        if (r == null) return false
        if (r.code in 200..299) return true
        if (r.code == 409 && Json.parseObject(r.body)["error"] == "unknown_installation") { store.set(K.LAST_BODY, null); return false }
        return !(r.code >= 500 || r.code == 408 || r.code == 429)
    }

    private fun loadTags(): Map<String, Boolean> = store.get(K.TAGS)?.split('\n')?.mapNotNull { line ->
        val p = line.split('\t'); if (p.size == 2) p[0] to (p[1] == "1") else null
    }?.toMap() ?: emptyMap()

    private fun saveTags(tags: Map<String, Boolean>) =
        store.set(K.TAGS, if (tags.isEmpty()) null else tags.entries.joinToString("\n") { "${it.key}\t${if (it.value) "1" else "0"}" })

    // Push opens

    /** The push's link (data key [linkKey]); an open report is queued when the push carries `ttmToken`. */
    @Synchronized fun opened(data: Map<String, String?>): String? {
        val token = data["ttmToken"]
        if (!token.isNullOrEmpty() && token.length <= 256 && token.none { it == '\t' || it == '\n' }) {
            val opens = loadOpens() + (token to iso(now()))
            saveOpens(opens.takeLast(MAX_PENDING_OPENS))
        }
        return linkOf(data, linkKey)
    }

    /** Sends queued open reports: dropped on 2xx and on 4xx (except 408 and 429), kept for later otherwise. */
    @Synchronized fun flushOpens(): Boolean {
        var opens = loadOpens()
        while (opens.isNotEmpty()) {
            val (token, at) = opens.first()
            val r = request("POST", "/p/open", Json.encode(linkedMapOf("t" to token, "at" to at)), withKey = false)
            if (r == null || r.code >= 500 || r.code == 408 || r.code == 429) return false
            opens = opens.drop(1)
            saveOpens(opens)
        }
        return true
    }

    private fun loadOpens(): List<Pair<String, String>> = store.get(K.OPENS)?.split('\n')?.mapNotNull { line ->
        val p = line.split('\t'); if (p.size == 2) p[0] to p[1] else null
    } ?: emptyList()

    private fun saveOpens(opens: List<Pair<String, String>>) =
        store.set(K.OPENS, if (opens.isEmpty()) null else opens.joinToString("\n") { "${it.first}\t${it.second}" })

    // Calls the app waits for

    /** Signs the address up through the app's form; TTMailer sends a confirmation e-mail. Returns whether it confirms. */
    fun subscribe(email: String, language: String?): Boolean {
        // The installation first, so the confirmed address joins this installation's contact.
        sync()
        val b = linkedMapOf<String, Any?>("email" to email.trim())
        (language ?: store.get(K.LANGUAGE) ?: deviceInfo().language)?.let { b["language"] = it }
        val r = check(request("POST", "/sdk/v1/installations/$installationId/subscribe", Json.encode(b)))
        return Json.parseObject(r.body)["confirm"] as? Boolean ?: true
    }

    /** Removes this installation's device (and its anonymous contact) and starts a new installation. */
    @Synchronized fun reset() {
        check(request("DELETE", "/sdk/v1/installations/$installationId", null))
        newInstallation()
    }

    /** Erases the installation's contact in TTMailer (the user deleted their account) and starts a new installation. */
    @Synchronized fun deleteContact() {
        check(request("DELETE", "/sdk/v1/installations/$installationId/contact", null))
        newInstallation()
    }

    private fun newInstallation() {
        installationId = UUID.randomUUID().toString()
        store.set(K.INSTALLATION, installationId)
        listOf(K.TOKEN, K.ENABLED, K.USER_SET, K.USER, K.LAST_BODY, K.LAST_AT, K.DEAD_KEY_AT, K.TAGS, K.PREMIUM, K.SENT_PREMIUM).forEach { store.set(it, null) }
    }

    private fun check(r: HttpResponse?): HttpResponse {
        if (r == null) throw TTMailerException(TTMailerException.Reason.NETWORK)
        if (r.code in 200..299) return r
        val code = Json.parseObject(r.body)["error"] as? String
        val reason = when {
            r.code == 400 && code == "invalid_email" -> TTMailerException.Reason.INVALID_EMAIL
            r.code == 409 && code == "no_form" -> TTMailerException.Reason.NO_FORM
            r.code == 409 && code == "form_without_confirmation" -> TTMailerException.Reason.FORM_WITHOUT_CONFIRMATION
            r.code == 429 -> TTMailerException.Reason.RATE_LIMITED
            else -> TTMailerException.Reason.SERVER
        }
        throw TTMailerException(reason, r.code, code)
    }

    // HTTP

    private fun request(method: String, path: String, body: String?, withKey: Boolean = true): HttpResponse? {
        val headers = LinkedHashMap<String, String>()
        if (withKey) headers["X-TTMailer-App-Key"] = appKey
        if (body != null) headers["Content-Type"] = "application/json"
        return try {
            transport.send(method, baseUrl.trimEnd('/') + path, headers, body)
        } catch (_: IOException) {
            null
        }
    }

}

/** The push's link under [key] when it is an absolute URL (a deep link or https). */
internal fun linkOf(data: Map<String, String?>, key: String): String? {
    val s = data[key]?.trim() ?: return null
    if (s.isEmpty() || s.any { it.isWhitespace() }) return null
    return if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(s)) s else null
}
