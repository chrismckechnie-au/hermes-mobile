package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUiGroupingTest {
    @Test
    fun `assistant avatar appears once per user turn rather than after every tool`() {
        val messages = listOf(
            ChatUiItem.User("u1", "do the work"),
            ChatUiItem.Assistant("a1", "Starting"),
            ChatUiItem.Tool("t1", "terminal", "first", running = false),
            ChatUiItem.Assistant("a2", "Continuing"),
            ChatUiItem.Tool("t2", "terminal", "second", running = false),
            ChatUiItem.Assistant("a3", "Done"),
            ChatUiItem.User("u2", "one more thing"),
            ChatUiItem.Tool("t3", "skill", "checking", running = false),
            ChatUiItem.Assistant("a4", "Finished"),
        )

        assertEquals(setOf("a1", "a4"), firstAssistantIdsByTurn(messages))
    }

    @Test
    fun `streaming timeline changes do not invalidate composer input state`() {
        val host = HostProfile("h1", "Host", "https://host.example", "key")
        val key = SessionKey("h1", "s1")
        val run = ActiveRun(
            host = host,
            sessionId = "s1",
            sessionTitle = "Session",
            runId = "run-1",
            tail = listOf(ChatUiItem.Assistant("a1", "Starting", streaming = true)),
        )
        val state = HermesUiState(
            hosts = listOf(host),
            activeHostId = host.id,
            activeSessionId = key.sessionId,
            connectionPhase = HostConnectionPhase.Connected,
            activeRuns = mapOf(key to run),
        )
        val updated = state.copy(
            activeRuns = mapOf(
                key to run.copy(tail = listOf(ChatUiItem.Assistant("a1", "Streaming more text", streaming = true))),
            ),
        )

        assertEquals(composerInputState(state), composerInputState(updated))
    }
}
