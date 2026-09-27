package dev.local.opencodecompanion.protocol

import org.junit.Assert.*
import org.junit.Test

class VcsDiffCodecTest {
    @Test
    fun `actual binary deletion and rename pair preserve host meaning`() {
        val body =
            requireNotNull(javaClass.getResource("/opencode/1.18.32/changes/changed-small.json"))
                .readText()
        val rows = VcsDiffCodec.decode(body)
        assertTrue(rows.single { it.file == "binary.dat" }.binary)
        assertEquals("deleted", rows.single { it.file == "before.txt" }.status)
        assertEquals("added", rows.single { it.file == "after.txt" }.status)
        assertTrue(VcsDiffCodec.decode("[]").isEmpty())
    }

    @Test
    fun `large patches truncate explicitly and missing patch stays unavailable`() {
        val large = "a".repeat(100_001)
        val row =
            VcsDiffCodec.decode(
                    """[{"file":"large","patch":"$large","additions":5000,"deletions":0}]"""
                )
                .single()
        assertTrue(row.truncated)
        assertEquals(100_000, requireNotNull(row.patch).length)
        val absent =
            VcsDiffCodec.decode("""[{"file":"unknown","additions":0,"deletions":0}]""").single()
        assertNull(absent.patch)
        assertFalse(absent.binary)
    }

    @Test
    fun `invalid counts unsupported statuses and oversized payload fail visibly`() {
        for (body in
            listOf(
                """[{"file":"x","additions":-1,"deletions":0}]""",
                """[{"file":"x","additions":"1","deletions":0}]""",
                """[{"file":"x","additions":1,"deletions":0,"status":"renamed"}]""",
                " ".repeat(1_048_577) + "[]",
            )) {
            assertThrows(V2WireException::class.java) { VcsDiffCodec.decode(body) }
        }
    }
}
