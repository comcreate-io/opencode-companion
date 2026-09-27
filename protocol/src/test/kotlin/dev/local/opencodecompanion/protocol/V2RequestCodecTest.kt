package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixtures copied from docs/evidence/M1/permissions/run-1 and execution/run-5. */
class V2RequestCodecTest {
    private val permissionSession =
        SessionKey(MachineId("machine-a"), SessionId("ses_f1e7b03b6ffedIekrOo8h39Alt"))
    private val questionSession =
        SessionKey(MachineId("machine-a"), SessionId("ses_f1e7a61fdfferPc0b3uxJLFS29"))

    @Test
    fun capturedPermissionBindsRequestAndSession() {
        val raw = fixture("permission-pending")
        val pending =
            V2RequestCodec.permission(envelope(raw), permissionSession, "per_m1syntheticonce")
        assertEquals("m1.synthetic.permission", pending.action)
        assertEquals(listOf("m1://permission-fixture"), pending.resources)
        assertEquals(null, pending.save)
        assertEquals(null, pending.source)
        assertEquals(
            listOf(pending),
            V2RequestCodec.permissions(envelope("[$raw]"), permissionSession),
        )
        invalid {
            V2RequestCodec.permission(
                envelope(raw),
                SessionKey(MachineId("machine-a"), SessionId("other")),
                pending.id,
            )
        }
        invalid { V2RequestCodec.permission(envelope(raw), permissionSession, "per_other") }
        invalid {
            V2RequestCodec.permissions(
                envelope("[$raw]"),
                SessionKey(MachineId("machine-a"), SessionId("other")),
            )
        }
    }

    @Test
    fun capturedQuestionPreservesOptionsAndAbsentFlags() {
        val questions =
            V2RequestCodec.questions(envelope(fixture("question-pending")), questionSession)
        val request = questions.single()
        assertEquals("que_0e1859e67001DWC2jHRWMOFbUI", request.id)
        assertEquals("call_question_fixture", request.tool?.callId)
        assertEquals("Continue fixture?", request.questions.single().question)
        assertEquals(
            listOf(V2QuestionOption("Yes", "Continue test")),
            request.questions.single().options,
        )
        assertEquals(null, request.questions.single().multiple)
        assertEquals(null, request.questions.single().custom)
        invalid {
            V2RequestCodec.questions(
                envelope(fixture("question-pending")),
                SessionKey(MachineId("machine-a"), SessionId("other")),
            )
        }
    }

    @Test
    fun optionalFlagsAndUnknownOptionFieldsDoNotInventDefaults() {
        val captured = fixture("question-pending")
        val augmented =
            captured
                .replace("\"options\": [", "\"multiple\": false, \"custom\": true, \"options\": [")
                .replace(
                    "\"description\": \"Continue test\"",
                    "\"description\": \"Continue test\", \"future\": 1",
                )
        val info =
            V2RequestCodec.questions(envelope(augmented), questionSession)
                .single()
                .questions
                .single()
        assertEquals(false, info.multiple)
        assertEquals(true, info.custom)
        invalid {
            V2RequestCodec.questions(
                envelope(augmented.replace("\"multiple\": false", "\"multiple\": null")),
                questionSession,
            )
        }
    }

    @Test
    fun malformedRequiredFieldsFailWithoutPayloadEcho() {
        val raw = fixture("permission-pending")
        invalid {
            V2RequestCodec.permission(
                envelope(raw.replace("\"id\": \"per_m1syntheticonce\",", "")),
                permissionSession,
                "per_m1syntheticonce",
            )
        }
        invalid {
            V2RequestCodec.permission(
                envelope(raw.replace("\"resources\": [", "\"resources\": null, \"unused\": [")),
                permissionSession,
                "per_m1syntheticonce",
            )
        }
        val questions = fixture("question-pending")
        invalid {
            V2RequestCodec.questions(
                envelope(
                    questions.replace(
                        "\"sessionID\": \"ses_f1e7a61fdfferPc0b3uxJLFS29\"",
                        "\"sessionID\": null",
                    )
                ),
                questionSession,
            )
        }
        invalid {
            V2RequestCodec.questions(
                envelope(questions.replace("\"label\": \"Yes\"", "\"label\": 7")),
                questionSession,
            )
        }
    }

