package io.github.lordofpolls.shellwave.core.db

import io.github.lordofpolls.shellwave.core.db.entities.decodeAgentKeyIds
import io.github.lordofpolls.shellwave.core.db.entities.encodeAgentKeyIds
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentKeyIdsTest {
    @Test
    fun roundTripsInOrder() {
        assertEquals(listOf(7L, 3L, 12L), decodeAgentKeyIds(encodeAgentKeyIds(listOf(7, 3, 12))))
        assertEquals(emptyList<Long>(), decodeAgentKeyIds(encodeAgentKeyIds(emptyList())))
    }

    @Test
    fun garbageEntriesAreDroppedNotGuessed() {
        assertEquals(listOf(1L, 3L), decodeAgentKeyIds("[1, x, 3,]"))
        assertEquals(emptyList<Long>(), decodeAgentKeyIds(""))
    }
}
