package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * A Room `Flow` delivers `null` for an editing screen's row on the first composition even when
 * editing a saved one, and a plain `remember { }` latches that empty state before the real row
 * arrives - the form opens blank. This bit AddEditHostScreen and the script editor separately; key
 * every form field on the row's own id (or anything else that changes together with it) instead.
 */
@Composable
fun <T> rememberFormState(key: Any?, init: () -> T): MutableState<T> =
    remember(key) { mutableStateOf(init()) }
