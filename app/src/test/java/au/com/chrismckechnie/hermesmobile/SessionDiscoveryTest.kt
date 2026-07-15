package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDiscoveryTest {
    private val sessions = listOf(
        HermesSession("s1", "Release planning", "Review the Android build", "hermes", "terra", null, 4),
        HermesSession("s2", "Research", "Find the official documentation", "web", "luna", null, 2),
        HermesSession("s3", null, "Repair a shell script", "terminal", null, null, 1),
    )

    @Test
    fun `session search matches title preview source and model case insensitively`() {
        assertEquals(listOf("s1"), filterSessions(sessions, "PLAN").map(HermesSession::id))
        assertEquals(listOf("s2"), filterSessions(sessions, "documentation").map(HermesSession::id))
        assertEquals(listOf("s3"), filterSessions(sessions, "terminal").map(HermesSession::id))
        assertEquals(listOf("s1"), filterSessions(sessions, "terra").map(HermesSession::id))
    }

    @Test
    fun `blank search preserves current session ordering`() {
        assertEquals(sessions, filterSessions(sessions, "  "))
    }

    @Test
    fun `transcript export includes only conversation messages`() {
        val transcript = formatSessionTranscript(
            sessionTitle = "Release planning",
            messages = listOf(
                ChatUiItem.User("u1", "Ship the release"),
                ChatUiItem.Tool("tool", "terminal", "./gradlew", running = false),
                ChatUiItem.Reasoning("reasoning", listOf("Private progress")),
                ChatUiItem.Assistant("a1", "The release is ready"),
                ChatUiItem.Assistant("live", "", streaming = true, safeStatus = "Working"),
            ),
        )

        assertTrue(transcript.contains("Session: Release planning"))
        assertTrue(transcript.contains("You:\nShip the release"))
        assertTrue(transcript.contains("Hermes:\nThe release is ready"))
        assertFalse(transcript.contains("./gradlew"))
        assertFalse(transcript.contains("Private progress"))
        assertFalse(transcript.contains("Working"))
    }

    @Test
    fun `transcript export has a safe fallback title`() {
        val transcript = formatSessionTranscript(
            sessionTitle = null,
            messages = listOf(ChatUiItem.User("u1", "Hello")),
        )

        assertTrue(transcript.contains("Session: Untitled session"))
    }
}
