package io.github.lordofpolls.shellwave.feature.host

import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private class InMemoryProfileDao(val rows: MutableMap<Long, TerminalProfileEntity> = mutableMapOf()) :
    TerminalProfileDao {
    private var nextId = 1L

    override suspend fun getAll(): List<TerminalProfileEntity> = rows.values.toList()
    override suspend fun getDefault(): TerminalProfileEntity? = rows.minByOrNull { it.key }?.value
    override fun observeDefault(): Flow<TerminalProfileEntity?> = TODO()
    override suspend fun getById(id: Long): TerminalProfileEntity? = rows[id]
    override suspend fun insert(profile: TerminalProfileEntity): Long {
        val id = nextId++
        rows[id] = profile.copy(id = id)
        return id
    }
    override suspend fun update(profile: TerminalProfileEntity) = TODO()
    override suspend fun delete(profile: TerminalProfileEntity) = TODO()
}

private class InMemorySchemeDao(val rows: MutableMap<Long, ColorSchemeEntity> = mutableMapOf()) : ColorSchemeDao {
    private var nextId = 1L

    override suspend fun getAll(): List<ColorSchemeEntity> = rows.values.toList()
    override suspend fun getDefault(): ColorSchemeEntity? = rows.minByOrNull { it.key }?.value
    override fun observeDefault(): Flow<ColorSchemeEntity?> = TODO()
    override suspend fun getById(id: Long): ColorSchemeEntity? = rows[id]
    override suspend fun insert(scheme: ColorSchemeEntity): Long {
        val id = nextId++
        rows[id] = scheme.copy(id = id)
        return id
    }
    override suspend fun update(scheme: ColorSchemeEntity) = TODO()
    override suspend fun delete(scheme: ColorSchemeEntity) = TODO()
}

class InsertOverrideTest {

    @Test
    fun profileOverride_inEmptyTable_doesNotBecomeDefault() = runBlocking {
        val dao = InMemoryProfileDao()
        val override = dao.insertOverride()
        assertNotEquals(override.id, dao.getDefault()?.id)
        assertEquals("Default", dao.getDefault()?.name)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun profileOverride_withExistingDefault_addsOnlyTheOverride() = runBlocking {
        val dao = InMemoryProfileDao()
        dao.insertOverride()
        val defaultId = dao.getDefault()?.id
        dao.insertOverride()
        assertEquals(defaultId, dao.getDefault()?.id)
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun schemeOverride_inEmptyTable_doesNotBecomeDefault() = runBlocking {
        val dao = InMemorySchemeDao()
        val override = dao.insertOverride()
        assertNotEquals(override.id, dao.getDefault()?.id)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun schemeOverride_withExistingDefault_addsOnlyTheOverride() = runBlocking {
        val dao = InMemorySchemeDao()
        dao.insertOverride()
        val defaultId = dao.getDefault()?.id
        dao.insertOverride()
        assertEquals(defaultId, dao.getDefault()?.id)
        assertEquals(3, dao.rows.size)
    }
}
