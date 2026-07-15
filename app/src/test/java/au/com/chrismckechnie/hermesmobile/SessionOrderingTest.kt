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

    @Test
    fun `session pills filter running approval and surface without losing search`() {
        val sessions = listOf(
            session("mobile", "1800000000", source = "api_server", model = "hermes-default"),
            session("approval", "1700000000", source = "cli", model = "terra"),
            session("desktop", "1600000000", source = "tui", model = "hermes-default"),
        )

        assertEquals(
            listOf("mobile", "approval"),
            filterSessions(sessions, "", SessionFilter.Running, setOf("mobile", "approval")).map(HermesSession::id),
        )
        assertEquals(
            listOf("approval"),
            filterSessions(
                sessions,
                "",
                SessionFilter.Approval,
                activeSessionIds = setOf("mobile", "approval"),
                approvalSessionIds = setOf("approval"),
            ).map(HermesSession::id),
        )
        assertEquals(listOf("mobile"), filterSessions(sessions, "", SessionFilter.Mobile).map(HermesSession::id))
        assertEquals(
            listOf("desktop"),
            filterSessions(sessions, "luna", SessionFilter.Desktop, defaultModel = "gpt-5.6-luna").map(HermesSession::id),
        )
    }

    @Test
    fun `placeholder session model resolves to the configured host default`() {
        assertEquals("gpt-5.6-luna", displaySessionModel("hermes-default", "gpt-5.6-luna"))
        assertEquals("Host default", displaySessionModel("hermes-agent", null))
        assertEquals("terra", displaySessionModel("terra", "gpt-5.6-luna"))
    }

    private fun session(
        id: String,
        lastActive: String,
        source: String? = null,
        model: String? = null,
    ) = HermesSession(
        id = id,
        title = id,
        preview = null,
        source = source,
        model = model,
        lastActive = lastActive,
        messageCount = null,
    )
}
