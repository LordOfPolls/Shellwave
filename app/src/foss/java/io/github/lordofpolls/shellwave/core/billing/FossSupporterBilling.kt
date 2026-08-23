package io.github.lordofpolls.shellwave.core.billing

import android.app.Activity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `foss` flavour: there is no donation here, because there is no non-free billing library here.
 * F-Droid rejects an APK that links Play Billing, so this flavour is the one it builds, and it
 * must not so much as name `com.android.billingclient`.
 *
 * [SupporterState.Unavailable] is not a failure state - Settings treats it as "the Support
 * section does not exist", which is exactly right for a build that cannot take a payment.
 */
@Singleton
class FossSupporterBilling
    @Inject
    constructor() : SupporterBilling {
        override val state: StateFlow<SupporterState> =
            MutableStateFlow<SupporterState>(SupporterState.Unavailable).asStateFlow()

        override fun launchPurchase(activity: Activity) = Unit
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SupporterBillingModule {
    @Binds
    abstract fun bindSupporterBilling(impl: FossSupporterBilling): SupporterBilling
}
