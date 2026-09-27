package dev.local.opencodecompanion.client.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dev.local.opencodecompanion.client.ReadScope
import dev.local.opencodecompanion.client.SendDestination
import dev.local.opencodecompanion.client.SendState
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2Delivery
import dev.local.opencodecompanion.protocol.V2PromptAdmission
import dev.local.opencodecompanion.protocol.V2SessionSummary
import dev.local.opencodecompanion.protocol.transcript.DurableTranscriptEvent
import dev.local.opencodecompanion.protocol.transcript.TranscriptApply
import dev.local.opencodecompanion.protocol.transcript.TranscriptDecode
import dev.local.opencodecompanion.protocol.transcript.TranscriptState
import dev.local.opencodecompanion.protocol.transcript.V2Transcript
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class MachineProfile(
    val id: MachineId,
    val displayName: String,
    val origin: String,
    val credentialReference: String?,
    val credentialGeneration: Long,
    val sharedPasswordAcknowledged: Boolean = false,
)

data class DraftKey(val session: SessionKey, val project: ProjectKey, val location: String) {
    init {
        require(session.machineId == project.machineId)
        require(location.isNotBlank())
    }
}

data class DraftSnapshot(
    val key: DraftKey,
    val revision: Long,
    val text: String,
    val cleared: Boolean,
)

data class StoredOutgoing(
    val id: String,
    val destination: SendDestination,
    val origin: String,
    val promptText: String,
    val state: SendState,
    val delivery: V2Delivery?,
    val submittedDraft: SubmittedDraft?,
)

data class SubmittedDraft(val key: DraftKey, val revision: Long)

data class StoredSessionSummary(
    val scope: ReadScope,
    val key: SessionKey,
    val summary: V2SessionSummary,
)

data class PendingCredentialRotation(
    val machineId: MachineId,
    val oldOrigin: String,
    val oldGeneration: Long,
    val oldReference: String,
    val newOrigin: String,
    val newGeneration: Long,
    val newReference: String,
)

data class JournalEvent(val sequence: Long, val eventId: String, val rawJson: String)

