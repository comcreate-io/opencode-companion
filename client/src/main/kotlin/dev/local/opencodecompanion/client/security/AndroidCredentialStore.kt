package dev.local.opencodecompanion.client.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.local.opencodecompanion.protocol.MachineId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class CredentialFailure {
    InvalidInput,
    Missing,
    AlreadyExists,
    ScopeMismatch,
    KeyUnavailable,
    Corrupt,
    StorageFailure,
}

sealed interface CredentialResult<out T> {
    data class Success<T>(val value: T) : CredentialResult<T>

    data class Failure(val reason: CredentialFailure) : CredentialResult<Nothing>
}

/** A dispatch-only value. Its diagnostic representation never contains credential material. */
class CredentialSecret internal constructor(private val value: String) {
    fun authorizationHeader(): String = value

    override fun toString(): String = "CredentialSecret(<redacted>)"
}

/** All three fields are authenticated with the envelope. The reference contains no credential. */
class CredentialScope
private constructor(val machineId: MachineId, val origin: String, val generation: Long) {
    val reference: String =
        "credential:${hex(sha256(machineId.value.toByteArray(StandardCharsets.UTF_8)))}"

    companion object {
        fun create(
            machineId: MachineId,
            origin: String,
            generation: Long,
        ): CredentialResult<CredentialScope> {
            if (machineId.value.length > 256 || origin.length > 2_048 || generation < 0) {
                return CredentialResult.Failure(CredentialFailure.InvalidInput)
            }
            val url =
                origin.toHttpUrlOrNull()
                    ?: return CredentialResult.Failure(CredentialFailure.InvalidInput)
            if (
                url.scheme != "https" ||
                    url.encodedPath != "/" ||
                    url.query != null ||
                    url.fragment != null ||
                    url.username.isNotEmpty() ||
                    url.password.isNotEmpty()
            ) {
                return CredentialResult.Failure(CredentialFailure.InvalidInput)
            }
            return CredentialResult.Success(CredentialScope(machineId, url.toString(), generation))
        }
    }

    internal fun digest(): ByteArray {
        val machine = machineId.value.toByteArray(StandardCharsets.UTF_8)
        val host = origin.toByteArray(StandardCharsets.UTF_8)
        return sha256(
            ByteBuffer.allocate(4 + machine.size + 4 + host.size + 8)
                .putInt(machine.size)
                .put(machine)
                .putInt(host.size)
                .put(host)
                .putLong(generation)
                .array()
        )
    }

    override fun toString(): String =
        "CredentialScope(machineId=$machineId, origin=$origin, generation=$generation, reference=$reference)"
}

/**
 * An AES-GCM envelope under noBackupFilesDir. Calls run on IO and are serialized within this
 * process. Profile rows contain only [CredentialScope.reference]; a profile update and envelope
 * rotation are separate durable operations and must be reconciled by their caller.
 *
 * Keystore protects the encryption key, not the Room database or arbitrary cached content.
 */
