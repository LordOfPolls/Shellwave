package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved script: name/icon/colour, optional target host, the snippet itself, run mode, whether to
 * disconnect after, `{{param}}` definitions and whether to confirm before running.
 *
 * [mode] stores ScriptMode's `.name`, same plain-string-not-TypeConverter convention as
 * `CredentialEntity.type`. [icon] holds an emoji glyph that nothing writes or renders any more; the
 * column is kept only so a value saved by an older build is not destroyed. [color] is an ARGB `Int`
 * suitable for [androidx.compose.ui.graphics.Color].
 *
 * [paramsJson] holds ScriptParam definitions, never a run's values - a `SECRET` value must not
 * reach disk, and this column is the static template.
 */
@Entity(
    tableName = "scripts",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetHostId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("targetHostId")],
)
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // Dead since the icon picker went: nothing writes it and no surface renders it. Kept anyway -
    // retiring a column nothing reads costs a migration and a migration test, and an older build's
    // saved value survives at no cost.
    val icon: String?,
    val color: Int?,
    val targetHostId: Long?,
    val snippet: String,
    val mode: String,
    val disconnectAfter: Boolean,
    val paramsJson: String,
    val confirmBeforeRun: Boolean,
    val createdAt: Long,
    /**
     * Per-script opt-in for the exported `RUN_SCRIPT` action, default false. The app-wide toggle and
     * its token decide whether an outside app may ask at all; this column decides what it may ask for.
     * Without it one pasted token would reach every script the user has ever saved, including the one
     * that runs `terraform destroy`, and flipping the app-wide toggle on would arm them retroactively.
     *
     * Enforced in backgroundTriggerRefusal alongside the refusals the widget obeys, not instead of
     * them.
     */
    val allowAutomation: Boolean = false,
)
