package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import io.github.lordofpolls.shellwave.core.prefs.AutomationPreferences.regenerateToken
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
private const val KEY_AUTOMATION_TOKEN = "automation_token"

/** 32 characters, short enough to eyeball whether a paste into Tasker landed. */
private const val TOKEN_BYTES = 24

/**
 * URL-safe Base64, no padding: this gets pasted into a Tasker extra and typed into
 * `adb shell am start`, where `+`, `/` and `=` all need quoting, and a token that only works when
 * quoted right gets reported as a broken app.
 */
internal fun newAutomationToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(TOKEN_BYTES)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Pure and outside [AutomationPreferences] so a JVM test can pin the gate without tapping through
 * Settings.
 *
 * The refusals name which gate stopped the request. Whoever is setting the automation up is who
 * reads them, and an unexplained silence gets debugged by switching safeguards off one at a time.
 */
internal fun automationRefusal(
    enabled: Boolean,
    storedToken: String?,
    providedToken: String?
): String? {
    // Before the token, so "I never turned this on" never depends on comparing against a token that
    // does not exist yet.
    if (!enabled || storedToken.isNullOrEmpty()) {
        return "Shellwave is not accepting automation requests. Turn on \"Let other apps run scripts\" in Settings first."
    }
    if (providedToken.isNullOrEmpty()) {
        return "This request carried no token. Copy the token from Shellwave's Settings into the intent's \"token\" extra."
    }
    if (!tokensMatch(storedToken, providedToken)) {
        return "This request's token does not match. Copy the current token from Shellwave's Settings - generating a new one revokes the old."
    }
    return null
}

/** Constant-time. The signal is faint across a `startActivity` boundary, but this costs one import. */
private fun tokensMatch(stored: String, provided: String): Boolean =
    MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))

/**
 * The master switch and the shared secret `AutomationTriggerActivity` checks before it starts
 * anything.
 *
 * SharedPreferences and not the vault, which fits what the token is worth: a capability to ask, not
 * a credential. Holding it lets an app request a script the user separately marked allowAutomation
 * and nothing else. Sealing it in the Keystore would imply it guards more than it does, and would
 * make reading it to paste into Tasker the awkward operation.
 *
 * Off by default, and enabling mints the token in the same step, so the switch is never on with
 * nothing behind it.
 */
object AutomationPreferences {
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOMATION_ENABLED, false)

    /**
     * Switching off leaves the token alone, so turning the feature off to think about it does not mean
     * re-pasting into every task afterwards. Revoking is [regenerateToken]'s job.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled)
        if (enabled && token(context).isNullOrEmpty()) editor.putString(
            KEY_AUTOMATION_TOKEN,
            newAutomationToken()
        )
        editor.apply()
    }

    fun token(context: Context): String? = prefs(context).getString(KEY_AUTOMATION_TOKEN, null)

    /** The old token stops working at once, so every task still holding it breaks. */
    fun regenerateToken(context: Context): String {
        val token = newAutomationToken()
        prefs(context).edit().putString(KEY_AUTOMATION_TOKEN, token).apply()
        return token
    }

    fun refusalFor(context: Context, providedToken: String?): String? =
        automationRefusal(isEnabled(context), token(context), providedToken)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
