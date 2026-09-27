package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OutgoingIntentTest {
    @Test
    fun collidingUpstreamIdsRemainMachineScoped() {
        val first = destination("laptop")
        val second = destination("desktop")

        assertNotEquals(first.session, second.session)
        assertNotEquals(first.project, second.project)
        assertNotEquals(first, second)
        assertEquals(first, OutgoingIntent.prepare("send-1", first).beginDispatch().destination)
        assertThrows(IllegalArgumentException::class.java) {
            SendDestination(first.session, second.project, "/repo", 1)
        }
    }

    @Test
    fun interruptedDispatchBecomesUnknownAndCannotBlindlyRetry() {
        val intent = OutgoingIntent.prepare("send-1", destination("laptop")).beginDispatch()
        val recovered = intent.recoverAfterProcessDeath()

        assertEquals(SendState.OutcomeUnknown, recovered.state)
        assertThrows(IllegalStateException::class.java) { recovered.beginDispatch() }
        assertEquals(SendState.Admitted, recovered.recordAdmission().state)

        val lostResponse = intent.responseMissing()
        assertEquals(SendState.OutcomeUnknown, lostResponse.state)
        assertThrows(IllegalStateException::class.java) { lostResponse.beginDispatch() }
    }

    @Test
    fun admittedCleanupCannotRedispatch() {
        val admitted =
            OutgoingIntent.prepare("send-1", destination("laptop"))
                .beginDispatch()
                .recordAdmission()
        // A failed local cleanup leaves the durable admission state intact.
        assertEquals(admitted, admitted.recoverAfterProcessDeath())
        assertThrows(IllegalStateException::class.java) { admitted.beginDispatch() }
        val cleaned = admitted.finalizeLocalBookkeeping()

        assertEquals(SendState.Finalized, cleaned.state)
        assertSame(cleaned, cleaned.finalizeLocalBookkeeping())
        assertThrows(IllegalStateException::class.java) { cleaned.beginDispatch() }
    }

    @Test
    fun invalidTransitionsAreRejected() {
        val prepared = OutgoingIntent.prepare("send-1", destination("laptop"))
        assertEquals(prepared, prepared.recoverAfterProcessDeath())
        assertThrows(IllegalStateException::class.java) { prepared.recordAdmission() }
        assertThrows(IllegalStateException::class.java) { prepared.finalizeLocalBookkeeping() }
        val dispatching = prepared.beginDispatch()
        assertThrows(IllegalStateException::class.java) { dispatching.beginDispatch() }
        val rejected = dispatching.recordRejection()
        assertThrows(IllegalStateException::class.java) { rejected.recordAdmission() }
    }

    private fun destination(machine: String) =
        SendDestination(
            session = SessionKey(MachineId(machine), SessionId("same-session")),
            project = ProjectKey(MachineId(machine), ProjectId("same-project")),
            location = "/repo",
            credentialGeneration = 1,
        )
}
