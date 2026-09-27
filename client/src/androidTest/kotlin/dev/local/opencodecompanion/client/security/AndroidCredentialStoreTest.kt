package dev.local.opencodecompanion.client.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.protocol.MachineId
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AndroidCredentialStore(context)
    private val machine = MachineId("synthetic-${UUID.randomUUID()}")
    private val ownedFiles = mutableSetOf<File>()

    @After
    fun removeSyntheticEnvelopes() {
        // JUnit invokes @After even when an assertion fails. All paths contain this test's UUID.
        for (base in ownedFiles) {
            for (file in listOf(base, File(base.path + ".bak"), File(base.path + ".new"))) {
                if (file.exists()) assertTrue("Could not remove synthetic envelope", file.delete())
            }
        }
    }

    @Test
    fun encryptedEnvelopeReopensAndIsExcludedFromBackup() = runBlocking {
        val scope = scope(machine, "https://EXAMPLE.com:443/", 1)
        val secret = "Basic synthetic-${UUID.randomUUID()}"
        assertEquals("https://example.com/", scope.origin)
        assertEquals(CredentialResult.Success(Unit), store.store(scope, secret))

        val envelope = envelopeFile(scope)
        assertTrue(
            envelope.canonicalPath.startsWith(
                context.noBackupFilesDir.canonicalPath + File.separator
            )
        )
        assertTrue(envelope.isFile)
        assertFalse(envelope.readBytes().toString(Charsets.UTF_8).contains(secret))
        assertFalse(scope.reference.contains("example.com"))
        val reopened = AndroidCredentialStore(context)
        val result = reopened.load(scope)
        assertTrue(result is CredentialResult.Success)
        val loaded = (result as CredentialResult.Success).value
        assertEquals(secret, loaded.authorizationHeader())
        assertFalse(loaded.toString().contains(secret))
        assertEquals(CredentialResult.Success(Unit), reopened.delete(scope))
    }

    @Test
    fun machineOriginAndGenerationCannotReuseCredential() = runBlocking {
        val original = scope(machine, "https://example.com/", 7)
        val sameOrigin = scope(machine, "https://EXAMPLE.COM:443/", 7)
        val wrongOrigin = scope(machine, "https://other.example/", 7)
        val wrongGeneration = scope(machine, "https://example.com/", 8)
        val wrongMachine =
            scope(MachineId("synthetic-${UUID.randomUUID()}"), "https://example.com/", 7)
        assertEquals(CredentialResult.Success(Unit), store.store(original, "Basic synthetic"))
        assertEquals(
            CredentialResult.Failure(CredentialFailure.AlreadyExists),
            store.store(original, "Basic replacement"),
        )
        assertTrue(store.load(sameOrigin) is CredentialResult.Success)
        assertEquals(
            CredentialResult.Failure(CredentialFailure.ScopeMismatch),
            store.load(wrongOrigin),
        )
        assertEquals(
            CredentialResult.Failure(CredentialFailure.ScopeMismatch),
            store.load(wrongGeneration),
        )
        assertEquals(CredentialResult.Failure(CredentialFailure.Missing), store.load(wrongMachine))
        assertEquals(
            CredentialResult.Failure(CredentialFailure.ScopeMismatch),
            store.delete(wrongOrigin),
        )
        assertEquals(
            CredentialResult.Failure(CredentialFailure.ScopeMismatch),
            store.rotate(
                wrongOrigin,
                scope(machine, "https://other.example/", 9),
                "Basic replacement",
            ),
        )
        assertEquals(
            "Basic synthetic",
            (store.load(original) as CredentialResult.Success).value.authorizationHeader(),
        )
        assertEquals(CredentialResult.Success(Unit), store.delete(original))
    }

    @Test
    fun rotationAtomicallyReplacesEnvelopeAndDeniesOldScope() = runBlocking {
        val old = scope(machine, "https://example.com/", 2)
        val next = scope(machine, "https://next.example/", 3)
        assertEquals(CredentialResult.Success(Unit), store.store(old, "Basic old-synthetic"))
        assertEquals(CredentialResult.Success(Unit), store.rotate(old, next, "Basic new-synthetic"))
        assertEquals(CredentialResult.Failure(CredentialFailure.ScopeMismatch), store.load(old))
        val loaded = store.load(next)
        assertEquals(
            "Basic new-synthetic",
            (loaded as CredentialResult.Success).value.authorizationHeader(),
        )
        assertEquals(
            CredentialResult.Failure(CredentialFailure.InvalidInput),
            store.rotate(next, old, "Basic rollback"),
        )
        assertEquals(CredentialResult.Success(Unit), store.delete(next))
        assertEquals(CredentialResult.Failure(CredentialFailure.Missing), store.load(next))
    }

    @Test
    fun tamperedCiphertextFailsClosedWithoutCredentialDisclosure() = runBlocking {
        val current = scope(machine, "https://example.com/", 1)
        val secret = "Basic synthetic-tamper"
        assertEquals(CredentialResult.Success(Unit), store.store(current, secret))
        val file = envelopeFile(current)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertEquals(CredentialResult.Failure(CredentialFailure.Corrupt), store.load(current))
        assertEquals(
            CredentialResult.Failure(CredentialFailure.Corrupt),
            store.rotate(current, scope(machine, "https://example.com/", 2), "Basic new"),
        )
        assertEquals(CredentialResult.Failure(CredentialFailure.Corrupt), store.delete(current))
        assertTrue(file.delete())
    }

    @Test
    fun invalidScopeAndSecretRejectBeforeWriting() = runBlocking {
        assertEquals(
            CredentialResult.Failure(CredentialFailure.InvalidInput),
            CredentialScope.create(machine, "http://example.com/", 1),
        )
        assertEquals(
            CredentialResult.Failure(CredentialFailure.InvalidInput),
            CredentialScope.create(machine, "https://user:secret@example.com/", 1),
        )
        val scope = scope(machine, "https://example.com/", 1)
        assertEquals(
            CredentialResult.Failure(CredentialFailure.InvalidInput),
            store.store(scope, "Basic x\n"),
        )
        assertFalse(envelopeFile(scope).exists())
    }

    @Test
    fun twoStoreInstancesSerializeCreateAndRotate() = runBlocking {
        val first = AndroidCredentialStore(context)
        val second = AndroidCredentialStore(context)
        val initial = scope(machine, "https://example.com/", 1)
        val createA = async(Dispatchers.IO) { first.store(initial, "Basic synthetic-A") }
        val createB = async(Dispatchers.IO) { second.store(initial, "Basic synthetic-B") }
        val creates = listOf(createA.await(), createB.await())
        assertEquals(1, creates.count { it == CredentialResult.Success(Unit) })
        assertEquals(
            1,
            creates.count { it == CredentialResult.Failure(CredentialFailure.AlreadyExists) },
        )

        val nextA = scope(machine, "https://a.example/", 2)
        val nextB = scope(machine, "https://b.example/", 3)
        val rotateA = async(Dispatchers.IO) { first.rotate(initial, nextA, "Basic rotated-A") }
        val rotateB = async(Dispatchers.IO) { second.rotate(initial, nextB, "Basic rotated-B") }
        val rotations = listOf(rotateA.await(), rotateB.await())
        assertEquals(1, rotations.count { it == CredentialResult.Success(Unit) })
        assertEquals(
            1,
            rotations.count { it == CredentialResult.Failure(CredentialFailure.ScopeMismatch) },
        )
        assertEquals(CredentialResult.Failure(CredentialFailure.ScopeMismatch), store.load(initial))
        val winner = if (rotations[0] == CredentialResult.Success(Unit)) nextA else nextB
        val expected = if (winner === nextA) "Basic rotated-A" else "Basic rotated-B"
        assertEquals(
            expected,
            (store.load(winner) as CredentialResult.Success).value.authorizationHeader(),
        )
    }

    @Test
    fun interruptedAtomicReplacementRestoresBackupBeforeRead() = runBlocking {
        val current = scope(machine, "https://example.com/", 4)
        assertEquals(CredentialResult.Success(Unit), store.store(current, "Basic synthetic-backup"))
        val base = envelopeFile(current)
        val backup = File(base.path + ".bak")
        assertTrue(base.renameTo(backup))
        assertFalse(base.exists())
        assertEquals(
            "Basic synthetic-backup",
            (AndroidCredentialStore(context).load(current) as CredentialResult.Success)
                .value
                .authorizationHeader(),
        )
        assertTrue(base.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun silentFinalizeFailureCannotClaimRotationPersisted() = runBlocking {
        val old = scope(machine, "https://example.com/", 10)
        val next = scope(machine, "https://next.example/", 11)
        assertEquals(CredentialResult.Success(Unit), store.store(old, "Basic old-synthetic"))
        val failedFinalizer =
            AndroidCredentialStore(context) { atomic, stream ->
                // Model AtomicFile's logged rename failure: .new is gone, old base remains.
                stream.close()
                File(atomic.baseFile.path + ".new").delete()
            }
        assertEquals(
            CredentialResult.Failure(CredentialFailure.StorageFailure),
            failedFinalizer.rotate(old, next, "Basic new-synthetic"),
        )
        assertEquals(
            "Basic old-synthetic",
            (store.load(old) as CredentialResult.Success).value.authorizationHeader(),
        )
        assertEquals(CredentialResult.Failure(CredentialFailure.ScopeMismatch), store.load(next))
    }

    @Test
    fun missingKeyFailsClosedAndCannotBeRegeneratedOverExistingEnvelope() = runBlocking {
        // This library's instrumentation APK self-targets its own isolated package and UID.
        // Never delete an alias while instrumenting the production app or its debug variant.
        assertEquals("dev.local.opencodecompanion.client.test", context.packageName)
        val current = scope(machine, "https://example.com/", 1)
        val another = scope(MachineId("synthetic-${UUID.randomUUID()}"), "https://example.com/", 1)
        assertEquals(
            CredentialResult.Success(Unit),
            store.store(current, "Basic synthetic-key-loss"),
        )
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "dev.local.opencodecompanion.credentials.v1"
        assertTrue(keystore.containsAlias(alias))
        keystore.deleteEntry(alias)
        assertEquals(
            CredentialResult.Failure(CredentialFailure.KeyUnavailable),
            store.load(current),
        )
        assertEquals(
            CredentialResult.Failure(CredentialFailure.KeyUnavailable),
            store.store(another, "Basic synthetic-no-regeneration"),
        )
        assertFalse(envelopeFile(another).exists())
    }

    private fun scope(machineId: MachineId, origin: String, generation: Long): CredentialScope =
        (CredentialScope.create(machineId, origin, generation) as CredentialResult.Success)
            .value
            .also { ownedFiles += envelopeFile(it) }

    private fun envelopeFile(scope: CredentialScope): File =
        File(
            context.noBackupFilesDir,
            "credentials/credential-${scope.reference.removePrefix("credential:")}.bin",
        )
}
