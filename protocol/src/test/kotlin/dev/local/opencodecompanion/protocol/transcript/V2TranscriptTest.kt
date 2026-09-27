package dev.local.opencodecompanion.protocol.transcript

import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2TranscriptTest {
    @Test
    fun capturedTextHistoryRebuildsAndRepeatedReplayDoesNotAppend() {
        val (session, page) = fixture("text-history", "ses_f1e762078ffefza45uIYRBbAGq")
        val state = replay(session, page)
        assertEquals(6, state.lastSequence)
        assertEquals("STREAM_CASE", state.prompts.values.single().text)
        assertTrue(state.prompts.values.single().prompted)
        assertEquals("Fixture complete.", (state.texts.values.single() as TextSummary.Ended).text)
        assertEquals("stop", (state.steps.values.single().status as StepStatus.Ended).finish)
        for (decoded in page.events) {
            assertEquals(
                TranscriptApply.Duplicate,
                state.apply((decoded as TranscriptDecode.Supported).event),
            )
        }
    }

    @Test
    fun capturedReadAndQuestionLifecyclesRetainToolResults() {
        val (readSession, readPage) = fixture("read-history", "ses_f1e761fe5ffeMbGHzT2Ys6zrqo")
        val read = replay(readSession, readPage)
        assertEquals(12, read.lastSequence)
        val readTool = read.tools.values.single() as ToolSummary.Succeeded
        assertEquals("read", readTool.name)
        assertEquals("/fixture/repo/README.md", (readTool.input["path"] as JsonPrimitive).content)
        assertEquals("M1_READ_MARKER\n", (readTool.structured["content"] as JsonPrimitive).content)
        assertEquals("Fixture complete.", (read.texts.values.single() as TextSummary.Ended).text)
        assertEquals(2, read.steps.size)

        val (questionSession, questionPage) =
            fixture("question-history", "ses_f1e761f00ffe6mjbRPjd2TpAx5")
        val question = replay(questionSession, questionPage)
        val questionTool = question.tools.values.single() as ToolSummary.Succeeded
        assertEquals("question", questionTool.name)
        assertEquals(
            "Yes",
            (questionTool.structured["answers"]!!.jsonArray[0].jsonArray[0] as JsonPrimitive)
                .content,
        )
        assertEquals(
            "tool-calls",
            (question.steps.values.first().status as StepStatus.Ended).finish,
        )
        assertEquals("stop", (question.steps.values.last().status as StepStatus.Ended).finish)
    }

    @Test
    fun capturedInterruptionRetainsFinalTextAndFailedStep() {
        val (session, page) = fixture("interrupt-history", "ses_f1e761e0dffepqwowlRgAWTO0V")
        val state = replay(session, page)
        assertEquals("Waiting fixture", (state.texts.values.single() as TextSummary.Ended).text)
        val failure = state.steps.values.single().status as StepStatus.Failed
        assertEquals("Provider turn interrupted", failure.error.message)
    }

    @Test
    fun machineCollisionGapLateAndConflictingSequenceFailClosed() {
        val (session, page) = fixture("text-history", "ses_f1e762078ffefza45uIYRBbAGq")
        val first = (page.events[0] as TranscriptDecode.Supported).event
        val second = (page.events[1] as TranscriptDecode.Supported).event
        assertEquals(TranscriptApply.Gap(1, 2), TranscriptState(session).apply(second))
        val one = (TranscriptState(session).apply(first) as TranscriptApply.Applied).state
        assertEquals(TranscriptApply.Duplicate, one.apply(first))
        assertTrue(one.apply(first.copy(id = "evt_conflict")) is TranscriptApply.Conflict)
        // Admission and prompted carry the same data shape, so replay identity includes event kind.
        val changedType =
            V2Transcript.event(
                JsonObject(
                        Json.parseToJsonElement(first.rawEvent()).jsonObject +
                            ("type" to JsonPrimitive("session.next.prompted"))
                    )
                    .toString(),
                session,
            ) as TranscriptDecode.Supported
        assertTrue(one.apply(changedType.event) is TranscriptApply.Conflict)
        assertEquals(
            TranscriptApply.LateUnverified(1),
            TranscriptState(session, lastSequence = 2).apply(first),
        )

        val other = session.copy(machineId = MachineId("second-machine"))
        val otherFirst =
            (V2Transcript.event(first.rawEvent(), other) as TranscriptDecode.Supported).event
        val otherState = (TranscriptState(other).apply(otherFirst) as TranscriptApply.Applied).state
        assertEquals(1, otherState.lastSequence)
        assertTrue(one.apply(otherFirst) is TranscriptApply.Conflict)
    }

    @Test
    fun unsupportedAndMalformedEventsCannotAdvanceState() {
        val (session, page) = fixture("text-history", "ses_f1e762078ffefza45uIYRBbAGq")
        val first = (page.events[0] as TranscriptDecode.Supported).event
        val base = Json.parseToJsonElement(first.rawEvent()).jsonObject
        val unknown = JsonObject(base + ("type" to JsonPrimitive("session.next.future")))
        assertTrue(V2Transcript.event(unknown.toString(), session) is TranscriptDecode.Unsupported)
        val durable = base["durable"]!!.jsonObject
        val wrongVersion =
            JsonObject(base + ("durable" to JsonObject(durable + ("version" to JsonPrimitive(9)))))
        assertTrue(
            V2Transcript.event(wrongVersion.toString(), session) is TranscriptDecode.Unsupported
        )
        assertInvalid { V2Transcript.event("{", session) }
        assertInvalid { V2Transcript.event(" ".repeat(1_048_577), session) }
        val badIdentity =
            JsonObject(
                base +
                    ("durable" to
                        JsonObject(durable + ("aggregateID" to JsonPrimitive("ses_other"))))
            )
        assertInvalid { V2Transcript.event(badIdentity.toString(), session) }
        var nested: JsonElement = JsonPrimitive(0)
        repeat(65) { nested = JsonArray(listOf(nested)) }
        assertInvalid {
            V2Transcript.event(JsonObject(base + ("nested" to nested)).toString(), session)
        }
        assertEquals(0, TranscriptState(session).lastSequence)
    }

    @Test
    fun sourceConfirmedToolFailureShapeUsesSyntheticMutation() {
        val (session, page) = fixture("read-history", "ses_f1e761fe5ffeMbGHzT2Ys6zrqo")
        val prefix = page.events.take(6)
        var state = replay(session, TranscriptPage(prefix, true))
        val success = (page.events[6] as TranscriptDecode.Supported).event
        val root = Json.parseToJsonElement(success.rawEvent()).jsonObject
        val data = root["data"]!!.jsonObject
        val failureData =
            JsonObject(
                data - "structured" - "content" - "outputPaths" +
                    ("error" to
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("unknown"),
                                "message" to JsonPrimitive("Synthetic tool failure"),
                            )
                        ))
            )
        val synthetic =
            JsonObject(
                root +
                    ("type" to JsonPrimitive("session.next.tool.failed")) +
                    ("data" to failureData)
            )
        val decoded =
            V2Transcript.event(synthetic.toString(), session) as TranscriptDecode.Supported
        state = (state.apply(decoded.event) as TranscriptApply.Applied).state
        assertEquals(
            "Synthetic tool failure",
            (state.tools.values.single() as ToolSummary.Failed).error.message,
        )
    }

    private fun replay(session: SessionKey, page: TranscriptPage): TranscriptState {
        var state = TranscriptState(session)
        for (item in page.events) state =
            (state.apply((item as TranscriptDecode.Supported).event) as TranscriptApply.Applied)
                .state
        return state
    }

    private fun fixture(name: String, id: String): Pair<SessionKey, TranscriptPage> {
        val session = SessionKey(MachineId("first-machine"), SessionId(id))
        val body =
            checkNotNull(javaClass.getResource("/opencode/1.18.32/transcript/$name.json"))
                .readText()
        return session to V2Transcript.history(body, session)
    }

    private fun DurableTranscriptEvent.rawEvent(): String =
        JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id),
                    "type" to JsonPrimitive(typeName()),
                    "durable" to
                        JsonObject(
                            mapOf(
                                "aggregateID" to JsonPrimitive(session.sessionId.value),
                                "seq" to JsonPrimitive(sequence),
                                "version" to JsonPrimitive(version),
                            )
                        ),
                    "data" to raw,
                )
            )
            .toString()

    private fun DurableTranscriptEvent.typeName(): String =
        when (kind) {
            is Kind.Prompt ->
                if ((kind as Kind.Prompt).prompted) "session.next.prompted"
                else "session.next.prompt.admitted"
            is Kind.StepStarted -> "session.next.step.started"
            is Kind.StepEnded -> "session.next.step.ended"
            is Kind.StepFailed -> "session.next.step.failed"
            is Kind.TextStarted -> "session.next.text.started"
            is Kind.TextEnded -> "session.next.text.ended"
            is Kind.ToolInputStarted -> "session.next.tool.input.started"
            is Kind.ToolInputEnded -> "session.next.tool.input.ended"
            is Kind.ToolCalled -> "session.next.tool.called"
            is Kind.ToolSuccess -> "session.next.tool.success"
            is Kind.ToolFailed -> "session.next.tool.failed"
        }

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(TranscriptDecodeException::class.java, block)
    }
}
