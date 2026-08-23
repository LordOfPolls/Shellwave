package io.github.lordofpolls.shellwave.terminal

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import io.github.lordofpolls.shellwave.R

private const val LOG_TAG = "TerminalFonts"

/**
 * Affects only [TerminalCanvas]'s glyph grid, never app chrome: the chrome is the app speaking, the
 * grid is the server speaking, and only the latter is themeable.
 *
 * The bundled files are Google Fonts' variable-font releases, one file spanning the whole weight
 * range, though only the regular named instance is ever requested. See /NOTICE for licences.
 */
enum class TerminalFontFamily(val displayName: String, @FontRes val fontRes: Int?) {
    JETBRAINS_MONO("JetBrains Mono", R.font.jetbrains_mono),
    FIRA_CODE("Fira Code", R.font.fira_code),
    SOURCE_CODE_PRO("Source Code Pro", R.font.source_code_pro),
    ROBOTO_MONO("Roboto Mono", R.font.roboto_mono),
    CUSTOM("Custom font...", null),
    ;

    companion object {
        fun fromStored(name: String?): TerminalFontFamily =
            entries.firstOrNull { it.name == name } ?: JETBRAINS_MONO
    }
}

/**
 * Never throws. A revoked SAF permission or a deleted custom font falls back to the bundled default
 * instead of crashing the terminal.
 */
fun resolveTerminalTypeface(
    context: Context,
    fontFamily: TerminalFontFamily,
    customFontUri: String?
): Typeface {
    if (fontFamily == TerminalFontFamily.CUSTOM) {
        val loaded = customFontUri?.let { loadCustomTypeface(context, it) }
        if (loaded != null) return loaded
        Log.w(
            LOG_TAG,
            "Custom font unavailable (uri=$customFontUri) - falling back to bundled default"
        )
    }
    val resId =
        (if (fontFamily == TerminalFontFamily.CUSTOM) TerminalFontFamily.JETBRAINS_MONO else fontFamily).fontRes
    return resId?.let { ResourcesCompat.getFont(context, it) } ?: Typeface.MONOSPACE
}

private fun loadCustomTypeface(context: Context, uriString: String): Typeface? =
    runCatching {
        val uri = Uri.parse(uriString)
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            Typeface.Builder(pfd.fileDescriptor).build()
        }
    }.onFailure { e -> Log.w(LOG_TAG, "Failed to load custom font $uriString: ${e.message}") }
        .getOrNull()
