package io.github.lordofpolls.shellwave.feature.session

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
import androidx.compose.ui.platform.LocalContext
import io.github.lordofpolls.shellwave.terminal.KeyboardDeviceInfo

private fun sampleKeyboardDevices(): List<KeyboardDeviceInfo> =
    InputDevice.getDeviceIds().toList().mapNotNull { id ->
        InputDevice.getDevice(id)?.let {
            KeyboardDeviceInfo(
                isVirtual = it.isVirtual,
                isExternal = it.isExternal,
                isEnabled = it.isEnabled,
                isKeyboard = it.supportsSource(InputDevice.SOURCE_KEYBOARD),
                isAlphabetic = it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            )
        }
    }

/** `Configuration` doesn't change for every keyboard that comes and goes, so this listens to `InputManager`. */
@Composable
internal fun rememberKeyboardDevices(): List<KeyboardDeviceInfo> {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(sampleKeyboardDevices()) }
    DisposableEffect(context) {
        val inputManager = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { devices = sampleKeyboardDevices() }
            override fun onInputDeviceRemoved(deviceId: Int) { devices = sampleKeyboardDevices() }
            override fun onInputDeviceChanged(deviceId: Int) { devices = sampleKeyboardDevices() }
        }
        devices = sampleKeyboardDevices()
        inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { inputManager.unregisterInputDeviceListener(listener) }
    }
    return devices
}
