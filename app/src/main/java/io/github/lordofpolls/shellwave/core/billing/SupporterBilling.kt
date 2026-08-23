package io.github.lordofpolls.shellwave.core.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

sealed class SupporterState {
    data object Loading : SupporterState()

    data class Purchasable(val priceLabel: String) : SupporterState()

    data object Supporter : SupporterState()

    data object Unavailable : SupporterState()
}

/**
 * The donation row in Settings, and nothing else: no part of the app gates a feature on [state].
 *
 * This is an interface, and its only implementations live in the flavour source sets, because
 * Play Billing is a proprietary library and F-Droid will not build an app that links one. The
 * `play` flavour binds the real client; the `foss` flavour binds a stub that reports
 * [SupporterState.Unavailable] forever, which the Settings screen already renders as "no Support
 * section at all". Keeping the type here (rather than making the call sites flavour-aware) means
 * MainActivity and SettingsScreen stay in `main` and compile identically for both flavours.
 */
interface SupporterBilling {
    val state: StateFlow<SupporterState>

    /** No-op where billing is unavailable; callers do not check first. */
    fun launchPurchase(activity: Activity)
}
