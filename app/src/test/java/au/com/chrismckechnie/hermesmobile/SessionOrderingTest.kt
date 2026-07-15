package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class SessionOrderingTest {
    @Test
    fun `sessions are ordered strictly by latest update`() {
        val sessions = listOf(
            session("old", "1700000000"),
            session("active", "1600000000"),
            session("new", "1800000000"),
        )

        assertEquals(
            listOf("new", "old", "active"),
            sortSessionsByActivity(sessions).map(HermesSession::id),
        )
    }

    @Test
    fun `newer host activity updates the session ordering`() {
        val sessions = listOf(
            session("working", "1600000000"),
            session("recent", "1800000000"),
        )

        assertEquals(
            listOf("working", "recent"),
            sortSessionsByActivity(
                sessions,
                activityUpdatedAt = mapOf("working" to 1_900_000_000L),
            ).map(HermesSession::id),
        )
    }

    @Test
    fun `supports seconds milliseconds and ISO activity timestamps`() {
        assertEquals(1_700_000_000_000, sessionActivityMillis("1700000000"))
        assertEquals(1_700_000_000_123, sessionActivityMillis("1700000000123"))
        assertEquals(1_700_000_000_000, sessionActivityMillis("2023-11-14T22:13:20Z"))
        assertEquals(Long.MIN_VALUE, sessionActivityMillis("-9223372036854775808"))
    }

    @Test
    fun `session timestamps use compact readable dates`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-07-16T10:00:00Z").toEpochMilli()

        assertEquals("9:15 AM", formatSessionUpdatedAt("2026-07-16T09:15:00Z", now, zone, Locale.US))
        assertEquals("Yesterday", formatSessionUpdatedAt("2026-07-15T22:00:00Z", now, zone, Locale.US))
        assertEquals("10 Jul", formatSessionUpdatedAt("2026-07-10T12:00:00Z", now, zone, Locale.US))
        assertEquals("10 Jul 2025", formatSessionUpdatedAt("2025-07-10T12:00:00Z", now, zone, Locale.US))
        assertEquals(null, formatSessionUpdatedAt(null, now, zone, Locale.US))
        assertEquals(null, formatSessionUpdatedAt("-1", now, zone, Locale.US))
    }

    @Test
    fun `untitled sessions explain their origin and remain distinguishable`() {
        assertEquals("Delegated task · abcdef12", sessionDisplayTitle(session("abcdef123456", "1", source = "subagent").copy(title = null)))
        assertEquals("Scheduled run · abcdef12", sessionDisplayTitle(session("abcdef123456", "1", source = "cron").copy(title = "Untitled")))
        assertEquals("Discord chat · abcdef12", sessionDisplayTitle(session("abcdef123456", "1", source = "discord").copy(title = null)))
        assertEquals("My work", sessionDisplayTitle(session("abcdef123456", "1", source = "subagent").copy(title = "My work")))
        assertEquals("Delegated", sessionSourceLabel("subagent"))
        assertEquals("Scheduled", sessionSourceLabel("cron"))
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
        assertEquals(
            listOf("desktop"),
            filterSessions(
                sessions,
                "",
                SessionFilter.Stalled,
                stalledSessionIds = setOf("desktop"),
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
