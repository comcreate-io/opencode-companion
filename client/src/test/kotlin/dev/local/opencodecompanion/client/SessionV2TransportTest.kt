package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2CreateSessionCommand
import dev.local.opencodecompanion.protocol.V2Delivery
import dev.local.opencodecompanion.protocol.V2LocationSelection
import dev.local.opencodecompanion.protocol.V2PromptCommand
import dev.local.opencodecompanion.protocol.V2RequestCodec
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionV2TransportTest {
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
    private val transport = SessionV2Transport.forTests(trustedClient)
    private val machine = MachineId("machine-a")
    private val sessionId = SessionId("ses_f1eb18b8affefNQDAGh7yREtsz")
    private val key = SessionKey(machine, sessionId)
    private val destination =
        ReadDestination(
            machine,
            server.url("/").newBuilder().host("127.0.0.1").build().toString(),
            3,
            "Basic synthetic",
        )

    @After
    fun close() {
        server.close()
    }

    @Test
    fun readsExactSessionHistoryAndPendingRoutes() = runBlocking {
        server.enqueue(response(fixture("session")))
        val session = transport.session(destination, key)
        assertTrue(session is ReadResult.Success)
        assertEquals(key.sessionId, (session as ReadResult.Success).value.value.id)
        assertEquals("/api/session/${sessionId.value}", server.takeRequest().url.encodedPath)

        server.enqueue(response(fixture("read-history")))
        val history = transport.history(destination, key, 0)
        assertTrue(
            history is ReadResult.Failure
        ) // Captured read history belongs to another session.
        val historyRequest = server.takeRequest()
        assertEquals(
            "/api/session/${sessionId.value}/history?after=0&limit=100",
            historyRequest.url.encodedPath + "?" + historyRequest.url.encodedQuery,
        )

        val permissionKey = SessionKey(machine, SessionId("ses_f1e7b03b6ffedIekrOo8h39Alt"))
        server.enqueue(response("{\"data\":[${fixture("requests/permission-pending")}] }"))
        val permissions = transport.permissions(destination, permissionKey)
        assertTrue(permissions is ReadResult.Success)
        assertEquals(
            permissionKey,
            (permissions as ReadResult.Success).value.value.single().sessionKey,
        )
        assertEquals(
            "/api/session/${permissionKey.sessionId.value}/permission",
            server.takeRequest().url.encodedPath,
        )

        val questionKey = SessionKey(machine, SessionId("ses_f1e7a61fdfferPc0b3uxJLFS29"))
        server.enqueue(response("{\"data\":${fixture("requests/question-pending")}}"))
        val questions = transport.questions(destination, questionKey)
        assertTrue(questions is ReadResult.Success)
        assertEquals(questionKey, (questions as ReadResult.Success).value.value.single().sessionKey)
        assertEquals(
            "/api/session/${questionKey.sessionId.value}/question",
            server.takeRequest().url.encodedPath,
        )
    }

    @Test
    fun createAndPromptRequireExactCorrelatedSuccess() = runBlocking {
        server.enqueue(response(fixture("session")))
        val created =
            transport.create(
                destination,
                V2CreateSessionCommand(
                    location = V2LocationSelection("/fixture/repo"),
                    requestedSessionId = sessionId,
                ),
            )
        assertTrue(created is MutationResult.Acknowledged)
        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        assertEquals("/api/session", createRequest.url.encodedPath)
        assertTrue(
            requireNotNull(createRequest.body).utf8().contains("\"id\":\"${sessionId.value}\"")
        )

        val command =
            V2PromptCommand(
                key,
                "msg_m1probe000000000000000001",
                "Synthetic M1 admission only; do not execute.",
                V2Delivery.Steer,
            )
        server.enqueue(response(fixture("admission")))
        val admitted = transport.prompt(destination, command)
        assertTrue(admitted is MutationResult.Acknowledged)
        assertEquals(command.messageId, (admitted as MutationResult.Acknowledged).value.id)
        val promptRequest = server.takeRequest()
        assertEquals("/api/session/${sessionId.value}/prompt", promptRequest.url.encodedPath)
        assertEquals("Basic synthetic", promptRequest.headers["Authorization"])
        assertTrue(requireNotNull(promptRequest.body).utf8().contains("\"resume\":true"))

        server.enqueue(response(fixture("admission").replace(command.messageId, "msg_other")))
        assertTrue(transport.prompt(destination, command) is MutationResult.OutcomeUnknown)
        server.takeRequest()
        server.enqueue(response(fixture("conflict"), 409))
        assertEquals(
            MutationResult.Rejected(destination.scopeForTest(), ReadFailure.HttpStatus(409)),
            transport.prompt(destination, command),
        )
        server.takeRequest()
        assertEquals(4, server.requestCount)
    }

    @Test
    fun mutationsHandle204StaleAndAmbiguousSuccess() =
        runBlocking<Unit> {
            server.enqueue(response("", 204))
            assertTrue(transport.interrupt(destination, key) is MutationResult.Acknowledged)
            assertEquals(
                "/api/session/${sessionId.value}/interrupt",
                server.takeRequest().url.encodedPath,
            )

            val permissionKey = SessionKey(machine, SessionId("ses_f1e7b03b6ffedIekrOo8h39Alt"))
            val permission =
                V2RequestCodec.permissions(
                        "{\"data\":[${fixture("requests/permission-pending")}]}",
                        permissionKey,
                    )
                    .single()
            server.enqueue(response("", 204))
            assertTrue(
                transport.permissionReply(
                    destination,
                    permission,
                    dev.local.opencodecompanion.protocol.V2PermissionReply.ONCE,
                ) is MutationResult.Acknowledged
            )
            val reply = server.takeRequest()
            assertEquals(
                "/api/session/${permissionKey.sessionId.value}/permission/${permission.id}/reply",
                reply.url.encodedPath,
            )
            assertTrue(requireNotNull(reply.body).utf8().contains("\"reply\":\"once\""))

            server.enqueue(response("not found", 404))
            assertEquals(
                MutationResult.OutcomeUnknown(destination.scopeForTest()),
                transport.permissionReply(
                    destination,
                    permission,
                    dev.local.opencodecompanion.protocol.V2PermissionReply.REJECT,
                ),
            )
            server.takeRequest()

            server.enqueue(response("<!doctype html>"))
            assertTrue(
                transport.prompt(
                    destination,
                    V2PromptCommand(key, "msg_html", "x", V2Delivery.Steer),
                ) is MutationResult.OutcomeUnknown
            )
            server.takeRequest()
            server.enqueue(response("garbage", 200))
            assertTrue(transport.interrupt(destination, key) is MutationResult.OutcomeUnknown)
            server.takeRequest()
        }

    @Test
    fun lostResponseIsUnknownAndNeverRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val result =
            transport.prompt(destination, V2PromptCommand(key, "msg_lost", "x", V2Delivery.Steer))
        assertTrue(result is MutationResult.OutcomeUnknown)
        val sent = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertEquals("/api/session/${sessionId.value}/prompt", sent?.url?.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retryAfter503NeverResendsPromptOrEmptyInterrupt() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("busy").build()
        )
        server.enqueue(response(fixture("admission")))
        val prompt =
            transport.prompt(
                destination,
                V2PromptCommand(
                    key,
                    "msg_m1probe000000000000000001",
                    "Synthetic M1 admission only; do not execute.",
                    V2Delivery.Steer,
                ),
            )
        assertTrue(prompt is MutationResult.OutcomeUnknown)
        assertEquals("/api/session/${sessionId.value}/prompt", server.takeRequest().url.encodedPath)
        assertEquals(1, server.requestCount)

        // A separate server avoids the deliberately queued success from the first assertion.
        val other =
            MockWebServer().apply {
                useHttps(serverCertificates.sslSocketFactory())
                start()
            }
        try {
            val otherDestination =
                ReadDestination(
                    machine,
                    other.url("/").newBuilder().host("127.0.0.1").build().toString(),
                    3,
                    "Basic synthetic",
                )
            other.enqueue(
                MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("busy").build()
            )
            other.enqueue(response("", 204))
            assertTrue(transport.interrupt(otherDestination, key) is MutationResult.OutcomeUnknown)
            assertEquals(
                "/api/session/${sessionId.value}/interrupt",
                other.takeRequest().url.encodedPath,
            )
            assertEquals(1, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun ambiguousClientErrorsAreUnknownAndEachMutationIsSentOnce() = runBlocking {
        val replies =
            listOf(
                response("gateway timeout", 408),
                response("<!doctype html>", 400),
                response(
                    "{\"_tag\":\"ConflictError\",\"resource\":\"msg_other\",\"message\":\"unrelated\"}",
                    409,
                ),
            )
        replies.forEachIndexed { index, reply ->
            server.enqueue(reply)
            val result =
                transport.prompt(
                    destination,
                    V2PromptCommand(key, "msg_ambiguous_$index", "x", V2Delivery.Steer),
                )
            assertTrue(result is MutationResult.OutcomeUnknown)
            assertEquals(index + 1, server.requestCount)
            assertEquals(
                "/api/session/${sessionId.value}/prompt",
                server.takeRequest().url.encodedPath,
            )
        }
    }

    @Test
    fun catalogIsScopedAndDropsProviderConfiguration() = runBlocking {
        server.enqueue(response(fixture("model-catalog")))
        val result = transport.models(destination)
        assertTrue(result is ReadResult.Success)
        val page = (result as ReadResult.Success).value
        assertEquals(machine, page.scope.machineId)
        assertEquals("fixture-model", page.value.items.single().id)
        assertEquals("/api/model", server.takeRequest().url.encodedPath)
        assertFalse(page.toString().contains("context"))
    }

    @Test
    fun bodyCapTlsAndCancelledMutationRemainConservative() = runBlocking {
        server.enqueue(response("x".repeat(1_048_577)))
        assertEquals(ReadResult.Failure(ReadFailure.TooLarge), transport.session(destination, key))
        server.takeRequest()
        server.enqueue(response("x".repeat(1_048_577)))
        assertTrue(transport.interrupt(destination, key) is MutationResult.OutcomeUnknown)
        server.takeRequest()

        val systemTrust = SessionV2Transport()
        assertEquals(
            ReadResult.Failure(ReadFailure.TlsRejected),
            systemTrust.session(destination, key),
        )
        assertTrue(systemTrust.interrupt(destination, key) is MutationResult.OutcomeUnknown)

        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val pending = async(Dispatchers.IO) { transport.interrupt(destination, key) }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        pending.cancel()
        try {
            pending.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
            /* expected */
        }
        withTimeout(2_000) { while (transport.runningCallsForTests() != 0) delay(10) }
    }

    @Test
    fun wrongMachineAndRedirectNeverForwardAuthorization() = runBlocking {
        val wrong = SessionKey(MachineId("machine-b"), sessionId)
        assertEquals(
            ReadResult.Failure(ReadFailure.DecodeFailure),
            transport.session(destination, wrong),
        )
        assertTrue(
            transport.prompt(
                destination,
                V2PromptCommand(wrong, "msg_wrong", "x", V2Delivery.Steer),
            ) is MutationResult.NotDispatched
        )
        assertEquals(0, server.requestCount)
        val other =
            MockWebServer().apply {
                useHttps(serverCertificates.sslSocketFactory())
                start()
            }
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", other.url("/capture"))
                    .build()
            )
            assertTrue(
                transport.prompt(
                    destination,
                    V2PromptCommand(key, "msg_redirect", "x", V2Delivery.Steer),
                ) is MutationResult.OutcomeUnknown
            )
            assertEquals("Basic synthetic", server.takeRequest().headers["Authorization"])
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    private fun response(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).build()

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/opencode/1.18.32/$name.json")).readText()

    private fun ReadDestination.scopeForTest() =
        ReadScope(machineId, origin.toString(), credentialGeneration)
}
