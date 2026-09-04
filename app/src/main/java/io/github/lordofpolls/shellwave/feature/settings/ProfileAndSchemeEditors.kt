package io.github.lordofpolls.shellwave.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.terminal.BuiltInColorSchemes
import io.github.lordofpolls.shellwave.terminal.FONT_SIZE_SP_RANGE
import io.github.lordofpolls.shellwave.terminal.LINE_HEIGHT_MULTIPLIER_RANGE
import io.github.lordofpolls.shellwave.terminal.SCROLLBACK_LINES_RANGE
import io.github.lordofpolls.shellwave.terminal.TerminalCursorStyle
import io.github.lordofpolls.shellwave.terminal.TerminalFontFamily
import io.github.lordofpolls.shellwave.terminal.ansiColors
import io.github.lordofpolls.shellwave.terminal.autoCursorColorFor
import io.github.lordofpolls.shellwave.terminal.parseHexColorOrNull
import io.github.lordofpolls.shellwave.terminal.selectionHighlightColor
import io.github.lordofpolls.shellwave.terminal.toHexColorString
import io.github.lordofpolls.shellwave.ui.design.RadioRow
import kotlin.math.roundToInt

/**
 * The terminal-profile and colour-scheme field editors, shared by [SettingsScreen] and
 * AddEditHostScreen's per-host override so there is no second, drifting copy of every slider, radio
 * and hex field. `internal` and not `private` because Kotlin's `internal` is module-scoped, so both
 * call sites see these without any of it becoming public API.
 */
