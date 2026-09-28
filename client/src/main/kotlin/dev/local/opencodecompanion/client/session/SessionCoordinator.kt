package dev.local.opencodecompanion.client.session

import dev.local.opencodecompanion.client.MutationResult
import dev.local.opencodecompanion.client.ReadDestination
import dev.local.opencodecompanion.client.ReadFailure
import dev.local.opencodecompanion.client.ReadResult
import dev.local.opencodecompanion.client.ReadScope
import dev.local.opencodecompanion.client.ScopedSession
import dev.local.opencodecompanion.client.SendDestination
import dev.local.opencodecompanion.client.StreamResult
import dev.local.opencodecompanion.client.security.RotationOutcome
import dev.local.opencodecompanion.client.security.RotationReconciliation
import dev.local.opencodecompanion.client.storage.DraftKey
import dev.local.opencodecompanion.client.storage.DraftSnapshot
import dev.local.opencodecompanion.client.storage.MachineProfile
import dev.local.opencodecompanion.client.storage.StoredOutgoing
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2AgentSummary
import dev.local.opencodecompanion.protocol.V2CreateSessionCommand
import dev.local.opencodecompanion.protocol.V2Delivery
import dev.local.opencodecompanion.protocol.V2GlobalEvent
import dev.local.opencodecompanion.protocol.V2ModelSummary
import dev.local.opencodecompanion.protocol.V2PermissionReply
import dev.local.opencodecompanion.protocol.V2PermissionRequest
import dev.local.opencodecompanion.protocol.V2PromptAdmission
import dev.local.opencodecompanion.protocol.V2PromptCommand
import dev.local.opencodecompanion.protocol.V2QuestionRequest
import dev.local.opencodecompanion.protocol.V2SessionSummary
import dev.local.opencodecompanion.protocol.V2TextKey
import dev.local.opencodecompanion.protocol.V2TextProjection
import dev.local.opencodecompanion.protocol.VcsFileDiff
import dev.local.opencodecompanion.protocol.transcript.Kind
import dev.local.opencodecompanion.protocol.transcript.TranscriptApply
import dev.local.opencodecompanion.protocol.transcript.TranscriptDecode
import dev.local.opencodecompanion.protocol.transcript.TranscriptState
import dev.local.opencodecompanion.protocol.transcript.V2Transcript
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Shared-password access is a candidate mode only after per-machine user acknowledgement. */
enum class RemoteAccessPolicy {
    BlockedPendingD04,
    SharedPasswordCandidate,
}

sealed interface ConnectionState {
    data object Cached : ConnectionState

    data object Connecting : ConnectionState

    data object Ready : ConnectionState

    data class Unavailable(val reason: SessionProblem) : ConnectionState
}

sealed interface SessionProblem {
    data object SetupRequired : SessionProblem

    data object CredentialUnavailable : SessionProblem

    data object RemoteUseNotApproved : SessionProblem

    data object StorageFailure : SessionProblem

    data object ProtocolUnsupported : SessionProblem

    data object OutcomeUnknown : SessionProblem

    data object MutationInProgress : SessionProblem

    data object RequestStale : SessionProblem

    data object RotationBlocked : SessionProblem

    data object RotationRecoveryRequired : SessionProblem

    data object DestinationChanged : SessionProblem

    data class Transport(val reason: ReadFailure) : SessionProblem
}

sealed interface SessionActionResult {
    data object Completed : SessionActionResult

    data class Stopped(val problem: SessionProblem) : SessionActionResult
}

data class SessionUiState(
    val machines: List<MachineProfile> = emptyList(),
    val selectedMachine: MachineId? = null,
    val selectedSession: SessionKey? = null,
    val connection: ConnectionState = ConnectionState.Cached,
    val sessions: List<ScopedSession> = emptyList(),
    val agents: List<V2AgentSummary> = emptyList(),
    val models: List<V2ModelSummary> = emptyList(),
    val transcript: TranscriptState? = null,
    val transientText: V2TextProjection = V2TextProjection(),
    val draft: DraftSnapshot? = null,
    val active: Set<SessionKey> = emptySet(),
    val interrupting: Set<SessionKey> = emptySet(),
    val settlingRequestIds: Set<String> = emptySet(),
    val permissions: List<V2PermissionRequest> = emptyList(),
    val questions: List<V2QuestionRequest> = emptyList(),
    val outgoing: List<StoredOutgoing> = emptyList(),
    val changes: List<VcsFileDiff>? = null,
    val changesLoading: Boolean = false,
    val changesProblem: SessionProblem? = null,
    val problem: SessionProblem? = null,
    val busy: Boolean = false,
    val foreground: Boolean = false,
    val remoteAccessPolicy: RemoteAccessPolicy = RemoteAccessPolicy.SharedPasswordCandidate,
)

/** Persistence boundary exists for SQLite fakes; commits remain atomic in DurableStore. */
interface SessionStorePort {
    suspend fun machines(): List<MachineProfile>

    suspend fun putMachine(profile: MachineProfile)

    suspend fun sessionSummaries(scope: ReadScope): List<ScopedSession>

    suspend fun putSessionSummary(scope: ReadScope, session: ScopedSession)

    suspend fun draft(key: DraftKey): DraftSnapshot?

    suspend fun saveDraft(key: DraftKey, expectedRevision: Long, text: String): DraftSnapshot?

    suspend fun unresolvedOutgoing(machine: MachineId): List<StoredOutgoing>

    suspend fun prepareIntent(
        id: String,
        destination: SendDestination,
        text: String,
        delivery: V2Delivery,
        draft: DraftSnapshot?,
    ): Boolean

    suspend fun beginDispatch(machine: MachineId, id: String): Boolean

    suspend fun markOutcomeUnknown(machine: MachineId, id: String): Boolean

    suspend fun recordRejectionAfterProof(machine: MachineId, id: String): Boolean

    suspend fun acknowledgeAfterProof(
        machine: MachineId,
        id: String,
        admission: V2PromptAdmission,
    ): Boolean

    suspend fun cursor(key: SessionKey): Long

    suspend fun journal(key: SessionKey, after: Long, limit: Int): List<String>

    suspend fun commitEvents(key: SessionKey, expectedCursor: Long, rawEvents: List<String>): Long
}

/** Host calls are single-attempt; streams are cold and owned by this coordinator's child scope. */
interface SessionHostPort {
    suspend fun destination(profile: MachineProfile): ReadDestination?

    suspend fun compatibility(destination: ReadDestination): ReadResult<String>

    suspend fun sessions(destination: ReadDestination): ReadResult<List<ScopedSession>>

    suspend fun session(destination: ReadDestination, key: SessionKey): ReadResult<ScopedSession>

    suspend fun active(destination: ReadDestination): ReadResult<Set<SessionKey>>

    suspend fun agents(destination: ReadDestination): ReadResult<List<V2AgentSummary>>

    suspend fun models(destination: ReadDestination): ReadResult<List<V2ModelSummary>>

    suspend fun changes(
        destination: ReadDestination,
        key: SessionKey,
        directory: String,
    ): ReadResult<List<VcsFileDiff>>

    suspend fun history(
        destination: ReadDestination,
        key: SessionKey,
        after: Long,
    ): ReadResult<dev.local.opencodecompanion.protocol.transcript.TranscriptPage>

