package io.github.lordofpolls.shellwave.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [groupFingerprint] must only ever insert separators. A regression that dropped, duplicated or
 * reordered characters would render a wrong fingerprint that still looked entirely plausible - the
 * one failure mode a fingerprint display cannot have, since a human compares it character by
 * character against another source.
 */
class FingerprintBlockTest {
    @Test
    fun `groups the base64 body in eights and drops the prefix`() {
        val fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
        assertEquals(
            "abcdefgh ijklmnop qrstuvwx yz012345 6789ABCD EFG",
            groupFingerprint(fingerprint)
        )
    }

    /** The grouped form must be the original body exactly, once the inserted spaces are removed. */
    @Test
    fun `grouping is separator insertion only`() {
        val fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
        val body = fingerprint.substringAfter(':')
        assertEquals(body, groupFingerprint(fingerprint).replace(" ", ""))
    }

    /** A value with no prefix is grouped whole and not swallowed by substringAfter's default. */
    @Test
    fun `value without a prefix is grouped in full`() {
        assertEquals("abcdefgh ij", groupFingerprint("abcdefghij"))
    }

    @Test
    fun `body shorter than one group is returned unchanged`() {
        assertEquals("abc", groupFingerprint("SHA256:abc"))
    }
}
