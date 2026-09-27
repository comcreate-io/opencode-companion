package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V2WireCodecTest {
    private val sessionId = SessionId("ses_f1eb18b8affefNQDAGh7yREtsz")
    private val messageId = "msg_m1probe000000000000000001"

    @Test
    fun capturedHealthAndSessionResponses() {
        V2WireCodec.health(fixture("health"))
        val session = V2WireCodec.session(fixture("session"))
        assertEquals(sessionId, session.id)
        assertEquals(ProjectId("global"), session.projectId)
        assertEquals("/fixture/repo", session.directory)
        assertEquals(1790486410373L, session.created)

        val page = V2WireCodec.sessions(fixture("sessions"))
        assertEquals(listOf(session), page.sessions)
        assertTrue(page.previous!!.isNotEmpty())
        assertTrue(page.next!!.isNotEmpty())
    }

    @Test
    fun capturedAdmissionAndHistory() {
        val admission = V2WireCodec.admission(fixture("admission"), sessionId, messageId)
        assertEquals("msg_m1probe000000000000000001", admission.id)
        assertEquals(1L, admission.admittedSequence)
        assertEquals("steer", admission.delivery)
        val decoded = V2WireCodec.history(fixture("history"), sessionId, 0)
        assertTrue(decoded is V2HistoryDecode.Supported)
        val page = (decoded as V2HistoryDecode.Supported).page
        assertFalse(page.hasMore)
        assertEquals(admission.id, page.events.single().messageId)
        assertEquals(admission.promptText, page.events.single().promptText)
        assertEquals(
            admission.id,
            V2WireCodec.promptConflict(fixture("conflict"), admission.id).messageId,
        )
    }

    @Test
    fun cursorEnvelopeIsRequiredButItsDirectionsCanBeMissingOrNull() {
        val base = Json.parseToJsonElement(fixture("sessions")).jsonObject
        assertInvalid { V2WireCodec.sessions(JsonObject(base - "cursor").toString()) }
        assertInvalid { V2WireCodec.sessions(JsonObject(base + ("cursor" to JsonNull)).toString()) }
        assertEquals(
            null,
            V2WireCodec.sessions(JsonObject(base + ("cursor" to JsonObject(emptyMap()))).toString())
                .next,
        )
        val nullDirections = JsonObject(mapOf("previous" to JsonNull, "next" to JsonNull))
        assertEquals(
            null,
            V2WireCodec.sessions(JsonObject(base + ("cursor" to nullDirections)).toString()).next,
        )
        assertInvalid {
            V2WireCodec.sessions(JsonObject(base + ("cursor" to JsonPrimitive(7))).toString())
        }
    }

    @Test
    fun preservesLocationAndAllowsEmptyDisplayText() {
        val session =
            fixture("session")
                .replace("\"title\":\"New session - 2026-09-27T05:20:10.373Z\"", "\"title\":\"\"")
                .replace(
                    "\"location\":{\"directory\":\"/fixture/repo\"}",
                    "\"location\":{\"directory\":\"/fixture/repo\",\"workspaceID\":\"wrk_one\"},\"subpath\":\"src\"",
                )
        val decoded = V2WireCodec.session(session)
        assertEquals("", decoded.title)
        assertEquals("wrk_one", decoded.workspaceId)
        assertEquals("src", decoded.subpath)
        val admission =
            fixture("admission").replace("Synthetic M1 admission only; do not execute.", "")
        assertEquals("", V2WireCodec.admission(admission, sessionId, messageId).promptText)
    }

    @Test
    fun requiredFieldsAndTypesFailClosedWithoutEchoingPayload() {
        assertInvalid { V2WireCodec.health("{\"healthy\":false}") }
        assertInvalid { V2WireCodec.health("{\"healthy\":\"true\"}") }
        assertInvalid { V2WireCodec.health("<!doctype html><html><body>Application</body></html>") }
        assertInvalid {
            V2WireCodec.session(fixture("session").replace("\"projectID\":\"global\",", ""))
        }
        assertInvalid {
            V2WireCodec.admission(
                fixture("admission").replace("\"admittedSeq\":1", "\"admittedSeq\":\"1\""),
                sessionId,
                messageId,
            )
        }
        assertInvalid { V2WireCodec.admission(fixture("admission"), SessionId("other"), messageId) }
        assertInvalid { V2WireCodec.admission(fixture("admission"), sessionId, "other") }
        assertInvalid {
            V2WireCodec.admission(
                fixture("admission").replace("\"delivery\":\"steer\"", "\"delivery\":\"later\""),
                sessionId,
                messageId,
            )
        }
        assertInvalid { V2WireCodec.promptConflict(fixture("conflict"), "other") }
        assertInvalid { V2WireCodec.history(fixture("history"), SessionId("other"), 0) }
        assertInvalid { V2WireCodec.health("{\"healthy\":true,\"secret\":\"SENSITIVE\"") }
    }

    @Test
    fun unknownDurableSemanticsNeverReturnAnApplicablePage() {
        val history = fixture("history")
        val unknownType =
            V2WireCodec.history(
                history.replace("session.next.prompt.admitted", "future.event"),
                sessionId,
                0,
            )
        assertTrue(unknownType is V2HistoryDecode.Unsupported)
        assertEquals(1L, (unknownType as V2HistoryDecode.Unsupported).sequence)
        val unknownVersion =
            V2WireCodec.history(history.replace("\"version\":1", "\"version\":2"), sessionId, 0)
        assertTrue(unknownVersion is V2HistoryDecode.Unsupported)
    }

    @Test
    fun sequenceOrderIsStrictButDoesNotAssumeContiguity() {
        val history = fixture("history")
        val sparse = history.replace("\"seq\":1", "\"seq\":3")
        assertTrue(V2WireCodec.history(sparse, sessionId, 1) is V2HistoryDecode.Supported)
        assertInvalid { V2WireCodec.history(history, sessionId, 1) }
        assertInvalid {
            V2WireCodec.history(history.replace("\"seq\":1", "\"seq\":\"1\""), sessionId, 0)
        }
        assertInvalid {
            V2WireCodec.history(
                history.replace("\"sessionID\":\"${sessionId.value}\"", "\"sessionID\":\"other\""),
                sessionId,
                0,
            )
        }
    }

    @Test
    fun boundsRejectOversizedAndDeepJson() {
        assertInvalid { V2WireCodec.health("x".repeat(1_048_577)) }
        assertInvalid { V2WireCodec.health("[".repeat(65) + "]".repeat(65)) }
    }

    @Test
    fun realReplaySseFramesDecodeThroughTheSameDurableContract() {
        val decoder = SseDecoder()
        val frames = decoder.feed(fixtureText("durable-replay.sse").toByteArray(Charsets.UTF_8))
        decoder.finish()
        val replaySession = SessionId("ses_f1eae4fb4ffevgTGevR6cHTeFK")
        assertEquals(1, frames.size)
        val replay = V2WireCodec.durableEvent(frames.single().data, replaySession, 0)
        assertTrue(replay is V2HistoryDecode.Supported)
        assertEquals(1L, (replay as V2HistoryDecode.Supported).page.events.single().sequence)
        assertInvalid { V2WireCodec.durableEvent(frames.single().data, replaySession, 1) }
        assertInvalid {
            V2WireCodec.durableEvent(
                frames.single().data + "," + frames.single().data,
                replaySession,
                0,
            )
        }
    }

    private fun fixture(name: String): String = fixtureText("$name.json")

    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.getResource("/opencode/1.18.32/$name")).readText()

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected strict decode failure")
        } catch (error: V2WireException) {
            assertFalse(error.message.orEmpty().contains("SENSITIVE"))
        }
    }
}
