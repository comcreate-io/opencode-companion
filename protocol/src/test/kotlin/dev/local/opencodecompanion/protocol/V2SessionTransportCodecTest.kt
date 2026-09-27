package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SessionTransportCodecTest {
    private val machine = MachineId("machine-a")
    private val session = SessionKey(machine, SessionId("ses_test"))

    @Test
    fun createAndPromptUseExactPinnedPayloadKeys() {
        val created =
            Json.parseToJsonElement(
                    V2SessionCommands.create(
                        V2CreateSessionCommand(
                            agentId = "build",
                            model = V2ModelSelection("fixture", "fixture-model"),
                            location = V2LocationSelection("/fixture/repo", "workspace-1"),
                            requestedSessionId = SessionId("ses_requested"),
                        )
                    )
                )
                .jsonObject
        assertEquals(setOf("id", "agent", "model", "location"), created.keys)
        assertEquals("ses_requested", (created["id"] as JsonPrimitive).content)
        assertEquals(
            "fixture",
            (created["model"]!!.jsonObject["providerID"] as JsonPrimitive).content,
        )
        assertEquals(
            "workspace-1",
            (created["location"]!!.jsonObject["workspaceID"] as JsonPrimitive).content,
        )
        assertEquals(
            emptySet<String>(),
            Json.parseToJsonElement(V2SessionCommands.create(V2CreateSessionCommand()))
                .jsonObject
                .keys,
        )

        val prompted =
            Json.parseToJsonElement(
                    V2SessionCommands.prompt(
                        V2PromptCommand(session, "msg_test", "Hello", V2Delivery.Queue)
                    )
                )
                .jsonObject
        assertEquals(setOf("id", "prompt", "delivery", "resume"), prompted.keys)
        assertEquals("Hello", (prompted["prompt"]!!.jsonObject["text"] as JsonPrimitive).content)
        assertEquals("queue", (prompted["delivery"] as JsonPrimitive).content)
        assertEquals(true, (prompted["resume"] as JsonPrimitive).content.toBooleanStrict())
        assertInvalid {
            V2SessionCommands.prompt(V2PromptCommand(session, " ", "text", V2Delivery.Steer))
        }
        assertInvalid {
            V2SessionCommands.prompt(
                V2PromptCommand(session, "msg", "x".repeat(1_048_577), V2Delivery.Steer)
            )
        }
        assertInvalid {
            V2SessionCommands.create(V2CreateSessionCommand(location = V2LocationSelection("")))
        }
    }

    @Test
    fun activeRequiresRunningStatusAndPreservesIds() {
        assertEquals(
            setOf(SessionId("ses_a"), SessionId("ses_b")),
            V2SessionCommands.active(
                """{"data":{"ses_a":{"type":"running"},"ses_b":{"type":"running"}}}"""
            ),
        )
        assertEquals(emptySet<SessionId>(), V2SessionCommands.active("""{"data":{}}"""))
        assertInvalid { V2SessionCommands.active("""{"data":{"ses_a":{"type":"idle"}}}""") }
        assertInvalid { V2SessionCommands.active("""{"data":{"ses_a":{}}}""") }
    }

    @Test
    fun catalogKeepsSelectionFieldsAndDropsProviderSecrets() {
        val location =
            """{"directory":"/fixture/repo","project":{"id":"proj_a","directory":"/fixture/repo"}}"""
        val agentBody =
            """{"location":$location,"data":[{"id":"build","description":"Build agent","mode":"primary","hidden":false,"request":{"headers":{"Authorization":"Bearer SECRET_SENTINEL"}},"system":"SECRET_SENTINEL"}]}"""
        val agents = V2CatalogCodec.agents(agentBody)
        assertEquals("/fixture/repo", agents.location.directory)
        assertEquals(V2AgentMode.Primary, agents.items.single().mode)
        assertEquals("build", agents.items.single().id)
        assertFalse(agents.toString().contains("SECRET_SENTINEL"))

        val modelBody =
            """{"location":$location,"data":[{"id":"fixture-model","providerID":"fixture","name":"Fixture","status":"active","enabled":true,"capabilities":{"tools":true},"api":{"url":"https://secret.example/SECRET_SENTINEL"},"request":{"headers":{"Authorization":"SECRET_SENTINEL"}}}]}"""
        val models = V2CatalogCodec.models(modelBody)
        assertEquals(V2ModelStatus.Active, models.items.single().status)
        assertTrue(models.items.single().tools)
        assertFalse(models.toString().contains("SECRET_SENTINEL"))
        assertInvalid {
            V2CatalogCodec.models(modelBody.replace("\"enabled\":true", "\"enabled\":\"true\""))
        }
        assertInvalid {
            V2CatalogCodec.agents(
                agentBody.replace("\"mode\":\"primary\"", "\"mode\":\"invented\"")
            )
        }
    }

    @Test
    fun capturedCatalogEnvelopesRetainLocationAndSelections() {
        val models =
            V2CatalogCodec.models(
                resource("/opencode/1.18.32/session-transport/model-catalog.json")
            )
        assertEquals("/fixture/repo", models.location.directory)
        assertEquals("fixture-model", models.items.single().id)
        assertEquals("fixture", models.items.single().providerId)
        assertTrue(models.items.single().enabled)

        val agents =
            V2CatalogCodec.agents(
                resource("/opencode/1.18.32/session-transport/agents-summary.json")
            )
        assertEquals(models.location.projectId, agents.location.projectId)
        assertEquals(7, agents.items.size)
        assertEquals(V2AgentMode.Primary, agents.items.first().mode)
        assertTrue(agents.items.any { it.id == "compaction" && it.hidden })
    }

    @Test
    fun filesystemPathsHaveTheirOwnBoundApartFromIdentityIds() {
        val longPath = "/" + "segment/".repeat(40) + "repo"
        assertTrue(longPath.length > 256)
        val created =
            Json.parseToJsonElement(
                    V2SessionCommands.create(
                        V2CreateSessionCommand(location = V2LocationSelection(longPath))
                    )
                )
                .jsonObject
        assertEquals(
            longPath,
            (created["location"]!!.jsonObject["directory"] as JsonPrimitive).content,
        )

        val catalog =
            V2CatalogCodec.agents(
                """{"location":{"directory":"$longPath","project":{"id":"proj_a","directory":"$longPath"}},"data":[]}"""
            )
        assertEquals(longPath, catalog.location.directory)
        assertEquals(longPath, catalog.location.projectDirectory)

        val tooLong = "/" + "a".repeat(4096)
        assertInvalid {
            V2SessionCommands.create(
                V2CreateSessionCommand(location = V2LocationSelection(tooLong))
            )
        }
        assertInvalid {
            V2CatalogCodec.agents(
                """{"location":{"directory":"$tooLong","project":{"id":"proj_a","directory":"/fixture"}},"data":[]}"""
            )
        }
        assertInvalid {
            V2SessionCommands.create(
                V2CreateSessionCommand(location = V2LocationSelection("relative/repo"))
            )
        }
        assertInvalid {
            V2SessionCommands.create(
                V2CreateSessionCommand(location = V2LocationSelection("/bad\u0000path"))
            )
        }
        assertInvalid {
            V2SessionCommands.create(V2CreateSessionCommand(agentId = "a".repeat(257)))
        }
    }

    @Test
    fun globalStreamScopesTextByMachineAndTreatsOtherEventsAsNotifications() {
        val connected =
            resource("/opencode/1.18.32/global-connected.sse").removePrefix("data: ").trim()
        assertEquals(V2GlobalEvent.Connected, V2GlobalEventCodec.decode(connected, machine))
        val live =
            Json.parseToJsonElement(resource("/opencode/1.18.32/execution/text-live-events.json"))
                as JsonArray
        val delta =
            live
                .first { it.jsonObject["type"] == JsonPrimitive("session.next.text.delta") }
                .toString()
        val a = V2GlobalEventCodec.decode(delta, machine) as V2GlobalEvent.ScopedText
        val b = V2GlobalEventCodec.decode(delta, MachineId("machine-b")) as V2GlobalEvent.ScopedText
        assertEquals(machine, a.session.machineId)
        assertEquals(MachineId("machine-b"), b.session.machineId)
        assertEquals(a.session.sessionId, b.session.sessionId)
        assertEquals(a.session, a.event.key.session)
        val other =
            live.first { it.jsonObject["type"] == JsonPrimitive("session.created") }.toString()
        assertEquals(
            V2GlobalEvent.OtherNotifications("session.created"),
            V2GlobalEventCodec.decode(other, machine),
        )
        val deltaObject = Json.parseToJsonElement(delta).jsonObject
        val invalidData =
            JsonObject(deltaObject["data"]!!.jsonObject + ("sessionID" to JsonPrimitive(42)))
        assertInvalid {
            V2GlobalEventCodec.decode(
                JsonObject(deltaObject + ("data" to invalidData)).toString(),
                machine,
            )
        }
    }

    @Test
    fun malformedAndDeepGlobalFramesFailExplicitly() {
        assertInvalid { V2GlobalEventCodec.decode("<html>ok</html>", machine) }
        assertInvalid { V2GlobalEventCodec.decode(" ".repeat(1_052_673), machine) }
        var nested = "0"
        repeat(65) { nested = "[$nested]" }
        assertInvalid {
            V2GlobalEventCodec.decode("""{"type":"future","id":"evt","data":$nested}""", machine)
        }
        assertInvalid {
            V2GlobalEventCodec.decode(
                """{"type":"session.next.text.delta","id":"evt","data":{"sessionID":42}}""",
                machine,
            )
        }
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(V2WireException::class.java, block)
    }
}
