package io.github.lordofpolls.shellwave.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Play Console one-time product backing the donation ladder; its purchase options carry the actual prices. */
const val SUPPORTER_PRODUCT_ID = "supporter_badge"

/**
 * Play Billing for the donation ladder: one one-time product ([SUPPORTER_PRODUCT_ID]) whose
 * purchase options are the tiers; owning any one makes the user a supporter. Nothing else
 * in the app reads [state]; it exists purely so Settings can show prices or a thank-you.
 *
 * `play` flavour only. This file is the entire reason the flavour dimension exists: everything
 * it imports from `com.android.billingclient` is proprietary, and the `foss` flavour ships
 * [FossSupporterBilling] in its place so the F-Droid build links nothing non-free.
 */
@Singleton
class PlaySupporterBilling
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SupporterBilling, PurchasesUpdatedListener {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val _state = MutableStateFlow<SupporterState>(SupporterState.Loading)
        override val state: StateFlow<SupporterState> = _state.asStateFlow()

        private var productDetails: ProductDetails? = null
        private var offersByPurchaseOptionId: Map<String, ProductDetails.OneTimePurchaseOfferDetails> = emptyMap()

        private val client =
            BillingClient.newBuilder(context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()

        init {
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            scope.launch { refresh() }
                        } else {
                            _state.value = SupporterState.Unavailable
                        }
                    }

                    override fun onBillingServiceDisconnected() = Unit
                },
            )
        }

        private suspend fun refresh() {
            val owned =
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
                    .purchasesList
                    .filter { purchase ->
                        SUPPORTER_PRODUCT_ID in purchase.products && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    }

            if (owned.isNotEmpty()) {
                _state.value = SupporterState.Supporter
                owned.filterNot { it.isAcknowledged }.forEach { acknowledge(it) }
                return
            }

            val details = queryProductDetails()
            cacheOffers(details)
            val priced =
                offersByPurchaseOptionId.map { (purchaseOptionId, offer) ->
                    PricedOption(purchaseOptionId, offer.priceAmountMicros, offer.formattedPrice)
                }
            val tiers = supporterTiers(priced)
            _state.value = if (tiers.isNotEmpty()) SupporterState.Purchasable(tiers) else SupporterState.Unavailable
        }

        private suspend fun queryProductDetails(): ProductDetails? =
            client.queryProductDetails(
                QueryProductDetailsParams.newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(SUPPORTER_PRODUCT_ID)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build(),
                        ),
                    ).build(),
            ).productDetailsList?.firstOrNull()

        private fun cacheOffers(details: ProductDetails?) {
            productDetails = details
            offersByPurchaseOptionId =
                details?.oneTimePurchaseOfferDetailsList
                    ?.mapNotNull { offer -> offer.purchaseOptionId?.let { it to offer } }
                    ?.toMap() ?: emptyMap()
        }

        override fun launchPurchase(activity: Activity, purchaseOptionId: String) {
            scope.launch {
                var offer = offersByPurchaseOptionId[purchaseOptionId]
                var details = productDetails
                if (offer == null) {
                    val requeried = queryProductDetails()
                    if (requeried != null) cacheOffers(requeried)
                    offer = offersByPurchaseOptionId[purchaseOptionId]
                    details = productDetails
                }
                val offerToken = offer?.offerToken
                if (offerToken == null || details == null) return@launch
                val params =
                    BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(
                            listOf(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(details)
                                    .setOfferToken(offerToken)
                                    .build(),
                            ),
                        ).build()
                client.launchBillingFlow(activity, params)
            }
        }

        override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
            if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
            scope.launch {
                purchases.filter { purchase -> SUPPORTER_PRODUCT_ID in purchase.products && purchase.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { purchase ->
                        _state.value = SupporterState.Supporter
                        if (!purchase.isAcknowledged) acknowledge(purchase)
                    }
            }
        }

        private suspend fun acknowledge(purchase: Purchase) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SupporterBillingModule {
    // Scope comes from @Singleton on the implementation; the delegate binding inherits it.
    @Binds
    abstract fun bindSupporterBilling(impl: PlaySupporterBilling): SupporterBilling
}
