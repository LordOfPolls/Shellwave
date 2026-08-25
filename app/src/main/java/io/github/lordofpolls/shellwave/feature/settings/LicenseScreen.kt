package io.github.lordofpolls.shellwave.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.R
import io.github.lordofpolls.shellwave.ui.design.BackTopBar

/**
 * GPLv3 §6's "written offer of source" for this exact build: the URL a user reads this screen to
 * find out where the corresponding source for the binary they're running is published. Showing this
 * URL in the UI is a promise this project has to keep - the repository at this address must
 * actually be public and must actually carry the source corresponding to whatever binary is
 * distributed, before that binary reaches anyone outside this project. Don't ship a build that
 * points here if that isn't true yet.
 */
private const val SOURCE_REPOSITORY_URL = "https://github.com/LordOfPolls/shellwave"

/**
 * A literal and not read out of NOTICE at runtime: unlike the licence and attribution text below,
 * which is loaded from NOTICE - because this one fact has to read as a labelled headline claim
 * rather than being found inside a wall of reproduced text. Re-vendoring `:terminal-core` from a
 * newer commit means updating this alongside NOTICE and `terminal-core/VENDORING.md`.
 */
private const val TERMUX_PINNED_COMMIT = "3df69d1da197dd9bd71a3bafd902dffd720576b4"

/**
 * The GPLv3 compliance screen: what makes the LICENSE and NOTICE files reachable from inside the
 * running app, not only from a source checkout.
 *
 * A single static, scrolling screen with no state beyond which raw-text block is expanded - nothing
 * here is editable, so it needs none of the DAO load pattern the rest of `feature/settings` uses.
 *
 * The third-party dependency list is not re-typed here as a second Kotlin list: it is loaded
 * verbatim from `res/raw/notice.txt`, which the `:app:copyNoticeToRes` Gradle task generates from
 * the repository's own top-level `NOTICE`. NOTICE gains an entry every time a dependency is added,
 * and a hand-maintained duplicate would drift from it with no build failure to catch it. The full
 * GPLv3 text is loaded from `res/raw/license_gplv3.txt`, a hand-copy of `LICENSE`, which is fine
 * because GPLv3's text never changes, so the licence is readable with the device fully offline
 * instead of depending on a network fetch.
 */
@Composable
fun LicenseScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Read once per composition: fixed text blobs, and nothing worth re-reading off disk each time
    // an `expanded` toggle recomposes this.
    val noticeText = remember {
        context.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() }
    }
    val licenseText = remember {
        context.resources.openRawResource(R.raw.license_gplv3).bufferedReader()
            .use { it.readText() }
    }

    var licenseExpanded by remember { mutableStateOf(false) }
    var noticeExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = "Licences", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Shellwave", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shellwave is free software: you can redistribute it and/or modify it under the " +
                                "terms of the GNU General Public License version 3 (GPLv3) as published by the " +
                                "Free Software Foundation. It is distributed WITHOUT ANY WARRANTY, without even " +
                                "the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. " +
                                "The full licence text is reproduced below.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // The app's strongest compliance obligation, so it gets its own card ahead of the general
            // third-party list instead of being one more entry inside the reproduced NOTICE text.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Vendored terminal engine", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The terminal-emulation engine (module :terminal-core) is vendored, with minimal " +
                                "changes, from the Termux project (termux/termux-app), copyright the Termux " +
                                "project and its contributors, licensed under the GNU General Public License " +
                                "v3.0 only - the same licence as Shellwave itself. It is pinned to upstream " +
                                "commit $TERMUX_PINNED_COMMIT; see NOTICE below and terminal-core/VENDORING.md " +
                                "in the source tree for the exact file list and what was changed to make it " +
                                "compile without its original local-pty/JNI subprocess code, which this app " +
                                "does not use (Shellwave connects over SSH instead).",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Source code", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "GPLv3 requires that anyone who receives this app also be told how to get the " +
                                "corresponding source code for the exact version they're running:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        SOURCE_REPOSITORY_URL,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Third-party notices",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            noticeExpanded = !noticeExpanded
                        }) { Text(if (noticeExpanded) "Hide" else "Show") }
                    }
                    Text(
                        "Per-file attribution for the vendored engine, plus every bundled font and " +
                                "third-party library and its licence - reproduced verbatim from this " +
                                "repository's own NOTICE file, not retyped, so this screen can never say " +
                                "something different from it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (noticeExpanded) {
                        Text(
                            noticeText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Full licence text (GPLv3)",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            licenseExpanded = !licenseExpanded
                        }) { Text(if (licenseExpanded) "Hide" else "Show") }
                    }
                    if (licenseExpanded) {
                        Text(
                            licenseText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
