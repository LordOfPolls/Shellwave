package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * A Room `Flow` delivers `null` for an editing screen's row on the first composition even when
 * editing a saved one, and a plain `remember { }` latches that empty state before the real row
 * arrives - the form opens blank. This bit AddEditHostScreen and the script editor separately; key
 * every form field on the row's own id (or anything else that changes together with it) instead.
 *
 * The value survives Activity recreation too, saved with the key it belongs to. `rememberSaveable`'s
 * own inputs can't do this: after a rotation the row is `null` again for a frame, so the restored
 * value would be consumed by that frame and then replaced by `init` when the row arrives. Values
 * the platform can't save (entities, say) are simply not saved, as before.
 */
@Composable
fun <T> rememberFormState(key: Any?, init: () -> T): MutableState<T> {
    val slot = rememberSaveable(saver = FormSlotSaver) { FormSlot(null) }
    return remember(key) { slot.open(key, init) }
}

internal class FormSlot(private var pending: List<Any?>?) {
    private var key: Any? = null
    private var state: MutableState<*>? = null

    fun <T> open(key: Any?, init: () -> T): MutableState<T> {
        val restored = pending?.takeIf { it[0] == key }
        if (restored != null) pending = null
        @Suppress("UNCHECKED_CAST")
        return mutableStateOf(if (restored != null) restored[1] as T else init())
            .also { this.key = key; state = it }
    }

    fun save(scope: SaverScope): List<Any?>? =
        pending ?: listOf(key, state?.value).takeIf { scope.canSave(it) }
}

internal val FormSlotSaver = Saver<FormSlot, List<Any?>>(save = { it.save(this) }, restore = { FormSlot(it) })

// The registry accepts any Serializable container, but parcelling one fails on an element that
// isn't, so containers are checked element by element.
private fun SaverScope.canSave(value: Any?): Boolean =
    when (value) {
        null -> true
        is Pair<*, *> -> canSave(value.first) && canSave(value.second)
        is Collection<*> -> value.all { canSave(it) }
        else -> canBeSaved(value)
    }
