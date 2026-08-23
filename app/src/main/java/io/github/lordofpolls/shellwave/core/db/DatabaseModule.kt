package io.github.lordofpolls.shellwave.core.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ShellwaveDatabase =
        Room.databaseBuilder(
            context,
            ShellwaveDatabase::class.java,
            ShellwaveDatabase.DATABASE_NAME
        )
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7
            )
            .build()

    @Provides
    fun provideHostDao(db: ShellwaveDatabase) = db.hostDao()

    @Provides
    fun provideCredentialDao(db: ShellwaveDatabase) = db.credentialDao()

    @Provides
    fun provideTerminalProfileDao(db: ShellwaveDatabase) = db.terminalProfileDao()

    @Provides
    fun provideColorSchemeDao(db: ShellwaveDatabase) = db.colorSchemeDao()

    @Provides
    fun provideKeyBarLayoutDao(db: ShellwaveDatabase) = db.keyBarLayoutDao()

    @Provides
    fun provideScriptDao(db: ShellwaveDatabase) = db.scriptDao()

    @Provides
    fun provideScriptRunDao(db: ShellwaveDatabase) = db.scriptRunDao()

    @Provides
    fun providePortForwardDao(db: ShellwaveDatabase) = db.portForwardDao()
}
