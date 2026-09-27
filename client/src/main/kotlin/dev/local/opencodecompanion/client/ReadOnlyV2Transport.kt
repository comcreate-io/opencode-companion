package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2HistoryDecode
import dev.local.opencodecompanion.protocol.V2SessionPage
import dev.local.opencodecompanion.protocol.V2SessionSummary
import dev.local.opencodecompanion.protocol.V2WireCodec
import dev.local.opencodecompanion.protocol.V2WireException
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** An immutable dispatch snapshot. Credential material is omitted from [toString]. */
class ReadDestination(
    val machineId: MachineId,
    origin: String,
    val credentialGeneration: Long,
    private val authorization: String,
) {
    val origin: HttpUrl =
        origin.toHttpUrlOrNull()?.also {
            require(
                it.scheme == "https" &&
                    it.encodedPath == "/" &&
                    it.query == null &&
                    it.fragment == null &&
                    it.username.isEmpty() &&
                    it.password.isEmpty()
            ) {
                "Origin must be an HTTPS origin without URL credentials, path, query, or fragment"
            }
        } ?: throw IllegalArgumentException("Invalid HTTPS origin")

    init {
        require(credentialGeneration >= 0) { "Credential generation must be nonnegative" }
        require(
            authorization.isNotBlank() &&
                authorization.length <= 8_192 &&
                authorization.all { it.code in 0x20..0x7e }
        ) {
            "Invalid authorization header"
        }
    }

    internal fun authorizationHeader(): String = authorization

    override fun toString(): String =
        "ReadDestination(machineId=$machineId, origin=$origin, credentialGeneration=$credentialGeneration, authorization=<redacted>)"
}

data class ReadScope(val machineId: MachineId, val origin: String, val credentialGeneration: Long)

data class ScopedSession(val key: SessionKey, val summary: V2SessionSummary)

data class ScopedSessionPage(
    val scope: ReadScope,
    val sessions: List<ScopedSession>,
    val previous: String?,
    val next: String?,
)

data class ScopedHistory(val scope: ReadScope, val key: SessionKey, val decode: V2HistoryDecode)

sealed interface ReadFailure {
    data object AuthenticationRequired : ReadFailure

    data object TlsRejected : ReadFailure

    data object RedirectRejected : ReadFailure

    data object TooLarge : ReadFailure

    data object DecodeFailure : ReadFailure

    data object TransportUnavailable : ReadFailure

    data class HttpStatus(val code: Int) : ReadFailure
}

sealed interface ReadResult<out T> {
    data class Success<T>(val value: T) : ReadResult<T>

    data class Failure(val reason: ReadFailure) : ReadResult<Nothing>
}

/**
 * Read-only V2 subset observed against OpenCode 1.18.32. Health is reachability, not compatibility.
 */
class ReadOnlyV2Transport private constructor(baseClient: OkHttpClient) {
    constructor() : this(OkHttpClient())

    private val client =
        baseClient
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
                authenticator(okhttp3.Authenticator.NONE)
                proxyAuthenticator(okhttp3.Authenticator.NONE)
                cookieJar(okhttp3.CookieJar.NO_COOKIES)
                cache(null)
            }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .callTimeout(java.time.Duration.ofSeconds(20))
            .build()

    internal fun runningCallsForTests(): Int = client.dispatcher.runningCallsCount()

    suspend fun health(destination: ReadDestination): ReadResult<ReadScope> =
        get(destination, destination.origin.newBuilder().addPathSegments("api/health").build()) {
            body,
            scope ->
            V2WireCodec.health(body)
            scope
        }

    suspend fun sessions(
        destination: ReadDestination,
        cursor: String? = null,
    ): ReadResult<ScopedSessionPage> {
        if (cursor != null && cursor.isBlank()) return ReadResult.Failure(ReadFailure.DecodeFailure)
        val url =
            destination.origin
                .newBuilder()
                .addPathSegments("api/session")
                .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
                .build()
        return get(destination, url) { body, scope ->
            val page: V2SessionPage = V2WireCodec.sessions(body)
            ScopedSessionPage(
                scope,
                page.sessions.map { ScopedSession(SessionKey(scope.machineId, it.id), it) },
                page.previous,
                page.next,
            )
        }
    }

    suspend fun history(
        destination: ReadDestination,
        key: SessionKey,
        after: Long,
    ): ReadResult<ScopedHistory> {
        if (
            key.machineId != destination.machineId ||
                after < 0 ||
                !key.sessionId.value.startsWith("ses") ||
                !key.sessionId.value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        )
            return ReadResult.Failure(ReadFailure.DecodeFailure)
        val url =
            destination.origin
                .newBuilder()
                .addPathSegments("api/session")
                .addPathSegment(key.sessionId.value)
                .addPathSegment("history")
                .addQueryParameter("after", after.toString())
                .build()
        return get(destination, url) { body, scope ->
            ScopedHistory(scope, key, V2WireCodec.history(body, key.sessionId, after))
        }
    }

    private suspend fun <T> get(
        destination: ReadDestination,
        url: HttpUrl,
        decode: (String, ReadScope) -> T,
    ): ReadResult<T> {
        // The URL is built only from the captured origin. Even an HTTP redirect cannot move the
        // credential.
        if (
            url.scheme != "https" ||
                url.host != destination.origin.host ||
                url.port != destination.origin.port
        )
            return ReadResult.Failure(ReadFailure.RedirectRejected)
        val scope =
            ReadScope(
                destination.machineId,
                destination.origin.toString(),
                destination.credentialGeneration,
            )
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", destination.authorizationHeader())
                .get()
                .build()
        val raw =
            try {
                client.newCall(request).awaitBounded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SSLException) {
                return ReadResult.Failure(ReadFailure.TlsRejected)
            } catch (error: IOException) {
                val tlsCause = V2HttpBoundary.hasTlsFailure(error)
                return ReadResult.Failure(
                    if (tlsCause) ReadFailure.TlsRejected else ReadFailure.TransportUnavailable
                )
            }
        if (raw.tooLarge) return ReadResult.Failure(ReadFailure.TooLarge)
        if (raw.code == 401 || raw.code == 403)
            return ReadResult.Failure(ReadFailure.AuthenticationRequired)
        if (raw.code in 300..399) return ReadResult.Failure(ReadFailure.RedirectRejected)
        if (raw.code !in 200..299) return ReadResult.Failure(ReadFailure.HttpStatus(raw.code))
        val text =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw.body))
                    .toString()
            } catch (_: CharacterCodingException) {
                return ReadResult.Failure(ReadFailure.DecodeFailure)
            }
        return try {
            ReadResult.Success(decode(text, scope))
        } catch (_: V2WireException) {
            ReadResult.Failure(ReadFailure.DecodeFailure)
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
                                    val stream = received.body?.byteStream()
                                    val bytes = ByteArrayOutputStream()
                                    val buffer = ByteArray(8 * 1024)
                                    var tooLarge = false
                                    if (stream != null)
                                        while (true) {
                                            val count = stream.read(buffer)
                                            if (count < 0) break
                                            if (bytes.size() + count > MAX_BODY_BYTES) {
                                                tooLarge = true
                                                break
                                            }
                                            bytes.write(buffer, 0, count)
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

    private data class RawResponse(val code: Int, val body: ByteArray, val tooLarge: Boolean)

    companion object {
        private const val MAX_BODY_BYTES = 1_048_576

        /**
         * Injects TLS trust for local HTTPS fixtures; production uses the no-argument constructor.
         */
        internal fun forTests(tlsClient: OkHttpClient): ReadOnlyV2Transport =
            ReadOnlyV2Transport(tlsClient)
    }
}
