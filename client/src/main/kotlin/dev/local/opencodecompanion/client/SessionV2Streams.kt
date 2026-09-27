package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.SseDecodeException
import dev.local.opencodecompanion.protocol.SseDecodeFailure
import dev.local.opencodecompanion.protocol.SseDecoder
import dev.local.opencodecompanion.protocol.SseFrame
import dev.local.opencodecompanion.protocol.V2GlobalEvent
import dev.local.opencodecompanion.protocol.V2GlobalEventCodec
import dev.local.opencodecompanion.protocol.V2WireException
import dev.local.opencodecompanion.protocol.transcript.TranscriptDecode
import dev.local.opencodecompanion.protocol.transcript.TranscriptDecodeException
import dev.local.opencodecompanion.protocol.transcript.V2Transcript
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The raw durable event is retained for a later coordinator to persist with its cursor. */
data class DurableFrame(val rawJson: String, val decoded: TranscriptDecode)

sealed interface StreamFailure {
    data class Http(val reason: ReadFailure) : StreamFailure

    data class Framing(val reason: SseDecodeFailure) : StreamFailure

    data object Protocol : StreamFailure
}

sealed interface StreamResult<out T> {
    val scope: ReadScope

    data class Item<T>(override val scope: ReadScope, val value: T) : StreamResult<T>

    data class Failure(override val scope: ReadScope, val reason: StreamFailure) :
        StreamResult<Nothing>

    /**
     * A connection ended after headers due to transport/idle failure; no host state is inferred.
     */
    data class Disconnected(override val scope: ReadScope) : StreamResult<Nothing>

    /** The server closed a correctly framed response. This is not a durable replay cursor. */
    data class Eof(override val scope: ReadScope) : StreamResult<Nothing>
}

/**
 * Cold, single-connection SSE reads. The collector owns the HTTP call; cancellation closes its
 * response and cancels the call. No reconnect, cursor persistence, or host-status inference occurs.
 */
class SessionV2Streams private constructor(baseClient: OkHttpClient) {
    constructor() : this(OkHttpClient())

    private val client = V2HttpBoundary.harden(baseClient, finite = false)

    internal fun runningCallsForTests(): Int = client.dispatcher.runningCallsCount()

    fun durable(
        destination: ReadDestination,
        key: SessionKey,
        after: Long,
    ): Flow<StreamResult<DurableFrame>> {
        if (
            key.machineId != destination.machineId ||
                after < 0 ||
                !key.sessionId.value.startsWith("ses") ||
                !key.sessionId.value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        ) {
            return failureFlow(destination, StreamFailure.Http(ReadFailure.DecodeFailure))
        }
        val url =
            destination.origin
                .newBuilder()
                .addPathSegments("api/session")
                .addPathSegment(key.sessionId.value)
                .addPathSegment("event")
                .addQueryParameter("after", after.toString())
                .build()
        return stream(
            destination,
            V2HttpBoundary.authorizedRequest(destination, url).get().build(),
        ) { frame ->
            DurableFrame(frame.data, V2Transcript.event(frame.data, key))
        }
    }

    fun global(destination: ReadDestination): Flow<StreamResult<V2GlobalEvent>> {
        val url = destination.origin.newBuilder().addPathSegments("api/event").build()
        return stream(
            destination,
            V2HttpBoundary.authorizedRequest(destination, url).get().build(),
        ) { frame ->
            V2GlobalEventCodec.decode(frame.data, destination.machineId)
        }
    }

    private fun <T> failureFlow(
        destination: ReadDestination,
        reason: StreamFailure,
    ): Flow<StreamResult<T>> = flowOf(StreamResult.Failure(scope(destination), reason))

