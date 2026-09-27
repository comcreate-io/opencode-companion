package dev.local.opencodecompanion.client.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.client.SendDestination
import dev.local.opencodecompanion.client.storage.DraftKey
import dev.local.opencodecompanion.client.storage.DurableStore
import dev.local.opencodecompanion.client.storage.MachineProfile
import dev.local.opencodecompanion.client.storage.PendingCredentialRotation
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable Room database and Android Keystore envelope; no network or real credentials. */
@RunWith(AndroidJUnit4::class)
class MachineCredentialRotationTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var store: DurableStore
    private lateinit var vault: AndroidCredentialStore
    private lateinit var service: MachineCredentialRotation
    private val machine = MachineId("rotation-${UUID.randomUUID()}")
    private lateinit var old: CredentialScope
    private lateinit var next: CredentialScope
    private val origin = "https://fixture.example/"

    @Before
    fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.local.opencodecompanion.client.test", context.packageName)
        name = "rotation-${UUID.randomUUID()}.db"
        old = scope(1)
        next = scope(2)
        vault = AndroidCredentialStore(context)
        store = DurableStore.open(context, name)
        assertEquals(CredentialResult.Success(Unit), vault.store(old, "Basic old-synthetic"))
        store.putMachine(MachineProfile(machine, "Fixture", origin, old.reference, 1, true))
        service = MachineCredentialRotation(store, vault)
    }

    @After
    fun cleanup() {
        store.close()
        context.deleteDatabase(name)
        val file =
            File(
                context.noBackupFilesDir,
                "credentials/credential-${old.reference.removePrefix("credential:")}.bin",
            )
        for (candidate in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) {
            if (candidate.exists()) assertTrue(candidate.delete())
        }
    }

    @Test
    fun successfulRotationPreservesDraftAndJournal() = runBlocking {
        val key = SessionKey(machine, SessionId("ses_same"))
        val draftKey = DraftKey(key, ProjectKey(machine, ProjectId("global")), "/fixture/repo")
        store.saveDraft(draftKey, 0, "keep this draft")
        store.commitEvents(key, 0, listOf(event()))
        val result = service.rotate(machine, origin, 1, "Basic new-synthetic")
        assertTrue(result is RotationOutcome.Completed)
        assertEquals(2L, store.machine(machine)?.credentialGeneration)
        assertTrue(store.pendingCredentialRotations().isEmpty())
        assertEquals(CredentialResult.Failure(CredentialFailure.ScopeMismatch), vault.load(old))
        assertTrue(vault.load(next) is CredentialResult.Success)
        assertEquals("keep this draft", store.draft(draftKey)?.text)
        assertEquals(1L, store.cursor(key))
        assertEquals(1, store.journal(key).size)
    }

    @Test
    fun pendingBeforeVaultWriteRollsBackOnlyAfterExactOldRead() = runBlocking {
        val pending = pending()
        assertTrue(store.beginCredentialRotation(pending))
        val recovered = service.reconcilePending()
        assertEquals(1, recovered.size)
        assertTrue(recovered.single().outcome is RotationOutcome.RolledBack)
        assertTrue(store.pendingCredentialRotations().isEmpty())
        assertEquals(1L, store.machine(machine)?.credentialGeneration)
        assertTrue(vault.load(old) is CredentialResult.Success)
    }

    @Test
    fun pendingAfterVaultWriteFinishesOnFreshServiceInstance() = runBlocking {
        val pending = pending()
        assertTrue(store.beginCredentialRotation(pending))
        assertEquals(CredentialResult.Success(Unit), vault.rotate(old, next, "Basic new-synthetic"))
        val recovered =
            MachineCredentialRotation(store, AndroidCredentialStore(context)).reconcilePending()
        assertTrue(recovered.single().outcome is RotationOutcome.Completed)
        assertEquals(2L, store.machine(machine)?.credentialGeneration)
        assertTrue(store.pendingCredentialRotations().isEmpty())
    }

    @Test
    fun tamperedEnvelopeRetainsPendingAndFailsClosed() = runBlocking {
        val pending = pending()
        assertTrue(store.beginCredentialRotation(pending))
        val file =
            File(
                context.noBackupFilesDir,
                "credentials/credential-${old.reference.removePrefix("credential:")}.bin",
            )
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val result = service.reconcilePending().single().outcome
        assertEquals(RotationOutcome.FailClosed(RotationFailure.VaultStateUncertain), result)
        assertEquals(listOf(pending), store.pendingCredentialRotations())
        assertEquals(1L, store.machine(machine)?.credentialGeneration)
    }

    @Test
    fun unresolvedOutgoingBlocksRotationWithoutChangingVault() = runBlocking {
        val destination =
            SendDestination(
                SessionKey(machine, SessionId("ses_same")),
                ProjectKey(machine, ProjectId("global")),
                "/fixture/repo",
                1,
            )
        assertTrue(store.prepareIntent("msg_unknown", destination, "pending"))
        assertTrue(store.beginDispatch(machine, "msg_unknown"))
        assertTrue(store.markOutcomeUnknown(machine, "msg_unknown"))
        assertEquals(
            RotationOutcome.Blocked(RotationBlock.UnresolvedOutgoing),
            service.rotate(machine, origin, 1, "Basic new-synthetic"),
        )
        assertTrue(store.pendingCredentialRotations().isEmpty())
        assertTrue(vault.load(old) is CredentialResult.Success)
        assertFalse(vault.load(next) is CredentialResult.Success)
    }

    private fun scope(generation: Long) =
        (CredentialScope.create(machine, origin, generation) as CredentialResult.Success).value

    private fun pending() =
        PendingCredentialRotation(machine, origin, 1, old.reference, origin, 2, next.reference)

    private fun event() =
        """{"id":"evt_first","type":"session.next.prompt.admitted","durable":{"aggregateID":"ses_same","seq":1,"version":1},"data":{"timestamp":1,"sessionID":"ses_same","messageID":"msg_first","prompt":{"text":"fixture"},"delivery":"steer"}}"""
}
