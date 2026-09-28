package dev.local.opencodecompanion.client.session

import dev.local.opencodecompanion.client.DurableFrame
import dev.local.opencodecompanion.client.MutationResult
import dev.local.opencodecompanion.client.ReadDestination
import dev.local.opencodecompanion.client.ReadFailure
import dev.local.opencodecompanion.client.ReadResult
import dev.local.opencodecompanion.client.ReadScope
import dev.local.opencodecompanion.client.ScopedSession
import dev.local.opencodecompanion.client.SendDestination
import dev.local.opencodecompanion.client.SendState
import dev.local.opencodecompanion.client.StreamResult
import dev.local.opencodecompanion.client.security.RotationBlock
import dev.local.opencodecompanion.client.security.RotationFailure
import dev.local.opencodecompanion.client.security.RotationOutcome
import dev.local.opencodecompanion.client.security.RotationReconciliation
import dev.local.opencodecompanion.client.storage.DraftKey
import dev.local.opencodecompanion.client.storage.DraftSnapshot
import dev.local.opencodecompanion.client.storage.MachineProfile
import dev.local.opencodecompanion.client.storage.StoredOutgoing
import dev.local.opencodecompanion.client.storage.SubmittedDraft
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionId
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
import dev.local.opencodecompanion.protocol.V2RequestCodec
import dev.local.opencodecompanion.protocol.V2SessionSummary
import dev.local.opencodecompanion.protocol.VcsFileDiff
import dev.local.opencodecompanion.protocol.transcript.TranscriptDecode
import dev.local.opencodecompanion.protocol.transcript.TranscriptPage
import dev.local.opencodecompanion.protocol.transcript.TranscriptRecord
import dev.local.opencodecompanion.protocol.transcript.V2Transcript
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorTest {
    @Test
    fun machineSwitchKeepsCollidingSessionDraftsIsolated() = runBlocking {
        val first = profile("first")
        val second = profile("second")
        val store = FakeStore(first, second)
        val host = FakeHost()
        val firstKey = SessionKey(first.id, SessionId("ses_same"))
        val secondKey = SessionKey(second.id, SessionId("ses_same"))
        store.summaries += ScopedSession(firstKey, summary())
        store.summaries += ScopedSession(secondKey, summary())
        store.drafts[DraftKey(firstKey, ProjectKey(first.id, ProjectId("proj")), "/repo")] =
            DraftSnapshot(
                DraftKey(firstKey, ProjectKey(first.id, ProjectId("proj")), "/repo"),
                1,
                "first draft",
                false,
            )
        store.drafts[DraftKey(secondKey, ProjectKey(second.id, ProjectId("proj")), "/repo")] =
            DraftSnapshot(
                DraftKey(secondKey, ProjectKey(second.id, ProjectId("proj")), "/repo"),
                3,
                "second draft",
                false,
            )
        val coordinator = coordinator(store, host)
        try {
            assertEquals(SessionActionResult.Completed, coordinator.initialize())
            assertEquals(SessionActionResult.Completed, coordinator.selectSession(firstKey))
            assertEquals("first draft", coordinator.state.value.draft?.text)
            assertEquals(SessionActionResult.Completed, coordinator.selectMachine(second.id))
            assertEquals(SessionActionResult.Completed, coordinator.selectSession(secondKey))
            assertEquals("second draft", coordinator.state.value.draft?.text)
            assertEquals(secondKey, coordinator.state.value.selectedSession)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun uncertainSendPersistsIntentAndDoesNotResendSameDraft() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_one"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "do work", false)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            val result = coordinator.send()
            assertEquals(SessionActionResult.Stopped(SessionProblem.OutcomeUnknown), result)
            assertEquals(1, host.promptCalls)
            assertEquals(SendState.OutcomeUnknown, store.outgoing.values.single().state)
            assertEquals("do work", store.drafts[draftKey]?.text)
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.OutcomeUnknown),
                coordinator.send(),
            )
            assertEquals(1, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun proven401RejectionKeepsDraftAndDoesNotEnterUnknownRecovery() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "keep this", false)
        host.promptResult = { destination, _ ->
            MutationResult.Rejected(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                ),
                ReadFailure.AuthenticationRequired,
            )
        }
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            assertEquals(
                SessionActionResult.Stopped(
                    SessionProblem.Transport(ReadFailure.AuthenticationRequired)
                ),
                coordinator.send(),
            )
            assertEquals(SendState.Rejected, store.outgoing.values.single().state)
            assertEquals("keep this", store.drafts[draftKey]?.text)
            assertEquals(1, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun notDispatchedKeepsDraftAndExplicitNewSendUsesNewIntent() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "try later", false)
        host.promptResult = { destination, _ ->
            MutationResult.NotDispatched(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                ),
                ReadFailure.DecodeFailure,
            )
        }
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            val expected =
                SessionActionResult.Stopped(SessionProblem.Transport(ReadFailure.DecodeFailure))
            assertEquals(expected, coordinator.send())
            assertEquals(1, host.promptCalls)
            assertEquals(SendState.Rejected, store.outgoing.values.single().state)
            assertEquals("try later", store.drafts[draftKey]?.text)
            assertEquals(expected, coordinator.send())
            assertEquals(2, host.promptCalls)
            assertEquals(2, store.outgoing.keys.toSet().size)
            assertTrue(store.outgoing.values.all { it.state == SendState.Rejected })
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun failedRejectionPersistenceRemainsUnknownAndKeepsDraft() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "do not lose", false)
        store.rejectFails = true
        host.promptResult = { destination, _ ->
            MutationResult.Rejected(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                ),
                ReadFailure.AuthenticationRequired,
            )
        }
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.OutcomeUnknown),
                coordinator.send(),
            )
            assertEquals(SendState.OutcomeUnknown, store.outgoing.values.single().state)
            assertEquals("do not lose", store.drafts[draftKey]?.text)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun explicitAcknowledgementRequiredBeforeCredentialStorage() = runBlocking {
        val store = FakeStore()
        val host = FakeHost()
        var stored = false
        val coordinator =
            SessionCoordinator(
                store,
                host,
                object : SessionCredentialPort {
                    override suspend fun store(
                        profile: MachineProfile,
                        authorization: String,
                    ): String? {
                        stored = true
                        return "vault-ref"
                    }
                },
                FakeRotation(),
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                nextMachineId = { MachineId("new") },
            )
        try {
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.RemoteUseNotApproved),
                coordinator.addMachine("New", "https://example.test/", "Basic fixture", false),
            )
            assertFalse(stored)
            assertEquals(
                SessionActionResult.Completed,
                coordinator.addMachine("New", "https://example.test/", "Basic fixture", true),
            )
            assertTrue(stored)
            assertEquals("vault-ref", store.profiles.single().credentialReference)
            assertTrue(store.profiles.single().sharedPasswordAcknowledged)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun cancellationAfterDispatchLeavesUnknownIntent() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "cancel case", false)
        host.promptGate = CompletableDeferred()
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            val sending = async { coordinator.send() }
            host.promptStarted.await()
            sending.cancelAndJoin()
            assertEquals(1, host.promptCalls)
            assertEquals(SendState.OutcomeUnknown, store.outgoing.values.single().state)
            assertEquals("cancel case", store.drafts[draftKey]?.text)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun lateAcknowledgementAfterMachineSwitchStaysWithCapturedMachine() = runBlocking {
        val first = profile("first")
        val second = profile("second")
        val store = FakeStore(first, second)
        val host = FakeHost()
        val key = SessionKey(first.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(first.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "switch case", false)
        host.promptGate = CompletableDeferred()
        host.promptResult = { destination, command ->
            MutationResult.Acknowledged(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                ),
                V2PromptAdmission(
                    command.messageId,
                    command.sessionKey.sessionId,
                    1,
                    command.delivery.wireValue,
                    command.text,
                    1,
                ),
            )
        }
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            val sending = async { coordinator.send() }
            host.promptStarted.await()
            assertTrue(coordinator.state.value.busy)
            coordinator.selectMachine(second.id)
            assertFalse(coordinator.state.value.busy)
            host.promptGate?.complete(Unit)
            assertEquals(SessionActionResult.Completed, sending.await())
            assertEquals(second.id, coordinator.state.value.selectedMachine)
            assertEquals(null, coordinator.state.value.selectedSession)
            assertEquals(SendState.Admitted, store.outgoing.values.single().state)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun rapidDraftEditsSerializeRevisionBeforeSend() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        val saveGate = CompletableDeferred<Unit>()
        store.saveGate = saveGate
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            val first = async { coordinator.saveDraft("a") }
            store.saveStarted.await()
            val second = async { coordinator.saveDraft("ab") }
            saveGate.complete(Unit)
            assertEquals(SessionActionResult.Completed, first.await())
            assertEquals(SessionActionResult.Completed, second.await())
            assertEquals("ab", store.drafts[draftKey]?.text)
            assertEquals(2L, store.drafts[draftKey]?.revision)
            assertEquals("ab", coordinator.state.value.draft?.text)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun foregroundPollRefreshesPendingAndActiveIndependentlyOfTranscript() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            assertEquals(ConnectionState.Ready, coordinator.state.value.connection)
            host.activeSet = setOf(key)
            host.questionsList = listOf(V2QuestionRequest("que_test", key, emptyList(), null))
            delay(2_100)
            assertEquals(setOf(key), coordinator.state.value.active)
            assertEquals("que_test", coordinator.state.value.questions.single().id)
            assertEquals(0L, coordinator.state.value.transcript?.lastSequence)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun acknowledgedSendWithDraftReloadFailureRemainsAdmitted() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "accepted", false)
        host.promptResult = { destination, command ->
            MutationResult.Acknowledged(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                ),
                V2PromptAdmission(
                    command.messageId,
                    command.sessionKey.sessionId,
                    1,
                    command.delivery.wireValue,
                    command.text,
                    1,
                ),
            )
        }
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            store.failDraftAfterAdmission = true
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.StorageFailure),
                coordinator.send(),
            )
            assertEquals(SendState.Admitted, store.outgoing.values.single().state)
            assertEquals(1, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun editingWhilePromptAwaitsHostKeepsNewerDraftAfterAdmission() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "send this", false)
        host.promptGate = CompletableDeferred()
        host.promptResult = { destination, command ->
            MutationResult.Acknowledged(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                ),
                V2PromptAdmission(
                    command.messageId,
                    command.sessionKey.sessionId,
                    1,
                    command.delivery.wireValue,
                    command.text,
                    1,
                ),
            )
        }
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            val sending = async { coordinator.send() }
            host.promptStarted.await()
            assertEquals(SessionActionResult.Completed, coordinator.saveDraft("newer text"))
            assertEquals("newer text", store.drafts[draftKey]?.text)
            host.promptGate?.complete(Unit)
            assertEquals(SessionActionResult.Completed, sending.await())
            assertEquals("newer text", store.drafts[draftKey]?.text)
            assertEquals("newer text", coordinator.state.value.draft?.text)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun createdSessionOpensItsConversationWhenMachineRemainsSelected() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val created = SessionKey(machine.id, SessionId("ses_same"))
        host.createResult = { destination ->
            MutationResult.Acknowledged(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                ),
                ScopedSession(created, summary()),
            )
        }
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.foreground()
            assertEquals(SessionActionResult.Completed, coordinator.createSession())
            assertEquals(created, coordinator.state.value.selectedSession)
            assertTrue(coordinator.state.value.sessions.any { it.key == created })
            assertFalse(coordinator.state.value.busy)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun doubleTapInterruptDispatchesOnceAndWaitsForAuthoritativeInactive() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        host.activeSet = setOf(key)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            host.interruptGate = CompletableDeferred()
            val first = async { coordinator.interrupt() }
            host.interruptStarted.await()
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.MutationInProgress),
                coordinator.interrupt(),
            )
            host.interruptGate?.complete(Unit)
            assertEquals(SessionActionResult.Stopped(SessionProblem.OutcomeUnknown), first.await())
            assertEquals(1, host.interruptCalls)
            assertTrue(key in coordinator.state.value.interrupting)
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.MutationInProgress),
                coordinator.interrupt(),
            )
            host.activeSet = emptySet()
            delay(2_100)
            assertFalse(key in coordinator.state.value.interrupting)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun doubleTapQuestionReplyDispatchesOnce() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        val request = V2QuestionRequest("que_test", key, emptyList(), null)
        store.summaries += ScopedSession(key, summary())
        host.questionsList = listOf(request)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            host.questionGate = CompletableDeferred()
            val first = async { coordinator.replyQuestion(request, emptyList()) }
            host.questionStarted.await()
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.MutationInProgress),
                coordinator.replyQuestion(request, emptyList()),
            )
            host.questionGate?.complete(Unit)
            first.await()
            assertEquals(1, host.questionReplyCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun staleQuestionIsRefreshedButNeverPosted() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        val request = V2QuestionRequest("que_test", key, emptyList(), null)
        store.summaries += ScopedSession(key, summary())
        host.questionsList = listOf(request)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            assertEquals(request, coordinator.state.value.questions.single())
            host.questionsList = emptyList()
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.RequestStale),
                coordinator.replyQuestion(request, emptyList()),
            )
            assertEquals(0, host.questionReplyCalls)
            assertTrue(coordinator.state.value.questions.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun stalePermissionIsRefreshedButNeverPosted() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        val request =
            V2RequestCodec.permissions(
                    """{"data":[{"id":"per_test","sessionID":"ses_same","action":"read","resources":[]}]}""",
                    key,
                )
                .single()
        store.summaries += ScopedSession(key, summary())
        host.permissionsList = listOf(request)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            assertEquals(request, coordinator.state.value.permissions.single())
            host.permissionsList = emptyList()
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.RequestStale),
                coordinator.replyPermission(request, V2PermissionReply.ONCE),
            )
            assertEquals(0, host.permissionReplyCalls)
            assertTrue(coordinator.state.value.permissions.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun pendingStartupRotationFailsClosedBeforeCompatibilityOrDispatch() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val rotation = FakeRotation()
        rotation.reconciliations =
            listOf(
                RotationReconciliation(
                    machine.id,
                    RotationOutcome.FailClosed(RotationFailure.VaultStateUncertain),
                )
            )
        val coordinator = coordinator(store, host, rotation)
        try {
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.RotationRecoveryRequired),
                coordinator.initialize(),
            )
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.RotationRecoveryRequired),
                coordinator.foreground(),
            )
            assertEquals(0, host.compatibilityCalls)
            assertEquals(0, host.promptCalls)
            assertEquals(
                ConnectionState.Unavailable(SessionProblem.RotationRecoveryRequired),
                coordinator.state.value.connection,
            )
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun replacementCannotOverlapInFlightSendOrRestartItsRecovery() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val rotation = FakeRotation()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 1, "pending", false)
        host.promptGate = CompletableDeferred()
        val coordinator = coordinator(store, host, rotation)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            val sending = async { coordinator.send() }
            host.promptStarted.await()
            val compatibleReads = host.compatibilityCalls
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.MutationInProgress),
                coordinator.replaceCredential(machine.id, "Basic replacement"),
            )
            assertEquals(0, rotation.rotateCalls)
            assertEquals(compatibleReads, host.compatibilityCalls)
            assertEquals(1L, coordinator.state.value.machines.single().credentialGeneration)
            host.promptGate?.complete(Unit)
            // The fixture releases this request with an unknown outcome; rotation must not retry
            // it.
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.OutcomeUnknown),
                sending.await(),
            )
            assertEquals(1, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun replacementWorksAfterAuthFailureAndPreservesDraftWithoutSending() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val rotation = FakeRotation()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 4, "preserved draft", false)
        host.rejectGeneration = machine.credentialGeneration
        rotation.outcome =
            RotationOutcome.Completed(
                machine.copy(credentialGeneration = 2, credentialReference = "vault-ref-2")
            )
        val coordinator = coordinator(store, host, rotation)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            assertEquals(
                ConnectionState.Unavailable(
                    SessionProblem.Transport(ReadFailure.AuthenticationRequired)
                ),
                coordinator.state.value.connection,
            )
            assertEquals(
                SessionActionResult.Completed,
                coordinator.replaceCredential(machine.id, "Basic replacement"),
            )
            assertEquals(2L, coordinator.state.value.machines.single().credentialGeneration)
            assertEquals(ConnectionState.Ready, coordinator.state.value.connection)
            assertEquals("preserved draft", store.drafts[draftKey]?.text)
            assertEquals("preserved draft", coordinator.state.value.draft?.text)
            assertEquals(0, host.promptCalls)
            assertEquals(1, rotation.rotateCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun blockedReplacementPreservesOldProfileAndDraftWithoutDispatch() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val rotation = FakeRotation()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val draftKey = DraftKey(key, ProjectKey(machine.id, ProjectId("proj")), "/repo")
        store.drafts[draftKey] = DraftSnapshot(draftKey, 3, "blocked draft", false)
        rotation.outcome = RotationOutcome.Blocked(RotationBlock.UnresolvedOutgoing)
        val coordinator = coordinator(store, host, rotation)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.RotationBlocked),
                coordinator.replaceCredential(machine.id, "Basic replacement"),
            )
            assertEquals(1L, coordinator.state.value.machines.single().credentialGeneration)
            assertEquals("blocked draft", store.drafts[draftKey]?.text)
            assertEquals(0, host.promptCalls)
            assertEquals(ConnectionState.Ready, coordinator.state.value.connection)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun lateReplacementResultCannotOverwriteNewMachineContext() = runBlocking {
        val first = profile("first")
        val second = profile("second")
        val store = FakeStore(first, second)
        val host = FakeHost()
        val rotation = FakeRotation()
        rotation.outcome =
            RotationOutcome.Completed(
                first.copy(credentialGeneration = 2, credentialReference = "vault-ref-2")
            )
        rotation.gate = CompletableDeferred()
        val coordinator = coordinator(store, host, rotation)
        try {
            coordinator.initialize()
            coordinator.foreground()
            val replacing = async { coordinator.replaceCredential(first.id, "Basic replacement") }
            rotation.started.await()
            coordinator.selectMachine(second.id)
            rotation.gate?.complete(Unit)
            assertEquals(SessionActionResult.Completed, replacing.await())
            assertEquals(second.id, coordinator.state.value.selectedMachine)
            assertEquals(ConnectionState.Ready, coordinator.state.value.connection)
            assertEquals(
                2L,
                coordinator.state.value.machines.first { it.id == first.id }.credentialGeneration,
            )
            assertEquals(0, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun inconsistentReplacementProfileFailsClosedWithoutReconnecting() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val rotation = FakeRotation()
        rotation.outcome =
            RotationOutcome.Completed(
                machine.copy(origin = "https://other.example.test/", credentialGeneration = 2)
            )
        val coordinator = coordinator(store, host, rotation)
        try {
            coordinator.initialize()
            coordinator.foreground()
            val readsBefore = host.compatibilityCalls
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.RotationRecoveryRequired),
                coordinator.replaceCredential(machine.id, "Basic replacement"),
            )
            assertEquals(1L, coordinator.state.value.machines.single().credentialGeneration)
            assertEquals(
                ConnectionState.Unavailable(SessionProblem.RotationRecoveryRequired),
                coordinator.state.value.connection,
            )
            assertEquals(readsBefore, host.compatibilityCalls)
            assertEquals(0, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun explicitRecheckReadsHostAndReleasesOnlySelectedRetryBlockers() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        val request = V2QuestionRequest("que_test", key, emptyList(), null)
        store.summaries += ScopedSession(key, summary())
        host.activeSet = setOf(key)
        host.questionsList = listOf(request)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            coordinator.interrupt()
            coordinator.replyQuestion(request, emptyList())
            assertTrue(key in coordinator.state.value.interrupting)
            assertTrue(request.id in coordinator.state.value.settlingRequestIds)
            assertEquals(SessionActionResult.Completed, coordinator.recheckPendingActions())
            assertFalse(key in coordinator.state.value.interrupting)
            assertFalse(request.id in coordinator.state.value.settlingRequestIds)
            assertEquals(setOf(key), coordinator.state.value.active)
            assertEquals(request, coordinator.state.value.questions.single())
            assertEquals(1, host.interruptCalls)
            assertEquals(1, host.questionReplyCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun failedRecheckRetainsRetryBlockers() = runBlocking {
        val machine = profile("first")
        val store = FakeStore(machine)
        val host = FakeHost()
        val key = SessionKey(machine.id, SessionId("ses_same"))
        val request = V2QuestionRequest("que_test", key, emptyList(), null)
        store.summaries += ScopedSession(key, summary())
        host.activeSet = setOf(key)
        host.questionsList = listOf(request)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            coordinator.interrupt()
            coordinator.replyQuestion(request, emptyList())
            host.failQuestions = true
            assertEquals(
                SessionActionResult.Stopped(
                    SessionProblem.Transport(ReadFailure.TransportUnavailable)
                ),
                coordinator.recheckPendingActions(),
            )
            assertTrue(key in coordinator.state.value.interrupting)
            assertTrue(request.id in coordinator.state.value.settlingRequestIds)
            assertEquals(1, host.interruptCalls)
            assertEquals(1, host.questionReplyCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun oldMachineRecheckResponseCannotClearNewSelectionOrOldBlocker() = runBlocking {
        val first = profile("first")
        val second = profile("second")
        val store = FakeStore(first, second)
        val host = FakeHost()
        val key = SessionKey(first.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        host.activeSet = setOf(key)
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            coordinator.interrupt()
            assertTrue(key in coordinator.state.value.interrupting)
            host.activeGate = CompletableDeferred()
            host.activeStarted = CompletableDeferred()
            val recheck = async { coordinator.recheckPendingActions() }
            host.activeStarted.await()
            coordinator.selectMachine(second.id)
            host.activeGate?.complete(Unit)
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.DestinationChanged),
                recheck.await(),
            )
            coordinator.selectMachine(first.id)
            assertTrue(key in coordinator.state.value.interrupting)
            assertEquals(1, host.interruptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun suspendedDestinationCannotInterruptNewlySelectedMachine() = runBlocking {
        val first = profile("first")
        val second = profile("second")
        val store = FakeStore(first, second)
        val host = FakeHost()
        val key = SessionKey(first.id, SessionId("ses_same"))
        store.summaries += ScopedSession(key, summary())
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            host.destinationGate = CompletableDeferred()
            host.destinationStarted = CompletableDeferred()
            val pending = async { coordinator.interrupt() }
            host.destinationStarted.await()
            coordinator.selectMachine(second.id)
            host.destinationGate?.complete(Unit)
            assertEquals(
                SessionActionResult.Stopped(SessionProblem.DestinationChanged),
                pending.await(),
            )
            assertEquals(0, host.interruptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun stalledDurableStreamConvergesThroughHistoryAndIdleRemainsReady() = runBlocking {
        val profile = profile("first")
        val key = SessionKey(profile.id, SessionId("ses_same"))
        val store = FakeStore(profile)
        val host = FakeHost()
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            withTimeout(3_000) { host.durableStarted.await() }
            host.historyRecords = listOf(admissionRecord(key, 1))
            withTimeout(5_000) {
                while (coordinator.state.value.transcript?.lastSequence != 1L) delay(10)
            }
            assertEquals(ConnectionState.Ready, coordinator.state.value.connection)
            assertEquals(1L, store.cursor(key))
            val reconciled = host.historyCalls.get()
            withTimeout(5_000) { while (host.historyCalls.get() <= reconciled) delay(10) }
            assertEquals(ConnectionState.Ready, coordinator.state.value.connection)
            assertEquals(1, coordinator.state.value.transcript?.seen?.size)
            assertEquals(0, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun failedHistoryReconciliationCannotRemainReadyAndCancelsBothStreams() = runBlocking {
        val profile = profile("first")
        val key = SessionKey(profile.id, SessionId("ses_same"))
        val host = FakeHost()
        val coordinator = coordinator(FakeStore(profile), host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            withTimeout(3_000) {
                host.durableStarted.await()
                host.globalStarted.await()
            }
            host.failHistory = true
            withTimeout(5_000) {
                host.durableCancelled.await()
                host.globalCancelled.await()
            }
            assertEquals(
                ConnectionState.Unavailable(
                    SessionProblem.Transport(ReadFailure.TransportUnavailable)
                ),
                coordinator.state.value.connection,
            )
            assertEquals(0, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun historyPageAndConcurrentDurableEventCommitOneMonotonicProjection() = runBlocking {
        val profile = profile("first")
        val key = SessionKey(profile.id, SessionId("ses_same"))
        val store = FakeStore(profile)
        val host = FakeHost()
        val coordinator = coordinator(store, host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            withTimeout(3_000) { host.durableStarted.await() }
            val first = admissionRecord(key, 1)
            val gate = CompletableDeferred<Unit>()
            host.historyGate = gate
            withTimeout(5_000) { host.historyStarted.await() }
            host.durableFrames.send(DurableFrame(first.rawJson, first.decoded))
            withTimeout(2_000) { host.durableEmitting.await() }
            assertNull(withTimeoutOrNull(150) { host.durableEmitted.await() })
            assertEquals(0L, store.cursor(key))
            host.historyRecords = listOf(first, admissionRecord(key, 2))
            gate.complete(Unit)
            withTimeout(2_000) { host.durableEmitted.await() }
            withTimeout(5_000) {
                while (coordinator.state.value.transcript?.lastSequence != 2L) delay(10)
            }
            assertEquals(2L, store.cursor(key))
            assertEquals(listOf(1L, 2L), coordinator.state.value.transcript?.seen?.keys?.toList())
            assertEquals(ConnectionState.Ready, coordinator.state.value.connection)
            assertEquals(0, host.promptCalls)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun backgroundCancelsHistoryFetchHoldingJournalLockAndWaitingDurableCommit() = runBlocking {
        val profile = profile("first")
        val key = SessionKey(profile.id, SessionId("ses_same"))
        val host = FakeHost()
        val coordinator = coordinator(FakeStore(profile), host)
        try {
            coordinator.initialize()
            coordinator.selectSession(key)
            coordinator.foreground()
            withTimeout(3_000) {
                host.durableStarted.await()
                host.globalStarted.await()
            }
            host.historyGate = CompletableDeferred()
            withTimeout(5_000) { host.historyStarted.await() }
            val first = admissionRecord(key, 1)
            host.durableFrames.send(DurableFrame(first.rawJson, first.decoded))
            withTimeout(2_000) { host.durableEmitting.await() }
            coordinator.background()
            withTimeout(2_000) {
                host.historyCancelled.await()
                host.durableCancelled.await()
                host.globalCancelled.await()
            }
            assertEquals(ConnectionState.Cached, coordinator.state.value.connection)
            assertFalse(coordinator.state.value.foreground)
            assertEquals(0L, coordinator.state.value.transcript?.lastSequence)
        } finally {
            coordinator.close()
        }
    }

    private fun admissionRecord(key: SessionKey, sequence: Int): TranscriptRecord {
        val raw =
            """{"id":"evt_$sequence","type":"session.next.prompt.admitted","durable":{"aggregateID":"${key.sessionId.value}","seq":$sequence,"version":1},"data":{"timestamp":1,"sessionID":"${key.sessionId.value}","messageID":"msg_$sequence","prompt":{"text":"Synthetic"},"delivery":"steer"}}"""
        return TranscriptRecord(raw, V2Transcript.event(raw, key))
    }

    private fun coordinator(
        store: FakeStore,
        host: FakeHost,
        rotation: FakeRotation = FakeRotation(),
    ): SessionCoordinator {
        var nextId = 0
        return SessionCoordinator(
            store,
            host,
            object : SessionCredentialPort {
                override suspend fun store(profile: MachineProfile, authorization: String) =
                    "vault-ref"
            },
            rotation,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nextMessageId = { "msg_" + (++nextId).toString().padStart(32, '0') },
        )
    }

    private class FakeRotation : SessionRotationPort {
        var reconciliations: List<RotationReconciliation> = emptyList()
        var outcome: RotationOutcome? = null
        var rotateCalls = 0
        var reconcileCalls = 0
        val started = CompletableDeferred<Unit>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun rotate(
            machineId: MachineId,
            expectedOrigin: String,
            expectedGeneration: Long,
            newAuthorization: String,
        ): RotationOutcome {
            rotateCalls++
            started.complete(Unit)
            gate?.await()
            return outcome ?: RotationOutcome.FailClosed(RotationFailure.StorageFailure)
        }

        override suspend fun reconcilePending(): List<RotationReconciliation> {
            reconcileCalls++
            return reconciliations
        }
    }

    private fun profile(id: String) =
        MachineProfile(MachineId(id), id, "https://$id.example.test/", "vault-ref", 1, true)

    private fun summary() =
        V2SessionSummary(
            SessionId("ses_same"),
            ProjectId("proj"),
            "/repo",
            null,
            null,
            "Session",
            1,
            1,
        )

    private class FakeStore(vararg initial: MachineProfile) : SessionStorePort {
        val profiles = initial.toMutableList()
        val summaries = mutableListOf<ScopedSession>()
        val drafts = mutableMapOf<DraftKey, DraftSnapshot>()
        val outgoing = mutableMapOf<String, StoredOutgoing>()
        val saveStarted = CompletableDeferred<Unit>()
        var saveGate: CompletableDeferred<Unit>? = null
        var failDraftAfterAdmission = false
        var rejectFails = false

        override suspend fun machines() = profiles.toList()

        override suspend fun putMachine(profile: MachineProfile) {
            profiles += profile
        }

        override suspend fun sessionSummaries(scope: ReadScope) =
            summaries.filter { it.key.machineId == scope.machineId }

        override suspend fun putSessionSummary(scope: ReadScope, session: ScopedSession) {
            summaries.removeAll { it.key == session.key }
            summaries += session
        }

        override suspend fun draft(key: DraftKey): DraftSnapshot? {
            if (failDraftAfterAdmission && outgoing.values.any { it.state == SendState.Admitted })
                error("synthetic draft reload failure")
            return drafts[key]
        }

        override suspend fun saveDraft(
            key: DraftKey,
            expectedRevision: Long,
            text: String,
        ): DraftSnapshot? {
            val gate = saveGate
            if (gate != null) {
                saveGate = null
                saveStarted.complete(Unit)
                gate.await()
            }
            if ((drafts[key]?.revision ?: 0) != expectedRevision) return null
            return DraftSnapshot(key, expectedRevision + 1, text, false).also { drafts[key] = it }
        }

        override suspend fun unresolvedOutgoing(machine: MachineId) =
            outgoing.values.filter {
                it.destination.session.machineId == machine &&
                    it.state in
                        setOf(SendState.Prepared, SendState.Dispatching, SendState.OutcomeUnknown)
            }

        override suspend fun prepareIntent(
            id: String,
            destination: SendDestination,
            text: String,
            delivery: V2Delivery,
            draft: DraftSnapshot?,
        ): Boolean {
            if (id in outgoing) return false
            outgoing[id] =
                StoredOutgoing(
                    id,
                    destination,
                    profiles.first { it.id == destination.session.machineId }.origin,
                    text,
                    SendState.Prepared,
                    delivery,
                    draft?.let { SubmittedDraft(it.key, it.revision) },
                )
            return true
        }

        override suspend fun beginDispatch(machine: MachineId, id: String): Boolean {
            val old = outgoing[id] ?: return false
            outgoing[id] = old.copy(state = SendState.Dispatching)
            return true
        }

        override suspend fun markOutcomeUnknown(machine: MachineId, id: String): Boolean {
            val old = outgoing[id] ?: return false
            outgoing[id] = old.copy(state = SendState.OutcomeUnknown)
            return true
        }

        override suspend fun recordRejectionAfterProof(machine: MachineId, id: String): Boolean {
            if (rejectFails) return false
            val old = outgoing[id] ?: return false
            if (old.destination.session.machineId != machine || old.state != SendState.Dispatching)
                return false
            outgoing[id] = old.copy(state = SendState.Rejected)
            return true
        }

        override suspend fun acknowledgeAfterProof(
            machine: MachineId,
            id: String,
            admission: V2PromptAdmission,
        ): Boolean {
            val old = outgoing[id] ?: return false
            if (old.id != admission.id || old.promptText != admission.promptText) return false
            outgoing[id] = old.copy(state = SendState.Admitted)
            old.submittedDraft?.let { submitted ->
                val current = drafts[submitted.key]
                if (current?.revision == submitted.revision)
                    drafts[submitted.key] =
                        current.copy(revision = current.revision + 1, text = "", cleared = true)
            }
            return true
        }

        private val events = mutableMapOf<SessionKey, java.util.SortedMap<Long, String>>()

        override suspend fun cursor(key: SessionKey) = events[key]?.keys?.lastOrNull() ?: 0L

        override suspend fun journal(key: SessionKey, after: Long, limit: Int) =
            events[key].orEmpty().filterKeys { it > after }.values.take(limit)

        override suspend fun commitEvents(
            key: SessionKey,
            expectedCursor: Long,
            rawEvents: List<String>,
        ): Long {
            val rows = events.getOrPut(key) { sortedMapOf() }
            val decoded =
                rawEvents.associateBy {
                    (V2Transcript.event(it, key) as TranscriptDecode.Supported).event.sequence
                }
            val current = if (rows.isEmpty()) 0L else rows.lastKey()
            check(current == expectedCursor || decoded.all { (seq, raw) -> rows[seq] == raw })
            for ((sequence, raw) in decoded) {
                check(rows[sequence] == null || rows[sequence] == raw)
                rows[sequence] = raw
            }
            return if (rows.isEmpty()) 0L else rows.lastKey()
        }
    }

    private inner class FakeHost : SessionHostPort {
        @Volatile var historyRecords: List<TranscriptRecord> = emptyList()
        @Volatile var failHistory = false
        @Volatile var historyGate: CompletableDeferred<Unit>? = null
        val historyStarted = CompletableDeferred<Unit>()
        val historyCancelled = CompletableDeferred<Unit>()
        val historyCalls = AtomicInteger()
        val durableFrames = Channel<DurableFrame>(Channel.UNLIMITED)
        val durableStarted = CompletableDeferred<Unit>()
        val durableEmitting = CompletableDeferred<Unit>()
        val durableEmitted = CompletableDeferred<Unit>()
        val globalStarted = CompletableDeferred<Unit>()
        val durableCancelled = CompletableDeferred<Unit>()
        val globalCancelled = CompletableDeferred<Unit>()
        var promptCalls = 0
        var interruptCalls = 0
        var questionReplyCalls = 0
        var permissionReplyCalls = 0
        var permissionsList: List<V2PermissionRequest> = emptyList()
        var destinationStarted = CompletableDeferred<Unit>()
        var destinationGate: CompletableDeferred<Unit>? = null
        val interruptStarted = CompletableDeferred<Unit>()
        var interruptGate: CompletableDeferred<Unit>? = null
        val questionStarted = CompletableDeferred<Unit>()
        var questionGate: CompletableDeferred<Unit>? = null
        var activeSet: Set<SessionKey> = emptySet()
        var activeStarted = CompletableDeferred<Unit>()
        var activeGate: CompletableDeferred<Unit>? = null
        var failQuestions = false
        var rejectGeneration: Long? = null
        var compatibilityCalls = 0
        var questionsList: List<V2QuestionRequest> = emptyList()
        val promptStarted = CompletableDeferred<Unit>()
        var promptGate: CompletableDeferred<Unit>? = null
        var promptResult:
            ((ReadDestination, V2PromptCommand) -> MutationResult<V2PromptAdmission>)? =
            null
        var createResult: ((ReadDestination) -> MutationResult<ScopedSession>)? = null

        override suspend fun destination(profile: MachineProfile): ReadDestination {
            destinationStarted.complete(Unit)
            destinationGate?.await()
            return ReadDestination(
                profile.id,
                profile.origin,
                profile.credentialGeneration,
                "Basic fixture",
            )
        }

        override suspend fun compatibility(destination: ReadDestination): ReadResult<String> {
            compatibilityCalls++
            return if (destination.credentialGeneration == rejectGeneration)
                ReadResult.Failure(ReadFailure.AuthenticationRequired)
            else ReadResult.Success("1.18.32")
        }

        override suspend fun sessions(destination: ReadDestination) =
            ReadResult.Success(emptyList<ScopedSession>())

        override suspend fun session(destination: ReadDestination, key: SessionKey) =
            ReadResult.Success(ScopedSession(key, summary()))

        override suspend fun active(destination: ReadDestination): ReadResult<Set<SessionKey>> {
            activeStarted.complete(Unit)
            activeGate?.await()
            return ReadResult.Success(activeSet)
        }

        override suspend fun agents(destination: ReadDestination) =
            ReadResult.Success(emptyList<V2AgentSummary>())

        override suspend fun models(destination: ReadDestination) =
            ReadResult.Success(emptyList<V2ModelSummary>())

        override suspend fun changes(
            destination: ReadDestination,
            key: SessionKey,
            directory: String,
        ) = ReadResult.Success(emptyList<VcsFileDiff>())

        override suspend fun history(
            destination: ReadDestination,
            key: SessionKey,
            after: Long,
        ): ReadResult<TranscriptPage> {
            historyCalls.incrementAndGet()
            historyGate?.let {
                historyStarted.complete(Unit)
                try {
                    it.await()
                } finally {
                    historyCancelled.complete(Unit)
                }
            }
            val records =
                historyRecords.filter {
                    (it.decoded as TranscriptDecode.Supported).event.sequence > after
                }
            return if (failHistory) ReadResult.Failure(ReadFailure.TransportUnavailable)
            else ReadResult.Success(TranscriptPage(records, false))
        }

        override suspend fun permissions(destination: ReadDestination, key: SessionKey) =
            ReadResult.Success(permissionsList)

        override suspend fun questions(destination: ReadDestination, key: SessionKey) =
            if (failQuestions) ReadResult.Failure(ReadFailure.TransportUnavailable)
            else ReadResult.Success(questionsList)

        override suspend fun create(
            destination: ReadDestination,
            command: V2CreateSessionCommand,
        ): MutationResult<ScopedSession> =
            createResult?.invoke(destination)
                ?: MutationResult.OutcomeUnknown(
                    ReadScope(
                        destination.machineId,
                        destination.origin.toString(),
                        destination.credentialGeneration,
                    )
                )

        override suspend fun prompt(
            destination: ReadDestination,
            command: V2PromptCommand,
        ): MutationResult<V2PromptAdmission> {
            promptCalls++
            promptStarted.complete(Unit)
            promptGate?.await()
            return promptResult?.invoke(destination, command)
                ?: MutationResult.OutcomeUnknown(
                    ReadScope(
                        destination.machineId,
                        destination.origin.toString(),
                        destination.credentialGeneration,
                    )
                )
        }

        override suspend fun interrupt(
            destination: ReadDestination,
            key: SessionKey,
        ): MutationResult<Unit> {
            interruptCalls++
            interruptStarted.complete(Unit)
            interruptGate?.await()
            return MutationResult.OutcomeUnknown(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                )
            )
        }

        override suspend fun permissionReply(
            destination: ReadDestination,
            request: V2PermissionRequest,
            reply: V2PermissionReply,
        ): MutationResult<Unit> {
            permissionReplyCalls++
            return MutationResult.OutcomeUnknown(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                )
            )
        }

        override suspend fun questionReply(
            destination: ReadDestination,
            request: V2QuestionRequest,
            answers: List<List<String>>,
        ): MutationResult<Unit> {
            questionReplyCalls++
            questionStarted.complete(Unit)
            questionGate?.await()
            return MutationResult.OutcomeUnknown(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                )
            )
        }

        override suspend fun questionReject(
            destination: ReadDestination,
            request: V2QuestionRequest,
        ): MutationResult<Unit> =
            MutationResult.OutcomeUnknown(
                ReadScope(
                    destination.machineId,
                    destination.origin.toString(),
                    destination.credentialGeneration,
                )
            )

        override fun durable(
            destination: ReadDestination,
            key: SessionKey,
            after: Long,
        ): Flow<StreamResult<DurableFrame>> = flow {
            durableStarted.complete(Unit)
            try {
                for (frame in durableFrames) {
                    durableEmitting.complete(Unit)
                    emit(
                        StreamResult.Item(
                            ReadScope(
                                destination.machineId,
                                destination.origin.toString(),
                                destination.credentialGeneration,
                            ),
                            frame,
                        )
                    )
                    durableEmitted.complete(Unit)
                }
            } finally {
                durableCancelled.complete(Unit)
            }
        }

        override fun global(destination: ReadDestination): Flow<StreamResult<V2GlobalEvent>> =
            flow {
                globalStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    globalCancelled.complete(Unit)
                }
            }
    }
}
