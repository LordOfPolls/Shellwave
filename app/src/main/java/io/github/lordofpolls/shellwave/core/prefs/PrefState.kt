package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * SharedPreferences has no change notification Compose can observe, so re-read [read] on any change.
 * Comparing keys isn't worth the code. [read] is captured once, so it must not close over state.
 */
@Composable
fun <T> rememberPrefState(read: (Context) -> T): State<T> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(read(context)) }
    DisposableEffect(context) {
        val prefs = sharedPrefs(context)
        // Held strongly here: SharedPreferences only keeps a weak reference to the listener.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
