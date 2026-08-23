package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the bar a user sees before they have ever opened the key bar editor, checked
 * against the live [DEFAULT_KEY_BAR_KEYS]: restating the constants here would pin the copy.
 *
 * The default bar is the one piece of terminal chrome nobody chose, so what is worth pinning is
 * what a later edit could break silently. It has to survive the round trip into
 * [io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity.keysJson], it has to stay
 * rebuildable from the editor's own catalogue, and it has to keep the shape [KeyBar] lays out.
 */
class KeyBarLayoutModelTest {
    /** [KeyBar] prepends Ctrl and Alt, so they occupy two slots without being [KeyBarKey]s. */
    private val flowingButtonCount =
        2 + DEFAULT_KEY_BAR_KEYS.count { it.type != KeyBarKeyType.CURSOR_CLUSTER }

    @Test
    fun `the default bar survives the round trip through a saved layout`() {
        // What happens the first time someone opens the editor: KeyBarLayoutsScreen seeds a new
        // layout from DEFAULT_KEY_BAR_KEYS through encodeKeyBarKeys, and the session screen reads
        // it back through decodeKeyBarKeys. A key that does not survive that is a key that vanishes
        // from the bar the moment the user assigns a layout to a host.
        assertEquals(DEFAULT_KEY_BAR_KEYS, decodeKeyBarKeys(encodeKeyBarKeys(DEFAULT_KEY_BAR_KEYS)))
    }

    @Test
    fun `every default key can be added back from the editor's own catalogue`() {
        // The editor can only offer what is in SPECIAL_KEY_CHOICES. A default key with no entry
        // there is one a user can delete and then never restore - and they would have no way of
        // knowing that until after deleting it.
        val offered = SPECIAL_KEY_CHOICES.map { it.keyCode }.toSet()
        DEFAULT_KEY_BAR_KEYS.filter { it.type == KeyBarKeyType.SPECIAL }.forEach { key ->
            assertTrue(
                "${key.label} is on the default bar but not offered by the editor",
                key.keyCode in offered
            )
        }
    }

    @Test
    fun `every default key describes what it does, not what it is labelled`() {
        DEFAULT_KEY_BAR_KEYS.filter { it.type == KeyBarKeyType.SPECIAL }.forEach { key ->
            // keyBarKeyDescription falls back to "Send <label> key" for a keycode it has no curated
            // wording for, which for several of these would be true but useless ("Send Home key"
            // rather than "Move to start of line"). Deliberately probed with a nonsense *label*: a
            // curated description is built from the keycode and ignores the label, so a fallback is
            // the only thing that can echo it back, whatever the real label happens to be.
            val probe = KeyBarKey("zzz", KeyBarKeyType.SPECIAL, keyCode = key.keyCode)
            assertNotEquals(
                "${key.label} has no curated TalkBack description",
                "Send zzz key",
                keyBarKeyDescription(probe)
            )
        }
    }

    @Test
    fun `the default bar has exactly one cursor cluster, at the end`() {
        // KeyBar pins the cluster as a column to the right of the flowing keys regardless of where
        // it sits in the list, so a cluster anywhere but last would render in a different order
        // than the editor's own list shows it in.
        val clusters = DEFAULT_KEY_BAR_KEYS.filter { it.type == KeyBarKeyType.CURSOR_CLUSTER }
        assertEquals(1, clusters.size)
        assertEquals(clusters.single(), DEFAULT_KEY_BAR_KEYS.last())
    }

    @Test
    fun `the default bar's flowing keys split into two equal rows`() {
        // The cluster makes the strip two rows tall whatever else is on it, so KeyBar lays the flat
        // keys across both rows - free height that would otherwise sit blank. An odd count leaves a
        // hole on the second row, which is the visual defect this arrangement exists to remove.
        assertEquals("flowing keys do not divide evenly across two rows", 0, flowingButtonCount % 2)
        assertTrue("too few keys for the two-row split to read as a grid", flowingButtonCount >= 4)
    }

    @Test
    fun `no default key types anything at the far end`() {
        // A MACRO sends literal text to the remote shell. Shipping one on the bar nobody chose
        // would put a command a user never wrote one tap from their server.
        DEFAULT_KEY_BAR_KEYS.forEach { key ->
            assertNotEquals("${key.label} is a macro", KeyBarKeyType.MACRO, key.type)
        }
    }
}