class AndroidCredentialStore
internal constructor(
    context: Context,
    private val finalizeWrite: (AtomicFile, java.io.FileOutputStream) -> Unit,
) {
    constructor(context: Context) : this(context, { atomic, stream -> atomic.finishWrite(stream) })

    private val directory = File(context.applicationContext.noBackupFilesDir, "credentials")

    suspend fun store(scope: CredentialScope, authorization: String): CredentialResult<Unit> =
        withContext(Dispatchers.IO) { synchronized(lock) { storeLocked(scope, authorization) } }

    suspend fun load(scope: CredentialScope): CredentialResult<CredentialSecret> =
        withContext(Dispatchers.IO) { synchronized(lock) { loadLocked(scope) } }

    suspend fun rotate(
        current: CredentialScope,
        next: CredentialScope,
        authorization: String,
    ): CredentialResult<Unit> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (current.machineId != next.machineId || next.generation <= current.generation) {
                    return@synchronized CredentialResult.Failure(CredentialFailure.InvalidInput)
                }
                val validated = validateAuthorization(authorization)
                if (validated != null) return@synchronized CredentialResult.Failure(validated)
                when (val old = readAuthenticated(current)) {
                    is CredentialResult.Failure -> old
                    is CredentialResult.Success -> {
                        old.value.fill(0)
                        writeLocked(next, authorization)
                    }
                }
            }
        }

    suspend fun delete(scope: CredentialScope): CredentialResult<Unit> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                when (val old = readAuthenticated(scope)) {
                    is CredentialResult.Failure -> old
                    is CredentialResult.Success -> {
                        old.value.fill(0)
                        val atomic = atomicFile(scope)
                        try {
                            atomic.delete()
                            if (hasEnvelopeArtifacts(atomic)) {
                                CredentialResult.Failure(CredentialFailure.StorageFailure)
                            } else {
                                CredentialResult.Success(Unit)
                            }
                        } catch (_: SecurityException) {
                            CredentialResult.Failure(CredentialFailure.StorageFailure)
                        }
                    }
                }
            }
        }

    private fun storeLocked(scope: CredentialScope, authorization: String): CredentialResult<Unit> {
        validateAuthorization(authorization)?.let {
            return CredentialResult.Failure(it)
        }
        val atomic = atomicFile(scope)
        when (val raw = readRaw(atomic)) {
            is CredentialResult.Success ->
                return CredentialResult.Failure(CredentialFailure.AlreadyExists)
            is CredentialResult.Failure -> if (raw.reason != CredentialFailure.Missing) return raw
        }
        return writeLocked(scope, authorization)
    }

    private fun loadLocked(scope: CredentialScope): CredentialResult<CredentialSecret> =
        when (val read = readAuthenticated(scope)) {
            is CredentialResult.Failure -> read
            is CredentialResult.Success -> {
                val bytes = read.value
                try {
                    val text =
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                    if (validateAuthorization(text) != null) {
                        CredentialResult.Failure(CredentialFailure.Corrupt)
                    } else {
                        CredentialResult.Success(CredentialSecret(text))
                    }
                } catch (_: CharacterCodingException) {
                    CredentialResult.Failure(CredentialFailure.Corrupt)
                } finally {
                    bytes.fill(0)
                }
            }
        }

    private fun readAuthenticated(scope: CredentialScope): CredentialResult<ByteArray> {
        val envelope =
            when (val raw = readRaw(atomicFile(scope))) {
                is CredentialResult.Failure -> return raw
                is CredentialResult.Success -> raw.value
            }
        val key =
            when (val available = existingKey()) {
                is CredentialResult.Failure -> return available
                is CredentialResult.Success -> available.value
            }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv))
            cipher.updateAAD(aad(envelope.scopeDigest))
            val plaintext = cipher.doFinal(envelope.ciphertext)
            if (!MessageDigest.isEqual(envelope.scopeDigest, scope.digest())) {
                plaintext.fill(0)
                CredentialResult.Failure(CredentialFailure.ScopeMismatch)
            } else {
                CredentialResult.Success(plaintext)
            }
        } catch (_: AEADBadTagException) {
            CredentialResult.Failure(CredentialFailure.Corrupt)
        } catch (_: GeneralSecurityException) {
            CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        } catch (_: ProviderException) {
            CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        }
    }

    private fun writeLocked(scope: CredentialScope, authorization: String): CredentialResult<Unit> {
        if (!directory.exists() && !directory.mkdirs()) {
            return CredentialResult.Failure(CredentialFailure.StorageFailure)
        }
        val key =
            when (val available = keyForWrite()) {
                is CredentialResult.Failure -> return available
                is CredentialResult.Success -> available.value
            }
        val plaintext = authorization.toByteArray(StandardCharsets.UTF_8)
        val envelope =
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                if (iv.size != IV_BYTES)
                    return CredentialResult.Failure(CredentialFailure.KeyUnavailable)
                val digest = scope.digest()
                cipher.updateAAD(aad(digest))
                Envelope(digest, iv, cipher.doFinal(plaintext))
            } catch (_: GeneralSecurityException) {
                return CredentialResult.Failure(CredentialFailure.KeyUnavailable)
            } catch (_: ProviderException) {
                return CredentialResult.Failure(CredentialFailure.KeyUnavailable)
            } finally {
                plaintext.fill(0)
            }
        val atomic = atomicFile(scope)
        var stream: java.io.FileOutputStream? = null
        return try {
            val opened = atomic.startWrite()
            stream = opened
            DataOutputStream(opened).apply {
                write(MAGIC)
                writeByte(VERSION)
                write(envelope.scopeDigest)
                write(envelope.iv)
                writeInt(envelope.ciphertext.size)
                write(envelope.ciphertext)
                flush()
            }
            // AtomicFile.finishWrite logs sync/close/rename errors instead of reporting them.
            // Surface sync errors ourselves, then verify the committed base is this envelope.
            opened.fd.sync()
            finalizeWrite(atomic, opened)
            when (val committed = readRaw(atomic)) {
                is CredentialResult.Failure ->
                    CredentialResult.Failure(CredentialFailure.StorageFailure)
                is CredentialResult.Success ->
                    if (
                        committed.value.scopeDigest.contentEquals(envelope.scopeDigest) &&
                            committed.value.iv.contentEquals(envelope.iv) &&
                            committed.value.ciphertext.contentEquals(envelope.ciphertext)
                    ) {
                        CredentialResult.Success(Unit)
                    } else {
                        CredentialResult.Failure(CredentialFailure.StorageFailure)
                    }
            }
        } catch (_: IOException) {
            stream?.let(atomic::failWrite)
            CredentialResult.Failure(CredentialFailure.StorageFailure)
        } catch (_: SecurityException) {
            stream?.let(atomic::failWrite)
            CredentialResult.Failure(CredentialFailure.StorageFailure)
        }
    }

    private fun readRaw(atomic: AtomicFile): CredentialResult<Envelope> {
        val bytes =
            try {
                atomic.openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(2_048)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_FILE_BYTES) {
                            return CredentialResult.Failure(CredentialFailure.Corrupt)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (_: FileNotFoundException) {
                return CredentialResult.Failure(
                    if (hasEnvelopeArtifacts(atomic)) CredentialFailure.StorageFailure
                    else CredentialFailure.Missing
                )
            } catch (_: IOException) {
                return CredentialResult.Failure(CredentialFailure.StorageFailure)
            } catch (_: SecurityException) {
                return CredentialResult.Failure(CredentialFailure.StorageFailure)
            }
        if (bytes.size < HEADER_BYTES + TAG_BYTES) {
            return CredentialResult.Failure(CredentialFailure.Corrupt)
        }
        return try {
            DataInputStream(bytes.inputStream()).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                val version = input.readUnsignedByte()
                if (!magic.contentEquals(MAGIC) || version != VERSION) {
                    return CredentialResult.Failure(CredentialFailure.Corrupt)
                }
                val digest = ByteArray(DIGEST_BYTES).also(input::readFully)
                val iv = ByteArray(IV_BYTES).also(input::readFully)
                val length = input.readInt()
                if (
                    length < TAG_BYTES ||
                        length > MAX_FILE_BYTES - HEADER_BYTES ||
                        length != input.available()
                ) {
                    return CredentialResult.Failure(CredentialFailure.Corrupt)
                }
                val ciphertext = ByteArray(length).also(input::readFully)
                CredentialResult.Success(Envelope(digest, iv, ciphertext))
            }
        } catch (_: IOException) {
            CredentialResult.Failure(CredentialFailure.Corrupt)
        }
    }

    private fun existingKey(): CredentialResult<SecretKey> =
        try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val key = store.getKey(KEY_ALIAS, null) as? SecretKey
            if (key == null) CredentialResult.Failure(CredentialFailure.KeyUnavailable)
            else CredentialResult.Success(key)
        } catch (_: GeneralSecurityException) {
            CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        } catch (_: IOException) {
            CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        } catch (_: ProviderException) {
            CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        }

    private fun keyForWrite(): CredentialResult<SecretKey> {
        when (val existing = existingKey()) {
            is CredentialResult.Success -> return existing
            is CredentialResult.Failure ->
                if (existing.reason != CredentialFailure.KeyUnavailable) return existing
        }
        // Existing envelopes must never be made unreadable by silently creating a replacement key.
        val entries =
            directory.listFiles()
                ?: return CredentialResult.Failure(CredentialFailure.StorageFailure)
        if (entries.any { it.name.startsWith(FILE_PREFIX) }) {
            return CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            CredentialResult.Success(generator.generateKey())
        } catch (_: GeneralSecurityException) {
            CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        } catch (_: ProviderException) {
            CredentialResult.Failure(CredentialFailure.KeyUnavailable)
        }
    }

    private fun atomicFile(scope: CredentialScope): AtomicFile =
        AtomicFile(
            File(directory, "$FILE_PREFIX${scope.reference.removePrefix("credential:")}.bin")
        )

    private fun hasEnvelopeArtifacts(atomic: AtomicFile): Boolean {
        val base = atomic.baseFile
        return base.exists() ||
            File(base.path + ".bak").exists() ||
            File(base.path + ".new").exists()
    }

    private fun validateAuthorization(value: String): CredentialFailure? =
        if (
            value.isBlank() ||
                value.length > MAX_SECRET_CHARS ||
                value.any { it.code !in 0x20..0x7e }
        ) {
            CredentialFailure.InvalidInput
        } else null

    private data class Envelope(
        val scopeDigest: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    companion object {
        private val lock = Any()
        private val MAGIC =
            byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
        private const val VERSION = 1
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dev.local.opencodecompanion.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE_PREFIX = "credential-"
        private const val DIGEST_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 4 + 1 + DIGEST_BYTES + IV_BYTES + 4
        private const val MAX_SECRET_CHARS = 8_192
        private const val MAX_FILE_BYTES = 16_384

        private fun aad(digest: ByteArray): ByteArray =
            MAGIC + byteArrayOf(VERSION.toByte()) + digest
    }
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
