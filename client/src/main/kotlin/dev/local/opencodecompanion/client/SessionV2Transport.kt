package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2AgentSummary
import dev.local.opencodecompanion.protocol.V2CatalogCodec
import dev.local.opencodecompanion.protocol.V2CatalogPage
import dev.local.opencodecompanion.protocol.V2CreateSessionCommand
import dev.local.opencodecompanion.protocol.V2ModelSummary
import dev.local.opencodecompanion.protocol.V2PermissionReply
import dev.local.opencodecompanion.protocol.V2PermissionRequest
import dev.local.opencodecompanion.protocol.V2PromptAdmission
import dev.local.opencodecompanion.protocol.V2PromptCommand
import dev.local.opencodecompanion.protocol.V2QuestionRequest
import dev.local.opencodecompanion.protocol.V2RequestCodec
import dev.local.opencodecompanion.protocol.V2RequestPayload
import dev.local.opencodecompanion.protocol.V2SessionCommands
import dev.local.opencodecompanion.protocol.V2SessionSummary
import dev.local.opencodecompanion.protocol.V2WireCodec
import dev.local.opencodecompanion.protocol.V2WireException
import dev.local.opencodecompanion.protocol.transcript.TranscriptDecode
import dev.local.opencodecompanion.protocol.transcript.TranscriptPage
import dev.local.opencodecompanion.protocol.transcript.V2Transcript
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

data class ScopedValue<T>(val scope: ReadScope, val value: T)

sealed interface MutationResult<out T> {
    val scope: ReadScope

    data class Acknowledged<T>(override val scope: ReadScope, val value: T) : MutationResult<T>

    data class Rejected(override val scope: ReadScope, val reason: ReadFailure) :
        MutationResult<Nothing>

    data class OutcomeUnknown(override val scope: ReadScope) : MutationResult<Nothing>

    data class NotDispatched(override val scope: ReadScope, val reason: ReadFailure) :
        MutationResult<Nothing>
}

/** Finite, single-attempt calls. This class never retries mutations or updates durable intent. */
class SessionV2Transport private constructor(baseClient: OkHttpClient) {
    constructor() : this(OkHttpClient())

    private val client = V2HttpBoundary.harden(baseClient, finite = true)

    internal fun runningCallsForTests(): Int = client.dispatcher.runningCallsCount()

    /** Supported release gate, separate from validating individual V2 endpoint shapes. */
    suspend fun compatibility(destination: ReadDestination): ReadResult<ScopedValue<String>> {
        val result =
            read(
                destination,
                destination.origin.newBuilder().addPathSegments("global/health").build(),
            ) {
                dev.local.opencodecompanion.protocol.HostVersionCodec.decode(it)
            }
        return when (result) {
            is ReadResult.Failure -> result
            is ReadResult.Success ->
                if (
                    result.value.value ==
                        dev.local.opencodecompanion.protocol.HostVersionCodec.SUPPORTED_VERSION
                )
                    result
                else ReadResult.Failure(ReadFailure.UnsupportedVersion)
        }
    }

