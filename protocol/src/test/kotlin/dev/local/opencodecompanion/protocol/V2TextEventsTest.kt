package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2TextEventsTest {
    private val session =
        SessionKey(MachineId("machine-one"), SessionId("ses_f1e79112dfferVGW99EzcW04Kb"))

    @Test
    fun capturedLiveDeltasAndDurableEndReconcileToOneFragment() {
        val live = fixture("text-live-events").jsonArray
        val events = live.map { V2TextEvents.decode(it.toString(), session) }
        assertEquals(6, events.count { it is V2TextDecode.NotText })
        var projection = V2TextProjection()
        val texts = events.filterIsInstance<V2TextDecode.Text>().map { it.event }
        projection = projection.apply(texts[0])
        assertEquals("Fixture ", projection.fragments.values.single().text)
        projection = projection.apply(texts[1])
        assertEquals("Fixture complete.", projection.fragments.values.single().text)
        projection = projection.apply(texts[2])
        assertEquals("Fixture complete.", projection.fragments.values.single().text)
        assertTrue(projection.fragments.values.single() is V2TextFragment.Final)
        assertEquals(5L, (projection.fragments.values.single() as V2TextFragment.Final).sequence)
    }

    @Test
    fun historyEndRepairsLostDeltasAndReplayIsIdempotent() {
        val live = fixture("text-live-events").jsonArray
        val delta = text(live[5].toString())
        val end =
            fixture("text-history")
                .jsonObject["data"]!!
                .jsonArray
                .map { V2TextEvents.decode(it.toString(), session) }
                .filterIsInstance<V2TextDecode.Text>()
                .single()
                .event
        val provisional = V2TextProjection().apply(delta)
        assertEquals("Fixture ", provisional.fragments.values.single().text)
        assertEquals(provisional, provisional.apply(delta))
        val repaired = provisional.apply(end)
        assertEquals("Fixture complete.", repaired.fragments.values.single().text)
        assertEquals(repaired, repaired.apply(end))
        assertEquals(repaired, repaired.apply(delta))
        assertTrue(provisional.fragments.values.single() is V2TextFragment.Provisional)
    }

    @Test
    fun sameUpstreamIdentityOnAnotherMachineIsIndependent() {
        val raw = fixture("text-live-events").jsonArray[5].toString()
        val other = session.copy(machineId = MachineId("machine-two"))
        val first = text(raw, session)
        val second = text(raw, other)
        val projection = V2TextProjection().apply(first).apply(second)
        assertEquals(2, projection.fragments.size)
        assertEquals("Fixture ", projection.fragments[first.key]?.text)
        assertEquals("Fixture ", projection.fragments[second.key]?.text)
    }

    @Test
    fun rejectsWrongIdentityVersionAndConflictingFinal() {
        val live = fixture("text-live-events").jsonArray
        val delta = live[5].jsonObject
        val data = delta["data"]!!.jsonObject
        val wrongData = JsonObject(data + ("sessionID" to JsonPrimitive("ses_other")))
        assertInvalid {
            V2TextEvents.decode(JsonObject(delta + ("data" to wrongData)).toString(), session)
        }
        val end = live[7].jsonObject
        val durable = end["durable"]!!.jsonObject
        assertInvalid {
            V2TextEvents.decode(
                JsonObject(
                        end + ("durable" to JsonObject(durable + ("version" to JsonPrimitive(2))))
                    )
                    .toString(),
                session,
            )
        }
        val final = text(end.toString())
        val changed = (final as V2TextEvent.Ended).copy(text = "conflict")
        assertInvalid { V2TextProjection().apply(final).apply(changed) }
    }

    @Test
    fun boundsAppendWithoutSilentlyDroppingText() {
        val key = V2TextKey(session, "msg_one", "text-0")
        val large = "a".repeat(V2TextEvents.MAX_TEXT_CHARS)
        val full = V2TextProjection().apply(V2TextEvent.Delta(key, "evt_one", large))
        assertInvalid { full.apply(V2TextEvent.Delta(key, "evt_two", "b")) }
        assertEquals(large, full.fragments[key]?.text)
    }

    @Test
    fun boundsAggregateTextAndDeepJson() {
        var projection = V2TextProjection()
        val chunk = "x".repeat(V2TextEvents.MAX_TEXT_CHARS)
        for (index in 0 until 4) {
            projection =
                projection.apply(
                    V2TextEvent.Ended(
                        V2TextKey(session, "msg_$index", "text-0"),
                        "evt_$index",
                        index + 1L,
                        chunk,
                    )
                )
        }
        val full = projection
        assertInvalid {
            full.apply(
                V2TextEvent.Ended(V2TextKey(session, "msg_five", "text-0"), "evt_five", 5, "x")
            )
        }
        val delta = fixture("text-live-events").jsonArray[5].jsonObject
        assertInvalid {
            V2TextEvents.decode(
                JsonObject(
                        delta +
                            ("unexpected" to
                                Json.parseToJsonElement("[".repeat(65) + "0" + "]".repeat(65)))
                    )
                    .toString(),
                session,
            )
        }
    }

    @Test
    fun emptyDeltasAcrossFragmentsCannotExhaustMemoryWithRetainedIds() {
        val first = V2TextKey(session, "msg_first", "text-0")
        val second = V2TextKey(session, "msg_second", "text-0")
        var projection = V2TextProjection()
        for (index in 0 until 2048) {
            projection = projection.apply(V2TextEvent.Delta(first, "evt_a_$index", ""))
            projection = projection.apply(V2TextEvent.Delta(second, "evt_b_$index", ""))
        }
        assertEquals(0, projection.fragments.values.sumOf { it.text.length })
        val full = projection
        assertInvalid { full.apply(V2TextEvent.Delta(first, "evt_over_budget", "")) }
        assertEquals(full, full.apply(V2TextEvent.Delta(first, "evt_a_0", "")))

        val finalized = full.apply(V2TextEvent.Ended(first, "evt_final", 1, ""))
        val resumed = finalized.apply(V2TextEvent.Delta(second, "evt_after_final", ""))
        assertEquals(2049, (resumed.fragments[second] as V2TextFragment.Provisional).deltaIds.size)
    }

    private fun text(raw: String, destination: SessionKey = session): V2TextEvent =
        (V2TextEvents.decode(raw, destination) as V2TextDecode.Text).event

    private fun fixture(name: String) =
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/opencode/1.18.32/execution/$name.json")).readText()
        )

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(V2WireException::class.java, block)
    }
}