@Composable
internal fun TerminalProfileFields(
    profile: TerminalProfileEntity,
    onChange: (TerminalProfileEntity) -> Unit
) {
    val context = LocalContext.current
    val pickCustomFont =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Persisted rather than a one-shot read permission: this URI must still be readable
                // on the next app launch.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                onChange(
                    profile.copy(
                        fontFamily = TerminalFontFamily.CUSTOM.name,
                        customFontUri = uri.toString()
                    )
                )
            }
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Font family", style = MaterialTheme.typography.labelLarge)
        val currentFamily = TerminalFontFamily.fromStored(profile.fontFamily)
        TerminalFontFamily.entries.forEach { family ->
            RadioRow(
                selected = currentFamily == family,
                onClick = {
                    if (family == TerminalFontFamily.CUSTOM) {
                        pickCustomFont.launch(arrayOf("*/*"))
                    } else {
                        onChange(profile.copy(fontFamily = family.name, customFontUri = null))
                    }
                },
            ) {
                Text(family.displayName)
            }
        }
        if (currentFamily == TerminalFontFamily.CUSTOM) {
            Text(
                profile.customFontUri?.let {
                    "Using: ${
                        displayNameForUri(
                            context,
                            it
                        ) ?: it.substringAfterLast('/')
                    }"
                } ?: "No file chosen yet",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { pickCustomFont.launch(arrayOf("*/*")) }) { Text("Choose font file...") }
        }

        Text(
            "Font size: ${profile.fontSizeSp.roundToInt()}sp",
            style = MaterialTheme.typography.labelLarge
        )
        Slider(
            value = profile.fontSizeSp,
            onValueChange = { onChange(profile.copy(fontSizeSp = it)) },
            valueRange = FONT_SIZE_SP_RANGE,
            steps = (FONT_SIZE_SP_RANGE.endInclusive - FONT_SIZE_SP_RANGE.start).roundToInt() - 1,
        )

        Text(
            "Line height: ${"%.1f".format(profile.lineHeightMultiplier)}x",
            style = MaterialTheme.typography.labelLarge
        )
        Slider(
            value = profile.lineHeightMultiplier,
            onValueChange = { onChange(profile.copy(lineHeightMultiplier = it)) },
            valueRange = LINE_HEIGHT_MULTIPLIER_RANGE,
        )

        Text("Cursor style", style = MaterialTheme.typography.labelLarge)
        val currentCursorStyle = TerminalCursorStyle.fromStored(profile.cursorStyle)
        TerminalCursorStyle.entries.forEach { style ->
            RadioRow(
                selected = currentCursorStyle == style,
                onClick = { onChange(profile.copy(cursorStyle = style.name)) },
            ) {
                Text(style.displayName)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cursor blink", style = MaterialTheme.typography.labelLarge)
            Switch(
                checked = profile.cursorBlink,
                onCheckedChange = { onChange(profile.copy(cursorBlink = it)) })
        }

        ScrollbackField(
            scrollbackLines = profile.scrollbackLines,
            onCommit = { onChange(profile.copy(scrollbackLines = it)) })
        Text(
            "Scrollback depth applies to newly-opened sessions, not ones already connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [onChange] receives a ready-to-persist `ColorSchemeEntity` for every edit. A built-in pick keeps
 * [scheme]'s own `id`, so the caller updates the same row rather than inserting a new one, and any
 * individual colour edit forks away from whichever built-in it started as. The "insert on first
 * edit, update thereafter" bookkeeping stays in the caller.
 */
@Composable
internal fun ColorSchemeFields(scheme: ColorSchemeEntity, onChange: (ColorSchemeEntity) -> Unit) {
    fun edit(transform: ColorSchemeEntity.() -> ColorSchemeEntity) {
        onChange(scheme.transform().copy(isBuiltIn = false, name = "Custom"))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Built-in", style = MaterialTheme.typography.labelLarge)
        BuiltInColorSchemes.ALL.forEach { builtin ->
            RadioRow(
                selected = scheme.isBuiltIn && scheme.name == builtin.name,
                onClick = { onChange(builtin.copy(id = scheme.id)) },
            ) {
                Text(builtin.name)
            }
        }
        if (!scheme.isBuiltIn) {
            Text(
                "Custom (edited below)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ColorSchemePreview(scheme = scheme, modifier = Modifier.fillMaxWidth())

        Text(
            "Foreground / background / cursor / selection",
            style = MaterialTheme.typography.labelLarge
        )
        ColorHexField(
            label = "Foreground",
            color = scheme.foreground,
            onValid = { edit { copy(foreground = it) } })
        ColorHexField(
            label = "Background",
            color = scheme.background,
            onValid = { edit { copy(background = it) } })
        ColorHexField(
            label = "Cursor",
            color = scheme.cursor,
            onValid = { edit { copy(cursor = it) } },
            trailing = {
                TextButton(onClick = { edit { copy(cursor = autoCursorColorFor(background)) } }) {
                    Text(
                        "Auto"
                    )
                }
            },
        )
        ColorHexField(
            label = "Selection",
            color = scheme.selection,
            onValid = { edit { copy(selection = it) } })

        Text("ANSI colours", style = MaterialTheme.typography.labelLarge)
        val ansi = scheme.ansiColors()
        ANSI_COLOR_NAMES.forEachIndexed { index, name ->
            ColorHexField(
                label = "$index $name",
                color = ansi[index],
                onValid = { newColor ->
                    edit {
                        val updatedAnsi = ansiColors()
                        updatedAnsi[index] = newColor
                        copy(ansiColorsCsv = updatedAnsi.joinToString(",") { it.toHexColorString() })
                    }
                },
            )
        }
    }
}

/**
 * Clamped to [SCROLLBACK_LINES_RANGE] on commit rather than per keystroke, so typing "5" on the way
 * to "50000" isn't snapped to 100 before the rest of the digits land. The engine gives no feedback
 * when it substitutes a rejected value, so this is the one place validation happens: out-of-range
 * input is clamped visibly instead of becoming the engine's silent 2000.
 */
@Composable
internal fun ScrollbackField(scrollbackLines: Int, onCommit: (Int) -> Unit) {
    var text by remember(scrollbackLines) { mutableStateOf(scrollbackLines.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit) },
        label = { Text("Scrollback lines (${SCROLLBACK_LINES_RANGE.first}-${SCROLLBACK_LINES_RANGE.last})") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = {
        val clamped = (text.toIntOrNull() ?: scrollbackLines).coerceIn(SCROLLBACK_LINES_RANGE)
        text = clamped.toString()
        onCommit(clamped)
    }) { Text("Apply") }
}

/** Standard xterm 16-colour naming, in ColorSchemeEntity.ansiColorsCsv's order. Editor labels only. */
internal val ANSI_COLOR_NAMES =
    listOf(
        "Black",
        "Red",
        "Green",
        "Yellow",
        "Blue",
        "Magenta",
        "Cyan",
        "White",
        "Bright black",
        "Bright red",
        "Bright green",
        "Bright yellow",
        "Bright blue",
        "Bright magenta",
        "Bright cyan",
        "Bright white",
    )

/**
 * A swatch, a `#RRGGBB` field and an optional trailing action. [onValid] fires on every keystroke
 * that parses as a well-formed colour; malformed input - mid-typing or a genuine typo - is left
 * uncommitted and flagged, and not silently doing nothing the way an unchecked
 * [com.termux.terminal.TerminalColors.tryParseColor] call would.
 */
@Composable
internal fun ColorHexField(
    label: String,
    color: Int,
    onValid: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    var text by remember(color) { mutableStateOf(color.toHexColorString()) }
    val parsed = parseHexColorOrNull(text)
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier
                .size(24.dp)
                .background(Color(color)))
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    parseHexColorOrNull(newText)?.let(onValid)
                },
                label = { Text(label) },
                isError = parsed == null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        if (parsed == null) {
            Text(
                "Enter a colour as #RRGGBB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * A swatch per ANSI colour in order, a sample line in the scheme's foreground on its background,
 * and a selection-tinted span next to a cursor-coloured block.
 */
@Composable
internal fun ColorSchemePreview(scheme: ColorSchemeEntity, modifier: Modifier = Modifier) {
    val ansi = scheme.ansiColors()
    Column(modifier = modifier
        .background(Color(scheme.background))
        .padding(8.dp)) {
        Row {
            ansi.forEach { colour ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color(colour))
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("The quick brown fox jumps", color = Color(scheme.foreground))
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.background(Color(scheme.selectionHighlightColor()))) {
                Text(" selected text ", color = Color(scheme.foreground))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier
                .width(8.dp)
                .height(16.dp)
                .background(Color(scheme.cursor)))
        }
    }
}

/**
 * The SAF document's actual filename, not the opaque document id a `content://` URI's path segment
 * happens to be. Falls back to `null` instead of the raw URI, so the caller's own fallback is what
 * gets shown.
 */
internal fun displayNameForUri(context: Context, uriString: String): String? =
    runCatching {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
