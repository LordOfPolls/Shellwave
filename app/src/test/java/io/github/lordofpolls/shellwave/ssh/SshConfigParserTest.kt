package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic, no device or Android dependency - see [parseSshConfig]'s own class doc for which
 * parts of real ssh_config semantics are implemented versus skipped. All fixtures here are
 * synthetic (made-up hostnames/users), never real credentials or key material.
 */
class SshConfigParserTest {

    @Test
    fun `multiple Host entries are each parsed with their own fields`() {
        val config =
            """
            Host web1
                HostName 10.0.0.1
                User deploy
                Port 2222

            Host web2
                HostName 10.0.0.2
                User admin
            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals(listOf("web1", "web2"), parsed.hosts.map { it.alias })
        val web1 = parsed.hosts.first { it.alias == "web1" }
        assertEquals("10.0.0.1", web1.hostName)
        assertEquals("deploy", web1.user)
        assertEquals(2222, web1.port)
        val web2 = parsed.hosts.first { it.alias == "web2" }
        assertEquals("10.0.0.2", web2.hostName)
        assertEquals("admin", web2.user)
        assertNull(web2.port)
    }

    @Test
    fun `a wildcard Host block is not itself an importable entry`() {
        val config =
            """
            Host *
                User defaultuser

            Host onlyrealhost
                HostName 10.0.0.5
            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals(listOf("onlyrealhost"), parsed.hosts.map { it.alias })
    }

    @Test
    fun `Host star is conventionally a defaults block applied to literal aliases`() {
        val config =
            """
            Host *
                User globaluser
                Port 2200

            Host special
                HostName 10.1.1.1
                User specificuser
            """.trimIndent()

        val parsed = parseSshConfig(config)

        val plain = parsed.hosts.first { it.alias == "special" }
        // Host * wins here even though "special" has its own User. ssh keeps the first value it
        // obtains for a keyword, so whichever block comes first in the file decides; being more
        // specific buys nothing. This is why ssh_config files put narrow blocks at the top.
        assertEquals("globaluser", plain.user)
        assertEquals(2200, plain.port)
    }

    @Test
    fun `a more specific block placed before Host star wins for that alias`() {
        val config =
            """
            Host special
                HostName 10.1.1.1
                User specificuser

            Host *
                User globaluser
                Port 2200
            """.trimIndent()

        val parsed = parseSshConfig(config)

        val special = parsed.hosts.first { it.alias == "special" }
        assertEquals("specificuser", special.user)
        // Port was never set on the "special" block itself, so the later Host * still supplies it.
        assertEquals(2200, special.port)
    }

