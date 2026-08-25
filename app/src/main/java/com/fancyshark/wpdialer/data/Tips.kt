package com.fancyshark.wpdialer.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tip jar (v1.1 pricing decision: the app is free, tips are optional
 * consumables so a user can tip again). Everything degrades silently:
 * no Play, no products configured, or any billing error just means
 * [products] stays empty and the About section never shows.
 */
object Tips {

    private val PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")

    private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
    val products: StateFlow<List<ProductDetails>> = _products

    /** Flips true after a tip lands — shows the thank-you line. */
    private val _thanked = MutableStateFlow(false)
    val thanked: StateFlow<Boolean> = _thanked

    private var client: BillingClient? = null

    fun init(context: Context) {
        if (client != null) return
        val c = BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases?.forEach { consume(it) }
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) query()
            }

            override fun onBillingServiceDisconnected() {
                // Next AboutScreen visit retries via init() — keep it simple.
                client = null
            }
        })
    }

    private fun query() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                PRODUCT_IDS.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        client?.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _products.value = details.sortedBy { PRODUCT_IDS.indexOf(it.productId) }
            }
        }
    }

    fun launch(activity: Activity, product: ProductDetails) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build(),
                ),
            )
            .build()
        client?.launchBillingFlow(activity, params)
    }

    // Tips are consumables: consuming re-enables the product for next time
    // and doubles as the acknowledgement Play requires within 3 days.
    private fun consume(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        client?.consumeAsync(
            ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { result, _ ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _thanked.value = true
            }
        }
    }
}
