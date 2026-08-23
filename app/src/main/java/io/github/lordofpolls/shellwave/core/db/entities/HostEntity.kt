package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved SSH destination. The secret side of auth lives in CredentialEntity, referenced by id. */
@Entity(
    tableName = "hosts",
    foreignKeys = [
        ForeignKey(
            entity = CredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["credentialId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["proxyJumpHostId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("credentialId"), Index("proxyJumpHostId")],
)
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String?,
    val hostname: String,
    val port: Int,
    val username: String,
    val credentialId: Long,
    val lastConnectedAt: Long?,
    val createdAt: Long,
    /** Sends `tmux new -A -s shellwave` as the shell's first line. See [RESILIENT_SESSION_BOOTSTRAP]. */
    val resilientSession: Boolean = false,
    /**
     * Per-host overrides; `null` means the app-wide default.
     *
     * No `@ForeignKey`: losing the row one of these points at already falls back to the default.
     * [terminalProfileId] and [colorSchemeId] each own a row created for this host alone;
     * [keyBarLayoutId] points at one of the shared layouts.
     */
    val terminalProfileId: Long? = null,
    val colorSchemeId: Long? = null,
    val keyBarLayoutId: Long? = null,
    /**
     * N hops fall out of chaining, so there is no list or join table: if the host this points at has
     * its own [proxyJumpHostId], resolveProxyChain walks the whole pointer chain.
     *
     * This one does carry a `@ForeignKey` with `onDelete = RESTRICT`, because there is no default jump
     * host to fall back on: lose the row and the host is unreachable. A dangling id can still arrive
     * from outside Room, and `resolveProxyChain` reports that as a connect-time error.
     */
    val proxyJumpHostId: Long? = null,
)
