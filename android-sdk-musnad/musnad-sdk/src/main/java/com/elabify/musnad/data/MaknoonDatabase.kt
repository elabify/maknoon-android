// Room schema for the SDK's at-rest state, mirroring the iOS HolderStore
// sub-stores: verified credentials, registered hardware devices, and known
// issuers. The database file itself is encrypted with SQLCipher (key sealed
// by StrongBox); see MaknoonStore.

package com.elabify.musnad.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val cid: String,
    val issuerDid: String,
    val subjectDid: String,
    val schema: String,
    val credentialJson: String,
    val nickname: String?,
    val createdAt: Long,
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val serial: String,
    val kind: String,
    val label: String,
    val attestationPubkeyHex: String?,
)

@Entity(tableName = "issuers")
data class IssuerEntity(
    @PrimaryKey val host: String,
    val trusted: Boolean,
    val label: String?,
)

// Per-presentation history. Every successful Share records a row keyed by the
// recipient verifier's DID (or "did:elabify:open" when the holder shared
// without a scanned verifier request, the "anyone with the QR" case). Mirrors
// iOS VerifierHistoryEntry. The disclosed claim keys are stored as a single
// JSON array string (disclosedKeysJson) so Room needs no list type converter.
@Entity(tableName = "verifier_history")
data class VerifierHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val verifierDid: String,
    val verifierName: String?,
    // Display nickname for "did:elabify:open" (the open-share path). Generated
    // by the recorder, not user-editable for now.
    val label: String,
    val credentialId: String,
    val credentialSchema: String,
    // JSON array of the disclosed claim keys, e.g. ["name","dob"].
    val disclosedKeysJson: String,
    val lastUsedAt: Long,
)

@Dao
interface CredentialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(credential: CredentialEntity)

    @Query("SELECT * FROM credentials ORDER BY createdAt DESC")
    suspend fun all(): List<CredentialEntity>

    /** Newest credential per (issuer, subject, schema) -- supersede semantics. */
    @Query(
        "SELECT * FROM credentials WHERE issuerDid = :iss AND subjectDid = :sub " +
            "AND schema = :schema ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun latest(iss: String, sub: String, schema: String): CredentialEntity?

    @Query("DELETE FROM credentials WHERE cid = :cid")
    suspend fun delete(cid: String)
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices")
    suspend fun all(): List<DeviceEntity>

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface IssuerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(issuer: IssuerEntity)

    @Query("SELECT * FROM issuers")
    suspend fun all(): List<IssuerEntity>
}

@Dao
interface VerifierHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VerifierHistoryEntity): Long

    /** All history rows, newest first. */
    @Query("SELECT * FROM verifier_history ORDER BY lastUsedAt DESC")
    suspend fun all(): List<VerifierHistoryEntity>

    /** Trim to the most recent [keep] rows, dropping older ones. */
    @Query(
        "DELETE FROM verifier_history WHERE id NOT IN " +
            "(SELECT id FROM verifier_history ORDER BY lastUsedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM verifier_history")
    suspend fun clear()
}

@Database(
    entities = [
        CredentialEntity::class,
        DeviceEntity::class,
        IssuerEntity::class,
        VerifierHistoryEntity::class,
    ],
    // Bumped from 1 to 2 for the verifier_history table. No explicit Migration
    // is supplied: MaknoonStore opens with fallbackToDestructiveMigration, which
    // is acceptable for this dev DB (a schema change wipes at-rest state rather
    // than crashing). Revisit before any GA persistence guarantee.
    version = 2,
    exportSchema = false,
)
abstract class MaknoonDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao
    abstract fun devices(): DeviceDao
    abstract fun issuers(): IssuerDao
    abstract fun verifierHistory(): VerifierHistoryDao
}
