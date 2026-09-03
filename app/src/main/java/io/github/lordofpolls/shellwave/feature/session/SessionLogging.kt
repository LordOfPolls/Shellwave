package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.lordofpolls.shellwave.ssh.SshConnection

/**
 * One SAF picker away from a session log: whether a session is currently logging lives on
 * [SshConnection.isLogging] itself, not duplicated here, since that is what the "Stop logging"
 * label in the overflow menu needs to read live.
 */
internal class SessionLoggingController {
    var pendingTarget by mutableStateOf<SshConnection?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun requestStart(connection: SshConnection) {
        pendingTarget = connection
    }

    /** The `CreateDocument` launcher's result; a null [destination] means the picker was cancelled. */
    fun destinationPicked(context: Context, destination: Uri?) {
        val connection = pendingTarget
        pendingTarget = null
        if (connection == null || destination == null) return
        val out = runCatching { context.contentResolver.openOutputStream(destination, "wa") }
            .getOrNull()
        if (out == null) {
            error = "Couldn't open that file for logging."
            return
        }
        connection.startLogging(out) { error = "Writing the session log failed - logging stopped." }
    }

    fun clearError() {
        error = null
    }
}

@Composable
internal fun rememberSessionLoggingController(): SessionLoggingController =
    remember { SessionLoggingController() }

/** Owns the `CreateDocument` launcher [controller] needs, and the toast for a write failure. */
@Composable
internal fun SessionLoggingEffects(controller: SessionLoggingController) {
    val context = LocalContext.current

    val pending = controller.pendingTarget
    val createDocument =
        // "text/plain" makes SAF append .txt to the suggested name; octet-stream keeps ".log".
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            controller.destinationPicked(context, uri)
        }
    LaunchedEffect(pending) {
        if (pending != null) createDocument.launch("shellwave-session.log")
    }

    val error = controller.error
    LaunchedEffect(error) {
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            controller.clearError()
        }
    }
}
