package com.tappytaps.ttmailer

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads the user's purchases from Play Billing for the automatic premium (TTM-585), on the SDK's worker thread. Its
 * own read-only client: nothing is acknowledged or consumed (the app keeps handling its purchases). Play does not say
 * when a subscription ends or whether it is a trial: the end is the next renewal by the product's billing period
 * (a month when Play does not say), the kind `paid`.
 */
internal object BillingReader {
    /** Is Play Billing 7+ in the app (the SDK only compiles against it; PendingPurchasesParams came with 7)? */
    val available: Boolean by lazy { has("com.android.billingclient.api.BillingClient") && has("com.android.billingclient.api.PendingPurchasesParams") }

    /** Play Billing 8 changed the product details callback: the SDK (built against 7) must not call it there. */
    private val billing8: Boolean by lazy { has("com.android.billingclient.api.QueryProductDetailsResult") }

    private fun has(name: String) = runCatching { Class.forName(name) }.isSuccess

    private const val DEFAULT_PERIOD = "P1M"
    private const val TIMEOUT_S = 20L

    /** The purchases now; null when Play could not be asked (no connection to Play, no billing in the app). */
    fun entitlements(context: Context, now: Long): List<Entitlement>? {
        if (!available) return null
        val client = try {
            BillingClient.newBuilder(context)
                .setListener { _, _ -> }
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
        } catch (_: Throwable) {
            return null
        }
        try {
            val ready = CountDownLatch(1)
            var ok = false
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) { ok = r.responseCode == BillingClient.BillingResponseCode.OK; ready.countDown() }
                override fun onBillingServiceDisconnected() { ready.countDown() }
            })
            if (!ready.await(TIMEOUT_S, TimeUnit.SECONDS) || !ok) return null
            val subs = purchases(client, BillingClient.ProductType.SUBS) ?: return null
            val inapp = purchases(client, BillingClient.ProductType.INAPP) ?: return null
            val periods = periods(client, subs.flatMap { it.products }.distinct())
            val out = mutableListOf<Entitlement>()
            for (p in subs) for (id in p.products) {
                val end = subscriptionEnd(p.purchaseTime, periods[id] ?: DEFAULT_PERIOD, now)
                out += Entitlement(id, Entitlement.Kind.SUBSCRIPTION, end, p.purchaseTime, false)
            }
            // Only acknowledged ones: a consumable (coins) is consumed, never acknowledged, and is not an unlock.
            for (p in inapp.filter { it.isAcknowledged }) for (id in p.products) out += Entitlement(id, Entitlement.Kind.ONE_TIME, null, p.purchaseTime, false)
            return out
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { client.endConnection() }
        }
    }

    private fun purchases(client: BillingClient, type: String): List<Purchase>? {
        val done = CountDownLatch(1)
        var out: List<Purchase>? = null
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) out = list.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            done.countDown()
        }
        return if (done.await(TIMEOUT_S, TimeUnit.SECONDS)) out else null
    }

    /** The billing period of each subscription (its base plan's recurring phase); empty when Play does not answer, or
     * the app's Play Billing has another API for it (version 8+): then a month. */
    private fun periods(client: BillingClient, ids: List<String>): Map<String, String> {
        if (ids.isEmpty() || billing8) return emptyMap()
        return try {
            val done = CountDownLatch(1)
            val out = HashMap<String, String>()
            val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            }).build()
            client.queryProductDetailsAsync(params) { _, details ->
                for (d in details) d.subscriptionOfferDetails?.firstOrNull { it.offerId == null }?.pricingPhases?.pricingPhaseList?.lastOrNull()
                    ?.billingPeriod?.let { out[d.productId] = it }
                done.countDown()
            }
            done.await(TIMEOUT_S, TimeUnit.SECONDS)
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}
