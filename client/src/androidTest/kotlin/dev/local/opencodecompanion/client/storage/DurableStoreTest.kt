package dev.local.opencodecompanion.client.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableStoreTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var store: DurableStore
    private val first = MachineId("machine-a")
    private val second = MachineId("machine-b")

    @Before
    fun open() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "durable-${UUID.randomUUID()}.db"
        store = DurableStore.open(context, name)
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    @Test
    fun machineCollisionAndDraftRevisionTombstonePreventOldCleanup() = runBlocking {
        store.putMachine(profile(first))
        store.putMachine(profile(second))
        val a = draftKey(first)
        val b = draftKey(second)
        val old = requireNotNull(store.saveDraft(a, 0, "old"))
        val other = requireNotNull(store.saveDraft(b, 0, "other machine"))
        assertEquals(1L, old.revision)
        assertFalse(store.compareAndClearDraft(a, 2))
        val newer = requireNotNull(store.saveDraft(a, old.revision, "new edit"))
        assertFalse(store.compareAndClearDraft(a, old.revision))
        assertEquals("new edit", store.draft(a)?.text)
        assertTrue(store.compareAndClearDraft(a, newer.revision))
        val tombstone = requireNotNull(store.draft(a))
        assertTrue(tombstone.cleared)
        val recreated = requireNotNull(store.saveDraft(a, tombstone.revision, "after clear"))
        assertNotEquals(old.revision, recreated.revision)
        assertFalse(store.compareAndClearDraft(a, old.revision))
        assertEquals("after clear", store.draft(a)?.text)
        assertEquals(other, store.draft(b))
    }

    @Test
    fun preparedDispatchIsSingleUseAndRotationBlocksStaleDestination() = runBlocking {
        store.putMachine(profile(first))
        val destination = destination(first)
        assertTrue(store.prepareIntent("msg_one", destination, "hello"))
        assertFalse(store.prepareIntent("msg_one", destination, "different payload"))
        assertTrue(store.beginDispatch(first, "msg_one"))
        assertFalse(store.beginDispatch(first, "msg_one"))
        assertTrue(store.recordAdmissionAfterProof(first, "msg_one"))
        assertFalse(store.recordRejectionAfterProof(first, "msg_one"))
        assertTrue(store.finalizeBookkeeping(first, "msg_one"))
        assertTrue(store.finalizeBookkeeping(first, "msg_one"))
        assertFalse(store.beginDispatch(first, "msg_one"))
        assertEquals(SendState.Finalized, store.outgoing(first, "msg_one")?.state)

        assertTrue(store.prepareIntent("msg_stale", destination, "pending"))
        val rotation =
            PendingCredentialRotation(
                first,
                "https://fixture.example/",
                1,
                "credential:${first.value}",
                "https://fixture.example/",
                2,
                "credential:${first.value}",
            )
        assertFalse(store.beginCredentialRotation(rotation))
        assertTrue(store.beginDispatch(first, "msg_stale"))
        assertTrue(store.recordRejectionAfterProof(first, "msg_stale"))
        assertTrue(store.finalizeBookkeeping(first, "msg_stale"))
        assertTrue(store.beginCredentialRotation(rotation))
        assertTrue(store.finishCredentialRotation(rotation))
        assertFalse(store.beginDispatch(first, "msg_stale"))
        assertEquals(SendState.Finalized, store.outgoing(first, "msg_stale")?.state)
    }

    @Test
    fun reopenTurnsUncertainDispatchIntoUnknownWithoutResend() = runBlocking {
        store.putMachine(profile(first))
        assertTrue(store.prepareIntent("msg_crash", destination(first), "may have arrived"))
        assertTrue(store.beginDispatch(first, "msg_crash"))
        store.close()
        store = DurableStore.open(context, name)
        assertEquals(SendState.OutcomeUnknown, store.outgoing(first, "msg_crash")?.state)
        assertFalse(store.beginDispatch(first, "msg_crash"))
        assertTrue(store.recordAdmissionAfterProof(first, "msg_crash"))
        assertTrue(store.finalizeBookkeeping(first, "msg_crash"))
        assertEquals(SendState.Finalized, store.outgoing(first, "msg_crash")?.state)
    }

    @Test
    fun repeatedCloseCannotReleaseAReopenedStore() = runBlocking {
        store.putMachine(profile(first))
        val old = store
        old.close()
        store = DurableStore.open(context, name)
        old.close()
        expectFailure { DurableStore.open(context, name).close() }
        assertTrue(store.prepareIntent("msg_live", destination(first), "still owned"))
        assertTrue(store.beginDispatch(first, "msg_live"))
        assertEquals(SendState.Dispatching, store.outgoing(first, "msg_live")?.state)
    }

    @Test
    fun cancellationBeforeCallerResumesReleasesAcquiredDatabase() = runBlocking {
        store.close()
        val caller = QueuedDispatcher()
        val delivered = AtomicReference<DurableStore?>()
        val opening = launch(caller) { delivered.set(DurableStore.open(context, name)) }
        caller.next().run() // Start the coroutine; open suspends onto IO.
        val returnToCaller = caller.next() // IO acquired the store; caller has not resumed.
        opening.cancel()
        returnToCaller.run()
        while (!opening.isCompleted) caller.next().run()
        assertEquals(null, delivered.get())
        store = DurableStore.open(context, name)
    }

    @Test
    fun capturedReadTranscriptCommitsAcrossPagesAndSurvivesReopen() = runBlocking {
        val source =
            context.assets.open("opencode/1.18.32/read-history.json").bufferedReader().use {
                it.readText()
            }
        val data = JSONObject(source).getJSONArray("data")
        val raw = (0 until data.length()).map { data.getJSONObject(it).toString() }
        val key = SessionKey(first, SessionId("ses_f1e761fe5ffeMbGHzT2Ys6zrqo"))
        assertEquals(5L, store.commitEvents(key, 0, raw.take(5)))
        assertEquals(12L, store.commitEvents(key, 5, raw.drop(5)))
        store.close()
        store = DurableStore.open(context, name)
        assertEquals(12L, store.cursor(key))
        assertEquals((1L..12L).toList(), store.journal(key).map { it.sequence })
        assertEquals(12L, store.commitEvents(key, 5, raw.drop(5)))
        assertEquals(12, store.journal(key).size)
    }

    @Test
    fun journalAndCursorRollbackTogetherOnConflictAndUnknownEvent() = runBlocking {
        val key = SessionKey(first, SessionId("ses_same"))
        val other = SessionKey(second, SessionId("ses_same"))
        store.putMachine(profile(first))
        store.putMachine(profile(second))
        assertEquals(1L, store.commitEvents(key, 0, listOf(event(1, "evt_first", "msg_first"))))
        assertEquals(1L, store.commitEvents(other, 0, listOf(event(1, "evt_first", "msg_first"))))
        val reordered =
            """{"data":{"delivery":"steer","prompt":{"text":"fixture"},"messageID":"msg_first","sessionID":"ses_same","timestamp":1},"durable":{"version":1,"seq":1,"aggregateID":"ses_same"},"type":"session.next.prompt.admitted","id":"evt_first"}"""
        assertEquals(1L, store.commitEvents(key, 0, listOf(reordered)))
        assertEquals(1L, store.commitEvents(key, 1, listOf("  $reordered  ")))
        assertEquals(1, store.journal(key).size)
        assertEquals(1, store.journal(other).size)

        expectFailure {
            store.commitEvents(
                key,
                1,
                listOf(event(2, "evt_dup", "msg_second"), event(3, "evt_dup", "msg_third")),
            )
        }
        assertEquals(1L, store.cursor(key))
        assertEquals(1, store.journal(key).size)
        expectFailure {
            store.commitEvents(
                key,
                1,
                listOf(
                    event(2, "evt_unknown", "msg_next")
                        .replace("session.next.prompt.admitted", "future.event")
                ),
            )
        }
        assertEquals(1L, store.cursor(key))
        expectFailure { store.commitEvents(key, 1, listOf(event(3, "evt_gap", "msg_gap"))) }
        assertEquals(1L, store.cursor(key))
        assertEquals(1L, store.commitEvents(key, 0, listOf(event(1, "evt_first", "msg_first"))))
        assertEquals(1, store.journal(key).size)
        expectFailure { store.commitEvents(key, 0, listOf(event(1, "evt_first", "msg_changed"))) }
        assertEquals(1L, store.cursor(key))
    }

    @Test
    fun summariesAndUnresolvedIntentsRemainMachineAndCredentialScoped() = runBlocking {
        store.putMachine(profile(first))
        store.putMachine(profile(second))
        val firstScope = ReadScope(first, "https://fixture.example/", 1)
        val secondScope = ReadScope(second, "https://fixture.example/", 1)
        val summary =
            V2SessionSummary(
                SessionId("ses_same"),
                ProjectId("global"),
                "/fixture/repo",
                null,
                null,
                "Fixture",
                1,
                2,
            )
        assertTrue(store.putSessionSummary(firstScope, destination(first).session, summary))
        assertTrue(store.putSessionSummary(secondScope, destination(second).session, summary))
        assertEquals(2, store.machines().size)
        assertEquals(1, store.sessionSummaries(firstScope).size)
        assertEquals(1, store.sessionSummaries(secondScope).size)
        assertFalse(
            store.putSessionSummary(
                firstScope.copy(origin = "https://elsewhere.example/"),
                destination(first).session,
                summary,
            )
        )
        assertTrue(store.sessionSummaries(firstScope.copy(credentialGeneration = 2)).isEmpty())

        assertTrue(
            store.prepareIntent("msg_first", destination(first), "first", V2Delivery.Steer, null)
        )
        assertTrue(
            store.prepareIntent("msg_second", destination(second), "second", V2Delivery.Queue, null)
        )
        assertEquals(listOf("msg_first"), store.unresolvedOutgoing(first).map { it.id })
        assertEquals(V2Delivery.Queue, store.unresolvedOutgoing(second).single().delivery)
    }

    @Test
    fun acknowledgementChecksHostIdentityAndOnlyClearsMatchingDraftRevision() = runBlocking {
        store.putMachine(profile(first))
        val key = draftKey(first)
        val old = requireNotNull(store.saveDraft(key, 0, "first text"))
        assertTrue(
            store.prepareIntent("msg_first", destination(first), old.text, V2Delivery.Steer, old)
        )
        assertTrue(store.beginDispatch(first, "msg_first"))
        val new = requireNotNull(store.saveDraft(key, old.revision, "new text"))
        assertFalse(
            store.acknowledgeAfterProof(first, "msg_first", admission("msg_wrong", "first text"))
        )
        assertFalse(
            store.acknowledgeAfterProof(second, "msg_first", admission("msg_first", "first text"))
        )
        assertTrue(
            store.acknowledgeAfterProof(first, "msg_first", admission("msg_first", "first text"))
        )
        assertEquals(SendState.Admitted, store.outgoing(first, "msg_first")?.state)
        assertEquals(new, store.draft(key))
        assertTrue(store.finalizeBookkeeping(first, "msg_first"))
        assertTrue(store.unresolvedOutgoing(first).isEmpty())

        assertTrue(
            store.prepareIntent("msg_second", destination(first), new.text, V2Delivery.Steer, new)
        )
        assertTrue(store.beginDispatch(first, "msg_second"))
        assertTrue(
            store.acknowledgeAfterProof(first, "msg_second", admission("msg_second", new.text))
        )
        assertTrue(requireNotNull(store.draft(key)).cleared)
        assertFalse(
            store.acknowledgeAfterProof(first, "msg_second", admission("msg_second", "different"))
        )
    }

    @Test
    fun pendingRotationSurvivesReopenAndClearsOnlyOldMachineCache() = runBlocking {
        store.putMachine(profile(first))
        store.putMachine(profile(second))
        val firstScope = ReadScope(first, "https://fixture.example/", 1)
        val secondScope = ReadScope(second, "https://fixture.example/", 1)
        val summary =
            V2SessionSummary(
                SessionId("ses_same"),
                ProjectId("global"),
                "/fixture/repo",
                null,
                null,
                "Fixture",
                1,
                2,
            )
        store.putSessionSummary(firstScope, destination(first).session, summary)
        store.putSessionSummary(secondScope, destination(second).session, summary)
        val pending =
            PendingCredentialRotation(
                first,
                "https://fixture.example/",
                1,
                "credential:${first.value}",
                "https://fixture.example/",
                2,
                "credential:${first.value}",
            )
        assertTrue(store.beginCredentialRotation(pending))
        store.close()
        store = DurableStore.open(context, name)
        assertEquals(listOf(pending), store.pendingCredentialRotations())
        assertTrue(store.finishCredentialRotation(pending))
        assertEquals("https://fixture.example/", store.machine(first)?.origin)
        assertTrue(store.sessionSummaries(firstScope).isEmpty())
        assertEquals(1, store.sessionSummaries(secondScope).size)
        assertTrue(store.pendingCredentialRotations().isEmpty())
        val crossOrigin =
            pending.copy(oldGeneration = 2, newGeneration = 3, newOrigin = "https://new.example/")
        try {
            store.beginCredentialRotation(crossOrigin)
            org.junit.Assert.fail("Expected new origin to require a new machine identity")
        } catch (_: IllegalArgumentException) {
            assertTrue(store.pendingCredentialRotations().isEmpty())
            assertEquals(2L, store.machine(first)?.credentialGeneration)
        }
    }

    private fun admission(id: String, text: String) =
        V2PromptAdmission(id, SessionId("ses_same"), 1, "steer", text, 1)

    private fun profile(machine: MachineId, generation: Long = 1) =
        MachineProfile(
            machine,
            machine.value,
            "https://fixture.example/",
            "credential:${machine.value}",
            generation,
        )

    private fun destination(machine: MachineId) =
        SendDestination(
            SessionKey(machine, SessionId("ses_same")),
            ProjectKey(machine, ProjectId("global")),
            "/fixture/repo",
            1,
        )

    private fun draftKey(machine: MachineId) =
        DraftKey(
            SessionKey(machine, SessionId("ses_same")),
            ProjectKey(machine, ProjectId("global")),
            "/fixture/repo",
        )

    /** Synthetic supported admission shape; no host or credential is accessed. */
    private fun event(seq: Int, id: String, message: String) =
        """{"id":"$id","type":"session.next.prompt.admitted","durable":{"aggregateID":"ses_same","seq":$seq,"version":1},"data":{"timestamp":1,"sessionID":"ses_same","messageID":"$message","prompt":{"text":"fixture"},"delivery":"steer"}}"""

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("Expected storage rejection")
        } catch (_: IllegalStateException) {
            // The full Room transaction must roll back.
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // Duplicate event identity rejected by SQLite's scoped unique index.
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.put(block)
        }

        fun next(): Runnable =
            requireNotNull(queue.poll(10, TimeUnit.SECONDS)) {
                "Caller continuation was not dispatched"
            }
    }
}
