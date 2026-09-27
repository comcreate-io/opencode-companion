package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.SseDecodeFailure
import dev.local.opencodecompanion.protocol.V2GlobalEvent
import dev.local.opencodecompanion.protocol.transcript.TranscriptDecode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionV2StreamsTest {
    private val certificate =
        HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates =
        HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates =
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val server =
        MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
    private val trustedClient =
        OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .build()
    private val streams = SessionV2Streams.forTests(trustedClient)
    private val machine = MachineId("machine-a")
    private val session = SessionKey(machine, SessionId("ses_fixture123"))
    private val destination =
        ReadDestination(machine, server.url("/").toString(), 7, "Basic synthetic")

    @After
    fun close() {
        server.close()
    }

    @Test
    fun durableUsesExclusiveCursorAndKeepsUnsupportedEventExplicit() = runBlocking {
        server.enqueue(sse(frame(durableJson("future.event"))))
        val results = streams.durable(destination, session, 0).toList()
        val item = results[0] as StreamResult.Item<DurableFrame>
        assertEquals(machine, item.scope.machineId)
        assertTrue(item.value.decoded is TranscriptDecode.Unsupported)
        assertEquals(durableJson("future.event"), item.value.rawJson)
        assertTrue(results[1] is StreamResult.Eof)
        val request = server.takeRequest()
        assertEquals(
            "/api/session/ses_fixture123/event?after=0",
            request.url.encodedPath + "?" + request.url.encodedQuery,
        )
        assertEquals("Basic synthetic", request.headers["Authorization"])
    }

    @Test
    fun splitUtf8AndHeartbeatProduceOnlyScopedGlobalEvents() = runBlocking {
        val body =
            ": heartbeat\n\n" +
                frame(connectedJson()) +
                frame(textDeltaJson("ses_elsewhere", "café 🙂"))
        server.enqueue(sse(body).newBuilder().throttleBody(1, 1, TimeUnit.MILLISECONDS).build())
        val results = streams.global(destination).toList()
        assertEquals(3, results.size)
        assertEquals(
            V2GlobalEvent.Connected,
            (results[0] as StreamResult.Item<V2GlobalEvent>).value,
        )
        val text =
            (results[1] as StreamResult.Item<V2GlobalEvent>).value as V2GlobalEvent.ScopedText
        assertEquals(SessionKey(machine, SessionId("ses_elsewhere")), text.session)
        assertTrue(results[2] is StreamResult.Eof)
        assertEquals("/api/event", server.takeRequest().url.encodedPath)
    }

    @Test
    fun completedDurableFramePrecedesMalformedUtf8Failure() = runBlocking {
        val bytes =
            Buffer()
                .writeUtf8(frame(durableJson()))
                .writeUtf8("data: ")
                .write(byteArrayOf(0xc3.toByte(), 0x28))
                .writeUtf8("\n\n")
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(bytes)
                .build()
        )
        val results = streams.durable(destination, session, 0).toList()
        assertEquals(2, results.size)
        assertTrue(results[0] is StreamResult.Item)
        assertEquals(
            StreamFailure.Framing(SseDecodeFailure.MALFORMED_UTF8),
            (results[1] as StreamResult.Failure).reason,
        )
    }

    @Test
    fun oversizedFrameFailsWithoutEof() = runBlocking {
        server.enqueue(sse("data: " + "x".repeat(1_048_577) + "\n\n"))
        val results = streams.global(destination).toList()
        assertEquals(1, results.size)
        assertTrue((results.single() as StreamResult.Failure).reason is StreamFailure.Framing)
    }

    @Test
    fun boundedBackpressureDoesNotDropDurableFrames() = runBlocking {
        val body = (1..120).joinToString("") { frame(durableJson(seq = it)) }
        server.enqueue(sse(body))
        val results = streams.durable(destination, session, 0).onEach { delay(1) }.toList()
        val items = results.filterIsInstance<StreamResult.Item<*>>()
        assertEquals(120, items.size)
        assertEquals(
            (1L..120L).toList(),
            items.map {
                ((it.value as DurableFrame).decoded as TranscriptDecode.Supported).event.sequence
            },
        )
        assertTrue(results.last() is StreamResult.Eof)
    }

    @Test
    fun wrongMachineAndUnsafeSessionNeverDispatch() = runBlocking {
        val wrong = SessionKey(MachineId("machine-b"), session.sessionId)
        assertEquals(
            StreamFailure.Http(ReadFailure.DecodeFailure),
            (streams.durable(destination, wrong, 0).first() as StreamResult.Failure).reason,
        )
        val unsafe = SessionKey(machine, SessionId(".."))
        assertEquals(
            StreamFailure.Http(ReadFailure.DecodeFailure),
            (streams.durable(destination, unsafe, 0).first() as StreamResult.Failure).reason,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirectCannotForwardCredentialAndWrongTlsIsTyped() = runBlocking {
        val other =
            MockWebServer().apply {
                useHttps(serverCertificates.sslSocketFactory())
                start()
            }
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", other.url("/steal").toString())
                    .build()
            )
            val result = streams.global(destination).first() as StreamResult.Failure
            assertEquals(StreamFailure.Http(ReadFailure.RedirectRejected), result.reason)
            assertEquals("Basic synthetic", server.takeRequest().headers["Authorization"])
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
        // Keep the TLS fixture ready through the handshake; trust must fail before response data.
        server.enqueue(sse(frame(connectedJson())))
        val untrusted = SessionV2Streams()
        val tls = untrusted.global(destination).first() as StreamResult.Failure
        assertEquals(StreamFailure.Http(ReadFailure.TlsRejected), tls.reason)
    }

    @Test
    fun cancellationDuringHeadersAndBodyStopsUnderlyingCalls() = runBlocking {
        server.enqueue(
            sse(frame(connectedJson())).newBuilder().headersDelay(5, TimeUnit.SECONDS).build()
        )
        val waiting = async { streams.global(destination).first() }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertTrue(streams.runningCallsForTests() > 0)
        waiting.cancel()
        withTimeout(2_000) { while (streams.runningCallsForTests() != 0) delay(10) }

        server.enqueue(
            sse(frame(connectedJson()) + "data: " + "x".repeat(4_096))
                .newBuilder()
                .throttleBody(250, 1, TimeUnit.SECONDS)
                .build()
        )
        assertEquals(
            V2GlobalEvent.Connected,
            (streams.global(destination).first() as StreamResult.Item<V2GlobalEvent>).value,
        )
        withTimeout(2_000) { while (streams.runningCallsForTests() != 0) delay(10) }
    }

    @Test(timeout = 35_000)
    fun streamBodyCanOutliveFiniteTwentySecondDeadline() = runBlocking {
        server.enqueue(
            sse(frame(connectedJson())).newBuilder().bodyDelay(21, TimeUnit.SECONDS).build()
        )
        val results = streams.global(destination).toList()
        assertEquals(
            V2GlobalEvent.Connected,
            (results.first() as StreamResult.Item<V2GlobalEvent>).value,
        )
        assertTrue(results.last() is StreamResult.Eof)
    }

    @Test
    fun htmlSuccessAndKnownTextIdentityFailureAreExplicit() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("<html></html>").build())
        assertEquals(
            StreamFailure.Protocol,
            (streams.global(destination).first() as StreamResult.Failure).reason,
        )
        server.enqueue(sse(frame(textDeltaJson("", "bad"))))
        assertEquals(
            StreamFailure.Protocol,
            (streams.global(destination).first() as StreamResult.Failure).reason,
        )
    }

    private fun sse(body: String): MockResponse =
        MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream; charset=utf-8")
            .body(body)
            .build()

    private fun frame(json: String) = "data: $json\n\n"

    private fun connectedJson() = """{"id":"evt_connected","type":"server.connected","data":{}}"""

    private fun textDeltaJson(sessionId: String, delta: String) =
        """{"id":"evt_delta","type":"session.next.text.delta","data":{"sessionID":"$sessionId","assistantMessageID":"msg_a","textID":"text_a","delta":"$delta"}}"""

    private fun durableJson(type: String = "session.next.prompt.admitted", seq: Int = 1): String =
        """{"id":"evt_$seq","type":"$type","durable":{"aggregateID":"ses_fixture123","seq":$seq,"version":1},"data":{"timestamp":1,"sessionID":"ses_fixture123","messageID":"msg_$seq","prompt":{"text":"Synthetic"},"delivery":"steer"}}"""
}
