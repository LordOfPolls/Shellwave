package io.github.lordofpolls.shellwave.core.crypto

import io.github.lordofpolls.shellwave.ssh.AuthMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One test per rule keeping the stash from becoming a second way to do what
 * [CredentialVault.decryptWindowed] refuses. A fake clock stands in for `SystemClock`.
 */
class TriggerAuthStashTest {

    private var clock = 0L
    private val stash = TriggerAuthStash(ttlMs = 1_000L, now = { clock })

    private val auth = AuthMethod.Password("hunter2")
    private val otherAuth = AuthMethod.Password("different")

    @Test
    fun `a stashed credential is returned once`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        assertEquals(auth, stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `single-use - the second read of the same stash returns null`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        stash.take("token", scriptId = 1, credentialId = 10)
        assertNull(stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `binding - a stash for script A does not satisfy a request for script B`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        assertNull(stash.take("token", scriptId = 2, credentialId = 10))
    }

    @Test
    fun `binding - a wrong-script read still consumes the entry, so retrying with the right script also fails`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        stash.take("token", scriptId = 2, credentialId = 10) // wrong script, consumes anyway
        assertNull(stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `binding - an unrelated token cannot see another flow's stash at all`() {
        stash.put("token-a", scriptId = 1, credentialId = 10, authMethod = auth)

        assertNull(stash.take("token-b", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `binding - the wrong credential id under the same token and script is not returned`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        assertNull(stash.take("token", scriptId = 1, credentialId = 99))
    }

    @Test
    fun `expiry - a stale stash past its deadline is not honoured`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        clock += 1_000L // exactly at the deadline: already stale, not "still good"
        assertNull(stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `expiry - a stash read just before its deadline still succeeds`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        clock += 999L
        assertEquals(auth, stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `expiry - purgeExpired drops a stale entry nobody ever read`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)
        clock += 1_000L

        stash.purgeExpired()

        clock = 0L // rewinding must not resurrect it: purge removes, it does not just hide
        assertNull(stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `shared credential - a second put for the same key adds a use instead of overwriting`() {
        // Target and jump host can share one saved credential, so the trampoline stashes twice.
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)

        assertEquals(auth, stash.take("token", scriptId = 1, credentialId = 10))
        assertEquals(auth, stash.take("token", scriptId = 1, credentialId = 10))
        assertNull(stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `a put after the previous entry for the same key expired starts a fresh single use, not a third use`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)
        clock += 1_000L // the first entry is now stale

        stash.put("token", scriptId = 1, credentialId = 10, authMethod = otherAuth)

        assertEquals(otherAuth, stash.take("token", scriptId = 1, credentialId = 10))
        assertNull(stash.take("token", scriptId = 1, credentialId = 10))
    }

    @Test
    fun `different credential ids under the same token and script are independent`() {
        stash.put("token", scriptId = 1, credentialId = 10, authMethod = auth)
        stash.put("token", scriptId = 1, credentialId = 20, authMethod = otherAuth)

        assertEquals(auth, stash.take("token", scriptId = 1, credentialId = 10))
        assertEquals(otherAuth, stash.take("token", scriptId = 1, credentialId = 20))
    }
}
