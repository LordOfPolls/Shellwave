package io.github.lordofpolls.shellwave.feature.glance

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lordofpolls.shellwave.core.db.dao.ScriptDao

/**
 * `ScriptWidget.provideGlance` and [ScriptTileService] get a plain `Context`, with nothing Hilt can
 * inject into, so they reach the same `@Singleton` ScriptDao through this.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun scriptDao(): ScriptDao
}
