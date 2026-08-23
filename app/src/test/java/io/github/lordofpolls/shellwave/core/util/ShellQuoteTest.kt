package io.github.lordofpolls.shellwave.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `{{param}}` values are attacker-reachable text landing in a remote shell command, so this
 * function's failure mode is a shell injection vulnerability and not a cosmetic bug. See
 * [posixQuote]/[substituteParams]'s KDoc for why single-quote-with-escape is the mandatory path.
 */
class ShellQuoteTest {

    // --- posixQuote: exact-output cases -------------------------------------------------------

    @Test
    fun `plain word is wrapped in single quotes`() {
        assertEquals("'hello'", posixQuote("hello"))
    }

    @Test
    fun `empty string becomes an empty quoted token`() {
        assertEquals("''", posixQuote(""))
    }

    @Test
    fun `spaces are preserved literally inside the quotes`() {
        assertEquals("'a b c'", posixQuote("a b c"))
    }

    @Test
    fun `a single embedded quote is escaped with close-escape-reopen`() {
        // it's -> 'it'\''s'  (close 'it', literal \' outside quotes, reopen 's')
        assertEquals("'it'\\''s'", posixQuote("it's"))
    }

    @Test
    fun `a lone single quote round-trips to the escape sequence alone`() {
        assertEquals("''\\'''", posixQuote("'"))
    }

    @Test
    fun `multiple embedded quotes are each escaped independently`() {
        assertEquals("'a'\\''b'\\''c'", posixQuote("a'b'c"))
    }

    // --- posixQuote: adversarial cases ---------------------------------------------------------

    @Test
    fun `semicolon command injection is neutralised`() {
        val quoted = posixQuote("; rm -rf ~")
        assertEquals("'; rm -rf ~'", quoted)
        // No unescaped, unquoted semicolon can appear outside the single-quoted span.
        assertFalse(hasUnquotedChar(quoted, ';'))
    }

    @Test
    fun `command substitution dollar-paren is neutralised`() {
        val quoted = posixQuote("\$(id)")
        assertEquals("'\$(id)'", quoted)
        assertFalse(hasUnquotedChar(quoted, '$'))
    }

    @Test
    fun `backtick command substitution is neutralised`() {
        val quoted = posixQuote("`id`")
        assertEquals("'`id`'", quoted)
        assertFalse(hasUnquotedChar(quoted, '`'))
    }

    @Test
    fun `embedded newline stays inside the single quotes as one token`() {
        val quoted = posixQuote("line1\nline2")
        assertEquals("'line1\nline2'", quoted)
        // The whole thing between the outer quotes is exactly the original value.
        assertEquals("line1\nline2", quoted.substring(1, quoted.length - 1))
    }

    @Test
    fun `double quotes are inert inside single quotes`() {
        assertEquals("'\"quoted\"'", posixQuote("\"quoted\""))
    }

    @Test
    fun `pipe and redirection metacharacters are neutralised`() {
        val quoted = posixQuote("a | b > c &")
        assertEquals("'a | b > c &'", quoted)
    }

    @Test
    fun `and-and chaining is neutralised`() {
        val quoted = posixQuote("x && rm -rf /")
        assertEquals("'x && rm -rf /'", quoted)
    }

    @Test
    fun `glob characters are not expanded`() {
        assertEquals("'*.txt'", posixQuote("*.txt"))
    }

    @Test
    fun `an adversarial payload round-trips through a POSIX shell parse`() {
        val payload = "'; rm -rf ~; echo \$(id) `whoami` \"x\" \n done"
        val quoted = posixQuote(payload)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        // Parsing the quoted form back with real POSIX single-quote/backslash-escape grammar must
        // reproduce the original payload exactly: the strongest available check that quoting is
        // reversible and therefore didn't corrupt or truncate anything adversarial in it.
        assertEquals(payload, parseAsPosixShellWord(quoted))
    }

    // --- substituteParams: the actual call site --------------------------------------------

    @Test
    fun `a single placeholder is replaced with its quoted value`() {
        assertEquals(
            "echo 'hello world'",
            substituteParams("echo {{msg}}", mapOf("msg" to "hello world"))
        )
    }

    @Test
    fun `an injection payload placed in a parameter cannot escape its quoting`() {
        val result = substituteParams("touch {{name}}", mapOf("name" to "; touch /tmp/pwned"))
        assertEquals("touch '; touch /tmp/pwned'", result)
        // The dangerous semicolon sits inside the quoted span, so it is no shell separator.
        assertFalse(hasUnquotedChar(result, ';'))
    }

    @Test
    fun `repeated placeholders are each substituted`() {
        assertEquals("'a' then 'a'", substituteParams("{{x}} then {{x}}", mapOf("x" to "a")))
    }

    @Test
    fun `a placeholder with no value substitutes an empty quoted token`() {
        assertEquals("echo ''", substituteParams("echo {{missing}}", emptyMap()))
    }

    @Test
    fun `unrelated braces are left untouched`() {
        assertEquals(
            "echo {not a param}",
            substituteParams("echo {not a param}", mapOf("x" to "y"))
        )
    }

    @Test
    fun `extractParamNames finds distinct names in first-occurrence order`() {
        assertEquals(
            listOf("host", "env"),
            extractParamNames("ping {{host}} on {{env}}, again {{host}}")
        )
    }

    @Test
    fun `extractParamNames returns nothing for a snippet with no placeholders`() {
        assertTrue(extractParamNames("echo hi").isEmpty())
    }

    // --- helpers --------------------------------------------------------------------------

    /** True if [char] appears in [s] outside of any single-quoted span (a naive but sufficient scanner for these tests). */
    private fun hasUnquotedChar(s: String, char: Char): Boolean {
        var inQuotes = false
        for (c in s) {
            if (c == '\'') inQuotes = !inQuotes
            else if (c == char && !inQuotes) return true
        }
        return false
    }

    /**
     * A minimal POSIX "single word made of quoted spans and backslash-escapes" parser: enough to
     * reverse exactly what [posixQuote] produces (single-quoted spans, plus `\'` outside them for an
     * escaped literal quote) and confirm the round trip reconstructs the original value.
     */
    private fun parseAsPosixShellWord(s: String): String {
        val out = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inQuotes && c == '\'' -> inQuotes = false
                inQuotes -> out.append(c)
                !inQuotes && c == '\'' -> inQuotes = true
                !inQuotes && c == '\\' && i + 1 < s.length -> {
                    out.append(s[i + 1])
                    i++
                }

                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
