package io.github.lordofpolls.shellwave.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPair
import java.security.Signature
import java.util.Base64

/**
 * PuTTY `.ppk` import, which users asked for and which turns out to need no code: sshj registers
 * `PuTTYKeyFile` in `DefaultConfig`, [publicKeyLineOf] reaches it through the same
 * `loadKeys(private, public, finder)` call every other format uses, and the SAF picker already
 * accepts every MIME type. These tests exist so that stays true - the failure mode if it regresses
 * is an import that reports "No provider available", which no other test would catch.
 *
 * The fixtures are written by [PuttyKeyFixtures], not by sshj, so a decoder change that quietly
 * stopped matching PuTTY would fail here rather than agree with itself.
 */
class KeyImportPuttyTest {

    /**
     * The same passphrase for every encrypted fixture. It protects a key that is generated in this
     * process and discarded when the test ends, so it is a test parameter instead of a secret.
     */
    private val passphrase = "correct-horse-battery-staple"

    @Test
    fun ppkIsRecognisedAsAPuttyKeyFile() {
        val ppk = PuttyKeyFixtures.ppk(keyPair, version = 3, passphrase = passphrase)

        assertEquals(KeyFormat.PuTTY, KeyProviderUtil.detectKeyFileFormat(ppk, false))
    }

    @Test
    fun ppkV2_unencrypted_yieldsTheSamePublicKeyAsTheKeyItself() {
        val ppk = PuttyKeyFixtures.ppk(keyPair, version = 2)

        assertEquals(expectedPublicKeyLine, publicKeyLineOf(ppk, null))
    }

    @Test
    fun ppkV2_encrypted_isDecryptedByTheHistoricalSha1KeyDerivation() {
        val ppk = PuttyKeyFixtures.ppk(keyPair, version = 2, passphrase = passphrase)

        assertEquals(expectedPublicKeyLine, publicKeyLineOf(ppk, passphrase))
    }

    @Test
    fun ppkV3_unencrypted_yieldsTheSamePublicKeyAsTheKeyItself() {
        val ppk = PuttyKeyFixtures.ppk(keyPair, version = 3)

        assertEquals(expectedPublicKeyLine, publicKeyLineOf(ppk, null))
    }

    /**
     * The highest-risk path of the four: v3 derives its cipher key with Argon2, which sshj takes from
     * BouncyCastle's low-level `Argon2BytesGenerator` and not through a JCE provider. That is a direct
     * class reference, so R8 keeps it and the provider-ordering problem does not apply. It is still the
     * one piece of PPK support that could plausibly be missing on Android, so it gets asserted.
     */
    @Test
    fun ppkV3_encrypted_isDecryptedByArgon2() {
        val ppk = PuttyKeyFixtures.ppk(keyPair, version = 3, passphrase = passphrase)

        assertEquals(expectedPublicKeyLine, publicKeyLineOf(ppk, passphrase))
    }

    /**
     * A wrong passphrase has to fail loudly. sshj reports it as "Invalid passphrase" from the MAC
     * check, not as a decryption error, and [publicKeyLineOf]'s caller shows that message verbatim, so
     * the wording is part of the contract.
     */
    @Test
    fun ppkV3_wrongPassphrase_failsWithAMessageWorthShowing() {
        val ppk = PuttyKeyFixtures.ppk(keyPair, version = 3, passphrase = passphrase)

        val thrown =
            assertThrows(Exception::class.java) { publicKeyLineOf(ppk, "not-the-passphrase") }

        assertEquals("Invalid passphrase", thrown.message)
    }

    /**
     * A missing passphrase must not be mistaken for a corrupt file: the same "Invalid passphrase" has
     * to come back, because sshj falls back to an empty passphrase instead of asking.
     */
    @Test
    fun ppkV3_encrypted_withNoPassphraseAtAll_stillSaysInvalidPassphrase() {
        val ppk = PuttyKeyFixtures.ppk(keyPair, version = 3, passphrase = passphrase)

        val thrown = assertThrows(Exception::class.java) { publicKeyLineOf(ppk, null) }

        assertEquals("Invalid passphrase", thrown.message)
    }

    /**
     * Ed25519 needs its own cases because its private blob is a bare 32-byte string, and there are two
     * plausible things those bytes could be: the RFC 8410 seed or the clamped scalar. PuTTY's spec
     * calls it "the discrete log of the public point", which reads as the scalar; puttygen writes the
     * seed. PuttyKeyFixtures.ed25519Key records how that was settled.
     *
     * Asserting the public line alone would not catch getting this wrong. `PuTTYKeyFile` copies the
     * public half straight out of the file and never checks it against the private half, so a misread
     * private blob still previews the user's real fingerprint and still saves - it only fails at the
     * first connection with "Exhausted available authentication methods", which reads like anything but
     * a bad key. So the assertion that matters is that the two halves verify each other.
     */
    @Test
    fun ppkEd25519_unencrypted_loadsIntoAKeyPairWhoseHalvesAgree() {
        val key = PuttyKeyFixtures.ed25519Key()

        assertHalvesAgree(key, SSHClient().loadKeys(PuttyKeyFixtures.ed25519Ppk(key), null, null))
    }

    @Test
    fun ppkEd25519_encrypted_loadsIntoAKeyPairWhoseHalvesAgree() {
        val key = PuttyKeyFixtures.ed25519Key()
        val ppk = PuttyKeyFixtures.ed25519Ppk(key, passphrase = passphrase)

        val loaded =
            SSHClient().loadKeys(ppk, null, PasswordUtils.createOneOff(passphrase.toCharArray()))

        assertHalvesAgree(key, loaded)
    }

    /** Both halves of [loaded] are [key]: the public one matches the file, and it verifies what the private one signs. */
    private fun assertHalvesAgree(key: PuttyKeyFixtures.Ed25519Key, loaded: KeyProvider) {
        assertEquals(
            "the public half is copied out of the file",
            Base64.getEncoder().encodeToString(PuttyKeyFixtures.ed25519PublicKeyBlob(key)),
            opensshPublicKeyLine(loaded.public).split(" ")[1],
        )

        val message = "shellwave".toByteArray()
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(loaded.private)
        signature.update(message)
        val signed = signature.sign()
        signature.initVerify(loaded.public)
        signature.update(message)

        assertTrue("the loaded halves must belong to each other", signature.verify(signed))
    }

    companion object {
        /**
         * One 2048-bit RSA key for the whole class - key generation dominates the runtime of these tests,
         * and every case only needs a key; freshness buys nothing.
         */
        private lateinit var keyPair: KeyPair
        private lateinit var expectedPublicKeyLine: String

        @BeforeClass
        @JvmStatic
        fun generateKey() {
            keyPair = PuttyKeyFixtures.rsaKeyPair()
            expectedPublicKeyLine = opensshPublicKeyLine(keyPair.public)
        }
    }
}