    @Test
    fun replyBodiesAreExplicitAndBoundToRequestIdentity() {
        val permission =
            V2RequestCodec.permission(
                envelope(fixture("permission-pending")),
                permissionSession,
                "per_m1syntheticonce",
            )
        val once = V2RequestCodec.permissionReply(permission, V2PermissionReply.ONCE)
        assertEquals(permissionSession, once.sessionKey)
        assertEquals(permission.id, once.requestId)
        assertEquals(
            "once",
            (Json.parseToJsonElement(once.body!!) as JsonObject)["reply"]?.let {
                (it as JsonPrimitive).content
            },
        )
        assertEquals(
            "reject",
            (Json.parseToJsonElement(
                    V2RequestCodec.permissionReply(permission, V2PermissionReply.REJECT).body!!
                ) as JsonObject)["reply"]
                ?.let { (it as JsonPrimitive).content },
        )
        assertFalse(V2PermissionReply.entries.any { it.wireValue == "always" })

        val question =
            V2RequestCodec.questions(envelope(fixture("question-pending")), questionSession)
                .single()
        val answer = V2RequestCodec.questionReply(question, listOf(listOf("Yes")))
        assertEquals(question.id, answer.requestId)
        assertEquals("{\"answers\":[[\"Yes\"]]}", answer.body)
        assertEquals(null, V2RequestCodec.questionReject(question).body)
        invalid { V2RequestCodec.questionReply(question, emptyList()) }
        invalid { V2RequestCodec.questionReply(question, listOf(listOf("Yes"), listOf("Extra"))) }
    }

    @Test
    fun collidingUpstreamIdsRemainBoundToDifferentMachines() {
        val otherPermissionKey = SessionKey(MachineId("machine-b"), permissionSession.sessionId)
        val first =
            V2RequestCodec.permission(
                envelope(fixture("permission-pending")),
                permissionSession,
                "per_m1syntheticonce",
            )
        val second =
            V2RequestCodec.permission(
                envelope(fixture("permission-pending")),
                otherPermissionKey,
                "per_m1syntheticonce",
            )
        assertEquals(first.id, second.id)
        assertFalse(first == second)
        assertEquals(
            permissionSession,
            V2RequestCodec.permissionReply(first, V2PermissionReply.ONCE).sessionKey,
        )
        assertEquals(
            otherPermissionKey,
            V2RequestCodec.permissionReply(second, V2PermissionReply.ONCE).sessionKey,
        )

        val otherQuestionKey = SessionKey(MachineId("machine-b"), questionSession.sessionId)
        val firstQuestion =
            V2RequestCodec.questions(envelope(fixture("question-pending")), questionSession)
                .single()
        val secondQuestion =
            V2RequestCodec.questions(envelope(fixture("question-pending")), otherQuestionKey)
                .single()
        assertEquals(firstQuestion.id, secondQuestion.id)
        assertFalse(firstQuestion == secondQuestion)
        assertEquals(
            otherQuestionKey,
            V2RequestCodec.questionReply(secondQuestion, listOf(listOf("Yes"))).sessionKey,
        )
    }

    @Test
    fun nestingLimitRunsBeforeJsonParsing() {
        val deep = "[".repeat(65) + "]".repeat(65)
        invalid { V2RequestCodec.permissions(deep, permissionSession) }
        val quotedBraces = "{\"data\":[],\"note\":\"" + "[".repeat(100) + "\"}"
        assertEquals(
            emptyList<V2PermissionRequest>(),
            V2RequestCodec.permissions(quotedBraces, permissionSession),
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/opencode/1.18.32/requests/$name.json")).readText()

    private fun envelope(data: String) = "{\"data\":$data}"

    private fun invalid(block: () -> Unit) {
        try {
            block()
            fail("Expected decode failure")
        } catch (error: V2WireException) {
            assertFalse(error.message.orEmpty().contains("m1://permission-fixture"))
            assertTrue(error.message.orEmpty().isNotEmpty())
        }
    }
}
