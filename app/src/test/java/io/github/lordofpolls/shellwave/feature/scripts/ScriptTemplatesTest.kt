package io.github.lordofpolls.shellwave.feature.scripts

import io.github.lordofpolls.shellwave.core.util.extractParamNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants the starter catalogue has to hold, checked against the live ScriptTemplates and not a
 * copied list of expected values, which would only pin the copy.
 *
 * Every template has to survive the round trip into a real [ScriptEntity] and back out through the
 * runner, because nothing else stands between these constants and a command executing on someone's
 * server: a `{{param}}` the editor cannot see, or a mode string the runner cannot parse, would
 * otherwise fail there instead of here.
 */
class ScriptTemplatesTest {
    @Test
    fun `every template declares a param for each placeholder in its snippet`() {
        ScriptTemplates.ALL.forEach { template ->
            val placeholders = extractParamNames(template.snippet).toSet()
            val declared = template.params.map { it.name }.toSet()
            // Both directions matter. An undeclared placeholder is substituted with an empty string
            // by substituteParams and not prompted for, so the script would silently run against
            // nothing; a declared param with no placeholder prompts for a value that is then
            // dropped on the floor.
            assertEquals(
                "${template.name}: placeholders and declared params disagree",
                placeholders,
                declared
            )
        }
    }

    @Test
    fun `every template's mode round-trips through the enum the runner parses`() {
        ScriptTemplates.ALL.forEach { template ->
            val entity = template.toEntity()
            // ScriptEntity.mode is a plain String column, and ScriptRunController.execute reports
            // "Unknown script mode" for anything ScriptMode.valueOf rejects.
            assertEquals(template.name, template.mode, ScriptMode.valueOf(entity.mode))
        }
    }

    @Test
    fun `every template's params survive the JSON encoding the entity stores`() {
        ScriptTemplates.ALL.forEach { template ->
            // paramsJson is the only place a template's param definitions live once saved, so a
            // param that does not survive encodeParams/decodeParams is a param the editor and the
            // prompt will never show.
            assertEquals(
                template.name,
                template.params,
                decodeParams(template.toEntity().paramsJson)
            )
        }
    }

    @Test
    fun `templates are saved hostless so each run picks a host`() {
        ScriptTemplates.ALL.forEach { template ->
            // A null targetHostId means "ask which host each run", which is the only sensible
            // target for a script shipped before the user has any hosts. It is also what keeps a
            // starter script off a widget or Quick Settings tile until the user gives it a fixed
            // host - ScriptTriggerService refuses hostless scripts.
            assertEquals(template.name, null, template.toEntity().targetHostId)
        }
    }

    @Test
    fun `templates that change the server ask first`() {
        // Not a blanket rule - the read-only ones do not confirm, because a confirmation on `df -h`
        // is noise that teaches users to tap through confirmations.
        val mutating = ScriptTemplates.ALL.filter { it.snippet.contains("sudo ") }
        assertTrue(
            "expected the catalogue to still contain mutating examples",
            mutating.isNotEmpty()
        )
        mutating.forEach {
            assertTrue(
                "${it.name} runs sudo without confirmBeforeRun",
                it.confirmBeforeRun
            )
        }
    }

    @Test
    fun `no template reboots immediately`() {
        // An immediate reboot drops the connection while capture mode is still reading the exec
        // channel, so a reboot that worked is reported as a connection failure. The delayed form
        // returns cleanly: see ScriptTemplates.REBOOT. Guards against someone "simplifying" this
        // back to `sudo reboot` later, which would look tidier and be worse.
        ScriptTemplates.ALL.forEach { template ->
            assertTrue(
                "${template.name}: use a delayed shutdown, not an immediate reboot",
                !template.snippet.contains("reboot") && !template.snippet.contains("shutdown -r now"),
            )
        }
    }

    @Test
    fun `template names are unique`() {
        // MainActivity round-trips the chosen template through its name (prefillTemplateName) so
        // the selection survives process death without ScriptTemplate being Parcelable. Two
        // templates sharing a name would silently prefill the editor with the wrong one.
        val names = ScriptTemplates.ALL.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `every template has a name, a description and a snippet`() {
        ScriptTemplates.ALL.forEach { template ->
            assertTrue("blank name", template.name.isNotBlank())
            assertTrue("${template.name}: blank description", template.description.isNotBlank())
            assertTrue("${template.name}: blank snippet", template.snippet.isNotBlank())
            // canSave() in the editor requires both, so a blank one would land the user in a form
            // they cannot save without noticing why.
            assertNotNull(template.toEntity().color)
        }
    }
}
