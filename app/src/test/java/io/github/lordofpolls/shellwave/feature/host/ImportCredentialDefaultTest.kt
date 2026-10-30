package io.github.lordofpolls.shellwave.feature.host

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportCredentialDefaultTest {

    @Test
    fun `no saved credentials defaults to a new password`() {
        assertEquals(EntryCredentialChoice.PASSWORD to null, defaultCredentialChoice(emptyList()))
    }

    @Test
    fun `a single saved credential is preselected`() {
        assertEquals(EntryCredentialChoice.EXISTING to 7L, defaultCredentialChoice(listOf(7L)))
    }

    @Test
    fun `several saved credentials leave the pick to the user`() {
        assertEquals(EntryCredentialChoice.EXISTING to null, defaultCredentialChoice(listOf(1L, 2L)))
    }
}