/** Room-backed state transitions. The host remains authoritative; this store never dispatches. */
class DurableStore
private constructor(
    private val database: DurableDatabase,
    private val io: CoroutineDispatcher,
    private val openedName: String,
) : AutoCloseable {
    private val dao = database.dao()
    private val closed = AtomicBoolean(false)

    companion object {
        private const val MAX_TEXT_CHARS = 1_048_576
        private const val MAX_JOURNAL_EVENTS = 4_096
        private const val MAX_JOURNAL_CHARS = 4L * 1_048_576
        private val openedNames = mutableSetOf<String>()

        suspend fun open(context: Context, name: String = "companion-state.db"): DurableStore {
            // withContext can discard an acquired store if cancellation wins its return dispatch.
            val acquired = AtomicReference<DurableStore?>()
            try {
                return withContext(Dispatchers.IO) {
                    require(name.isNotBlank() && '/' !in name && '\\' !in name)
                    synchronized(openedNames) {
                        check(openedNames.add(name)) { "Database is already open in this process" }
                    }
                    var database: DurableDatabase? = null
                    try {
                        database =
                            Room.databaseBuilder(
                                    context.applicationContext,
                                    DurableDatabase::class.java,
                                    name,
                                )
                                .addMigrations(MIGRATION_1_2)
                                .build()
                        DurableStore(database, Dispatchers.IO, name).also {
                            it.recoverAfterReopen()
                            acquired.set(it)
                        }
                    } catch (error: Throwable) {
                        try {
                            database?.close()
                        } catch (closeError: Throwable) {
                            error.addSuppressed(closeError)
                        } finally {
                            synchronized(openedNames) { openedNames.remove(name) }
                        }
                        throw error
                    }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        acquired.getAndSet(null)?.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            database.close()
        } finally {
            synchronized(openedNames) { openedNames.remove(openedName) }
        }
    }

    suspend fun putMachine(profile: MachineProfile) =
        withContext(io) {
            val normalized =
                profile.origin.toHttpUrlOrNull()?.takeIf {
                    it.scheme == "https" &&
                        it.encodedPath == "/" &&
                        it.query == null &&
                        it.fragment == null &&
                        it.username.isEmpty() &&
                        it.password.isEmpty()
                } ?: throw IllegalArgumentException("Machine origin must be an HTTPS root")
            require(profile.origin == normalized.toString()) { "Machine origin must be canonical" }
            require(profile.displayName.isNotBlank() && profile.displayName.length <= 200)
            require(profile.credentialGeneration >= 0)
            require(
                profile.credentialReference == null ||
                    (profile.credentialReference.isNotBlank() &&
                        profile.credentialReference.length <= 200)
            )
            database.withTransaction {
                val prior = dao.machine(profile.id.value)
                if (prior != null) {
                    require(
                        profile.origin == prior.origin &&
                            profile.credentialGeneration == prior.credentialGeneration &&
                            profile.credentialReference == prior.credentialReference
                    ) {
                        "Credential scope changes require a pending rotation"
                    }
                }
                dao.putMachine(profile.toRow())
            }
        }

    suspend fun machine(id: MachineId): MachineProfile? =
        withContext(io) { dao.machine(id.value)?.toProfile() }

    suspend fun machines(): List<MachineProfile> =
        withContext(io) { dao.machines().map { it.toProfile() } }

    suspend fun putSessionSummary(
        scope: ReadScope,
        key: SessionKey,
        summary: V2SessionSummary,
    ): Boolean =
        withContext(io) {
            require(scope.machineId == key.machineId && summary.id == key.sessionId)
            require(
                summary.title.length <= 1_024 &&
                    summary.directory.length <= 4_096 &&
                    summary.projectId.value.length <= 256 &&
                    key.sessionId.value.length <= 256 &&
                    (summary.workspaceId?.length ?: 0) <= 256 &&
                    (summary.subpath?.length ?: 0) <= 4_096 &&
                    scope.origin.length <= 2_048
            )
            database.withTransaction {
                val machine = dao.machine(scope.machineId.value) ?: return@withTransaction false
                if (
                    machine.origin != scope.origin ||
                        machine.credentialGeneration != scope.credentialGeneration
                )
                    return@withTransaction false
                dao.putSessionSummary(
                    SessionSummaryRow(
                        key.machineId.value,
                        scope.origin,
                        scope.credentialGeneration,
                        key.sessionId.value,
                        summary.projectId.value,
                        summary.directory,
                        summary.workspaceId,
                        summary.subpath,
                        summary.title,
                        summary.created,
                        summary.updated,
                    )
                )
                dao.trimSessionSummaries(
                    key.machineId.value,
                    scope.origin,
                    scope.credentialGeneration,
                )
                true
            }
        }

    suspend fun sessionSummaries(scope: ReadScope): List<StoredSessionSummary> =
        withContext(io) {
            val machine = dao.machine(scope.machineId.value) ?: return@withContext emptyList()
            if (
                machine.origin != scope.origin ||
                    machine.credentialGeneration != scope.credentialGeneration
            )
                return@withContext emptyList()
            dao.sessionSummaries(scope.machineId.value, scope.origin, scope.credentialGeneration)
                .map { row ->
                    val key = SessionKey(scope.machineId, SessionId(row.sessionId))
                    StoredSessionSummary(
                        scope,
                        key,
                        V2SessionSummary(
                            key.sessionId,
                            ProjectId(row.projectId),
                            row.directory,
                            row.workspaceId,
                            row.subpath,
                            row.title,
                            row.created,
                            row.updated,
                        ),
                    )
                }
        }

    suspend fun beginCredentialRotation(pending: PendingCredentialRotation): Boolean =
        withContext(io) {
            require(pending.newGeneration > pending.oldGeneration)
            require(pending.newOrigin == pending.oldOrigin) {
                "A new origin requires a new machine identity"
            }
            require(pending.oldReference.isNotBlank() && pending.newReference.isNotBlank())
            require(
                pending.newOrigin.toHttpUrlOrNull()?.let {
                    it.scheme == "https" &&
                        it.encodedPath == "/" &&
                        it.query == null &&
                        it.fragment == null &&
                        it.username.isEmpty() &&
                        it.password.isEmpty() &&
                        it.toString() == pending.newOrigin
                } == true
            ) {
                "New origin must be canonical HTTPS"
            }
            database.withTransaction {
                val current = dao.machine(pending.machineId.value) ?: return@withTransaction false
                if (dao.unresolvedCount(pending.machineId.value) != 0L) return@withTransaction false
                if (
                    current.origin != pending.oldOrigin ||
                        current.credentialGeneration != pending.oldGeneration ||
                        current.credentialReference != pending.oldReference
                )
                    return@withTransaction false
                dao.insertPendingRotation(pending.toRow()) != -1L
            }
        }

    suspend fun pendingCredentialRotations(): List<PendingCredentialRotation> =
        withContext(io) { dao.pendingRotations().map { it.toPending() } }

    /** Only call after the old Keystore envelope was authenticated at the exact old scope. */
    suspend fun cancelPendingCredentialRotation(pending: PendingCredentialRotation): Boolean =
        withContext(io) {
            database.withTransaction {
                if (dao.pendingRotation(pending.machineId.value)?.toPending() != pending)
                    return@withTransaction false
                val current = dao.machine(pending.machineId.value) ?: return@withTransaction false
                if (
                    current.origin != pending.oldOrigin ||
                        current.credentialGeneration != pending.oldGeneration ||
                        current.credentialReference != pending.oldReference
                )
                    return@withTransaction false
                dao.deletePendingRotation(pending.machineId.value)
                true
            }
        }

    /** Caller has verified the new Keystore scope before this DB-only finalization. */
    suspend fun finishCredentialRotation(pending: PendingCredentialRotation): Boolean =
        withContext(io) {
            require(pending.newOrigin == pending.oldOrigin) {
                "A new origin requires a new machine identity"
            }
            database.withTransaction {
                if (dao.pendingRotation(pending.machineId.value)?.toPending() != pending)
                    return@withTransaction false
                val current = dao.machine(pending.machineId.value) ?: return@withTransaction false
                if (
                    current.origin != pending.oldOrigin ||
                        current.credentialGeneration != pending.oldGeneration ||
                        current.credentialReference != pending.oldReference
                )
                    return@withTransaction false
                dao.putMachine(
                    current.copy(
                        origin = pending.newOrigin,
                        credentialGeneration = pending.newGeneration,
                        credentialReference = pending.newReference,
                    )
                )
                dao.clearSessionSummaries(pending.machineId.value)
                dao.deletePendingRotation(pending.machineId.value)
                true
            }
        }

    suspend fun draft(key: DraftKey): DraftSnapshot? =
        withContext(io) {
            dao.draft(
                    key.session.machineId.value,
                    key.project.projectId.value,
                    key.location,
                    key.session.sessionId.value,
                )
                ?.let { DraftSnapshot(key, it.revision, it.text, it.cleared) }
        }

    /** Expected revision zero creates; otherwise only the observed revision may advance. */
    suspend fun saveDraft(key: DraftKey, expectedRevision: Long, text: String): DraftSnapshot? =
        withContext(io) {
            require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE)
            require(text.length <= MAX_TEXT_CHARS)
            database.withTransaction {
                val prior =
                    dao.draft(
                        key.session.machineId.value,
                        key.project.projectId.value,
                        key.location,
                        key.session.sessionId.value,
                    )
                if (prior == null) {
                    if (expectedRevision != 0L) return@withTransaction null
                    dao.insertDraft(
                        DraftRow(
                            key.session.machineId.value,
                            key.project.projectId.value,
                            key.location,
                            key.session.sessionId.value,
                            1,
                            text,
                            false,
                        )
                    )
                    DraftSnapshot(key, 1, text, false)
                } else {
                    if (prior.revision != expectedRevision) return@withTransaction null
                    val next = expectedRevision + 1
                    check(
                        dao.updateDraft(
                            key.session.machineId.value,
                            key.project.projectId.value,
                            key.location,
                            key.session.sessionId.value,
                            expectedRevision,
                            next,
                            text,
                        ) == 1
                    )
                    DraftSnapshot(key, next, text, false)
                }
            }
        }

    suspend fun compareAndClearDraft(key: DraftKey, expectedRevision: Long): Boolean =
        withContext(io) {
            require(expectedRevision > 0 && expectedRevision < Long.MAX_VALUE)
            dao.clearDraft(
                key.session.machineId.value,
                key.project.projectId.value,
                key.location,
                key.session.sessionId.value,
                expectedRevision,
                expectedRevision + 1,
            ) == 1
        }

    /** A duplicate ID never overwrites an existing intent, even when its payload differs. */
    suspend fun prepareIntent(
        id: String,
        destination: SendDestination,
        promptText: String,
    ): Boolean = prepareIntentInternal(id, destination, promptText, null, null)

    /** The intent ID is the exact upstream message ID; legacy v1 prepares remain uncorrelated. */
    suspend fun prepareIntent(
        id: String,
        destination: SendDestination,
        promptText: String,
        delivery: V2Delivery,
        submittedDraft: DraftSnapshot?,
    ): Boolean = prepareIntentInternal(id, destination, promptText, delivery, submittedDraft)

    private suspend fun prepareIntentInternal(
        id: String,
        destination: SendDestination,
        promptText: String,
        delivery: V2Delivery?,
        submittedDraft: DraftSnapshot?,
    ): Boolean =
        withContext(io) {
            require(id.isNotBlank() && id.length <= 200)
            if (delivery != null) require(id.startsWith("msg_"))
            require(promptText.length <= MAX_TEXT_CHARS)
            require(
                destination.session.sessionId.value.length <= 256 &&
                    destination.project.projectId.value.length <= 256 &&
                    destination.location.length <= 4_096
            )
            database.withTransaction {
                val machine =
                    dao.machine(destination.session.machineId.value)
                        ?: error("Machine profile is missing")
                check(dao.pendingRotation(destination.session.machineId.value) == null) {
                    "Credential rotation is pending"
                }
                check(machine.credentialReference != null) { "Machine credential is missing" }
                check(machine.credentialGeneration == destination.credentialGeneration) {
                    "Credential generation changed"
                }
                if (submittedDraft != null) {
                    require(
                        submittedDraft.key ==
                            DraftKey(destination.session, destination.project, destination.location)
                    )
                    require(
                        !submittedDraft.cleared &&
                            submittedDraft.revision > 0 &&
                            submittedDraft.text == promptText
                    )
                    val row =
                        dao.draft(
                            destination.session.machineId.value,
                            destination.project.projectId.value,
                            destination.location,
                            destination.session.sessionId.value,
                        )
                    if (
                        row?.revision != submittedDraft.revision ||
                            row.cleared ||
                            row.text != promptText
                    )
                        return@withTransaction false
                }
                dao.insertOutgoing(
                    destination.toRow(id, machine.origin, promptText, delivery, submittedDraft)
                ) != -1L
            }
        }

    suspend fun outgoing(machineId: MachineId, id: String): StoredOutgoing? =
        withContext(io) { dao.outgoing(machineId.value, id)?.toStored() }

    suspend fun unresolvedOutgoing(machineId: MachineId): List<StoredOutgoing> =
        withContext(io) {
            check(dao.unresolvedCount(machineId.value) <= 500) {
                "Unresolved outgoing limit exceeded"
            }
            dao.unresolvedOutgoing(machineId.value).map { it.toStored() }
        }

    suspend fun beginDispatch(machineId: MachineId, id: String): Boolean =
        withContext(io) {
            database.withTransaction {
                val row = dao.outgoing(machineId.value, id) ?: return@withTransaction false
                if (row.state != "PREPARED") return@withTransaction false
                val machine = dao.machine(machineId.value) ?: return@withTransaction false
                if (machine.credentialReference == null) return@withTransaction false
                if (
                    machine.credentialGeneration != row.credentialGeneration ||
                        machine.origin != row.origin
                )
                    return@withTransaction false
                dao.transition(machineId.value, id, "PREPARED", "DISPATCHING") == 1
            }
        }

    suspend fun markOutcomeUnknown(machineId: MachineId, id: String): Boolean =
        transition(machineId, id, "DISPATCHING", "UNKNOWN")

    /**
     * Caller must first establish exact correlated host evidence; only outstanding states can
     * resolve.
     */
    suspend fun recordAdmissionAfterProof(machineId: MachineId, id: String): Boolean =
        resolve(machineId, id, "ADMITTED")

    /** Correlates host admission and clears only the draft revision captured when preparing. */
    suspend fun acknowledgeAfterProof(
        machineId: MachineId,
        id: String,
        admission: V2PromptAdmission,
    ): Boolean =
        withContext(io) {
            database.withTransaction {
                val row = dao.outgoing(machineId.value, id) ?: return@withTransaction false
                if (
                    row.delivery == null ||
                        admission.id != row.intentId ||
                        admission.sessionId.value != row.sessionId ||
                        admission.promptText != row.promptText ||
                        admission.delivery != row.delivery
                )
                    return@withTransaction false
                if (row.state == "ADMITTED" || row.state == "FINALIZED") return@withTransaction true
                if (row.state != "DISPATCHING" && row.state != "UNKNOWN")
                    return@withTransaction false
                if (dao.transition(machineId.value, id, row.state, "ADMITTED") != 1)
                    return@withTransaction false
                if (row.draftRevision != null) {
                    check(
                        row.draftProjectId != null &&
                            row.draftLocation != null &&
                            row.draftSessionId != null
                    )
                    dao.clearDraft(
                        machineId.value,
                        row.draftProjectId,
                        row.draftLocation,
                        row.draftSessionId,
                        row.draftRevision,
                        row.draftRevision + 1,
                    )
                }
                true
            }
        }

    suspend fun recordRejectionAfterProof(machineId: MachineId, id: String): Boolean =
        resolve(machineId, id, "REJECTED")

    suspend fun finalizeBookkeeping(machineId: MachineId, id: String): Boolean =
        withContext(io) {
            database.withTransaction {
                val row = dao.outgoing(machineId.value, id) ?: return@withTransaction false
                if (row.state == "FINALIZED") return@withTransaction true
                if (row.state != "ADMITTED" && row.state != "REJECTED") return@withTransaction false
                dao.transition(machineId.value, id, row.state, "FINALIZED") == 1
            }
        }

    /**
     * Invoked on every database open before any caller can mistake an interrupted dispatch for safe
     * retry.
     */
    private suspend fun recoverAfterReopen(): Int = withContext(io) { dao.recoverDispatches() }

    suspend fun cursor(key: SessionKey): Long =
        withContext(io) { dao.cursor(key.machineId.value, key.sessionId.value)?.sequence ?: 0L }

    suspend fun journal(key: SessionKey, after: Long = 0, limit: Int = 100): List<JournalEvent> =
        withContext(io) {
            require(after >= 0 && limit in 1..100)
            dao.journalPage(key.machineId.value, key.sessionId.value, after, limit).map {
                JournalEvent(it.sequence, it.eventId, it.rawJson)
            }
        }

    /**
     * Validates the supported event subset and commits the complete batch plus cursor atomically.
     */
    suspend fun commitEvents(key: SessionKey, expectedCursor: Long, rawEvents: List<String>): Long =
        withContext(io) {
            require(expectedCursor >= 0 && rawEvents.size <= 100)
            database.withTransaction {
                val current = dao.cursor(key.machineId.value, key.sessionId.value)?.sequence ?: 0L
                val stored = dao.journal(key.machineId.value, key.sessionId.value)
                check(
                    stored.size <= MAX_JOURNAL_EVENTS &&
                        stored.sumOf { it.rawJson.length.toLong() } <= MAX_JOURNAL_CHARS
                )
                var state = TranscriptState(key)
                for (row in stored) {
                    val event =
                        when (val decoded = V2Transcript.event(row.rawJson, key)) {
                            is TranscriptDecode.Supported -> decoded.event
                            is TranscriptDecode.Unsupported ->
                                error("Stored journal contains unsupported event")
                        }
                    state =
                        when (val applied = state.apply(event)) {
                            is TranscriptApply.Applied -> applied.state
                            else -> error("Stored journal is inconsistent")
                        }
                }
                check(state.lastSequence == current) { "Journal and cursor differ" }
                if (current != expectedCursor) {
                    check(
                        rawEvents.all { raw ->
                            val decoded =
                                V2Transcript.event(raw, key) as? TranscriptDecode.Supported
                                    ?: return@all false
                            val row =
                                dao.journalAt(
                                    key.machineId.value,
                                    key.sessionId.value,
                                    decoded.event.sequence,
                                )
                            row != null && sameStoredEvent(row, decoded.event, key)
                        }
                    ) {
                        "Durable cursor changed"
                    }
                    return@withTransaction current
                }
                var totalChars = stored.sumOf { it.rawJson.length.toLong() }
                var totalEvents = stored.size
                for (raw in rawEvents) {
                    require(raw.length <= MAX_TEXT_CHARS)
                    val event =
                        when (val decoded = V2Transcript.event(raw, key)) {
                            is TranscriptDecode.Supported -> decoded.event
                            is TranscriptDecode.Unsupported -> error("Unsupported durable event")
                        }
                    when (val applied = state.apply(event)) {
                        is TranscriptApply.Applied -> {
                            check(
                                totalEvents + 1 <= MAX_JOURNAL_EVENTS &&
                                    totalChars + raw.length <= MAX_JOURNAL_CHARS
                            ) {
                                "Journal budget exceeded"
                            }
                            dao.insertJournal(
                                JournalRow(
                                    key.machineId.value,
                                    key.sessionId.value,
                                    event.sequence,
                                    event.id,
                                    raw,
                                )
                            )
                            state = applied.state
                            totalEvents++
                            totalChars += raw.length
                        }
                        TranscriptApply.Duplicate -> {
                            val row =
                                dao.journalAt(
                                    key.machineId.value,
                                    key.sessionId.value,
                                    event.sequence,
                                )
                            check(row != null && sameStoredEvent(row, event, key)) {
                                "Conflicting replay"
                            }
                        }
                        else -> error("Durable event cannot advance cursor")
                    }
                }
                if (state.lastSequence != current)
                    dao.putCursor(
                        CursorRow(key.machineId.value, key.sessionId.value, state.lastSequence)
                    )
                state.lastSequence
            }
        }

    private fun sameStoredEvent(
        row: JournalRow,
        incoming: DurableTranscriptEvent,
        key: SessionKey,
    ): Boolean {
        if (row.eventId != incoming.id || row.sequence != incoming.sequence) return false
        val stored =
            (V2Transcript.event(row.rawJson, key) as? TranscriptDecode.Supported)?.event
                ?: return false
        return stored.sameContentAs(incoming)
    }

    private suspend fun transition(
        machineId: MachineId,
        id: String,
        from: String,
        to: String,
    ): Boolean = withContext(io) { dao.transition(machineId.value, id, from, to) == 1 }

    private suspend fun resolve(machineId: MachineId, id: String, next: String): Boolean =
        withContext(io) {
            database.withTransaction {
                val state = dao.outgoing(machineId.value, id)?.state ?: return@withTransaction false
                if (state != "DISPATCHING" && state != "UNKNOWN") return@withTransaction false
                dao.transition(machineId.value, id, state, next) == 1
            }
        }

    private fun MachineProfile.toRow() =
        MachineRow(
            id.value,
            displayName,
            origin,
            credentialReference,
            credentialGeneration,
            sharedPasswordAcknowledged,
        )

    private fun MachineRow.toProfile() =
        MachineProfile(
            MachineId(machineId),
            displayName,
            origin,
            credentialReference,
            credentialGeneration,
            sharedPasswordAcknowledged,
        )

    private fun PendingCredentialRotation.toRow() =
        PendingRotationRow(
            machineId.value,
            oldOrigin,
            oldGeneration,
            oldReference,
            newOrigin,
            newGeneration,
            newReference,
        )

    private fun PendingRotationRow.toPending() =
        PendingCredentialRotation(
            MachineId(machineId),
            oldOrigin,
            oldGeneration,
            oldReference,
            newOrigin,
            newGeneration,
            newReference,
        )

    private fun SendDestination.toRow(
        id: String,
        origin: String,
        promptText: String,
        delivery: V2Delivery?,
        submittedDraft: DraftSnapshot?,
    ) =
        OutgoingRow(
            session.machineId.value,
            id,
            project.projectId.value,
            location,
            session.sessionId.value,
            credentialGeneration,
            origin,
            promptText,
            "PREPARED",
            delivery?.wireValue,
            submittedDraft?.key?.project?.projectId?.value,
            submittedDraft?.key?.location,
            submittedDraft?.key?.session?.sessionId?.value,
            submittedDraft?.revision,
        )

    private fun OutgoingRow.toStored(): StoredOutgoing {
        val machine = MachineId(machineId)
        val destination =
            SendDestination(
                SessionKey(machine, SessionId(sessionId)),
                ProjectKey(machine, ProjectId(projectId)),
                location,
                credentialGeneration,
            )
        val state =
            when (state) {
                "PREPARED" -> SendState.Prepared
                "DISPATCHING" -> SendState.Dispatching
                "UNKNOWN" -> SendState.OutcomeUnknown
                "ADMITTED" -> SendState.Admitted
                "REJECTED" -> SendState.Rejected
                "FINALIZED" -> SendState.Finalized
                else -> error("Unknown stored send state")
            }
        val decodedDelivery =
            when (delivery) {
                null -> null
                "steer" -> V2Delivery.Steer
                "queue" -> V2Delivery.Queue
                else -> error("Unknown stored delivery")
            }
        val submitted =
            if (draftRevision == null) {
                check(draftProjectId == null && draftLocation == null && draftSessionId == null)
                null
            } else {
                check(draftProjectId != null && draftLocation != null && draftSessionId != null)
                SubmittedDraft(
                    DraftKey(
                        SessionKey(machine, SessionId(draftSessionId)),
                        ProjectKey(machine, ProjectId(draftProjectId)),
                        draftLocation,
                    ),
                    draftRevision,
                )
            }
        return StoredOutgoing(
            intentId,
            destination,
            origin,
            promptText,
            state,
            decodedDelivery,
            submitted,
        )
    }
}
