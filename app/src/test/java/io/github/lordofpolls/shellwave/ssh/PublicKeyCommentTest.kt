package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PublicKeyCommentTest {

    @Test
    fun `a label becomes a single-line comment`() {
        assertEquals("fable key", publicKeyComment("fable key"))
        assertEquals("a b c", publicKeyComment("a\nb\r\n\tc"))
        assertEquals("a b", publicKeyComment("a b"))
    }

    @Test
    fun `no usable label falls back to shellwave`() {
        assertEquals("shellwave", publicKeyComment(null))
        assertEquals("shellwave", publicKeyComment(" \n\t "))
    }

    @Test
    fun `a generated public key line carries the label and stays one line`() {
        val line = generateKeyPair(GeneratedKeyAlgorithm.ED25519, "fable\nkey").publicKeyLine
        assertEquals("fable key", line.substringAfter(' ').substringAfter(' '))
        assertFalse(line.contains('\n'))
    }
}
