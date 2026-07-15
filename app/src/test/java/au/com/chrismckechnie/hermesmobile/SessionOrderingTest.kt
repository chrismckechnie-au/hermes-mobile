package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionOrderingTest {
    @Test
    fun `active sessions are pinned before newest inactive sessions`() {
        val sessions = listOf(
            session("old", "1700000000"),
            session("active", "1600000000"),
            session("new", "1800000000"),
        )

        assertEquals(
            listOf("active", "new", "old"),
            sortSessionsByActivity(sessions, activeSessionIds = setOf("active")).map(HermesSession::id),
        )
    }

    @Test
    fun `supports seconds milliseconds and ISO activity timestamps`() {
        assertEquals(1_700_000_000_000, sessionActivityMillis("1700000000"))
        assertEquals(1_700_000_000_123, sessionActivityMillis("1700000000123"))
        assertEquals(1_700_000_000_000, sessionActivityMillis("2023-11-14T22:13:20Z"))
    }

    private fun session(id: String, lastActive: String) = HermesSession(
        id = id,
        title = id,
        preview = null,
        source = null,
        model = null,
        lastActive = lastActive,
        messageCount = null,
    )
}
