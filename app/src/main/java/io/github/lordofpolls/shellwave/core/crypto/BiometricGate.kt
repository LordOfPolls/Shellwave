package io.github.lordofpolls.shellwave.core.crypto

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A [BiometricPrompt] auth failed, was cancelled, or errored - see BiometricPrompt.AuthenticationCallback.onAuthenticationError. */
class BiometricAuthException(val errorCode: Int, message: String) : Exception(message)

/**
 * True when [this] is the user backing out of a biometric prompt (the negative button, a back
 * gesture, or the system cancelling the prompt on their behalf) rather than a genuine failure
 * (lockout, no biometric enrolled, a vanished Keystore key, etc.). Callers of resolve use this to
 * abort quietly on cancellation while still surfacing every other failure.
 */
fun Exception.isBiometricCancellation(): Boolean =
    this is BiometricAuthException &&
            (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED)

/**
 * A `BIOMETRIC_STRONG` [BiometricPrompt] round trip, in the two shapes the vault's two biometric
 * aliases need. They are not interchangeable, and picking the wrong one fails at runtime rather
 * than at compile time:
 *
 * - With a `Cipher` ([authenticate]): the `CryptoObject`-bound form [VaultCrypto.ALIAS_BIOMETRIC]
 *   requires. That key has a zero-second validity, so its cipher initialises fine but refuses to
 *   `doFinal` until this exact [Cipher] instance comes back authenticated, which is what this
 *   returns.
 * - Without one ([authenticate] taking no cipher): the only form that works for
 *   VaultCrypto.ALIAS_BIOMETRIC_WINDOWED. A time-bound key cannot be initialised at all before
 *   the user authenticates - [Cipher.init] itself throws - so there is no cipher to wrap in a
 *   `CryptoObject`. The caller prompts with this, then retries the whole Keystore operation from
 *   scratch inside the window the prompt opened.
 */
@Singleton
class BiometricGate @Inject constructor() {

    /** The `CryptoObject` form - [VaultCrypto.ALIAS_BIOMETRIC] only. */
    suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String? = null
    ): Cipher =
        prompt(activity, BiometricPrompt.CryptoObject(cipher), title, subtitle)?.cipher
            ?: throw IllegalStateException("BiometricPrompt succeeded without a CryptoObject")

    /**
     * The plain form - `VaultCrypto.ALIAS_BIOMETRIC_WINDOWED` only. Returns normally once the user has
     * authenticated, which is all the caller needs: the window is now open for every key on this device
     * requiring `BIOMETRIC_STRONG`, so the operation that just failed can simply be retried. Throws
     * [BiometricAuthException] on cancellation or failure, exactly like the other.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null) {
        prompt(activity, crypto = null, title = title, subtitle = subtitle)
    }

    private suspend fun prompt(
        activity: FragmentActivity,
        crypto: BiometricPrompt.CryptoObject?,
        title: String,
        subtitle: String?,
    ): BiometricPrompt.CryptoObject? =
        suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt =
                BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            // Null here is correct for the plain form and a contract breach for the
                            // CryptoObject one, so only that caller treats it as an error.
                            cont.resume(result.cryptoObject)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence
                        ) {
                            if (cont.isActive) cont.resumeWithException(
                                BiometricAuthException(
                                    errorCode,
                                    errString.toString()
                                )
                            )
                        }

                        override fun onAuthenticationFailed() {
                            // A single failed match (bad fingerprint/face): prompt stays open for
                            // retry.
                        }
                    },
                )
            val info =
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { if (subtitle != null) setSubtitle(subtitle) }
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BIOMETRIC_STRONG)
                    .build()
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        }
}
