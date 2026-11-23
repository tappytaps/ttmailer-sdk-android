package com.tappytaps.ttmailer

import java.util.Calendar
import java.util.TimeZone

/**
 * What the SDK tells TTMailer about the user's purchases (TTM-585): the premium's end (or lifetime), its kind and
 * start, and every one-time product the user owns (each a tag `koupeno_<product>` in TTMailer). Times in ms.
 * [products] null = not reported (the products' tags stay as they are); empty = owns none.
 */
data class PremiumReport(
    val until: Long?,
    val lifetime: Boolean,
    val kind: String?,
    val startedAt: Long?,
    val products: List<String>?,
) {
    internal fun json(): String {
        val b = LinkedHashMap<String, Any?>()
        if (lifetime) b["lifetime"] = true else until?.let { b["until"] = Engine.iso(it) }
        kind?.let { b["kind"] = it }
        startedAt?.let { b["startedAt"] = Engine.iso(it) }
        products?.let { b["products"] = it }
        return Json.encode(b)
    }

    /** One line for the store (fields split by U+001F, products by U+001E). */
    internal fun line(): String = listOf(until?.toString() ?: "", lifetime.toString(), kind ?: "", startedAt?.toString() ?: "",
        products?.joinToString("\u001e") ?: "\u0000").joinToString("\u001f")

    companion object {
        internal fun parse(s: String?): PremiumReport? {
            val p = s?.split('\u001f') ?: return null
            if (p.size != 5) return null
            return PremiumReport(p[0].toLongOrNull(), p[1] == "true", p[2].ifEmpty { null }, p[3].toLongOrNull(),
                when (p[4]) { "\u0000" -> null; "" -> emptyList(); else -> p[4].split('\u001e') })
        }

        /**
         * The report for what the store says now, given the last one sent; null = nothing to tell (never paid).
         * A lifetime product → lifetime; else the active subscription ending last → until its end; nothing active
         * after a premium → it ended now (a refund, a cancellation), kind and start stay.
         */
        internal fun from(entitlements: List<Entitlement>, lifetimeProducts: Set<String>, previous: PremiumReport?, now: Long): PremiumReport? {
            val products = entitlements.filter { it.kind == Entitlement.Kind.ONE_TIME }.map { it.productId }.distinct().sorted()
            entitlements.firstOrNull { it.kind == Entitlement.Kind.ONE_TIME && it.productId in lifetimeProducts }?.let {
                return PremiumReport(null, true, "lifetime", it.originalPurchaseAt, products)
            }
            entitlements.filter { it.kind == Entitlement.Kind.SUBSCRIPTION && (it.expiresAt ?: 0) > now }.maxByOrNull { it.expiresAt!! }?.let {
                return PremiumReport(it.expiresAt, false, if (it.trial) "trial" else "paid", it.originalPurchaseAt, products)
            }
            if (previous != null && (previous.lifetime || previous.until != null)) {
                val wasActive = previous.lifetime || (previous.until ?: 0) > now
                return PremiumReport(if (wasActive) now else previous.until, false, previous.kind, previous.startedAt, products)
            }
            if (products.isEmpty() && previous?.products.isNullOrEmpty()) return null
            return PremiumReport(null, false, null, null, products)
        }
    }
}

/** One purchase the store says the user has now. */
internal data class Entitlement(val productId: String, val kind: Kind, val expiresAt: Long?, val originalPurchaseAt: Long?, val trial: Boolean) {
    enum class Kind { SUBSCRIPTION, ONE_TIME }
}

/**
 * Play Billing does not tell the app when a subscription ends: its next renewal after [now], counted from the purchase
 * by the billing period (ISO 8601, e.g. P1W, P1M, P3M, P1Y). Null for a period it cannot read.
 */
internal fun subscriptionEnd(purchaseTime: Long, period: String, now: Long): Long? {
    val m = Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?$").matchEntire(period) ?: return null
    val (y, mo, w, d) = m.destructured
    val years = y.toIntOrNull() ?: 0; val months = mo.toIntOrNull() ?: 0; val days = (w.toIntOrNull() ?: 0) * 7 + (d.toIntOrNull() ?: 0)
    if (years == 0 && months == 0 && days == 0) return null
    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = purchaseTime }
    var n = 0
    while (c.timeInMillis <= now && n++ < 10_000) { c.add(Calendar.YEAR, years); c.add(Calendar.MONTH, months); c.add(Calendar.DAY_OF_MONTH, days) }
    return c.timeInMillis
}
