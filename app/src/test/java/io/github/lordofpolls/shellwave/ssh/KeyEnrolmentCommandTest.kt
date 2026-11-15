package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs the real command through `sh` against a throwaway `$HOME`, since the append logic only
 * exists as shell. Skipped where no `sh` is available.
 */
class KeyEnrolmentCommandTest {

    @get:Rule
    val home = TemporaryFolder()

    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAItest shellwave"

    private fun authorizedKeys() = File(home.root, ".ssh/authorized_keys")

    private fun run(): String {
        assumeTrue(File("/bin/sh").canExecute())
        val process = ProcessBuilder("/bin/sh", "-c", enrolmentCommand(key))
            .redirectErrorStream(true)
            .apply { environment()["HOME"] = home.root.absolutePath }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun seed(content: String) {
        authorizedKeys().parentFile.mkdirs()
        authorizedKeys().writeText(content)
    }

    @Test
    fun missingFile_getsTheKeyAndATrailingNewline() {
        assertTrue(ENROLMENT_OK_MARKER in run())
        assertEquals("$key\n", authorizedKeys().readText())
    }

    @Test
    fun emptyFile_getsNoBlankLine() {
        seed("")
        run()
        assertEquals("$key\n", authorizedKeys().readText())
    }

    @Test
    fun fileWithTrailingNewline_getsNoBlankLine() {
        seed("ssh-rsa AAAAold other\n")
        run()
        assertEquals("ssh-rsa AAAAold other\n$key\n", authorizedKeys().readText())
    }

    @Test
    fun fileWithoutTrailingNewline_keepsTheLastEntryIntact() {
        seed("ssh-rsa AAAAold other")
        assertTrue(ENROLMENT_OK_MARKER in run())
        assertEquals("ssh-rsa AAAAold other\n$key\n", authorizedKeys().readText())
    }

    @Test
    fun alreadyPresentKey_isNotAppendedAgain() {
        seed("$key\n")
        run()
        assertEquals("$key\n", authorizedKeys().readText())
    }

    @Test
    fun command_usesOnlySyntaxThatCshAlsoAccepts() {
        val command = enrolmentCommand(key)
        listOf("$(", "`", "{", "}", "[").forEach { assertTrue("contains $it", it !in command) }
    }
}
