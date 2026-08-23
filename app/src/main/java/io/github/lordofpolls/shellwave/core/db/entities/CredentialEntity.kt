package io.github.lordofpolls.shellwave.core.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Keyboard-interactive stores nothing; the server prompts live at connect time.
 *
 * [keystoreAlias] records which Keystore alias sealed the secret, because a credential sealed under
 * a biometric alias needs a successful prompt to unseal and one under `vault_default` does not.
 */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val label: String?,
    val keystoreAlias: String?,
    val secretIv: ByteArray?,
    val secretCiphertext: ByteArray?,
    val passphraseIv: ByteArray?,
    val passphraseCiphertext: ByteArray?,
    val publicKeyText: String?,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return id == other.id &&
                type == other.type &&
                label == other.label &&
                keystoreAlias == other.keystoreAlias &&
                secretIv.contentEquals(other.secretIv) &&
                secretCiphertext.contentEquals(other.secretCiphertext) &&
                passphraseIv.contentEquals(other.passphraseIv) &&
                passphraseCiphertext.contentEquals(other.passphraseCiphertext) &&
                publicKeyText == other.publicKeyText &&
                createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + (keystoreAlias?.hashCode() ?: 0)
        result = 31 * result + (secretIv?.contentHashCode() ?: 0)
        result = 31 * result + (secretCiphertext?.contentHashCode() ?: 0)
        result = 31 * result + (passphraseIv?.contentHashCode() ?: 0)
        result = 31 * result + (passphraseCiphertext?.contentHashCode() ?: 0)
        result = 31 * result + (publicKeyText?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
