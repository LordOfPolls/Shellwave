package io.github.lordofpolls.shellwave.feature.session

import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The few `InputDevice` facts [hasPhysicalKeyboard] decides on, sampled into a plain class so the
 * decision runs in a JVM test without an Android device.
 */
internal data class KeyboardDeviceInfo(
    val isVirtual: Boolean,
    val isExternal: Boolean,
    val isEnabled: Boolean,
    val isKeyboard: Boolean,
    val isAlphabetic: Boolean,
)

internal fun InputDevice.toKeyboardDeviceInfo() =
    KeyboardDeviceInfo(
        isVirtual = isVirtual,
        isExternal = isExternal,
        isEnabled = isEnabled,
        isKeyboard = supportsSource(InputDevice.SOURCE_KEYBOARD),
        isAlphabetic = keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
    )

/**
 * Whether a usable physical keyboard is attached. A closed slider keyboard still reports
 * `KEYBOARD_QWERTY`, so for the `Configuration` and for built-in devices `HARDKEYBOARDHIDDEN_YES`
 * rules the keyboard out. An external keyboard is exempt from that: with the system setting "Show
 * on-screen keyboard while physical keyboard is active" on, the window manager reports the
 * keyboard as hidden to keep the soft IME showing, though one is plainly attached. Tablet
 * keyboard covers, some Bluetooth keyboards and OEM skins also show up only as an `InputDevice`
 * and never set `Configuration.keyboard`.
 */
internal fun hasPhysicalKeyboard(
    devices: List<KeyboardDeviceInfo>,
    configKeyboardQwerty: Boolean,
    hardKeyboardHidden: Int,
): Boolean {
    val usable =
        devices.filter { it.isKeyboard && it.isAlphabetic && it.isEnabled && !it.isVirtual }
    if (usable.any { it.isExternal }) return true
    return hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES &&
        (configKeyboardQwerty || usable.isNotEmpty())
}

/**
 * Live [hasPhysicalKeyboard] for the current device list. The `Configuration` alone is not enough
 * to follow a keyboard coming and going, so this listens to `InputManager` and recomputes on every
 * device change.
 */
@Composable
internal fun physicalKeyboardConnected(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val configQwerty = configuration.keyboard == Configuration.KEYBOARD_QWERTY
    val hardKeyboardHidden = configuration.hardKeyboardHidden
    val inputManager = remember(context) { context.getSystemService(InputManager::class.java) }

    fun sample(): List<KeyboardDeviceInfo> =
        InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)?.toKeyboardDeviceInfo()
        }

    var devices by remember { mutableStateOf(sample()) }
    DisposableEffect(inputManager) {
        val listener =
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    devices = sample()
                }

                override fun onInputDeviceRemoved(deviceId: Int) {
                    devices = sample()
                }

                override fun onInputDeviceChanged(deviceId: Int) {
                    devices = sample()
                }
            }
        devices = sample()
        inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { inputManager.unregisterInputDeviceListener(listener) }
    }
    return hasPhysicalKeyboard(devices, configQwerty, hardKeyboardHidden)
}
