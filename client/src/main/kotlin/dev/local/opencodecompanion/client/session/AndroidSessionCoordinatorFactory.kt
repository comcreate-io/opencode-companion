package dev.local.opencodecompanion.client.session

import android.app.Application
import dev.local.opencodecompanion.client.ReadDestination
import dev.local.opencodecompanion.client.ReadOnlyV2Transport
import dev.local.opencodecompanion.client.ReadResult
import dev.local.opencodecompanion.client.ReadScope
import dev.local.opencodecompanion.client.ScopedSession
import dev.local.opencodecompanion.client.SessionV2Streams
import dev.local.opencodecompanion.client.SessionV2Transport
import dev.local.opencodecompanion.client.security.AndroidCredentialStore
import dev.local.opencodecompanion.client.security.CredentialResult
import dev.local.opencodecompanion.client.security.CredentialScope
import dev.local.opencodecompanion.client.security.MachineCredentialRotation
import dev.local.opencodecompanion.client.security.RotationOutcome
import dev.local.opencodecompanion.client.security.RotationReconciliation
import dev.local.opencodecompanion.client.storage.DraftKey
import dev.local.opencodecompanion.client.storage.DraftSnapshot
import dev.local.opencodecompanion.client.storage.DurableStore
import dev.local.opencodecompanion.client.storage.MachineProfile
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2AgentSummary
import dev.local.opencodecompanion.protocol.V2CreateSessionCommand
import dev.local.opencodecompanion.protocol.V2Delivery
import dev.local.opencodecompanion.protocol.V2ModelSummary
import dev.local.opencodecompanion.protocol.V2PermissionReply
import dev.local.opencodecompanion.protocol.V2PermissionRequest
import dev.local.opencodecompanion.protocol.V2PromptAdmission
import dev.local.opencodecompanion.protocol.V2PromptCommand
import dev.local.opencodecompanion.protocol.V2QuestionRequest
import dev.local.opencodecompanion.protocol.VcsFileDiff
import dev.local.opencodecompanion.protocol.transcript.TranscriptPage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Production graph uses system TLS trust; tests may pass fixture-configured transports. */
object AndroidSessionCoordinatorFactory {
    fun create(
        application: Application,
        scope: CoroutineScope,
        reads: ReadOnlyV2Transport = ReadOnlyV2Transport(),
        transport: SessionV2Transport = SessionV2Transport(),
        streams: SessionV2Streams = SessionV2Streams(),
        databaseName: String = "companion-state.db",
    ): SessionCoordinator {
        val store = AndroidStorePort(application, databaseName)
        val vault = AndroidCredentialStore(application)
        val credentials = AndroidSessionCredentials(vault)
        val rotation = AndroidSessionRotation(store, vault)
        val host = AndroidSessionHost(vault, reads, transport, streams)
        return SessionCoordinator(store, host, credentials, rotation, scope)
    }
}

private class AndroidStorePort(private val application: Application, private val name: String) :
    SessionStorePort, AutoCloseable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val closeLock = Any()
    @Volatile private var opened: DurableStore? = null

    private suspend fun store(): DurableStore =
        mutex.withLock {
            check(!closed.get()) { "Session coordinator is closed" }
            opened
                ?: DurableStore.open(application, name).let { acquired ->
                    synchronized(closeLock) {
                        if (closed.get()) {
                            acquired.close()
                            error("Session coordinator closed during database open")
                        }
                        opened = acquired
                        acquired
                    }
                }
        }

    suspend fun durableStore(): DurableStore = store()

    override fun close() {
        synchronized(closeLock) { if (closed.compareAndSet(false, true)) opened?.close() }
    }

    override suspend fun machines() = store().machines()

    override suspend fun putMachine(profile: MachineProfile) {
        store().putMachine(profile)
    }

    override suspend fun sessionSummaries(scope: ReadScope) =
        store().sessionSummaries(scope).map { ScopedSession(it.key, it.summary) }

    override suspend fun putSessionSummary(scope: ReadScope, session: ScopedSession) {
        check(store().putSessionSummary(scope, session.key, session.summary))
    }

    override suspend fun draft(key: DraftKey) = store().draft(key)

    override suspend fun saveDraft(key: DraftKey, expectedRevision: Long, text: String) =
        store().saveDraft(key, expectedRevision, text)

    override suspend fun unresolvedOutgoing(machine: MachineId) =
        store().unresolvedOutgoing(machine)

    override suspend fun prepareIntent(
        id: String,
        destination: dev.local.opencodecompanion.client.SendDestination,
        text: String,
        delivery: V2Delivery,
        draft: DraftSnapshot?,
    ) = store().prepareIntent(id, destination, text, delivery, draft)

    override suspend fun beginDispatch(machine: MachineId, id: String) =
        store().beginDispatch(machine, id)

    override suspend fun markOutcomeUnknown(machine: MachineId, id: String) =
        store().markOutcomeUnknown(machine, id)

    override suspend fun recordRejectionAfterProof(machine: MachineId, id: String) =
        store().recordRejectionAfterProof(machine, id)

    override suspend fun acknowledgeAfterProof(
        machine: MachineId,
        id: String,
        admission: V2PromptAdmission,
    ) = store().acknowledgeAfterProof(machine, id, admission)

    override suspend fun cursor(key: SessionKey) = store().cursor(key)

    override suspend fun journal(key: SessionKey, after: Long, limit: Int) =
        store().journal(key, after, limit).map { it.rawJson }

    override suspend fun commitEvents(
        key: SessionKey,
        expectedCursor: Long,
        rawEvents: List<String>,
    ) = store().commitEvents(key, expectedCursor, rawEvents)
}

