package io.github.lordofpolls.shellwave.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.terminal.BuiltInColorSchemes
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.FONT_SIZE_SP_RANGE
import io.github.lordofpolls.shellwave.terminal.LINE_HEIGHT_MULTIPLIER_RANGE
import io.github.lordofpolls.shellwave.terminal.SCROLLBACK_LINES_RANGE
import io.github.lordofpolls.shellwave.terminal.TerminalCursorStyle
import io.github.lordofpolls.shellwave.terminal.TerminalFontFamily
import io.github.lordofpolls.shellwave.terminal.ansiColors
import io.github.lordofpolls.shellwave.terminal.autoCursorColorFor
import io.github.lordofpolls.shellwave.terminal.parseHexColorOrNull
import io.github.lordofpolls.shellwave.terminal.resolveTerminalTypeface
import io.github.lordofpolls.shellwave.terminal.selectionHighlightColor
import io.github.lordofpolls.shellwave.terminal.toHexColorString
import io.github.lordofpolls.shellwave.ui.design.ColorPickerDialog
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
    onChange: (TerminalProfileEntity) -> Unit,
    previewScheme: ColorSchemeEntity = DEFAULT_COLOR_SCHEME,
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
        TerminalProfilePreview(profile = profile, scheme = previewScheme)
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
            modifier = Modifier.semantics { contentDescription = "Font size" },
        )

        Text(
            "Line height: ${"%.1f".format(profile.lineHeightMultiplier)}x",
            style = MaterialTheme.typography.labelLarge
        )
        Slider(
            value = profile.lineHeightMultiplier,
            onValueChange = { onChange(profile.copy(lineHeightMultiplier = it)) },
            valueRange = LINE_HEIGHT_MULTIPLIER_RANGE,
            modifier = Modifier.semantics { contentDescription = "Line height" },
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

        SettingsSwitch(
            title = "Cursor blink",
            description = null,
            checked = profile.cursorBlink,
            onCheckedChange = { onChange(profile.copy(cursorBlink = it)) },
        )

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
    var pendingBuiltInName by rememberSaveable { mutableStateOf<String?>(null) }

    @Composable
    fun SchemeColorField(
        label: String,
        color: Int,
        withColor: ColorSchemeEntity.(Int) -> ColorSchemeEntity,
        trailing: (@Composable () -> Unit)? = null,
    ) = ColorHexField(
        label = label,
        color = color,
        onValid = { edit { withColor(it) } },
        preview = { ColorSchemePreview(scheme = scheme.withColor(it), modifier = Modifier.fillMaxWidth()) },
        trailing = trailing,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Built-in", style = MaterialTheme.typography.labelLarge)
        BuiltInColorSchemes.ALL.forEach { builtin ->
            RadioRow(
                selected = scheme.isBuiltIn && scheme.name == builtin.name,
                onClick = {
                    if (scheme.isBuiltIn) onChange(builtin.copy(id = scheme.id))
                    else pendingBuiltInName = builtin.name
                },
            ) {
                Text(builtin.name)
            }
        }
        BuiltInColorSchemes.ALL.firstOrNull { it.name == pendingBuiltInName }?.let { builtin ->
            AlertDialog(
                onDismissRequest = { pendingBuiltInName = null },
                title = { Text("Replace your custom colours?") },
                text = { Text("Your edited colours are overwritten with ${builtin.name}. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingBuiltInName = null
                        onChange(builtin.copy(id = scheme.id))
                    }) { Text("Replace") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBuiltInName = null }) { Text("Cancel") }
                },
            )
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
        SchemeColorField("Foreground", scheme.foreground, { copy(foreground = it) })
        SchemeColorField("Background", scheme.background, { copy(background = it) })
        SchemeColorField(
            "Cursor",
            scheme.cursor,
            { copy(cursor = it) },
            trailing = {
                TextButton(onClick = { edit { copy(cursor = autoCursorColorFor(background)) } }) {
                    Text(
                        "Auto"
                    )
                }
            },
        )
        SchemeColorField("Selection", scheme.selection, { copy(selection = it) })

        Text("ANSI colours", style = MaterialTheme.typography.labelLarge)
        val ansi = scheme.ansiColors()
        ANSI_COLOR_NAMES.forEachIndexed { index, name ->
            SchemeColorField("$index $name", ansi[index], { newColor ->
                val updatedAnsi = ansiColors()
                updatedAnsi[index] = newColor
                copy(ansiColorsCsv = updatedAnsi.joinToString(",") { it.toHexColorString() })
            })
        }
    }
}

