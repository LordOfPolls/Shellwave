package io.github.lordofpolls.shellwave.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import kotlinx.coroutines.launch

/**
 * Font size, cursor style and sixteen ANSI hex values are real settings nobody opens twice, so they
 * are a pushed screen rather than disclosure groups on the main Settings scroll.
 *
 * Writing is all this screen does: MainActivity's observeDefault() collector calls
 * SessionManager.applyDefaultColorScheme so an edit reaches an already-open session.
 */
@Composable
fun TerminalSettingsScreen(
    terminalProfileDao: TerminalProfileDao,
    colorSchemeDao: ColorSchemeDao,
    onOpenKeyBarLayouts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf(DEFAULT_TERMINAL_PROFILE) }
    LaunchedEffect(Unit) {
        terminalProfileDao.getDefault()?.let { profile = it }
    }

    // Insert on first edit, update the same row thereafter. Applied optimistically to local state.
    fun save(updated: TerminalProfileEntity) {
        profile = updated
        scope.launch {
            if (updated.id == 0L) {
                profile = updated.copy(id = terminalProfileDao.insert(updated))
            } else {
                terminalProfileDao.update(updated)
            }
        }
    }

    var scheme by remember { mutableStateOf(DEFAULT_COLOR_SCHEME) }
    LaunchedEffect(Unit) {
        colorSchemeDao.getDefault()?.let { scheme = it }
    }

    fun saveScheme(updated: ColorSchemeEntity) {
        scheme = updated
        scope.launch {
            if (updated.id == 0L) {
                scheme = updated.copy(id = colorSchemeDao.insert(updated))
            } else {
                colorSchemeDao.update(updated)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = "Terminal", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionHeader("Profile", first = true)
            TerminalProfileFields(profile = profile, onChange = ::save)

            SettingsSectionHeader("Colour scheme")
            ColorSchemeFields(scheme = scheme, onChange = ::saveScheme)

            SettingsSectionHeader("Key bar")
            Text(
                "Custom keys, macros, one or two rows. A layout is assigned per host.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsRow(
                title = "Manage key bar layouts",
                chevron = true,
                onClick = onOpenKeyBarLayouts,
            )

            Spacer(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