    suspend fun session(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<ScopedValue<V2SessionSummary>> {
        if (!validKey(destination, key)) return ReadResult.Failure(ReadFailure.DecodeFailure)
        return read(destination, sessionUrl(destination, key)) { body ->
            V2WireCodec.session(body).also { require(it.id == key.sessionId) }
        }
    }

    /** Legacy working-tree read scoped to the selected session's explicit directory. */
    suspend fun changes(
        destination: ReadDestination,
        key: SessionKey,
        directory: String,
    ): ReadResult<ScopedValue<List<dev.local.opencodecompanion.protocol.VcsFileDiff>>> {
        if (!validKey(destination, key)) return ReadResult.Failure(ReadFailure.DecodeFailure)
        try {
            dev.local.opencodecompanion.protocol.VcsDiffCodec.directory(directory)
        } catch (_: V2WireException) {
            return ReadResult.Failure(ReadFailure.DecodeFailure)
        }
        return read(
            destination,
            destination.origin
                .newBuilder()
                .addPathSegments("vcs/diff")
                .addQueryParameter("mode", "git")
                .addQueryParameter("directory", directory)
                .build(),
        ) { body ->
            dev.local.opencodecompanion.protocol.VcsDiffCodec.decode(body)
        }
    }

    suspend fun active(
        destination: ReadDestination
    ): ReadResult<ScopedValue<Set<dev.local.opencodecompanion.protocol.SessionKey>>> =
        read(
            destination,
            destination.origin.newBuilder().addPathSegments("api/session/active").build(),
        ) { body ->
            dev.local.opencodecompanion.protocol.V2SessionCommands.active(body).mapTo(
                mutableSetOf()
            ) {
                SessionKey(destination.machineId, it)
            }
        }

    suspend fun history(
        destination: ReadDestination,
        key: SessionKey,
        after: Long,
    ): ReadResult<ScopedValue<TranscriptPage>> {
        if (after < 0 || !validKey(destination, key))
            return ReadResult.Failure(ReadFailure.DecodeFailure)
        return read(
            destination,
            sessionUrl(destination, key)
                .newBuilder()
                .addPathSegment("history")
                .addQueryParameter("after", after.toString())
                .addQueryParameter("limit", "100")
                .build(),
        ) { body ->
            V2Transcript.history(body, key).also { page ->
                var prior = after
                for (decoded in page.events) {
                    val sequence =
                        when (decoded) {
                            is TranscriptDecode.Supported -> decoded.event.sequence
                            is TranscriptDecode.Unsupported -> decoded.sequence
                        }
                    if (sequence <= prior) throw V2WireException("History sequence did not advance")
                    prior = sequence
                }
            }
        }
    }

    suspend fun permissions(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<ScopedValue<List<V2PermissionRequest>>> {
        if (!validKey(destination, key)) return ReadResult.Failure(ReadFailure.DecodeFailure)
        return read(
            destination,
            sessionUrl(destination, key).newBuilder().addPathSegment("permission").build(),
        ) { body ->
            V2RequestCodec.permissions(body, key)
        }
    }

    suspend fun questions(
        destination: ReadDestination,
        key: SessionKey,
    ): ReadResult<ScopedValue<List<V2QuestionRequest>>> {
        if (!validKey(destination, key)) return ReadResult.Failure(ReadFailure.DecodeFailure)
        return read(
            destination,
            sessionUrl(destination, key).newBuilder().addPathSegment("question").build(),
        ) { body ->
            V2RequestCodec.questions(body, key)
        }
    }

    suspend fun agents(
        destination: ReadDestination
    ): ReadResult<ScopedValue<V2CatalogPage<V2AgentSummary>>> =
        read(destination, destination.origin.newBuilder().addPathSegments("api/agent").build()) {
            body ->
            V2CatalogCodec.agents(body)
        }

    suspend fun models(
        destination: ReadDestination
    ): ReadResult<ScopedValue<V2CatalogPage<V2ModelSummary>>> =
        read(destination, destination.origin.newBuilder().addPathSegments("api/model").build()) {
            body ->
            V2CatalogCodec.models(body)
        }

    suspend fun create(
        destination: ReadDestination,
        command: V2CreateSessionCommand,
    ): MutationResult<ScopedSession> {
        val scope = destination.scope()
        val location = command.location
        if (location?.workspaceId != null)
            return MutationResult.NotDispatched(scope, ReadFailure.DecodeFailure)
        val body =
            try {
                V2SessionCommands.create(command)
            } catch (_: V2WireException) {
                return MutationResult.NotDispatched(scope, ReadFailure.DecodeFailure)
            }
        val url = destination.origin.newBuilder().addPathSegments("api/session").build()
        return mutate(destination, url, body) { code, text ->
            if (code != 200) invalidSuccess()
            val session = V2WireCodec.session(text)
            if (command.requestedSessionId != null && session.id != command.requestedSessionId)
                invalidSuccess()
            if (location != null && session.directory != location.directory) invalidSuccess()
            ScopedSession(SessionKey(destination.machineId, session.id), session)
        }
    }

    suspend fun prompt(
        destination: ReadDestination,
        command: V2PromptCommand,
    ): MutationResult<V2PromptAdmission> {
        val key = command.sessionKey
        if (!validKey(destination, key))
            return MutationResult.NotDispatched(destination.scope(), ReadFailure.DecodeFailure)
        val url = sessionUrl(destination, key).newBuilder().addPathSegment("prompt").build()
        val body =
            try {
                V2SessionCommands.prompt(command)
            } catch (_: V2WireException) {
                return MutationResult.NotDispatched(destination.scope(), ReadFailure.DecodeFailure)
            }
        return mutate(destination, url, body, conflictMessageId = command.messageId) { code, text ->
            if (code != 200) invalidSuccess()
            V2WireCodec.admission(text, key.sessionId, command.messageId).also {
                if (it.promptText != command.text || it.delivery != command.delivery.wireValue)
                    invalidSuccess()
            }
        }
    }

    suspend fun interrupt(destination: ReadDestination, key: SessionKey): MutationResult<Unit> {
        if (!validKey(destination, key))
            return MutationResult.NotDispatched(destination.scope(), ReadFailure.DecodeFailure)
        return mutate(
            destination,
            sessionUrl(destination, key).newBuilder().addPathSegment("interrupt").build(),
            null,
        ) { code, text ->
            if (code != 204 || text.isNotEmpty()) invalidSuccess()
            Unit
        }
    }

    suspend fun permissionReply(
        destination: ReadDestination,
        request: V2PermissionRequest,
        reply: V2PermissionReply,
    ): MutationResult<Unit> {
        val payload =
            try {
                V2RequestCodec.permissionReply(request, reply)
            } catch (_: V2WireException) {
                return MutationResult.NotDispatched(destination.scope(), ReadFailure.DecodeFailure)
            }
        return requestReply(destination, payload, "permission", "reply")
    }

    suspend fun questionReply(
        destination: ReadDestination,
        request: V2QuestionRequest,
        answers: List<List<String>>,
    ): MutationResult<Unit> {
        val payload =
            try {
                V2RequestCodec.questionReply(request, answers)
            } catch (_: V2WireException) {
                return MutationResult.NotDispatched(destination.scope(), ReadFailure.DecodeFailure)
            }
        return requestReply(destination, payload, "question", "reply")
    }

    suspend fun questionReject(
        destination: ReadDestination,
        request: V2QuestionRequest,
    ): MutationResult<Unit> =
        requestReply(destination, V2RequestCodec.questionReject(request), "question", "reject")

    private suspend fun requestReply(
        destination: ReadDestination,
        payload: V2RequestPayload,
        group: String,
        action: String,
    ): MutationResult<Unit> {
        if (
            !validKey(destination, payload.sessionKey) ||
                payload.requestId.isBlank() ||
                payload.requestId == "." ||
                payload.requestId == ".."
        )
            return MutationResult.NotDispatched(destination.scope(), ReadFailure.DecodeFailure)
        val url =
            sessionUrl(destination, payload.sessionKey)
                .newBuilder()
                .addPathSegment(group)
                .addPathSegment(payload.requestId)
                .addPathSegment(action)
                .build()
        return mutate(destination, url, payload.body) { code, text ->
            if (code != 204 || text.isNotEmpty()) invalidSuccess()
            Unit
        }
    }

    private fun sessionUrl(destination: ReadDestination, key: SessionKey): HttpUrl {
        require(key.machineId == destination.machineId) { "Session belongs to another machine" }
        val id = key.sessionId.value
        require(id.startsWith("ses") && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "Invalid session ID"
        }
        return destination.origin
            .newBuilder()
            .addPathSegments("api/session")
            .addPathSegment(id)
            .build()
    }

    private fun validKey(destination: ReadDestination, key: SessionKey): Boolean {
        val id = key.sessionId.value
        return key.machineId == destination.machineId &&
            id.startsWith("ses") &&
            id.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private suspend fun <T> read(
        destination: ReadDestination,
        url: HttpUrl,
        decode: (String) -> T,
    ): ReadResult<ScopedValue<T>> {
        val request = V2HttpBoundary.authorizedRequest(destination, url).get().build()
        val raw =
            try {
                client.newCall(request).awaitBounded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SSLException) {
                return ReadResult.Failure(ReadFailure.TlsRejected)
            } catch (error: IOException) {
                return ReadResult.Failure(
                    if (V2HttpBoundary.hasTlsFailure(error)) ReadFailure.TlsRejected
                    else ReadFailure.TransportUnavailable
                )
            }
        if (raw.tooLarge) return ReadResult.Failure(ReadFailure.TooLarge)
        if (raw.code == 401 || raw.code == 403)
            return ReadResult.Failure(ReadFailure.AuthenticationRequired)
        if (raw.code in 300..399) return ReadResult.Failure(ReadFailure.RedirectRejected)
        if (raw.code != 200) return ReadResult.Failure(ReadFailure.HttpStatus(raw.code))
        return try {
            ReadResult.Success(ScopedValue(destination.scope(), decode(raw.text())))
        } catch (_: V2WireException) {
            ReadResult.Failure(ReadFailure.DecodeFailure)
        } catch (_: IllegalArgumentException) {
            ReadResult.Failure(ReadFailure.DecodeFailure)
        }
    }

    private suspend fun <T> mutate(
        destination: ReadDestination,
        url: HttpUrl,
        body: String?,
        conflictMessageId: String? = null,
        decode: (Int, String) -> T,
    ): MutationResult<T> {
        val scope = destination.scope()
        val request =
            V2HttpBoundary.authorizedRequest(destination, url).post(oneShotBody(body)).build()
        val raw =
            try {
                client.newCall(request).awaitBounded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SSLException) {
                return MutationResult.OutcomeUnknown(scope)
            } catch (_: IOException) {
                return MutationResult.OutcomeUnknown(scope)
            }
        if (raw.tooLarge) return MutationResult.OutcomeUnknown(scope)
        if (raw.code == 401 || raw.code == 403)
            return MutationResult.Rejected(scope, ReadFailure.AuthenticationRequired)
        if (raw.code in 300..399) return MutationResult.OutcomeUnknown(scope)
        if (raw.code == 409 && conflictMessageId != null) {
            val confirmed =
                try {
                    V2WireCodec.promptConflict(raw.text(), conflictMessageId)
                    true
                } catch (_: V2WireException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
            return if (confirmed) MutationResult.Rejected(scope, ReadFailure.HttpStatus(409))
            else MutationResult.OutcomeUnknown(scope)
        }
        // A generic client error can be a proxy timeout or an unrelated HTML response. It does
        // not establish whether this particular command reached the host or changed state.
        if (raw.code !in 200..299) return MutationResult.OutcomeUnknown(scope)
        return try {
            MutationResult.Acknowledged(scope, decode(raw.code, raw.text()))
        } catch (_: V2WireException) {
            MutationResult.OutcomeUnknown(scope)
        } catch (_: IllegalArgumentException) {
            MutationResult.OutcomeUnknown(scope)
        }
    }

    private fun ReadDestination.scope() =
        ReadScope(machineId, origin.toString(), credentialGeneration)

    private fun invalidSuccess(): Nothing =
        throw IllegalArgumentException("Invalid success response")

    /** OkHttp can follow `503 Retry-After: 0` even with connection retries disabled. */
    private fun oneShotBody(body: String?): RequestBody {
        val delegate = (body ?: "").toRequestBody(if (body == null) null else JSON)
        return object : RequestBody() {
            override fun contentType(): MediaType? = delegate.contentType()

            override fun contentLength(): Long = delegate.contentLength()

            override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)

            override fun isOneShot(): Boolean = true
        }
    }

    private suspend fun Call.awaitBounded(): RawResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val raw =
                                response.use { received ->
                                    val bytes = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    var tooLarge = false
                                    received.body?.byteStream()?.let { stream ->
                                        while (true) {
                                            val count = stream.read(buffer)
                                            if (count < 0) break
                                            if (bytes.size() + count > MAX_BODY_BYTES) {
                                                tooLarge = true
                                                break
                                            }
                                            bytes.write(buffer, 0, count)
                                        }
                                    }
                                    RawResponse(received.code, bytes.toByteArray(), tooLarge)
                                }
                            if (continuation.isActive) continuation.resume(raw)
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            )
        }

    private data class RawResponse(val code: Int, val body: ByteArray, val tooLarge: Boolean) {
        fun text(): String =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("Invalid UTF-8 response")
            }
    }

    companion object {
        private const val MAX_BODY_BYTES = 1_048_576
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun forTests(tlsClient: OkHttpClient) = SessionV2Transport(tlsClient)
    }
}
