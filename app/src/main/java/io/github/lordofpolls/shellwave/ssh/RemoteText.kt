package io.github.lordofpolls.shellwave.ssh

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

const val MAX_REMOTE_TEXT_BYTES = 2L * 1024 * 1024

private const val BINARY_SNIFF_BYTES = 8 * 1024

/** [text] is LF-joined with no trailing newline; [lineSeparator] and [trailingNewline] restore the original bytes. */
data class RemoteText(
    val text: String,
    val lineSeparator: String,
    val trailingNewline: Boolean,
    val mtime: Long,
)

class RemoteFileChanged(val serverMtime: Long) :
    IOException("The file changed on the server (modified time $serverMtime)")

class RemoteFileNotText(val reason: String) : IOException(reason)

internal object RemoteTextCodec {
    fun decode(bytes: ByteArray, mtime: Long): RemoteText {
        if (bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }) throw RemoteFileNotText("binary file")
        val raw =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw RemoteFileNotText("not valid UTF-8")
            }
        // Mixed endings, and a double-converted \r\r\n, stay LF with their stray \r in the text so
        // they round-trip byte-exact and the CRLF fold in encode can never touch an unedited line.
        val isCrlf =
            '\n' in raw && "\r\r\n" !in raw &&
                raw.indices.none { raw[it] == '\n' && (it == 0 || raw[it - 1] != '\r') }
        val separator = if (isCrlf) "\r\n" else "\n"
        val trailing = raw.endsWith(separator)
        val body = if (trailing) raw.removeSuffix(separator) else raw
        return RemoteText(body.replace(separator, "\n"), separator, trailing, mtime)
    }

    /** The editor keeps a pasted `\r\n` as-is, so a CRLF file folds those first or they'd come back as `\r\r\n`. */
    fun encode(text: RemoteText): ByteArray {
        val body = if (text.lineSeparator == "\r\n") text.text.replace("\r\n", "\n") else text.text
        return (body.replace("\n", text.lineSeparator) + if (text.trailingNewline) text.lineSeparator else "")
            .toByteArray(Charsets.UTF_8)
    }
}