    private fun <T> stream(
        destination: ReadDestination,
        request: Request,
        decode: (SseFrame) -> T,
    ): Flow<StreamResult<T>> =
        callbackFlow {
                val captured = scope(destination)
                val call = client.newCall(request)
                val activeResponse = AtomicReference<Response?>(null)

                fun publish(result: StreamResult<T>): Boolean = trySendBlocking(result).isSuccess

                fun process(frame: SseFrame): Boolean =
                    try {
                        publish(StreamResult.Item(captured, decode(frame)))
                    } catch (_: V2WireException) {
                        publish(StreamResult.Failure(captured, StreamFailure.Protocol))
                        false
                    } catch (_: TranscriptDecodeException) {
                        publish(StreamResult.Failure(captured, StreamFailure.Protocol))
                        false
                    }

                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, error: IOException) {
                            if (!call.isCanceled()) {
                                publish(
                                    StreamResult.Failure(
                                        captured,
                                        StreamFailure.Http(
                                            if (V2HttpBoundary.hasTlsFailure(error))
                                                ReadFailure.TlsRejected
                                            else ReadFailure.TransportUnavailable
                                        ),
                                    )
                                )
                            }
                            close()
                        }

                        override fun onResponse(call: Call, response: Response) {
                            activeResponse.set(response)
                            try {
                                response.use { received ->
                                    val failure = responseFailure(received)
                                    if (failure != null) {
                                        publish(StreamResult.Failure(captured, failure))
                                        return@use
                                    }
                                    val input = received.body?.byteStream()
                                    if (input == null) {
                                        publish(
                                            StreamResult.Failure(captured, StreamFailure.Protocol)
                                        )
                                        return@use
                                    }
                                    val decoder =
                                        SseDecoder(
                                            maxLineBytes = FRAME_BYTES,
                                            maxFrameBytes = FRAME_BYTES,
                                        )
                                    val buffer = ByteArray(8 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) {
                                            try {
                                                decoder.finish()
                                                publish(StreamResult.Eof(captured))
                                            } catch (error: SseDecodeException) {
                                                publish(
                                                    StreamResult.Failure(
                                                        captured,
                                                        StreamFailure.Framing(error.reason),
                                                    )
                                                )
                                            }
                                            break
                                        }
                                        try {
                                            for (frame in decoder.feed(buffer.copyOf(count))) {
                                                if (!process(frame)) return@use
                                            }
                                        } catch (error: SseDecodeException) {
                                            for (frame in error.completedFrames) {
                                                if (!process(frame)) return@use
                                            }
                                            publish(
                                                StreamResult.Failure(
                                                    captured,
                                                    StreamFailure.Framing(error.reason),
                                                )
                                            )
                                            break
                                        }
                                    }
                                }
                            } catch (_: IOException) {
                                if (!call.isCanceled()) publish(StreamResult.Disconnected(captured))
                            } finally {
                                activeResponse.set(null)
                                close()
                            }
                        }
                    }
                )
                awaitClose {
                    call.cancel()
                    activeResponse.getAndSet(null)?.close()
                }
            }
            .buffer(capacity = 1, onBufferOverflow = BufferOverflow.SUSPEND)

    private fun responseFailure(response: Response): StreamFailure? {
        val code = response.code
        if (code == 401 || code == 403)
            return StreamFailure.Http(ReadFailure.AuthenticationRequired)
        if (code in 300..399) return StreamFailure.Http(ReadFailure.RedirectRejected)
        if (code != 200) return StreamFailure.Http(ReadFailure.HttpStatus(code))
        val type = response.header("Content-Type")?.substringBefore(';')?.trim()
        if (!type.equals("text/event-stream", ignoreCase = true)) return StreamFailure.Protocol
        return null
    }

    private fun scope(destination: ReadDestination): ReadScope =
        ReadScope(
            destination.machineId,
            destination.origin.toString(),
            destination.credentialGeneration,
        )

    companion object {
        private const val FRAME_BYTES = 1_048_576

        /**
         * Supplies only local TLS trust in tests; the hardened client removes inherited
         * interceptors.
         */
        internal fun forTests(tlsClient: OkHttpClient): SessionV2Streams =
            SessionV2Streams(tlsClient)
    }
}
