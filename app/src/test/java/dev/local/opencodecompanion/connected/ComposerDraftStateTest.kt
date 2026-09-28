package dev.local.opencodecompanion.connected

import dev.local.opencodecompanion.client.storage.DraftKey
import dev.local.opencodecompanion.client.storage.DraftSnapshot
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerDraftStateTest {
    private val session = SessionKey(MachineId("machine-a"), SessionId("session"))
    private val otherMachine = SessionKey(MachineId("machine-b"), SessionId("session"))

    private fun draft(
        revision: Long,
        text: String,
        cleared: Boolean = false,
        key: SessionKey = session,
    ) =
        DraftSnapshot(
            DraftKey(key, ProjectKey(key.machineId, ProjectId("project")), "/fixture"),
            revision,
            text,
            cleared,
        )

    @Test
    fun hydratesMatchingSessionAndIgnoresOldSessionIncludingTombstone() {
        val loaded =
            ComposerDraftState()
                .observe(session, draft(8, "wrong", cleared = true, key = otherMachine))
                .observe(session, draft(4, "stored"))
        assertEquals("stored", loaded.text)
        assertEquals("stored", loaded.observe(session, draft(3, "stale")).text)
    }

    @Test
    fun delayedFirstSaveAcknowledgementCannotRemoveRapidTyping() {
        var state = ComposerDraftState().edit("M").edit("MI").edit("MINI_UNSENT_DRAFT")
        state = state.observe(session, draft(1, "M"))
        assertEquals("MINI_UNSENT_DRAFT", state.text)
        state = state.observe(session, draft(2, "MI"))
        assertEquals("MINI_UNSENT_DRAFT", state.text)
        state = state.observe(session, draft(3, "MINI_UNSENT_DRAFT"))
        assertEquals("MINI_UNSENT_DRAFT", state.text)
    }

    @Test
    fun sendClearKeepsNewerEditsEvenWhenTextIsIdentical() {
        val submitted = ComposerDraftState().observe(session, draft(1, "hello")).submit()
        assertEquals("", submitted.observe(session, draft(2, "", cleared = true)).text)

        val newer = submitted.edit("hello")
        assertEquals("hello", newer.observe(session, draft(2, "", cleared = true)).text)
        assertEquals("hello", newer.observe(session, draft(3, "hello")).text)
    }

    @Test
    fun successiveSendsClearOnlyTheirOwnUnchangedInput() {
        val first = ComposerDraftState().observe(session, draft(1, "first")).submit()
        val secondInput = first.observe(session, draft(2, "", cleared = true)).edit("second")
        val second = secondInput.observe(session, draft(3, "second")).submit()
        assertEquals("", second.observe(session, draft(4, "", cleared = true)).text)
    }

    @Test
    fun localEditBeforeHydrationWinsOverStaleStoredText() {
        val local = ComposerDraftState().edit("new")
        assertEquals("new", local.observe(session, draft(7, "old")).text)
    }

    @Test
    fun sendWaitsForLatestEditSaveEvenWhenOldRowHasSameText() {
        val old = draft(1, "repeat")
        val first = ComposerDraftState().observe(session, old)
        val repeated = first.edit("repeat")
        assertEquals(false, repeated.canSend(session, old, ready = true, busy = false))
        val saved = repeated.saved(repeated.generation)
        assertEquals(true, saved.canSend(session, draft(2, "repeat"), ready = true, busy = false))

        val newest = saved.edit("repeat")
        assertEquals(false, newest.saved(repeated.generation).canSend(session, old, true, false))
        assertEquals(
            true,
            newest.saved(newest.generation).canSend(session, draft(3, "repeat"), true, false),
        )
    }
}