private class AndroidSessionRotation(
    private val store: AndroidStorePort,
    private val vault: AndroidCredentialStore,
) : SessionRotationPort {
    private suspend fun service() = MachineCredentialRotation(store.durableStore(), vault)

    override suspend fun rotate(
        machineId: MachineId,
        expectedOrigin: String,
        expectedGeneration: Long,
        newAuthorization: String,
    ): RotationOutcome =
        service().rotate(machineId, expectedOrigin, expectedGeneration, newAuthorization)

    override suspend fun reconcilePending(): List<RotationReconciliation> =
        service().reconcilePending()
}

private class AndroidSessionCredentials(private val vault: AndroidCredentialStore) :
    SessionCredentialPort {
    override suspend fun store(profile: MachineProfile, authorization: String): String? {
        val scope =
            (CredentialScope.create(profile.id, profile.origin, profile.credentialGeneration)
                    as? CredentialResult.Success)
                ?.value ?: return null
        return when (vault.store(scope, authorization)) {
            is CredentialResult.Success -> scope.reference
            is CredentialResult.Failure -> null
        }
    }
}

private class AndroidSessionHost(
    private val vault: AndroidCredentialStore,
    private val reads: ReadOnlyV2Transport,
    private val transport: SessionV2Transport,
    private val streams: SessionV2Streams,
) : SessionHostPort {
    override suspend fun destination(profile: MachineProfile): ReadDestination? {
        if (!profile.sharedPasswordAcknowledged) return null
        val scope =
            (CredentialScope.create(profile.id, profile.origin, profile.credentialGeneration)
                    as? CredentialResult.Success)
                ?.value ?: return null
        if (profile.credentialReference != scope.reference) return null
        val secret = (vault.load(scope) as? CredentialResult.Success)?.value ?: return null
        return ReadDestination(
            profile.id,
            profile.origin,
            profile.credentialGeneration,
            secret.authorizationHeader(),
        )
    }

    override suspend fun compatibility(destination: ReadDestination): ReadResult<String> =
        when (val result = transport.compatibility(destination)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value)
            is ReadResult.Failure -> result
        }

    override suspend fun sessions(destination: ReadDestination): ReadResult<List<ScopedSession>> =
        when (val result = reads.sessions(destination)) {
            is ReadResult.Success -> ReadResult.Success(result.value.sessions)
            is ReadResult.Failure -> result
        }

    override suspend fun session(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<ScopedSession> =
        when (val result = transport.session(destination, key)) {
            is ReadResult.Success -> ReadResult.Success(ScopedSession(key, result.value.value))
            is ReadResult.Failure -> result
        }

    override suspend fun active(destination: ReadDestination): ReadResult<Set<SessionKey>> =
        when (val result = transport.active(destination)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value)
            is ReadResult.Failure -> result
        }

    override suspend fun agents(destination: ReadDestination): ReadResult<List<V2AgentSummary>> =
        when (val result = transport.agents(destination)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value.items)
            is ReadResult.Failure -> result
        }

    override suspend fun models(destination: ReadDestination): ReadResult<List<V2ModelSummary>> =
        when (val result = transport.models(destination)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value.items)
            is ReadResult.Failure -> result
        }

    override suspend fun changes(
        destination: ReadDestination,
        key: SessionKey,
        directory: String,
    ): ReadResult<List<VcsFileDiff>> =
        when (val result = transport.changes(destination, key, directory)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value)
            is ReadResult.Failure -> result
        }

    override suspend fun history(
        destination: ReadDestination,
        key: SessionKey,
        after: Long,
    ): ReadResult<TranscriptPage> =
        when (val result = transport.history(destination, key, after)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value)
            is ReadResult.Failure -> result
        }

    override suspend fun permissions(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<List<V2PermissionRequest>> =
        when (val result = transport.permissions(destination, key)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value)
            is ReadResult.Failure -> result
        }

    override suspend fun questions(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<List<V2QuestionRequest>> =
        when (val result = transport.questions(destination, key)) {
            is ReadResult.Success -> ReadResult.Success(result.value.value)
            is ReadResult.Failure -> result
        }

    override suspend fun create(destination: ReadDestination, command: V2CreateSessionCommand) =
        transport.create(destination, command)

    override suspend fun prompt(destination: ReadDestination, command: V2PromptCommand) =
        transport.prompt(destination, command)

    override suspend fun interrupt(destination: ReadDestination, key: SessionKey) =
        transport.interrupt(destination, key)

    override suspend fun permissionReply(
        destination: ReadDestination,
        request: V2PermissionRequest,
        reply: V2PermissionReply,
    ) = transport.permissionReply(destination, request, reply)

    override suspend fun questionReply(
        destination: ReadDestination,
        request: V2QuestionRequest,
        answers: List<List<String>>,
    ) = transport.questionReply(destination, request, answers)

    override suspend fun questionReject(destination: ReadDestination, request: V2QuestionRequest) =
        transport.questionReject(destination, request)

    override fun durable(destination: ReadDestination, key: SessionKey, after: Long) =
        streams.durable(destination, key, after)

    override fun global(destination: ReadDestination) = streams.global(destination)
}