/**
 * Committed on each keystroke that lands inside [SCROLLBACK_LINES_RANGE] and never clamped, so
 * typing "5" on the way to "50000" isn't snapped to 100 before the rest of the digits land. The
 * engine gives no feedback when it substitutes a rejected value, so this is the one place validation
 * happens: out-of-range input is flagged and left uncommitted instead of becoming the engine's
 * silent 2000.
 */
@Composable
internal fun ScrollbackField(scrollbackLines: Int, onCommit: (Int) -> Unit) {
    var text by remember(scrollbackLines) { mutableStateOf(scrollbackLines.toString()) }
    val inRange = text.toIntOrNull()?.let { it in SCROLLBACK_LINES_RANGE } == true
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter(Char::isDigit)
            text.toIntOrNull()?.takeIf { it in SCROLLBACK_LINES_RANGE }?.let(onCommit)
        },
        label = { Text("Scrollback lines") },
        supportingText = { Text("${SCROLLBACK_LINES_RANGE.first}-${SCROLLBACK_LINES_RANGE.last}") },
        isError = !inRange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
    preview: (@Composable (Int) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    var text by remember(color) { mutableStateOf(color.toHexColorString()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val parsed = parseHexColorOrNull(text)
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Pick a colour", role = Role.Button) { picking = true }
                    .semantics { contentDescription = "$label colour" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(color), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
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
    if (picking) {
        ColorPickerDialog(
            initial = color,
            onPick = {
                onValid(it)
                picking = false
            },
            onDismiss = { picking = false },
            preview = preview,
        )
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

/** The cursor is a one-space cell, so its width matches the font's own cell at this size. */
@Composable
private fun TerminalProfilePreview(profile: TerminalProfileEntity, scheme: ColorSchemeEntity) {
    val context = LocalContext.current
    val fontFamily =
        remember(profile.fontFamily, profile.customFontUri) {
            FontFamily(
                resolveTerminalTypeface(
                    context,
                    TerminalFontFamily.fromStored(profile.fontFamily),
                    profile.customFontUri,
                ),
            )
        }
    val textStyle =
        TextStyle(
            color = Color(scheme.foreground),
            fontFamily = fontFamily,
            fontSize = profile.fontSizeSp.sp,
            lineHeight = (profile.fontSizeSp * profile.lineHeightMultiplier).sp,
        )
    val cursorStyle = TerminalCursorStyle.fromStored(profile.cursorStyle)
    val cursorColor = Color(scheme.cursor)
    val blinkPhase =
        if (profile.cursorBlink) {
            rememberInfiniteTransition(label = "cursor blink")
                .animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "phase")
        } else {
            null
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(scheme.background))
            .padding(8.dp),
    ) {
        Text("polls@nas:~$ ls", style = textStyle)
        Text("bin  etc  home  var", style = textStyle)
        Row {
            Text("polls@nas:~$ ", style = textStyle)
            Text(
                " ",
                style = textStyle,
                modifier = Modifier.drawBehind {
                    if (blinkPhase != null && blinkPhase.value >= 0.5f) return@drawBehind
                    val thickness = 2.dp.toPx()
                    when (cursorStyle) {
                        TerminalCursorStyle.BLOCK -> drawRect(cursorColor)
                        TerminalCursorStyle.UNDERLINE ->
                            drawRect(cursorColor, Offset(0f, size.height - thickness), Size(size.width, thickness))
                        TerminalCursorStyle.BAR -> drawRect(cursorColor, size = Size(thickness, size.height))
                    }
                },
            )
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