    @Test
    fun `wildcard patterns match literal aliases for defaults purposes`() {
        val config =
            """
            Host prod-*
                User prodservice

            Host prod-1
                HostName 10.2.2.1
            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals(listOf("prod-1"), parsed.hosts.map { it.alias })
        assertEquals("prodservice", parsed.hosts.single().user)
    }

    @Test
    fun `keys are case-insensitive`() {
        val config =
            """
            HOST mixedcase
                HOSTNAME 10.3.3.1
                uSeR someuser
                PORT 2201
            """.trimIndent()

        val parsed = parseSshConfig(config)

        val host = parsed.hosts.single()
        assertEquals("mixedcase", host.alias)
        assertEquals("10.3.3.1", host.hostName)
        assertEquals("someuser", host.user)
        assertEquals(2201, host.port)
    }

    @Test
    fun `ProxyJump can forward-reference a host defined later in the file`() {
        val config =
            """
            Host target
                HostName 10.4.4.1
                ProxyJump bastion

            Host bastion
                HostName 10.4.4.254
                User bastionuser
            """.trimIndent()

        val parsed = parseSshConfig(config)

        val target = parsed.hosts.first { it.alias == "target" }
        assertEquals("bastion", target.proxyJump)
        // The forward-referenced alias is itself present in the same parse result, proving the
        // parser doesn't need the referenced host to appear earlier - resolution is the caller's
        // job (matching against this same list); parseSshConfig itself never blocks on it.
        assertTrue(parsed.hosts.any { it.alias == "bastion" })
    }

    @Test
    fun `an unresolvable ProxyJump alias is carried through as plain text`() {
        val config =
            """
            Host lonely
                HostName 10.5.5.1
                ProxyJump ghost-host-that-does-not-exist
            """.trimIndent()

        val parsed = parseSshConfig(config)

        val lonely = parsed.hosts.single()
        assertEquals("ghost-host-that-does-not-exist", lonely.proxyJump)
        assertTrue(parsed.hosts.none { it.alias == "ghost-host-that-does-not-exist" })
    }

    @Test
    fun `Include directives are recorded but never followed`() {
        val config =
            """
            Include conf.d/*.conf

            Host onlyone
                HostName 10.6.6.1
            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals(listOf("conf.d/*.conf"), parsed.includeDirectives)
        // Only the literal Host block in this file itself was parsed: nothing from the (unfollowed)
        // included file could possibly appear.
        assertEquals(listOf("onlyone"), parsed.hosts.map { it.alias })
    }

    @Test
    fun `multiple Include directives are all recorded in file order`() {
        val config =
            """
            Include first.conf
            Host x
                HostName 10.7.7.1
            Include second.conf
            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals(listOf("first.conf", "second.conf"), parsed.includeDirectives)
    }

    @Test
    fun `a malformed line is ignored`() {
        val config =
            """
            this is not a valid directive at all
            Host fine
                HostName 10.8.8.1
            ===
            Port
            # a comment on its own line
            Host
                User orphaned
            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals(listOf("fine"), parsed.hosts.map { it.alias })
        assertEquals("10.8.8.1", parsed.hosts.single().hostName)
    }

    @Test
    fun `blank lines and full-line comments are ignored`() {
        val config =
            """

            # a leading comment
            Host commented

                # indented comment
                HostName 10.9.9.1

            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals("10.9.9.1", parsed.hosts.single().hostName)
    }

    @Test
    fun `HostName defaults to the alias itself when not specified`() {
        val config = "Host aliasonly\n    User someone"
        val parsed = parseSshConfig(config)

        assertEquals("aliasonly", parsed.hosts.single().hostName)
    }

    @Test
    fun `multiple patterns on one Host line each become their own entry`() {
        val config =
            """
            Host web1 web2
                User shared
            """.trimIndent()

        val parsed = parseSshConfig(config)

        assertEquals(listOf("web1", "web2"), parsed.hosts.map { it.alias })
        assertTrue(parsed.hosts.all { it.user == "shared" })
    }

    @Test
    fun `IdentityFile collects paths and nothing more`() {
        val config =
            """
            Host withkey
                HostName 10.10.10.1
                IdentityFile ~/.ssh/id_ed25519
                IdentityFile ~/.ssh/id_rsa_backup
            """.trimIndent()

        val parsed = parseSshConfig(config)

        val host = parsed.hosts.single()
        assertEquals(listOf("~/.ssh/id_ed25519", "~/.ssh/id_rsa_backup"), host.identityFiles)
    }

    @Test
    fun `Compression and ServerAliveInterval are parsed for display only`() {
        val config =
            """
            Host tuned
                HostName 10.11.11.1
                Compression yes
                ServerAliveInterval 45
            """.trimIndent()

        val parsed = parseSshConfig(config)

        val host = parsed.hosts.single()
        assertEquals(true, host.compression)
        assertEquals(45, host.serverAliveInterval)
    }

    @Test
    fun `Compression no parses to false, not null`() {
        val config =
            """
            Host notcompressed
                HostName 10.12.12.1
                Compression no
            """.trimIndent()

        assertEquals(false, parseSshConfig(config).hosts.single().compression)
    }

    @Test
    fun `key-value pairs may use equals-sign syntax`() {
        val config =
            """
            Host eq
                HostName=10.13.13.1
                Port = 2222
            """.trimIndent()

        val parsed = parseSshConfig(config)
        val host = parsed.hosts.single()
        assertEquals("10.13.13.1", host.hostName)
        assertEquals(2222, host.port)
    }

    @Test
    fun `trailing comments after a value are stripped`() {
        val config =
            """
            Host commented
                HostName 10.14.14.1 # production box
            """.trimIndent()

        assertEquals("10.14.14.1", parseSshConfig(config).hosts.single().hostName)
    }

    @Test
    fun `an empty file parses to no hosts and no includes`() {
        val parsed = parseSshConfig("")
        assertTrue(parsed.hosts.isEmpty())
        assertTrue(parsed.includeDirectives.isEmpty())
    }

    @Test
    fun `a directive before any Host line acts as a file-wide default`() {
        val config =
            """
            ServerAliveInterval 30

            Host anyhost
                HostName 10.15.15.1
            """.trimIndent()

        val parsed = parseSshConfig(config)
        assertEquals(30, parsed.hosts.single().serverAliveInterval)
    }
}
