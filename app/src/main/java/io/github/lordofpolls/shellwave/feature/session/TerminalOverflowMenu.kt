package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.ssh.SshConnection

/**
 * The terminal's overflow, pinned at the chip rail's right edge, and the only thing in that strip
 * besides the chips. It replaced four rows of chrome for actions touched once a session or less.
 *
 * Reconnect stayed on the Sessions overview, where a session that is not connected is managed:
 * offering it inside the terminal of a session with no terminal to show would be an action on a
 * surface that cannot display its own result.
 *
 * [connection] is non-null only while the selected session is `CONNECTED`, gating the three actions
 * that need a live channel. [onClose] is not gated - closing a session stuck mid-connect is exactly
 * when it is needed.
 *
 * Bell is a nested single-choice group carrying its value in the parent item's label, so the mode
 * reads without opening the submenu, which is the one thing the permanent toggle did well.
 */
@Composable
internal fun TerminalOverflowMenu(
    connection: SshConnection?,
    bellMode: BellMode,
    onBellMode: (BellMode) -> Unit,
    onDownload: (SshConnection) -> Unit,
    onUpload: (SshConnection) -> Unit,
    onBrowseFiles: (SshConnection) -> Unit,
    onRunScript: (() -> Unit)?,
    onClose: (() -> Unit)?,
    onSearch: () -> Unit,
    isLogging: Boolean,
    onStartLogging: (SshConnection) -> Unit,
    onStopLogging: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showingBell by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = {
                showingBell = false
                expanded = true
            },
        ) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Session options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showingBell) {
                DropdownMenuItem(
                    text = { Text("Back") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null
                        )
                    },
                    onClick = { showingBell = false },
                )
                BellMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(BellPreferences.modeName(mode)) },
                        // RadioButton carries the selected state to TalkBack as state; colour alone
                        // would not.
                        leadingIcon = { RadioButton(selected = mode == bellMode, onClick = null) },
                        onClick = {
                            onBellMode(mode)
                            showingBell = false
                        },
                    )
                }
            } else {
                if (connection != null) {
                    DropdownMenuItem(
                        text = { Text("Download file") },
                        leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onDownload(connection)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Upload file") },
                        leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onUpload(connection)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Browse files") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onBrowseFiles(connection)
                        },
                    )
                    if (onRunScript != null) {
                        DropdownMenuItem(
                            text = { Text("Run script here") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.PlayArrow,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                expanded = false
                                onRunScript()
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(BellPreferences.label(bellMode)) },
                    leadingIcon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                    trailingIcon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null
                        )
                    },
                    onClick = { showingBell = true },
                )
                if (onClose != null) {
                    DropdownMenuItem(
                        text = { Text("Close session") },
                        leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onClose()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Search") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSearch()
                    },
                )
                // Stop logging is reachable independent of `connection`, which is only non-null
                // while CONNECTED: a session already logging must stay stoppable through, say, a
                // reconnect, not just while the channel that started it is still up.
                if (isLogging) {
                    DropdownMenuItem(
                        text = { Text("Stop logging") },
                        leadingIcon = { Icon(Icons.Outlined.StopCircle, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onStopLogging()
                        },
                    )
                } else if (connection != null) {
                    DropdownMenuItem(
                        text = { Text("Log session to file…") },
                        leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onStartLogging(connection)
                        },
                    )
                }
            }
        }
    }
}
