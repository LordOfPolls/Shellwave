package io.github.lordofpolls.shellwave.feature.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostCredentialTest {

    private fun plan(
        kind: AuthKind,
        stored: Long? = null,
        keep: Boolean = false,
        touched: Boolean = false,
        existing: Long? = 5L,
    ) = planCredentialSave(kind, stored, keep, touched, existing)

    @Test
    fun `the host label replaces a public key comment that has spaces`() {
        assertEquals(
            "ssh-ed25519 AAAAC3 web1 key",
            withPublicKeyComment("ssh-ed25519 AAAAC3 old comment here", hostKeyLabel("web1")),
        )
    }

    @Test
    fun `a stored key is used as is and nothing is sealed`() {
        assertEquals(
            CredentialSavePlan(CredentialChoice.Stored(42L), null),
            plan(AuthKind.STORED_KEY, stored = 42L),
        )
    }

    @Test
    fun `a stored key ignores the existing credential and keep flag`() {
        assertEquals(
            CredentialSavePlan(CredentialChoice.Stored(42L), null),
            plan(AuthKind.STORED_KEY, stored = 42L, keep = true, existing = 5L),
        )
    }

    @Test
    fun `a certificate picked under a stored key goes to that key`() {
        assertEquals(42L, plan(AuthKind.STORED_KEY, stored = 42L, touched = true)!!.certificateTargetId)
    }

    @Test
    fun `a stored key with nothing picked cannot be saved`() {
        assertNull(plan(AuthKind.STORED_KEY, stored = null))
    }

    @Test
    fun `an unchanged credential keeps the existing id`() {
        assertEquals(
            CredentialSavePlan(CredentialChoice.Existing(5L), null),
            plan(AuthKind.PASSWORD, keep = true),
        )
    }

    @Test
    fun `an unchanged credential with no existing host cannot be saved`() {
        assertNull(plan(AuthKind.PASSWORD, keep = true, existing = null))
    }

    @Test
    fun `every other kind seals a new credential`() {
        listOf(AuthKind.PASSWORD, AuthKind.IMPORT_KEY, AuthKind.GENERATE_KEY, AuthKind.KEYBOARD_INTERACTIVE)
            .forEach { assertEquals(CredentialSavePlan(CredentialChoice.SealNew, null), plan(it, touched = true)) }
    }

    @Test
    fun `a certificate loaded under import key never lands on a password or keyboard-interactive row`() {
        listOf(AuthKind.PASSWORD, AuthKind.KEYBOARD_INTERACTIVE).forEach {
            assertNull(plan(it, keep = true, touched = true)!!.certificateTargetId)
        }
        assertEquals(5L, plan(AuthKind.IMPORT_KEY, keep = true, touched = true)!!.certificateTargetId)
    }

    @Test
    fun `a host generated key is named after the host without taking its name`() {
        assertEquals("fable key", hostKeyLabel("fable"))
        assertNull(hostKeyLabel(""))
        assertNull(hostKeyLabel(null))
    }
}
