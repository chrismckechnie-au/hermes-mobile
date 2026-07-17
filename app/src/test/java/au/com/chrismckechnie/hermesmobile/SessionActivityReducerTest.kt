package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActivityReducerTest {
    @Test
    fun `replayed events dedupe by cursor and retain stable tool correlation`() {
        val started = event(
            id = 1,
            type = "tool.start",
            toolId = "call-1",
            toolName = "write_file",
        )
        val complete = event(
            id = 2,
            type = "tool.complete",
            toolId = "call-1",
            toolName = "write_file",
            workspaceUpdate = HermesWorkspaceUpdate(
                listOf(HermesWorkspaceChange("app/Main.kt", "modified", 3, 1, "+activity")),
            ),
        )

        val state = listOf(started, started, complete).fold(SessionActivityState(), ::reduceSessionActivity)
        val tool = state.latestTurn!!.tools.single()

        assertEquals(2L, state.lastEventId)
        assertEquals("call-1", tool.id)
        assertFalse(tool.running)
        assertEquals(1, tool.workspaceChanges.size)
    }

    @Test
    fun `reasoning tasks subagents and terminal response build one compact turn`() {
        val state = listOf(
            event(1, "message.start", userText = "Ship it"),
            event(2, "reasoning.available", text = "Checking the build"),
            event(3, "tasks.updated", tasks = listOf(HermesTask("verify", "Verify release", "in_progress"))),
            event(4, "subagent.progress", subagent = HermesSubagent("sub-1", "working", goal = "Inspect tests", activity = "Running checks")),
            event(5, "message.complete", text = "Release verified."),
        ).fold(SessionActivityState(), ::reduceSessionActivity)
        val turn = state.latestTurn!!

        assertEquals("Ship it", turn.userText)
        assertEquals(listOf("Checking the build"), turn.reasoning)
        assertEquals("Verify release", turn.tasks.single().content)
        assertTrue(turn.subagents.getValue("sub-1").isWorking)
        assertTrue(turn.terminal)
        assertEquals("Release verified.", turn.assistantText)
        assertEquals("Completed", turn.latestStatus)
    }

    @Test
    fun `tool lifecycle chatter does not replace meaningful activity`() {
        var state = reduceSessionActivity(SessionActivityState(), event(1, "message.start", userText = "Fix it"))
        state = reduceSessionActivity(state, event(2, "reasoning.available", text = "Checking the build"))
        assertEquals("Checking the build", state.latestTurn?.latestStatus)

        state = reduceSessionActivity(state, event(3, "tool.start", toolId = "call-1", toolName = "terminal", status = "Using terminal…"))
        state = reduceSessionActivity(state, event(4, "tool.complete", toolId = "call-1", toolName = "terminal", status = "Continuing after terminal"))
        assertEquals("Checking the build", state.latestTurn?.latestStatus)

        state = reduceSessionActivity(state, event(5, "status.error", text = "Tests failed"))
        assertTrue(state.latestTurn?.terminal == true)
        assertEquals("The task hit an issue", state.latestTurn?.latestStatus)
    }

    private fun event(
        id: Long,
        type: String,
        userText: String? = null,
        text: String? = null,
        toolId: String? = null,
        toolName: String? = null,
        tasks: List<HermesTask> = emptyList(),
        subagent: HermesSubagent? = null,
        workspaceUpdate: HermesWorkspaceUpdate? = null,
        status: String? = null,
    ) = HermesSessionActivityEvent(
        eventId = id,
        sessionId = "session-1",
        turnId = "turn-1",
        type = type,
        userText = userText,
        text = text,
        toolId = toolId,
        toolName = toolName,
        tasks = tasks,
        subagent = subagent,
        workspaceUpdate = workspaceUpdate,
        status = status,
    )
}
