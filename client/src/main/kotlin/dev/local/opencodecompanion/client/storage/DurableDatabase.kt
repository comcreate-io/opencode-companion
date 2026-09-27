package dev.local.opencodecompanion.client.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "machines")
internal data class MachineRow(
    @PrimaryKey val machineId: String,
    val displayName: String,
    val origin: String,
    val credentialReference: String?,
    val credentialGeneration: Long,
    @ColumnInfo(defaultValue = "0") val sharedPasswordAcknowledged: Boolean,
)

@Entity(tableName = "drafts", primaryKeys = ["machineId", "projectId", "location", "sessionId"])
internal data class DraftRow(
    val machineId: String,
    val projectId: String,
    val location: String,
    val sessionId: String,
    val revision: Long,
    val text: String,
    val cleared: Boolean,
)

@Entity(tableName = "outgoing", primaryKeys = ["machineId", "intentId"])
internal data class OutgoingRow(
    val machineId: String,
    val intentId: String,
    val projectId: String,
    val location: String,
    val sessionId: String,
    val credentialGeneration: Long,
    val origin: String,
    val promptText: String,
    val state: String,
    val delivery: String?,
    val draftProjectId: String?,
    val draftLocation: String?,
    val draftSessionId: String?,
    val draftRevision: Long?,
)

@Entity(
    tableName = "session_summaries",
    primaryKeys = ["machineId", "origin", "credentialGeneration", "sessionId"],
)
internal data class SessionSummaryRow(
    val machineId: String,
    val origin: String,
    val credentialGeneration: Long,
    val sessionId: String,
    val projectId: String,
    val directory: String,
    val workspaceId: String?,
    val subpath: String?,
    val title: String,
    val created: Long,
    val updated: Long,
)

@Entity(tableName = "pending_rotations")
internal data class PendingRotationRow(
    @PrimaryKey val machineId: String,
    val oldOrigin: String,
    val oldGeneration: Long,
    val oldReference: String,
    val newOrigin: String,
    val newGeneration: Long,
    val newReference: String,
)

@Entity(
    tableName = "event_journal",
    primaryKeys = ["machineId", "sessionId", "sequence"],
    indices = [Index(value = ["machineId", "sessionId", "eventId"], unique = true)],
)
internal data class JournalRow(
    val machineId: String,
    val sessionId: String,
    val sequence: Long,
    val eventId: String,
    val rawJson: String,
)

@Entity(tableName = "durable_cursor", primaryKeys = ["machineId", "sessionId"])
internal data class CursorRow(val machineId: String, val sessionId: String, val sequence: Long)

@Dao
internal interface DurableDao {
    @Query("SELECT * FROM machines WHERE machineId = :machineId")
    suspend fun machine(machineId: String): MachineRow?

    @Query("SELECT * FROM machines ORDER BY displayName, machineId")
    suspend fun machines(): List<MachineRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMachine(row: MachineRow)

    @Query(
        "SELECT * FROM drafts WHERE machineId = :machineId AND projectId = :projectId AND location = :location AND sessionId = :sessionId"
    )
    suspend fun draft(
        machineId: String,
        projectId: String,
        location: String,
        sessionId: String,
    ): DraftRow?

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertDraft(row: DraftRow)

    @Query(
        "UPDATE drafts SET revision = :nextRevision, text = :text, cleared = 0 WHERE machineId = :machineId AND projectId = :projectId AND location = :location AND sessionId = :sessionId AND revision = :expectedRevision"
    )
    suspend fun updateDraft(
        machineId: String,
        projectId: String,
        location: String,
        sessionId: String,
        expectedRevision: Long,
        nextRevision: Long,
        text: String,
    ): Int

