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
}
