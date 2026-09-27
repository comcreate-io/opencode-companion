package dev.local.opencodecompanion.client.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.client.SendState
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2PromptAdmission
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the exported v1 table shape, then opens the real v2 store through its migration. */
@RunWith(AndroidJUnit4::class)
class DurableMigrationTest {
    @Test
    fun v1RowsPreserveIdentityDraftIntentAndCursorWithoutFabricatingAdmission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            db.execSQL(
                "CREATE TABLE machines (machineId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, origin TEXT NOT NULL, credentialReference TEXT, credentialGeneration INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE drafts (machineId TEXT NOT NULL, projectId TEXT NOT NULL, location TEXT NOT NULL, sessionId TEXT NOT NULL, revision INTEGER NOT NULL, text TEXT NOT NULL, cleared INTEGER NOT NULL, PRIMARY KEY(machineId, projectId, location, sessionId))"
            )
            db.execSQL(
                "CREATE TABLE outgoing (machineId TEXT NOT NULL, intentId TEXT NOT NULL, projectId TEXT NOT NULL, location TEXT NOT NULL, sessionId TEXT NOT NULL, credentialGeneration INTEGER NOT NULL, origin TEXT NOT NULL, promptText TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(machineId, intentId))"
            )
            db.execSQL(
                "CREATE TABLE event_journal (machineId TEXT NOT NULL, sessionId TEXT NOT NULL, sequence INTEGER NOT NULL, eventId TEXT NOT NULL, rawJson TEXT NOT NULL, PRIMARY KEY(machineId, sessionId, sequence))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX index_event_journal_machineId_sessionId_eventId ON event_journal(machineId, sessionId, eventId)"
            )
            db.execSQL(
                "CREATE TABLE durable_cursor (machineId TEXT NOT NULL, sessionId TEXT NOT NULL, sequence INTEGER NOT NULL, PRIMARY KEY(machineId, sessionId))"
            )
            db.execSQL(
                "INSERT INTO machines VALUES ('machine-a','Old profile','https://fixture.example/','credential:machine-a',1)"
            )
            db.execSQL(
                "INSERT INTO drafts VALUES ('machine-a','global','/fixture/repo','ses_same',7,'preserved draft',0)"
            )
            db.execSQL(
                "INSERT INTO outgoing VALUES ('machine-a','msg_legacy','global','/fixture/repo','ses_same',1,'https://fixture.example/','legacy text','DISPATCHING')"
            )
            db.execSQL(
                "INSERT INTO event_journal VALUES ('machine-a','ses_same',1,'evt_first',?)",
                arrayOf(
                    """{"id":"evt_first","type":"session.next.prompt.admitted","durable":{"aggregateID":"ses_same","seq":1,"version":1},"data":{"timestamp":1,"sessionID":"ses_same","messageID":"msg_old","prompt":{"text":"fixture"},"delivery":"steer"}}"""
                ),
            )
            db.execSQL("INSERT INTO durable_cursor VALUES ('machine-a','ses_same',1)")
            db.version = 1
        } finally {
            db.close()
        }

        try {
            DurableStore.open(context, name).use { store ->
                val machine = MachineId("machine-a")
                val key = SessionKey(machine, SessionId("ses_same"))
                val draftKey =
                    DraftKey(key, ProjectKey(machine, ProjectId("global")), "/fixture/repo")
                assertEquals("Old profile", store.machine(machine)?.displayName)
                assertFalse(requireNotNull(store.machine(machine)).sharedPasswordAcknowledged)
                assertEquals(7L, store.draft(draftKey)?.revision)
                assertEquals("preserved draft", store.draft(draftKey)?.text)
                assertEquals(1L, store.cursor(key))
                assertEquals("evt_first", store.journal(key).single().eventId)
                val outgoing = requireNotNull(store.outgoing(machine, "msg_legacy"))
                assertEquals(SendState.OutcomeUnknown, outgoing.state)
                assertNull(outgoing.delivery)
                assertNull(outgoing.submittedDraft)
                assertFalse(
                    store.acknowledgeAfterProof(
                        machine,
                        "msg_legacy",
                        V2PromptAdmission("msg_legacy", key.sessionId, 2, "steer", "legacy text", 2),
                    )
                )
                assertTrue(store.unresolvedOutgoing(machine).single().id == "msg_legacy")
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
