package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2HistoryDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyV2TransportTest {
    private val certificate =
        HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
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
    private val transport = ReadOnlyV2Transport.forTests(trustedClient)
    private val machine = MachineId("machine-a")
    private val destination =
        ReadDestination(
            machine,
            server.url("/").newBuilder().host("127.0.0.1").build().toString(),
            7,
            "Basic synthetic",
        )

    @After
    fun close() {
        server.close()
    }

    @Test
    fun capturedHealthAndSessionsKeepImmutableScope() = runBlocking {
        server.enqueue(response(fixture("health")))
        val health = transport.health(destination)
        assertEquals(
            ReadResult.Success(ReadScope(machine, destination.origin.toString(), 7)),
            health,
        )
        assertEquals("Basic synthetic", server.takeRequest().headers["Authorization"])

        server.enqueue(response(fixture("sessions")))
        val result = transport.sessions(destination)
        assertTrue(result is ReadResult.Success)
        val page = (result as ReadResult.Success).value
        assertEquals(machine, page.sessions.single().key.machineId)
        assertEquals(
            SessionId("ses_f1eb18b8affefNQDAGh7yREtsz"),
            page.sessions.single().key.sessionId,
        )
        assertEquals("/api/session", server.takeRequest().url.encodedPath)
        assertFalse(destination.toString().contains("Basic synthetic"))
    }

    @Test
    fun redirectDoesNotForwardCredential() = runBlocking {
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
            assertEquals(
                ReadResult.Failure(ReadFailure.RedirectRejected),
                transport.health(destination),
            )
            assertEquals("Basic synthetic", server.takeRequest().headers["Authorization"])
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun authHtmlAndBodyCapAreTypedFailures() = runBlocking {
        server.enqueue(response("denied", 401))
        assertEquals(
            ReadResult.Failure(ReadFailure.AuthenticationRequired),
            transport.health(destination),
        )
        server.enqueue(response("<!doctype html><html></html>"))
        assertEquals(ReadResult.Failure(ReadFailure.DecodeFailure), transport.health(destination))
        server.enqueue(response("x".repeat(1_048_577)))
        assertEquals(ReadResult.Failure(ReadFailure.TooLarge), transport.health(destination))
    }

    @Test
    fun wrongMachineDoesNotDispatchHistoryAndUnknownEventStaysUnsupported() = runBlocking {
        val session = SessionId("ses_f1eb18b8affefNQDAGh7yREtsz")
        assertEquals(
            ReadResult.Failure(ReadFailure.DecodeFailure),
            transport.history(destination, SessionKey(MachineId("machine-b"), session), 0),
        )
        assertEquals(
            ReadResult.Failure(ReadFailure.DecodeFailure),
            transport.history(destination, SessionKey(machine, SessionId("..")), 0),
        )
        assertEquals(
            ReadResult.Failure(ReadFailure.DecodeFailure),
            transport.history(destination, SessionKey(machine, SessionId("ses_/other")), 0),
        )
        assertEquals(0, server.requestCount)
        server.enqueue(
            response(fixture("history").replace("session.next.prompt.admitted", "future.event"))
        )
        val result = transport.history(destination, SessionKey(machine, session), 0)
        assertTrue(result is ReadResult.Success)
        assertTrue((result as ReadResult.Success).value.decode is V2HistoryDecode.Unsupported)
        val request = server.takeRequest()
        assertEquals(
            "/api/session/${session.value}/history?after=0",
            request.url.encodedPath + "?" + request.url.encodedQuery,
        )
    }

    @Test
    fun tlsFailureIsDistinct() = runBlocking {
        val untrusted = ReadOnlyV2Transport()
        assertEquals(ReadResult.Failure(ReadFailure.TlsRejected), untrusted.health(destination))
    }

    @Test
    fun cancellationStopsWaitingWithoutReturningFailure() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .headersDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                .body(fixture("health"))
                .build()
        )
        val waiting = async { transport.health(destination) }
        withContext(Dispatchers.IO) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(transport.runningCallsForTests() > 0)
        waiting.cancel()
        assertTrue(waiting.isCancelled)
        withTimeout(2_000) { while (transport.runningCallsForTests() != 0) delay(10) }
    }

    @Test
    fun cancellationDuringBodyReadStopsWaiting() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(fixture("health") + "x".repeat(32 * 1024))
                .throttleBody(8, 1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        val waiting = async { transport.health(destination) }
        withContext(Dispatchers.IO) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) }
        delay(100)
        assertTrue(transport.runningCallsForTests() > 0)
        waiting.cancel()
        assertTrue(waiting.isCancelled)
        withTimeout(2_000) { while (transport.runningCallsForTests() != 0) delay(10) }
    }

    @Test
    fun collidingSessionIdsKeepMachineScope() = runBlocking {
        server.enqueue(response(fixture("sessions")))
        server.enqueue(response(fixture("sessions")))
        val other =
            ReadDestination(
                MachineId("machine-b"),
                destination.origin.toString(),
                9,
                "Basic second",
            )
        val firstPage = (transport.sessions(destination) as ReadResult.Success).value
        val secondPage = (transport.sessions(other) as ReadResult.Success).value
        assertEquals(
            firstPage.sessions.single().key.sessionId,
            secondPage.sessions.single().key.sessionId,
        )
        assertFalse(firstPage.sessions.single().key == secondPage.sessions.single().key)
        assertEquals("Basic synthetic", server.takeRequest().headers["Authorization"])
        assertEquals("Basic second", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun originMustBeAnHttpsRootWithoutEmbeddedCredentials() {
        for (origin in
            listOf(
                "http://localhost/",
                "https://user:secret@localhost/",
                "https://localhost/other",
                "https://localhost/?x=1",
            )) {
            try {
                ReadDestination(machine, origin, 1, "Basic synthetic")
                org.junit.Assert.fail("Expected rejected origin")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test
    fun inheritedInterceptorsCannotAlterAuthorization() = runBlocking {
        val hostileBase =
            trustedClient
                .newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header("Authorization", "Basic injected")
                            .build()
                    )
                }
                .build()
        val guarded = ReadOnlyV2Transport.forTests(hostileBase)
        server.enqueue(response(fixture("health")))
        assertTrue(guarded.health(destination) is ReadResult.Success)
        assertEquals("Basic synthetic", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun authorizationHeaderRejectsControlsAndNonAsciiBeforeDispatch() {
        for (bad in
            listOf("Basic x\t", "Basic x\u007f", "Basic café", "Basic " + "x".repeat(8192))) {
            try {
                ReadDestination(machine, server.url("/").toString(), 7, bad)
                org.junit.Assert.fail("Expected invalid authorization header")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, server.requestCount)
            }
        }
    }

    private fun response(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).body(body).build()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/opencode/1.18.32/$name.json")).readText()
}