    suspend fun permissions(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<List<V2PermissionRequest>>

    suspend fun questions(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<List<V2QuestionRequest>>

    suspend fun create(
        destination: ReadDestination,
        command: V2CreateSessionCommand,
    ): MutationResult<ScopedSession>

    suspend fun prompt(
        destination: ReadDestination,
        command: V2PromptCommand,
    ): MutationResult<V2PromptAdmission>

    suspend fun interrupt(destination: ReadDestination, key: SessionKey): MutationResult<Unit>

    suspend fun permissionReply(
        destination: ReadDestination,
        request: V2PermissionRequest,
        reply: V2PermissionReply,
    ): MutationResult<Unit>

    suspend fun questionReply(
        destination: ReadDestination,
        request: V2QuestionRequest,
        answers: List<List<String>>,
    ): MutationResult<Unit>

    suspend fun questionReject(
        destination: ReadDestination,
        request: V2QuestionRequest,
    ): MutationResult<Unit>

    fun durable(
        destination: ReadDestination,
        key: SessionKey,
        after: Long,
    ): Flow<StreamResult<dev.local.opencodecompanion.client.DurableFrame>>

    fun global(destination: ReadDestination): Flow<StreamResult<V2GlobalEvent>>
}

/** Secure setup and credential generation are delegated to a platform-backed boundary. */
interface SessionCredentialPort {
    /** Returns the actual platform vault reference, never an inferred or fabricated value. */
    suspend fun store(profile: MachineProfile, authorization: String): String?
}

/** Same-origin vault/Room replacement is implemented by the platform rotation service. */
interface SessionRotationPort {
    suspend fun rotate(
        machineId: MachineId,
        expectedOrigin: String,
        expectedGeneration: Long,
        newAuthorization: String,
    ): RotationOutcome

    suspend fun reconcilePending(): List<RotationReconciliation>
}

class SessionCoordinator(
    private val store: SessionStorePort,
    private val host: SessionHostPort,
    private val credentials: SessionCredentialPort,
    private val rotation: SessionRotationPort,
    parentScope: CoroutineScope,
    private val remoteAccessPolicy: RemoteAccessPolicy = RemoteAccessPolicy.SharedPasswordCandidate,
    private val nextMachineId: () -> MachineId = { MachineId(UUID.randomUUID().toString()) },
    private val nextMessageId: () -> String = {
        "msg_" + UUID.randomUUID().toString().replace("-", "")
    },
) : AutoCloseable {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
        )
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(SessionUiState(remoteAccessPolicy = remoteAccessPolicy))
    val state: StateFlow<SessionUiState> = mutable.asStateFlow()
    private val machineLocks = ConcurrentHashMap<MachineId, Mutex>()
    private val interruptingByMachine = mutableSetOf<SessionKey>()
    private val settlingRequests = mutableSetOf<Pair<SessionKey, String>>()
    private val draftLocks = ConcurrentHashMap<DraftKey, Mutex>()
    private val initializationLock = Mutex()
    private val journalLocks = ConcurrentHashMap<SessionKey, Mutex>()
    private val rotationRecoveryRequired = mutableSetOf<MachineId>()
    private val rotatingMachines = mutableSetOf<MachineId>()
    private var startupReconciled = false
    private var epoch = 0L
    private var recovery: Job? = null
    private var live: Job? = null

    suspend fun initialize(): SessionActionResult =
        initializationLock.withLock {
            if (startupReconciled) return@withLock SessionActionResult.Completed
            try {
                startupReconciled = false
                val reconciled = rotation.reconcilePending()
                rotationRecoveryRequired.clear()
                rotationRecoveryRequired +=
                    reconciled.mapNotNull { item ->
                        if (
                            item.outcome is RotationOutcome.FailClosed ||
                                item.outcome is RotationOutcome.Blocked
                        )
                            item.machineId
                        else null
                    }
                val machines = store.machines()
                val selected =
                    mutable.value.selectedMachine?.takeIf { id -> machines.any { it.id == id } }
                        ?: machines.firstOrNull()?.id
                mutable.value = mutable.value.copy(machines = machines, selectedMachine = selected)
                startupReconciled = true
                if (selected != null) selectMachine(selected) else SessionActionResult.Completed
            } catch (cancelled: CancellationException) {
                startupReconciled = false
                throw cancelled
            } catch (_: Exception) {
                startupReconciled = false
                mutable.value =
                    mutable.value.copy(
                        connection =
                            ConnectionState.Unavailable(SessionProblem.RotationRecoveryRequired),
                        problem = SessionProblem.RotationRecoveryRequired,
                    )
                SessionActionResult.Stopped(SessionProblem.RotationRecoveryRequired)
            }
        }

    suspend fun addMachine(
        displayName: String,
        canonicalHttpsOrigin: String,
        authorization: String,
        acceptSharedPassword: Boolean,
    ): SessionActionResult {
        if (
            remoteAccessPolicy != RemoteAccessPolicy.SharedPasswordCandidate ||
                !acceptSharedPassword
        )
            return stopped(SessionProblem.RemoteUseNotApproved)
        val id = nextMachineId()
        val origin =
            try {
                ReadDestination(id, canonicalHttpsOrigin, 0, authorization).origin.toString()
            } catch (_: IllegalArgumentException) {
                return stopped(SessionProblem.SetupRequired)
            }
        val profile =
            MachineProfile(id, displayName, origin, null, 0, sharedPasswordAcknowledged = true)
        return runStorage {
            val reference =
                credentials.store(profile, authorization)
                    ?: return@runStorage stopped(SessionProblem.CredentialUnavailable)
            val stored = profile.copy(credentialReference = reference)
            store.putMachine(stored)
            val machines = store.machines()
            mutable.value = mutable.value.copy(machines = machines)
            selectMachine(id)
        }
    }

    /** Replaces only this machine's same-origin password; host authentication is verified later. */
    suspend fun replaceCredential(
        machineId: MachineId,
        authorization: String,
    ): SessionActionResult {
        if (!startupReconciled)
            return stoppedForMachine(machineId, SessionProblem.RotationRecoveryRequired)
        if (machineId in rotationRecoveryRequired)
            return stoppedForMachine(machineId, SessionProblem.RotationRecoveryRequired)
        val profile =
            mutable.value.machines.firstOrNull { it.id == machineId }
                ?: return stoppedForMachine(machineId, SessionProblem.SetupRequired)
        if (!profile.sharedPasswordAcknowledged)
            return stoppedForMachine(machineId, SessionProblem.RemoteUseNotApproved)
        val stamp = epoch
        var entered = false
        val result =
            withMachineMutation(machineId, stamp) {
                try {
                    val current = mutable.value.machines.firstOrNull { it.id == machineId }
                    if (current?.scope() != profile.scope())
                        return@withMachineMutation stoppedIfCurrent(
                            stamp,
                            SessionProblem.DestinationChanged,
                        )
                    rotatingMachines += machineId
                    entered = true
                    var rotationEpoch: Long? = null
                    if (mutable.value.selectedMachine == machineId) {
                        epoch++
                        rotationEpoch = epoch
                        val oldRecovery = recovery
                        val oldLive = live
                        oldRecovery?.cancelAndJoin()
                        oldLive?.cancelAndJoin()
                        if (epoch == rotationEpoch && mutable.value.selectedMachine == machineId)
                            mutable.value =
                                mutable.value.copy(
                                    connection = ConnectionState.Cached,
                                    active = emptySet(),
                                    permissions = emptyList(),
                                    questions = emptyList(),
                                    transientText = V2TextProjection(),
                                )
                    }
                    when (
                        val outcome =
                            rotation.rotate(
                                machineId,
                                profile.origin,
                                profile.credentialGeneration,
                                authorization,
                            )
                    ) {
                        is RotationOutcome.Completed -> {
                            if (
                                profile.credentialGeneration == Long.MAX_VALUE ||
                                    outcome.profile.id != machineId ||
                                    outcome.profile.origin != profile.origin ||
                                    outcome.profile.credentialGeneration !=
                                        profile.credentialGeneration + 1 ||
                                    outcome.profile.credentialReference == null ||
                                    !outcome.profile.sharedPasswordAcknowledged
                            ) {
                                markRotationFailClosed(machineId)
                                return@withMachineMutation SessionActionResult.Stopped(
                                    SessionProblem.RotationRecoveryRequired
                                )
                            }
                            replaceProfileInState(outcome.profile)
                            if (
                                epoch == rotationEpoch && mutable.value.selectedMachine == machineId
                            )
                                mutable.value = mutable.value.copy(problem = null)
                            SessionActionResult.Completed
                        }
                        is RotationOutcome.RolledBack -> {
                            if (
                                outcome.profile.id != machineId ||
                                    outcome.profile.origin != profile.origin ||
                                    outcome.profile.credentialGeneration !=
                                        profile.credentialGeneration ||
                                    outcome.profile.credentialReference !=
                                        profile.credentialReference
                            ) {
                                markRotationFailClosed(machineId)
                                return@withMachineMutation SessionActionResult.Stopped(
                                    SessionProblem.RotationRecoveryRequired
                                )
                            }
                            replaceProfileInState(outcome.profile)
                            stoppedForMachine(machineId, SessionProblem.RotationBlocked)
                        }
                        is RotationOutcome.Blocked ->
                            stoppedForMachine(machineId, SessionProblem.RotationBlocked)
                        is RotationOutcome.FailClosed -> {
                            markRotationFailClosed(machineId)
                            SessionActionResult.Stopped(SessionProblem.RotationRecoveryRequired)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    markRotationFailClosed(machineId)
                    throw cancelled
                } catch (_: Exception) {
                    markRotationFailClosed(machineId)
                    SessionActionResult.Stopped(SessionProblem.RotationRecoveryRequired)
                } finally {
                    rotatingMachines -= machineId
                }
            }
        if (
            entered &&
                mutable.value.selectedMachine == machineId &&
                mutable.value.foreground &&
                startupReconciled &&
                machineId !in rotationRecoveryRequired
        )
            foreground()
        return result
    }

    private fun replaceProfileInState(profile: MachineProfile) {
        mutable.value =
            mutable.value.copy(
                machines = mutable.value.machines.map { if (it.id == profile.id) profile else it }
            )
    }

    private fun markRotationFailClosed(machineId: MachineId) {
        rotationRecoveryRequired += machineId
        if (mutable.value.selectedMachine == machineId)
            mutable.value =
                mutable.value.copy(
                    connection =
                        ConnectionState.Unavailable(SessionProblem.RotationRecoveryRequired),
                    problem = SessionProblem.RotationRecoveryRequired,
                )
    }

    private fun stoppedForMachine(
        machineId: MachineId,
        problem: SessionProblem,
    ): SessionActionResult.Stopped =
        if (mutable.value.selectedMachine == machineId) stopped(problem)
        else SessionActionResult.Stopped(problem)

    suspend fun selectMachine(id: MachineId): SessionActionResult {
        if (mutable.value.machines.none { it.id == id })
            return stopped(SessionProblem.SetupRequired)
        epoch++
        recovery?.cancel()
        live?.cancel()
        mutable.value =
            mutable.value.copy(
                selectedMachine = id,
                selectedSession = null,
                connection =
                    if (id in rotationRecoveryRequired)
                        ConnectionState.Unavailable(SessionProblem.RotationRecoveryRequired)
                    else ConnectionState.Cached,
                sessions = emptyList(),
                agents = emptyList(),
                models = emptyList(),
                transcript = null,
                transientText = V2TextProjection(),
                draft = null,
                permissions = emptyList(),
                questions = emptyList(),
                active = emptySet(),
                interrupting =
                    interruptingByMachine.filterTo(mutableSetOf()) { it.machineId == id },
                settlingRequestIds = emptySet(),
                outgoing = emptyList(),
                changes = null,
                changesLoading = false,
                changesProblem = null,
                problem =
                    if (id in rotationRecoveryRequired) SessionProblem.RotationRecoveryRequired
                    else null,
                busy = machineLocks[id]?.isLocked == true,
            )
        val stamp = epoch
        return runStorage {
            val machine = mutable.value.machines.first { it.id == id }
            val scope = ReadScope(id, machine.origin, machine.credentialGeneration)
            val sessions = store.sessionSummaries(scope)
            val outgoing = store.unresolvedOutgoing(id)
            if (stamp == epoch)
                mutable.value = mutable.value.copy(sessions = sessions, outgoing = outgoing)
            if (id in rotationRecoveryRequired)
                SessionActionResult.Stopped(SessionProblem.RotationRecoveryRequired)
            else if (mutable.value.foreground) foreground() else SessionActionResult.Completed
        }
    }

    suspend fun selectSession(key: SessionKey): SessionActionResult {
        val machine = mutable.value.selectedMachine ?: return stopped(SessionProblem.SetupRequired)
        if (key.machineId != machine) return stopped(SessionProblem.DestinationChanged)
        epoch++
        recovery?.cancel()
        live?.cancel()
        val stamp = epoch
        mutable.value =
            mutable.value.copy(
                selectedSession = key,
                connection = ConnectionState.Cached,
                transcript = null,
                transientText = V2TextProjection(),
                draft = null,
                permissions = emptyList(),
                questions = emptyList(),
                settlingRequestIds =
                    settlingRequests.filter { it.first == key }.mapTo(mutableSetOf()) { it.second },
                busy = machineLocks[machine]?.isLocked == true,
                changes = null,
                changesLoading = false,
                changesProblem = null,
                problem = null,
            )
        return runStorage {
            val summary = mutable.value.sessions.firstOrNull { it.key == key }?.summary
            val draftKey = summary?.draftKey(key)
            val transcript = reconstruct(key)
            if (draftKey != null) {
                draftLocks
                    .computeIfAbsent(draftKey) { Mutex() }
                    .withLock {
                        val draft = store.draft(draftKey)
                        if (stamp == epoch)
                            mutable.value =
                                mutable.value.copy(draft = draft, transcript = transcript)
                    }
            } else if (stamp == epoch) {
                mutable.value = mutable.value.copy(draft = null, transcript = transcript)
            }
            if (mutable.value.foreground) foreground() else SessionActionResult.Completed
        }
    }

    suspend fun saveDraft(text: String): SessionActionResult {
        val key = mutable.value.selectedSession ?: return stopped(SessionProblem.SetupRequired)
        val summary =
            mutable.value.sessions.firstOrNull { it.key == key }?.summary
                ?: return stopped(SessionProblem.SetupRequired)
        val draftKey = summary.draftKey(key)
        val stamp = epoch
        return runStorage {
            draftLocks
                .computeIfAbsent(draftKey) { Mutex() }
                .withLock {
                    val revision = store.draft(draftKey)?.revision ?: 0
                    val saved =
                        store.saveDraft(draftKey, revision, text)
                            ?: return@withLock stoppedIfCurrent(
                                stamp,
                                SessionProblem.StorageFailure,
                            )
                    if (
                        mutable.value.selectedSession == key &&
                            mutable.value.sessions
                                .firstOrNull { it.key == key }
                                ?.summary
                                ?.draftKey(key) == draftKey
                    )
                        mutable.value = mutable.value.copy(draft = saved)
                    SessionActionResult.Completed
                }
        }
    }

    suspend fun createSession(
        command: V2CreateSessionCommand = V2CreateSessionCommand()
    ): SessionActionResult = mutationForSelectedMachine { destination, stamp ->
        when (val result = host.create(destination, command)) {
            is MutationResult.Acknowledged -> {
                store.putSessionSummary(result.scope, result.value)
                val sessions = store.sessionSummaries(result.scope)
                if (stamp == epoch && mutable.value.selectedMachine == destination.machineId) {
                    mutable.value = mutable.value.copy(sessions = sessions)
                    selectSession(result.value.key)
                } else SessionActionResult.Completed
            }
            else -> mutationProblem(result, stamp)
        }
    }

    suspend fun refreshChanges(): SessionActionResult {
        val stamp = epoch
        return try {
            refreshChangesOnce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val problem = SessionProblem.StorageFailure
            if (stamp == epoch)
                mutable.value = mutable.value.copy(changesLoading = false, changesProblem = problem)
            SessionActionResult.Stopped(problem)
        }
    }

    private suspend fun refreshChangesOnce(): SessionActionResult {
        val snapshot = mutable.value
        if (snapshot.connection != ConnectionState.Ready)
            return stopped(SessionProblem.SetupRequired)
        val key = snapshot.selectedSession ?: return stopped(SessionProblem.SetupRequired)
        val summary =
            snapshot.sessions.firstOrNull { it.key == key }?.summary
                ?: return stopped(SessionProblem.SetupRequired)
        val machine =
            snapshot.machines.firstOrNull { it.id == key.machineId }
                ?: return stopped(SessionProblem.SetupRequired)
        val stamp = epoch
        val destination =
            host.destination(machine) ?: return stopped(SessionProblem.CredentialUnavailable)
        if (destination.scope() != machine.scope())
            return stopped(SessionProblem.DestinationChanged)
        if (stamp == epoch)
            mutable.value = mutable.value.copy(changesLoading = true, changesProblem = null)
        return when (val result = host.changes(destination, key, summary.directory)) {
            is ReadResult.Success -> {
                if (stamp == epoch && mutable.value.selectedSession == key)
                    mutable.value =
                        mutable.value.copy(
                            changes = result.value,
                            changesLoading = false,
                            changesProblem = null,
                        )
                SessionActionResult.Completed
            }
            is ReadResult.Failure -> {
                val problem = SessionProblem.Transport(result.reason)
                if (stamp == epoch && mutable.value.selectedSession == key)
                    mutable.value =
                        mutable.value.copy(changesLoading = false, changesProblem = problem)
                SessionActionResult.Stopped(problem)
            }
        }
    }

    suspend fun send(delivery: V2Delivery = V2Delivery.Steer): SessionActionResult {
        if (
            !startupReconciled ||
                mutable.value.selectedMachine?.let { it in rotationRecoveryRequired } == true
        )
            return stopped(SessionProblem.RotationRecoveryRequired)
        if (remoteAccessPolicy != RemoteAccessPolicy.SharedPasswordCandidate)
            return stopped(SessionProblem.RemoteUseNotApproved)
        val snapshot = mutable.value
        val stamp = epoch
        val key = snapshot.selectedSession ?: return stopped(SessionProblem.SetupRequired)
        val summary =
            snapshot.sessions.firstOrNull { it.key == key }?.summary
                ?: return stopped(SessionProblem.SetupRequired)
        return withMachineMutation(key.machineId, stamp) {
            val draftKey = summary.draftKey(key)
            val draft =
                try {
                    draftLocks
                        .computeIfAbsent(draftKey) { Mutex() }
                        .withLock { store.draft(draftKey) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withMachineMutation stoppedIfCurrent(
                        stamp,
                        SessionProblem.StorageFailure,
                    )
                }
                    ?: return@withMachineMutation stoppedIfCurrent(
                        stamp,
                        SessionProblem.SetupRequired,
                    )
            try {
                sendCaptured(snapshot, key, summary, draft, delivery, stamp)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stoppedIfCurrent(stamp, SessionProblem.StorageFailure)
            }
        }
    }

    private suspend fun sendCaptured(
        snapshot: SessionUiState,
        key: SessionKey,
        summary: V2SessionSummary,
        draft: DraftSnapshot,
        delivery: V2Delivery,
        stamp: Long,
    ): SessionActionResult {
        if (stamp != epoch || mutable.value.selectedSession != key)
            return SessionActionResult.Stopped(SessionProblem.DestinationChanged)
        if (snapshot.connection != ConnectionState.Ready)
            return stoppedIfCurrent(stamp, SessionProblem.SetupRequired)
        if (draft.cleared || draft.text.isBlank())
            return stoppedIfCurrent(stamp, SessionProblem.SetupRequired)
        val machine =
            snapshot.machines.firstOrNull { it.id == key.machineId }
                ?: return stoppedIfCurrent(stamp, SessionProblem.SetupRequired)
        if (!machine.sharedPasswordAcknowledged)
            return stoppedIfCurrent(stamp, SessionProblem.RemoteUseNotApproved)
        val destination =
            host.destination(machine)
                ?: return stoppedIfCurrent(stamp, SessionProblem.CredentialUnavailable)
        if (stamp != epoch || mutable.value.selectedSession != key)
            return SessionActionResult.Stopped(SessionProblem.DestinationChanged)
        if (destination.scope() != machine.scope())
            return stoppedIfCurrent(stamp, SessionProblem.DestinationChanged)
        val id = nextMessageId()
        val sendDestination =
            SendDestination(
                key,
                ProjectKey(key.machineId, summary.projectId),
                summary.directory,
                machine.credentialGeneration,
            )
        return run {
            var beganDispatch = false
            var settled = false
            try {
                if (stamp != epoch || mutable.value.selectedSession != key)
                    return@run SessionActionResult.Stopped(SessionProblem.DestinationChanged)
                if (
                    store.unresolvedOutgoing(key.machineId).any {
                        it.destination.session == key &&
                            it.submittedDraft?.revision == draft.revision
                    }
                )
                    return@run stoppedIfCurrent(stamp, SessionProblem.OutcomeUnknown)
                if (!store.prepareIntent(id, sendDestination, draft.text, delivery, draft))
                    return@run stoppedIfCurrent(stamp, SessionProblem.StorageFailure)
                if (!store.beginDispatch(key.machineId, id))
                    return@run stoppedIfCurrent(stamp, SessionProblem.DestinationChanged)
                beganDispatch = true
                val result =
                    host.prompt(destination, V2PromptCommand(key, id, draft.text, delivery))
                when (result) {
                    is MutationResult.Acknowledged -> {
                        if (
                            result.scope != machine.scope() ||
                                !store.acknowledgeAfterProof(key.machineId, id, result.value)
                        ) {
                            val marked = persistUnknownBounded(key.machineId, id)
                            return@run stoppedIfCurrent(
                                stamp,
                                if (marked) SessionProblem.OutcomeUnknown
                                else SessionProblem.StorageFailure,
                            )
                        }
                        settled = true
                        try {
                            refreshOutgoing(key.machineId)
                            if (
                                stamp == epoch &&
                                    mutable.value.selectedSession == key &&
                                    mutable.value.draft?.revision == draft.revision
                            ) {
                                val currentDraft = store.draft(draft.key)
                                if (
                                    stamp == epoch &&
                                        mutable.value.selectedSession == key &&
                                        mutable.value.draft?.revision == draft.revision
                                )
                                    mutable.value = mutable.value.copy(draft = currentDraft)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            return@run stoppedIfCurrent(stamp, SessionProblem.StorageFailure)
                        }
                        SessionActionResult.Completed
                    }
                    is MutationResult.Rejected,
                    is MutationResult.NotDispatched -> {
                        val proven = result.scope == machine.scope()
                        val rejected =
                            if (proven) store.recordRejectionAfterProof(key.machineId, id)
                            else false
                        if (!rejected) {
                            val marked = persistUnknownBounded(key.machineId, id)
                            return@run stoppedIfCurrent(
                                stamp,
                                if (marked) SessionProblem.OutcomeUnknown
                                else SessionProblem.StorageFailure,
                            )
                        }
                        settled = true
                        refreshOutgoing(key.machineId)
                        mutationProblem(result, stamp)
                    }
                    is MutationResult.OutcomeUnknown -> {
                        val marked = persistUnknownBounded(key.machineId, id)
                        if (marked) refreshOutgoing(key.machineId)
                        if (marked) mutationProblem(result, stamp)
                        else stoppedIfCurrent(stamp, SessionProblem.StorageFailure)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (beganDispatch && !settled && !persistUnknownBounded(key.machineId, id))
                    cancelled.addSuppressed(
                        IllegalStateException("Could not persist unknown send outcome")
                    )
                throw cancelled
            } catch (_: Exception) {
                if (beganDispatch && !settled) {
                    val marked = persistUnknownBounded(key.machineId, id)
                    stoppedIfCurrent(
                        stamp,
                        if (marked) SessionProblem.OutcomeUnknown else SessionProblem.StorageFailure,
                    )
                } else stoppedIfCurrent(stamp, SessionProblem.StorageFailure)
            }
        }
    }

    /** A dispatched intent remains recoverable as DISPATCHING if this bounded repair fails. */
    private suspend fun persistUnknownBounded(machine: MachineId, id: String): Boolean =
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                try {
                    store.markOutcomeUnknown(machine, id)
                } catch (_: Exception) {
                    false
                }
            } ?: false
        }

    suspend fun interrupt(): SessionActionResult =
        selectedSessionMutation { destination, key, stamp ->
            if (key in interruptingByMachine)
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.MutationInProgress,
                )
            val result =
                try {
                    host.interrupt(destination, key)
                } catch (cancelled: CancellationException) {
                    markInterrupting(key, stamp)
                    throw cancelled
                } catch (_: Exception) {
                    markInterrupting(key, stamp)
                    return@selectedSessionMutation stoppedIfCurrent(
                        stamp,
                        SessionProblem.OutcomeUnknown,
                    )
                }
            if (result is MutationResult.Acknowledged || result is MutationResult.OutcomeUnknown)
                markInterrupting(key, stamp)
            mutationProblem(result, stamp)
        }

    suspend fun replyPermission(
        request: V2PermissionRequest,
        reply: V2PermissionReply,
    ): SessionActionResult = selectedSessionMutation { destination, key, stamp ->
        if (request.sessionKey != key)
            return@selectedSessionMutation stoppedIfCurrent(
                stamp,
                SessionProblem.DestinationChanged,
            )
        val identity = key to request.id
        if (identity in settlingRequests)
            return@selectedSessionMutation stoppedIfCurrent(
                stamp,
                SessionProblem.MutationInProgress,
            )
        val pending = host.permissions(destination, key)
        if (pending is ReadResult.Failure)
            return@selectedSessionMutation stoppedIfCurrent(
                stamp,
                SessionProblem.Transport(pending.reason),
            )
        if (stamp != epoch || mutable.value.selectedSession != key)
            return@selectedSessionMutation SessionActionResult.Stopped(
                SessionProblem.DestinationChanged
            )
        val current = (pending as ReadResult.Success).value
        mutable.value = mutable.value.copy(permissions = current)
        if (current.none { it.id == request.id && it == request })
            return@selectedSessionMutation stoppedIfCurrent(stamp, SessionProblem.RequestStale)
        val result =
            try {
                host.permissionReply(destination, request, reply)
            } catch (cancelled: CancellationException) {
                markRequestSettling(
                    identity,
                    MutationResult.OutcomeUnknown(destination.scope()),
                    stamp,
                )
                throw cancelled
            } catch (_: Exception) {
                markRequestSettling(
                    identity,
                    MutationResult.OutcomeUnknown(destination.scope()),
                    stamp,
                )
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.OutcomeUnknown,
                )
            }
        markRequestSettling(identity, result, stamp)
        refreshPending(destination, key, stamp)
        mutationProblem(result, stamp)
    }

    suspend fun replyQuestion(
        request: V2QuestionRequest,
        answers: List<List<String>>,
    ): SessionActionResult = selectedSessionMutation { destination, key, stamp ->
        if (request.sessionKey != key)
            return@selectedSessionMutation stoppedIfCurrent(
                stamp,
                SessionProblem.DestinationChanged,
            )
        val identity = key to request.id
        if (identity in settlingRequests)
            return@selectedSessionMutation stoppedIfCurrent(
                stamp,
                SessionProblem.MutationInProgress,
            )
        val pending = host.questions(destination, key)
        if (pending is ReadResult.Failure)
            return@selectedSessionMutation stoppedIfCurrent(
                stamp,
                SessionProblem.Transport(pending.reason),
            )
        if (stamp != epoch || mutable.value.selectedSession != key)
            return@selectedSessionMutation SessionActionResult.Stopped(
                SessionProblem.DestinationChanged
            )
        val current = (pending as ReadResult.Success).value
        mutable.value = mutable.value.copy(questions = current)
        if (current.none { it.id == request.id && it == request })
            return@selectedSessionMutation stoppedIfCurrent(stamp, SessionProblem.RequestStale)
        val result =
            try {
                host.questionReply(destination, request, answers)
            } catch (cancelled: CancellationException) {
                markRequestSettling(
                    identity,
                    MutationResult.OutcomeUnknown(destination.scope()),
                    stamp,
                )
                throw cancelled
            } catch (_: Exception) {
                markRequestSettling(
                    identity,
                    MutationResult.OutcomeUnknown(destination.scope()),
                    stamp,
                )
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.OutcomeUnknown,
                )
            }
        markRequestSettling(identity, result, stamp)
        refreshPending(destination, key, stamp)
        mutationProblem(result, stamp)
    }

    suspend fun rejectQuestion(request: V2QuestionRequest): SessionActionResult =
        selectedSessionMutation { destination, key, stamp ->
            if (request.sessionKey != key)
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.DestinationChanged,
                )
            val identity = key to request.id
            if (identity in settlingRequests)
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.MutationInProgress,
                )
            val pending = host.questions(destination, key)
            if (pending is ReadResult.Failure)
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.Transport(pending.reason),
                )
            if (stamp != epoch || mutable.value.selectedSession != key)
                return@selectedSessionMutation SessionActionResult.Stopped(
                    SessionProblem.DestinationChanged
                )
            val current = (pending as ReadResult.Success).value
            mutable.value = mutable.value.copy(questions = current)
            if (current.none { it.id == request.id && it == request })
                return@selectedSessionMutation stoppedIfCurrent(stamp, SessionProblem.RequestStale)
            val result =
                try {
                    host.questionReject(destination, request)
                } catch (cancelled: CancellationException) {
                    markRequestSettling(
                        identity,
                        MutationResult.OutcomeUnknown(destination.scope()),
                        stamp,
                    )
                    throw cancelled
                } catch (_: Exception) {
                    markRequestSettling(
                        identity,
                        MutationResult.OutcomeUnknown(destination.scope()),
                        stamp,
                    )
                    return@selectedSessionMutation stoppedIfCurrent(
                        stamp,
                        SessionProblem.OutcomeUnknown,
                    )
                }
            markRequestSettling(identity, result, stamp)
            refreshPending(destination, key, stamp)
            mutationProblem(result, stamp)
        }

    /**
     * An explicit user check releases retry blockers only after every authoritative read succeeds.
     * It does not decide whether the earlier mutation succeeded and never dispatches a mutation.
     */
    suspend fun recheckPendingActions(): SessionActionResult =
        selectedSessionMutation { destination, key, stamp ->
            val active = host.active(destination)
            if (active is ReadResult.Failure)
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.Transport(active.reason),
                )
            if (stamp != epoch || mutable.value.selectedSession != key)
                return@selectedSessionMutation SessionActionResult.Stopped(
                    SessionProblem.DestinationChanged
                )
            val permissions = host.permissions(destination, key)
            if (permissions is ReadResult.Failure)
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.Transport(permissions.reason),
                )
            if (stamp != epoch || mutable.value.selectedSession != key)
                return@selectedSessionMutation SessionActionResult.Stopped(
                    SessionProblem.DestinationChanged
                )
            val questions = host.questions(destination, key)
            if (questions is ReadResult.Failure)
                return@selectedSessionMutation stoppedIfCurrent(
                    stamp,
                    SessionProblem.Transport(questions.reason),
                )
            val currentMachine = mutable.value.machines.firstOrNull { it.id == key.machineId }
            if (
                stamp != epoch ||
                    mutable.value.selectedSession != key ||
                    currentMachine?.scope() != destination.scope()
            )
                return@selectedSessionMutation SessionActionResult.Stopped(
                    SessionProblem.DestinationChanged
                )
            interruptingByMachine.remove(key)
            settlingRequests.removeAll { it.first == key }
            mutable.value =
                mutable.value.copy(
                    active = (active as ReadResult.Success).value,
                    permissions = (permissions as ReadResult.Success).value,
                    questions = (questions as ReadResult.Success).value,
                    interrupting =
                        interruptingByMachine.filterTo(mutableSetOf()) {
                            it.machineId == key.machineId
                        },
                    settlingRequestIds = emptySet(),
                )
            SessionActionResult.Completed
        }

    suspend fun foreground(): SessionActionResult {
        mutable.value = mutable.value.copy(foreground = true)
        recovery?.cancel()
        val selected = mutable.value.selectedMachine ?: return SessionActionResult.Completed
        if (!startupReconciled || selected in rotationRecoveryRequired) {
            val problem = SessionProblem.RotationRecoveryRequired
            mutable.value =
                mutable.value.copy(
                    connection = ConnectionState.Unavailable(problem),
                    problem = problem,
                )
            return SessionActionResult.Stopped(problem)
        }
        if (selected in rotatingMachines) return SessionActionResult.Completed
        val stamp = epoch
        recovery = scope.launch { recover(selected, stamp) }
        return SessionActionResult.Completed
    }

    fun background() {
        mutable.value = mutable.value.copy(foreground = false, connection = ConnectionState.Cached)
        recovery?.cancel()
        live?.cancel()
    }

    override fun close() {
        background()
        val owner = scope.coroutineContext[Job]
        scope.cancel()
        closeScope.launch {
            owner?.join()
            (store as? AutoCloseable)?.close()
            closeScope.cancel()
        }
    }

    private suspend fun recover(machineId: MachineId, stamp: Long) {
        try {
            recoverOnce(machineId, stamp)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failIfCurrent(stamp, SessionProblem.StorageFailure)
        }
    }

    private suspend fun recoverOnce(machineId: MachineId, stamp: Long) {
        if (
            stamp != epoch ||
                !mutable.value.foreground ||
                !startupReconciled ||
                machineId in rotationRecoveryRequired ||
                machineId in rotatingMachines
        )
            return
        val profile = mutable.value.machines.firstOrNull { it.id == machineId } ?: return
        mutable.value = mutable.value.copy(connection = ConnectionState.Connecting)
        val destination =
            host.destination(profile)
                ?: return failIfCurrent(stamp, SessionProblem.CredentialUnavailable)
        if (stamp != epoch || !mutable.value.foreground) return
        if (destination.scope() != profile.scope())
            return failIfCurrent(stamp, SessionProblem.DestinationChanged)
        when (val compatibility = host.compatibility(destination)) {
            is ReadResult.Failure ->
                return failIfCurrent(stamp, SessionProblem.Transport(compatibility.reason))
            is ReadResult.Success ->
                if (compatibility.value != "1.18.32")
                    return failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
        }
        if (stamp != epoch || !mutable.value.foreground) return
        when (val list = host.sessions(destination)) {
            is ReadResult.Failure ->
                return failIfCurrent(stamp, SessionProblem.Transport(list.reason))
            is ReadResult.Success -> {
                if (stamp != epoch || !mutable.value.foreground) return
                for (session in list.value) store.putSessionSummary(profile.scope(), session)
                val summaries = store.sessionSummaries(profile.scope())
                if (stamp == epoch) mutable.value = mutable.value.copy(sessions = summaries)
            }
        }
        val agents = host.agents(destination)
        val models = host.models(destination)
        if (stamp != epoch || !mutable.value.foreground) return
        if (agents is ReadResult.Failure)
            return failIfCurrent(stamp, SessionProblem.Transport(agents.reason))
        if (models is ReadResult.Failure)
            return failIfCurrent(stamp, SessionProblem.Transport(models.reason))
        if (stamp == epoch)
            mutable.value =
                mutable.value.copy(
                    agents = (agents as ReadResult.Success).value,
                    models = (models as ReadResult.Success).value,
                )
        val key = mutable.value.selectedSession?.takeIf { it.machineId == machineId }
        if (key != null) {
            val remote = host.session(destination, key)
            if (stamp != epoch || !mutable.value.foreground) return
            if (remote is ReadResult.Failure)
                return failIfCurrent(stamp, SessionProblem.Transport(remote.reason))
            val summary = (remote as ReadResult.Success).value
            store.putSessionSummary(profile.scope(), summary)
            if (!replayHistory(destination, key, stamp)) return
            if (!refreshPending(destination, key, stamp)) return
        }
        val active = host.active(destination)
        if (stamp != epoch || !mutable.value.foreground) return
        if (active is ReadResult.Success && stamp == epoch) {
            reconcileInterrupting(machineId, active.value)
            mutable.value =
                mutable.value.copy(
                    active = active.value,
                    connection = ConnectionState.Ready,
                    problem = null,
                )
        } else if (active is ReadResult.Failure)
            return failIfCurrent(stamp, SessionProblem.Transport(active.reason))
        if (key != null && stamp == epoch) {
            live?.cancel()
            live = scope.launch { collectLive(destination, key, stamp) }
        }
    }

    private suspend fun replayHistory(
        destination: ReadDestination,
        key: SessionKey,
        stamp: Long,
    ): Boolean =
        journalLocks
            .computeIfAbsent(key) { Mutex() }
            .withLock {
                if (stamp != epoch || !mutable.value.foreground) return false
                var cursor = store.cursor(key)
                repeat(100) {
                    val result = host.history(destination, key, cursor)
                    val page =
                        when (result) {
                            is ReadResult.Failure -> {
                                failIfCurrent(stamp, SessionProblem.Transport(result.reason))
                                return false
                            }
                            is ReadResult.Success -> result.value
                        }
                    if (stamp != epoch || !mutable.value.foreground) return false
                    if (page.records.any { it.decoded !is TranscriptDecode.Supported }) {
                        failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
                        return false
                    }
                    if (page.records.isNotEmpty()) {
                        cursor = store.commitEvents(key, cursor, page.records.map { it.rawJson })
                        val transcript = reconstruct(key)
                        if (stamp == epoch) {
                            val ended =
                                page.events.mapNotNull {
                                    (it as? TranscriptDecode.Supported)?.event?.kind
                                        as? Kind.TextEnded
                                }
                            val keys = ended.map { V2TextKey(key, it.messageId, it.textId) }.toSet()
                            mutable.value =
                                mutable.value.copy(
                                    transcript = transcript,
                                    transientText =
                                        mutable.value.transientText.copy(
                                            fragments = mutable.value.transientText.fragments - keys
                                        ),
                                )
                        }
                    }
                    if (!page.hasMore) {
                        reconcileAdmissions(destination, key)
                        return true
                    }
                    if (page.records.isEmpty()) {
                        failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
                        return false
                    }
                }
                failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
                return false
            }

    private suspend fun collectLive(destination: ReadDestination, key: SessionKey, stamp: Long) {
        coroutineScope {
            launch {
                try {
                    collectDurable(destination, key, stamp)
                } catch (_: UnsupportedDurable) {
                    failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
                    this@coroutineScope.cancel()
                } catch (terminal: TerminalStreamFailure) {
                    failIfCurrent(stamp, terminal.problem)
                    this@coroutineScope.cancel()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failIfCurrent(stamp, SessionProblem.StorageFailure)
                    this@coroutineScope.cancel()
                }
            }
            launch {
                try {
                    collectTransient(destination, key, stamp)
                } catch (terminal: TerminalStreamFailure) {
                    failIfCurrent(stamp, terminal.problem)
                    this@coroutineScope.cancel()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
                    this@coroutineScope.cancel()
                }
            }
            launch {
                try {
                    refreshLoop(destination, key, stamp)
                    // Failed authoritative reconciliation must stop both subscriptions too.
                    this@coroutineScope.cancel()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failIfCurrent(stamp, SessionProblem.StorageFailure)
                    this@coroutineScope.cancel()
                }
            }
        }
    }

    private suspend fun collectDurable(destination: ReadDestination, key: SessionKey, stamp: Long) {
        repeat(3) { attempt ->
            if (stamp != epoch || !mutable.value.foreground) return
            host.durable(destination, key, store.cursor(key)).collect { item ->
                when (item) {
                    is StreamResult.Item -> {
                        if (stamp != epoch || !mutable.value.foreground) return@collect
                        if (
                            item.scope != destination.scope() ||
                                item.value.decoded !is TranscriptDecode.Supported
                        ) {
                            failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
                            throw UnsupportedDurable()
                        }
                        journalLocks
                            .computeIfAbsent(key) { Mutex() }
                            .withLock {
                                if (stamp != epoch || !mutable.value.foreground) return@withLock
                                val cursor = store.cursor(key)
                                store.commitEvents(key, cursor, listOf(item.value.rawJson))
                                val transcript = reconstruct(key)
                                if (stamp == epoch) {
                                    val ended =
                                        (item.value.decoded as TranscriptDecode.Supported)
                                            .event
                                            .kind as? Kind.TextEnded
                                    val overlay =
                                        if (ended != null)
                                            mutable.value.transientText.copy(
                                                fragments =
                                                    mutable.value.transientText.fragments -
                                                        V2TextKey(
                                                            key,
                                                            ended.messageId,
                                                            ended.textId,
                                                        )
                                            )
                                        else mutable.value.transientText
                                    mutable.value =
                                        mutable.value.copy(
                                            transcript = transcript,
                                            transientText = overlay,
                                        )
                                }
                                reconcileAdmissions(destination, key)
                            }
                    }
                    is StreamResult.Failure -> {
                        val http =
                            item.reason as? dev.local.opencodecompanion.client.StreamFailure.Http
                        if (
                            http?.reason == ReadFailure.AuthenticationRequired ||
                                http?.reason == ReadFailure.TlsRejected
                        )
                            throw TerminalStreamFailure(SessionProblem.Transport(http.reason))
                        if (
                            item.reason is dev.local.opencodecompanion.client.StreamFailure.Protocol
                        ) {
                            failIfCurrent(stamp, SessionProblem.ProtocolUnsupported)
                            throw UnsupportedDurable()
                        }
                    }
                    is StreamResult.Disconnected,
                    is StreamResult.Eof -> Unit
                }
            }
            // A completed SSE flow is disconnected even if it emitted no terminal frame.
            if (stamp == epoch)
                mutable.value =
                    mutable.value.copy(
                        connection = ConnectionState.Connecting,
                        active = emptySet(),
                        permissions = emptyList(),
                        questions = emptyList(),
                    )
            if (attempt < 2) delay(1_000L shl attempt)
        }
        throw TerminalStreamFailure(SessionProblem.Transport(ReadFailure.TransportUnavailable))
    }

    private suspend fun collectTransient(
        destination: ReadDestination,
        key: SessionKey,
        stamp: Long,
    ) {
        repeat(3) { attempt ->
            if (stamp != epoch || !mutable.value.foreground) return
            host.global(destination).collect { item ->
                when (item) {
                    is StreamResult.Item -> {
                        if (stamp != epoch || !mutable.value.foreground) return@collect
                        if (item.scope != destination.scope()) return@collect
                        val event = item.value as? V2GlobalEvent.ScopedText ?: return@collect
                        if (event.session == key && stamp == epoch) {
                            mutable.value =
                                mutable.value.copy(
                                    transientText = mutable.value.transientText.apply(event.event)
                                )
                        }
                    }
                    is StreamResult.Failure -> {
                        if (
                            item.reason == dev.local.opencodecompanion.client.StreamFailure.Protocol
                        )
                            throw TerminalStreamFailure(SessionProblem.ProtocolUnsupported)
                        val http =
                            item.reason as? dev.local.opencodecompanion.client.StreamFailure.Http
                        if (
                            http?.reason == ReadFailure.AuthenticationRequired ||
                                http?.reason == ReadFailure.TlsRejected
                        )
                            throw TerminalStreamFailure(SessionProblem.Transport(http.reason))
                    }
                    is StreamResult.Disconnected,
                    is StreamResult.Eof -> Unit
                }
            }
            // A completed SSE flow is disconnected even if it emitted no terminal frame.
            if (stamp == epoch)
                mutable.value =
                    mutable.value.copy(
                        transientText = V2TextProjection(),
                        connection = ConnectionState.Connecting,
                        active = emptySet(),
                        permissions = emptyList(),
                        questions = emptyList(),
                    )
            if (attempt < 2) delay(1_000L shl attempt)
        }
        throw TerminalStreamFailure(SessionProblem.Transport(ReadFailure.TransportUnavailable))
    }

    private suspend fun refreshLoop(destination: ReadDestination, key: SessionKey, stamp: Long) {
        var failures = 0
        while (stamp == epoch && mutable.value.foreground) {
            delay(2_000)
            if (stamp != epoch || !mutable.value.foreground) return
            // A quiet durable route may also be selectively stalled. Reconcile its cursor
            // through bounded finite reads before healthy status polls can publish Ready.
            if (!replayHistory(destination, key, stamp)) return
            val active = host.active(destination)
            val permissions = host.permissions(destination, key)
            val questions = host.questions(destination, key)
            if (stamp != epoch || !mutable.value.foreground) return
            val failure =
                sequenceOf(active, permissions, questions)
                    .filterIsInstance<ReadResult.Failure>()
                    .firstOrNull()
            if (failure != null) {
                failures++
                mutable.value =
                    mutable.value.copy(
                        active = emptySet(),
                        permissions = emptyList(),
                        questions = emptyList(),
                        connection =
                            ConnectionState.Unavailable(SessionProblem.Transport(failure.reason)),
                        problem = SessionProblem.Transport(failure.reason),
                    )
                if (failures >= 3) return
            } else {
                failures = 0
                val activeValue = (active as ReadResult.Success).value
                val permissionValue = (permissions as ReadResult.Success).value
                val questionValue = (questions as ReadResult.Success).value
                reconcileInterrupting(key.machineId, activeValue)
                val ids = (permissionValue.map { it.id } + questionValue.map { it.id }).toSet()
                settlingRequests.removeAll { it.first == key && it.second !in ids }
                mutable.value =
                    mutable.value.copy(
                        active = activeValue,
                        permissions = permissionValue,
                        questions = questionValue,
                        settlingRequestIds =
                            settlingRequests
                                .filter { it.first == key }
                                .mapTo(mutableSetOf()) { it.second },
                        connection = ConnectionState.Ready,
                        problem = null,
                    )
            }
        }
    }

    private fun reconcileInterrupting(machine: MachineId, active: Set<SessionKey>) {
        interruptingByMachine.removeAll { it.machineId == machine && it !in active }
        if (mutable.value.selectedMachine == machine)
            mutable.value =
                mutable.value.copy(
                    interrupting =
                        interruptingByMachine.filterTo(mutableSetOf()) { it.machineId == machine }
                )
    }

    private fun markInterrupting(key: SessionKey, stamp: Long) {
        interruptingByMachine += key
        if (stamp == epoch && mutable.value.selectedMachine == key.machineId)
            mutable.value =
                mutable.value.copy(
                    interrupting =
                        interruptingByMachine.filterTo(mutableSetOf()) {
                            it.machineId == key.machineId
                        }
                )
    }

    private suspend fun reconstruct(key: SessionKey): TranscriptState {
        var state = TranscriptState(key)
        var after = 0L
        while (true) {
            val rows = store.journal(key, after, 100)
            if (rows.isEmpty()) return state
            for (raw in rows) {
                val decoded =
                    V2Transcript.event(raw, key) as? TranscriptDecode.Supported
                        ?: error("unsupported local journal")
                state =
                    (state.apply(decoded.event) as? TranscriptApply.Applied)?.state
                        ?: error("inconsistent local journal")
                after = state.lastSequence
            }
        }
    }

    private suspend fun refreshPending(
        destination: ReadDestination,
        key: SessionKey,
        stamp: Long = epoch,
    ): Boolean {
        val permissions = host.permissions(destination, key)
        val questions = host.questions(destination, key)
        if (permissions is ReadResult.Failure) {
            failIfCurrent(stamp, SessionProblem.Transport(permissions.reason))
            return false
        }
        if (questions is ReadResult.Failure) {
            failIfCurrent(stamp, SessionProblem.Transport(questions.reason))
            return false
        }
        if (
            stamp == epoch &&
                mutable.value.selectedSession == key &&
                permissions is ReadResult.Success &&
                questions is ReadResult.Success
        ) {
            val authoritativeIds =
                (permissions.value.map { it.id } + questions.value.map { it.id }).toSet()
            settlingRequests.removeAll { it.first == key && it.second !in authoritativeIds }
            mutable.value =
                mutable.value.copy(
                    permissions = permissions.value,
                    questions = questions.value,
                    settlingRequestIds =
                        settlingRequests
                            .filter { it.first == key }
                            .mapTo(mutableSetOf()) { it.second },
                )
        }
        return true
    }

    private fun <T> markRequestSettling(
        identity: Pair<SessionKey, String>,
        result: MutationResult<T>,
        stamp: Long,
    ) {
        if (result is MutationResult.Acknowledged || result is MutationResult.OutcomeUnknown) {
            settlingRequests += identity
            if (stamp == epoch && mutable.value.selectedSession == identity.first)
                mutable.value =
                    mutable.value.copy(
                        settlingRequestIds = mutable.value.settlingRequestIds + identity.second
                    )
        }
    }

    private suspend fun reconcileAdmissions(destination: ReadDestination, key: SessionKey) {
        val outstanding =
            store.unresolvedOutgoing(key.machineId).filter {
                it.destination.session == key &&
                    it.origin == destination.origin.toString() &&
                    it.destination.credentialGeneration == destination.credentialGeneration &&
                    it.delivery != null
            }
        if (outstanding.isEmpty()) return
        val state = reconstruct(key)
        for (event in state.seen.values) {
            val admission = event.promptAdmission() ?: continue
            if (outstanding.none { it.id == admission.id }) continue
            store.acknowledgeAfterProof(key.machineId, admission.id, admission)
        }
        refreshOutgoing(key.machineId)
        val selected = mutable.value.selectedSession
        if (selected == key) {
            val draft = mutable.value.draft
            if (draft != null) {
                val currentDraft = store.draft(draft.key)
                if (
                    mutable.value.selectedSession == key &&
                        mutable.value.draft?.revision == draft.revision
                )
                    mutable.value = mutable.value.copy(draft = currentDraft)
            }
        }
    }

    private suspend fun refreshOutgoing(machine: MachineId) {
        val entries = store.unresolvedOutgoing(machine)
        if (mutable.value.selectedMachine == machine)
            mutable.value = mutable.value.copy(outgoing = entries)
    }

    private suspend fun withMachineMutation(
        machineId: MachineId,
        stamp: Long,
        block: suspend () -> SessionActionResult,
    ): SessionActionResult {
        val lock = machineLocks.computeIfAbsent(machineId) { Mutex() }
        if (!lock.tryLock()) return stoppedForMachine(machineId, SessionProblem.MutationInProgress)
        if (mutable.value.selectedMachine == machineId)
            mutable.value = mutable.value.copy(busy = true)
        return try {
            block()
        } finally {
            lock.unlock()
            if (mutable.value.selectedMachine == machineId)
                mutable.value = mutable.value.copy(busy = false)
        }
    }

    private suspend fun mutationForSelectedMachine(
        block: suspend (ReadDestination, Long) -> SessionActionResult
    ): SessionActionResult {
        if (
            !startupReconciled ||
                mutable.value.selectedMachine?.let { it in rotationRecoveryRequired } == true
        )
            return stopped(SessionProblem.RotationRecoveryRequired)
        if (remoteAccessPolicy != RemoteAccessPolicy.SharedPasswordCandidate)
            return stopped(SessionProblem.RemoteUseNotApproved)
        val stamp = epoch
        val machine =
            mutable.value.machines.firstOrNull { it.id == mutable.value.selectedMachine }
                ?: return stopped(SessionProblem.SetupRequired)
        if (!machine.sharedPasswordAcknowledged) return stopped(SessionProblem.RemoteUseNotApproved)
        if (mutable.value.connection != ConnectionState.Ready)
            return stopped(SessionProblem.SetupRequired)
        return withMachineMutation(machine.id, stamp) {
            try {
                val destination =
                    host.destination(machine)
                        ?: return@withMachineMutation stoppedIfCurrent(
                            stamp,
                            SessionProblem.CredentialUnavailable,
                        )
                if (stamp != epoch || destination.scope() != machine.scope())
                    return@withMachineMutation stoppedIfCurrent(
                        stamp,
                        SessionProblem.DestinationChanged,
                    )
                block(destination, stamp)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stoppedIfCurrent(stamp, SessionProblem.StorageFailure)
            }
        }
    }

    private suspend fun selectedSessionMutation(
        block: suspend (ReadDestination, SessionKey, Long) -> SessionActionResult
    ): SessionActionResult {
        val key = mutable.value.selectedSession ?: return stopped(SessionProblem.SetupRequired)
        return mutationForSelectedMachine { destination, stamp ->
            if (
                key.machineId != destination.machineId ||
                    mutable.value.selectedSession != key ||
                    stamp != epoch
            )
                return@mutationForSelectedMachine stoppedIfCurrent(
                    stamp,
                    SessionProblem.DestinationChanged,
                )
            block(destination, key, stamp)
        }
    }

    private fun stoppedIfCurrent(
        stamp: Long,
        problem: SessionProblem,
    ): SessionActionResult.Stopped =
        if (stamp == epoch) stopped(problem) else SessionActionResult.Stopped(problem)

    private fun <T> mutationProblem(
        result: MutationResult<T>,
        stamp: Long = epoch,
    ): SessionActionResult =
        when (result) {
            is MutationResult.Acknowledged -> SessionActionResult.Completed
            is MutationResult.Rejected ->
                stoppedIfCurrent(stamp, SessionProblem.Transport(result.reason))
            is MutationResult.NotDispatched ->
                stoppedIfCurrent(stamp, SessionProblem.Transport(result.reason))
            is MutationResult.OutcomeUnknown ->
                stoppedIfCurrent(stamp, SessionProblem.OutcomeUnknown)
        }

    private fun stopped(problem: SessionProblem): SessionActionResult.Stopped {
        mutable.value = mutable.value.copy(problem = problem)
        return SessionActionResult.Stopped(problem)
    }

    private fun failIfCurrent(stamp: Long, problem: SessionProblem) {
        if (stamp == epoch)
            mutable.value =
                mutable.value.copy(
                    connection = ConnectionState.Unavailable(problem),
                    problem = problem,
                )
    }

    private suspend fun runStorage(block: suspend () -> SessionActionResult): SessionActionResult =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stopped(SessionProblem.StorageFailure)
        }

    private fun MachineProfile.scope() = ReadScope(id, origin, credentialGeneration)

    private fun ReadDestination.scope() =
        ReadScope(machineId, origin.toString(), credentialGeneration)

    private fun V2SessionSummary.draftKey(key: SessionKey) =
        DraftKey(key, ProjectKey(key.machineId, projectId), directory)
}

private class UnsupportedDurable : RuntimeException()

private class TerminalStreamFailure(val problem: SessionProblem) : RuntimeException()