    @Query(
        "UPDATE drafts SET revision = :nextRevision, text = '', cleared = 1 WHERE machineId = :machineId AND projectId = :projectId AND location = :location AND sessionId = :sessionId AND revision = :expectedRevision AND cleared = 0"
    )
    suspend fun clearDraft(
        machineId: String,
        projectId: String,
        location: String,
        sessionId: String,
        expectedRevision: Long,
        nextRevision: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutgoing(row: OutgoingRow): Long

    @Query("SELECT * FROM outgoing WHERE machineId = :machineId AND intentId = :intentId")
    suspend fun outgoing(machineId: String, intentId: String): OutgoingRow?

    @Query(
        "SELECT * FROM outgoing WHERE machineId = :machineId AND state IN ('PREPARED', 'DISPATCHING', 'UNKNOWN') ORDER BY intentId LIMIT 500"
    )
    suspend fun unresolvedOutgoing(machineId: String): List<OutgoingRow>

    @Query(
        "SELECT COUNT(*) FROM outgoing WHERE machineId = :machineId AND state IN ('PREPARED', 'DISPATCHING', 'UNKNOWN')"
    )
    suspend fun unresolvedCount(machineId: String): Long

    @Query(
        "UPDATE outgoing SET state = :next WHERE machineId = :machineId AND intentId = :intentId AND state = :expected"
    )
    suspend fun transition(machineId: String, intentId: String, expected: String, next: String): Int

    @Query("UPDATE outgoing SET state = 'UNKNOWN' WHERE state = 'DISPATCHING'")
    suspend fun recoverDispatches(): Int

    @Query("SELECT * FROM durable_cursor WHERE machineId = :machineId AND sessionId = :sessionId")
    suspend fun cursor(machineId: String, sessionId: String): CursorRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCursor(row: CursorRow)

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertJournal(row: JournalRow)

    @Query(
        "SELECT * FROM event_journal WHERE machineId = :machineId AND sessionId = :sessionId ORDER BY sequence"
    )
    suspend fun journal(machineId: String, sessionId: String): List<JournalRow>

    @Query(
        "SELECT * FROM event_journal WHERE machineId = :machineId AND sessionId = :sessionId AND sequence = :sequence"
    )
    suspend fun journalAt(machineId: String, sessionId: String, sequence: Long): JournalRow?

    @Query(
        "SELECT * FROM event_journal WHERE machineId = :machineId AND sessionId = :sessionId AND sequence > :after ORDER BY sequence LIMIT :limit"
    )
    suspend fun journalPage(
        machineId: String,
        sessionId: String,
        after: Long,
        limit: Int,
    ): List<JournalRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSessionSummary(row: SessionSummaryRow)

    @Query(
        "SELECT * FROM session_summaries WHERE machineId = :machineId AND origin = :origin AND credentialGeneration = :generation ORDER BY updated DESC, sessionId LIMIT 500"
    )
    suspend fun sessionSummaries(
        machineId: String,
        origin: String,
        generation: Long,
    ): List<SessionSummaryRow>

    @Query(
        "DELETE FROM session_summaries WHERE machineId = :machineId AND origin = :origin AND credentialGeneration = :generation AND sessionId NOT IN (SELECT sessionId FROM session_summaries WHERE machineId = :machineId AND origin = :origin AND credentialGeneration = :generation ORDER BY updated DESC, sessionId LIMIT 500)"
    )
    suspend fun trimSessionSummaries(machineId: String, origin: String, generation: Long)

    @Query("DELETE FROM session_summaries WHERE machineId = :machineId")
    suspend fun clearSessionSummaries(machineId: String)

    @Query("SELECT * FROM pending_rotations WHERE machineId = :machineId")
    suspend fun pendingRotation(machineId: String): PendingRotationRow?

    @Query("SELECT * FROM pending_rotations ORDER BY machineId")
    suspend fun pendingRotations(): List<PendingRotationRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingRotation(row: PendingRotationRow): Long

    @Query("DELETE FROM pending_rotations WHERE machineId = :machineId")
    suspend fun deletePendingRotation(machineId: String)
}

@Database(
    entities =
        [
            MachineRow::class,
            DraftRow::class,
            OutgoingRow::class,
            JournalRow::class,
            CursorRow::class,
            SessionSummaryRow::class,
            PendingRotationRow::class,
        ],
    version = 2,
    exportSchema = true,
)
internal abstract class DurableDatabase : RoomDatabase() {
    abstract fun dao(): DurableDao
}

/** Preserves every v1 user row. New evidence fields stay null for legacy outgoing intents. */
internal val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE machines ADD COLUMN sharedPasswordAcknowledged INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE outgoing ADD COLUMN delivery TEXT")
            db.execSQL("ALTER TABLE outgoing ADD COLUMN draftProjectId TEXT")
            db.execSQL("ALTER TABLE outgoing ADD COLUMN draftLocation TEXT")
            db.execSQL("ALTER TABLE outgoing ADD COLUMN draftSessionId TEXT")
            db.execSQL("ALTER TABLE outgoing ADD COLUMN draftRevision INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS session_summaries (machineId TEXT NOT NULL, origin TEXT NOT NULL, credentialGeneration INTEGER NOT NULL, sessionId TEXT NOT NULL, projectId TEXT NOT NULL, directory TEXT NOT NULL, workspaceId TEXT, subpath TEXT, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(machineId, origin, credentialGeneration, sessionId))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS pending_rotations (machineId TEXT NOT NULL PRIMARY KEY, oldOrigin TEXT NOT NULL, oldGeneration INTEGER NOT NULL, oldReference TEXT NOT NULL, newOrigin TEXT NOT NULL, newGeneration INTEGER NOT NULL, newReference TEXT NOT NULL)"
            )
        }
    }
