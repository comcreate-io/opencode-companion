package dev.local.opencodecompanion.client.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "machines")
internal data class MachineRow(
    @PrimaryKey val machineId: String,
    val displayName: String,
    val origin: String,
    val credentialReference: String?,
    val credentialGeneration: Long,
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
}

@Database(
    entities =
        [
            MachineRow::class,
            DraftRow::class,
            OutgoingRow::class,
            JournalRow::class,
            CursorRow::class,
        ],
    version = 1,
    exportSchema = true,
)
internal abstract class DurableDatabase : RoomDatabase() {
    abstract fun dao(): DurableDao
}
