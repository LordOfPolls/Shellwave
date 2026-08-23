package io.github.lordofpolls.shellwave.service

import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.util.extractParamNames
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode

/**
 * Whether a background trigger - widget button, Quick Settings tile, another app's intent - may run
 * [script], and if not, the message for the notification. `null` means go ahead.
 *
 * Every refusal here comes from the same fact: nobody is present to answer a prompt. Lifted out of
 * [ScriptTriggerService] so a JVM test can pin the rules instead of a device and a widget tap. This
 * covers what the script row alone decides; the credential and host-key halves are enforced further
 * down. A launcher shortcut opens MainActivity and so is not a background trigger at all.
 *
 * [fromAutomation] marks the exported `RUN_SCRIPT` path and adds one refusal on top of the rest.
 * The app-wide switch and the token are already settled by automationRefusal before this is
 * reached.
 */
fun backgroundTriggerRefusal(script: ScriptEntity, fromAutomation: Boolean = false): String? {
    // Checked first because it is the coarsest: reporting a mode or parameter problem for a script
    // the caller was never entitled to name would confirm details about it.
    if (fromAutomation && !script.allowAutomation) {
        return "This script is not available to other apps. Open it in Shellwave and turn on \"Let other apps run this script\"."
    }
    // ATTACH wants an interactive session on screen; SEND_TO_CURRENT wants one already open, which
    // a cold trigger does not have. The message avoids naming the trigger - the same sentence has
    // to hold for a widget, a tile and another app alike.
    if (runCatching { ScriptMode.valueOf(script.mode) }.getOrNull() != ScriptMode.CAPTURE) {
        return "Only capture-mode scripts can run without Shellwave open. Open Shellwave to run this one."
    }
    // Substituting blanks would run something the user did not write.
    if (extractParamNames(script.snippet).isNotEmpty()) {
        return "This script needs parameter values, which there is no form to collect here. Open Shellwave to run it."
    }
    // A null target means "ask each run". Picking one here (most recent, first saved) would aim a
    // command at a machine the user did not choose.
    if (script.targetHostId == null) {
        return "This script asks which host to use each run, which needs the app open. Open Shellwave to run it."
    }
    return null
}
