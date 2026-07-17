package au.com.chrismckechnie.hermesmobile

import androidx.core.app.NotificationCompat
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSurfaceCopyTest {
    @Test
    fun `active work monitoring does not start the foreground service without the overlay`() {
        assertFalse(shouldStartOverlayService(overlayEnabled = false, canDrawOverlays = true))
        assertFalse(shouldStartOverlayService(overlayEnabled = true, canDrawOverlays = false))
        assertTrue(shouldStartOverlayService(overlayEnabled = true, canDrawOverlays = true))
    }

    @Test
    fun `workspace diff colours additions and deletions without colouring headers`() {
        val diff = colorWorkspaceDiff(
            "--- a/File.kt\n+++ b/File.kt\n-old\n+new\n unchanged",
            Color.Green,
            Color.Red,
            Color.Gray,
        )

        assertEquals(
            listOf(Color.Gray, Color.Gray, Color.Red, Color.Green, Color.Gray),
            diff.spanStyles.map { it.item.color },
        )
    }

    @Test
    fun `active work copy stays compact and summarizes additional sessions`() {
        val sessions = listOf(
            "Desktop" to HermesActiveSession("s1", "r1", "Fix the Android overlay", "running", "api_server"),
            "Server" to HermesActiveSession("s2", "r2", "Run release checks", "running", "cli"),
        )

        val copy = activeWorkCopy(sessions)

        assertEquals("Hermes is working on 2 tasks", copy.title)
        assertEquals("Fix the Android overlay · 1 more", copy.text)
        assertEquals("Desktop", copy.summary)
    }

    @Test
    fun `push copy distinguishes working approval and completion`() {
        val working = mobileNotificationCopy(event("session.started"))
        val approval = mobileNotificationCopy(event("approval.required"))
        val completed = mobileNotificationCopy(event("session.completed"))

        assertEquals("Hermes is working", working.title)
        assertEquals(NotificationCompat.CATEGORY_PROGRESS, working.category)
        assertTrue(working.ongoing)
        assertTrue(working.silent)
        assertEquals("Hermes needs your approval", approval.title)
        assertFalse(approval.silent)
        assertEquals("Hermes finished", completed.title)
        assertFalse(completed.ongoing)
    }

    @Test
    fun `active work surface promotes approval state`() {
        val session = HermesActiveSession(
            "s1", "r1", "Deploy the release", "waiting_for_approval", "api_server",
        )

        assertEquals("Hermes needs approval", activeWorkCopy(listOf("Desktop" to session)).title)
    }

    @Test
    fun `attention labels make terminal states scannable`() {
        assertEquals("Needs approval", attentionLabel("waiting_for_approval"))
        assertEquals("Failed", attentionLabel("failed"))
        assertEquals("Finished", attentionLabel("completed"))
    }

    @Test
    fun `session and job outcomes that need review create attention`() {
        assertTrue(event("approval.required").requiresAttention)
        assertTrue(event("session.completed").requiresAttention)
        assertFalse(event("session.started").requiresAttention)
        assertTrue(event("job.failed").requiresAttention)
    }

    @Test
    fun `tool activity summary remains one compact line`() {
        assertEquals(
            "Running command · Running tests now",
            compactToolSummary(ChatUiItem.Tool("tool-1", "terminal", "Running\n tests now", running = true)),
        )
        assertEquals(
            "Searching · Completed",
            compactToolSummary(ChatUiItem.Tool("tool-2", "search", null, running = false)),
        )
    }

    @Test
    fun `activity trace uses plain outcome copy and hides completed history while work is live`() {
        val completed = SessionActivityTurn(
            turnId = "turn-1",
            tools = listOf(ChatUiItem.Tool("tool-1", "terminal", "done", running = false)),
            terminal = true,
        )
        val active = SessionActivityTurn(
            turnId = "turn-2",
            tools = listOf(
                ChatUiItem.Tool("tool-2", "terminal", "done", running = false),
                ChatUiItem.Tool("tool-3", "search", "working", running = true),
            ),
        )

        assertEquals("Work completed · 1 step", activityTraceLabel(listOf(completed)))
        assertEquals("Hermes is working · 2 steps", activityTraceLabel(listOf(completed, active)))
        assertEquals(listOf("turn-2"), visibleActivityTurns(listOf(completed, active)).map { it.turnId })
        assertEquals(listOf("tool-3"), visibleActivityTools(active).map { it.id })
        assertEquals(listOf("tool-1"), visibleActivityTools(completed).map { it.id })
    }

    @Test
    fun `activity trace does not repeat its headline in the reasoning list`() {
        val turn = SessionActivityTurn(
            turnId = "turn-1",
            reasoning = listOf("Reviewing the request", "Checking the build"),
            latestStatus = "Checking the build",
        )

        assertEquals(listOf("Reviewing the request"), visibleReasoningUpdates(turn))
    }

    @Test
    fun `active work says what happens after tool activity ends`() {
        val waiting = SessionActivityTurn(
            turnId = "turn-1",
            tools = listOf(ChatUiItem.Tool("tool-1", "terminal", "done", running = false)),
        )
        val planned = waiting.copy(tasks = listOf(HermesTask("task-1", "Review the output", "in_progress")))
        val runningTool = waiting.copy(tools = listOf(ChatUiItem.Tool("tool-2", "terminal", "running", running = true)))

        assertEquals("Run is still active · wait or send a follow-up", activeWorkNextStep(waiting))
        assertEquals("Next: Review the output", activeWorkNextStep(planned))
        assertEquals(null, activeWorkNextStep(runningTool))
        assertEquals(null, activeWorkNextStep(waiting.copy(terminal = true)))
    }

    @Test
    fun `consecutive tool prompts become one expandable activity group`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatUiItem.User("user-1", "Check the build"),
                ChatUiItem.Tool("tool-1", "terminal", "./gradlew test", running = false),
                ChatUiItem.Tool("tool-2", "terminal", "./gradlew lint", running = true),
                ChatUiItem.Assistant("assistant-1", "The build is running."),
            ),
        )

        assertEquals(3, timeline.size)
        val activity = timeline[1] as ChatTimelineItem.ToolGroup
        assertEquals(listOf("tool-1", "tool-2"), activity.tools.map { it.id })
        assertEquals("Working · Running command", toolActivitySummary(activity.tools))
    }

    @Test
    fun `all tool calls in a turn stay in one activity group`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatUiItem.User("user-1", "Check the build"),
                ChatUiItem.Tool("tool-1", "terminal", "./gradlew test", running = false),
                ChatUiItem.Reasoning("reasoning-1", listOf("Checking results")),
                ChatUiItem.Tool("tool-2", "search", "Find lint report", running = false),
                ChatUiItem.Assistant("assistant-1", "The build passed."),
            ),
        )

        assertEquals(4, timeline.size)
        val activity = timeline[1] as ChatTimelineItem.ToolGroup
        assertEquals(listOf("tool-1", "tool-2"), activity.tools.map { it.id })
        assertTrue(timeline[2] is ChatTimelineItem.Message)
    }

    @Test
    fun `late tool records stay above the agent reply`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatUiItem.User("user-1", "Check the build"),
                ChatUiItem.Assistant("assistant-1", "The build passed."),
                ChatUiItem.Tool("tool-1", "terminal", "./gradlew test", running = false),
            ),
        )

        assertTrue(timeline[1] is ChatTimelineItem.ToolGroup)
        assertEquals("assistant-1", (timeline[2] as ChatTimelineItem.Message).item.id)

        val chronological = groupChatTimeline(
            listOf(
                ChatUiItem.User("user-1", "Check the build"),
                ChatUiItem.Assistant("assistant-1", "The build passed."),
                ChatUiItem.Tool("tool-1", "terminal", "./gradlew test", running = false),
            ),
            ChatActivityLayout.Chronological,
        )
        assertTrue(chronological[1] is ChatTimelineItem.ToolGroup)
        assertEquals("assistant-1", (chronological[2] as ChatTimelineItem.Message).item.id)
    }

    @Test
    fun `late activity cards stay above the agent reply`() {
        val digest = CompletedActivityDigest(
            hostId = "host-1",
            sessionId = "session-1",
            runId = "run-1",
            milestones = emptyList(),
            tools = emptyList(),
            outcome = ActivityOutcome.Completed,
            completedAtMillis = 1L,
        )
        val items = listOf(
            ChatUiItem.User("user-1", "Check the build"),
            ChatUiItem.Assistant("assistant-1", "The build passed."),
            ChatUiItem.Reasoning("reasoning-1", listOf("Checking results")),
            ChatUiItem.Activity("activity-1", emptyList()),
            ChatUiItem.CompletedActivity("completed-1", digest, showTools = false),
            ChatUiItem.Tool("tool-1", "terminal", "./gradlew test", running = false),
        )

        assertEquals(
            listOf("user-1", "reasoning-1", "activity-1", "completed-1", "tools:tool-1", "assistant-1"),
            groupChatTimeline(items).map(ChatTimelineItem::id),
        )
        assertEquals(
            listOf("user-1", "reasoning-1", "activity-1", "completed-1", "tools:tool-1", "assistant-1"),
            groupChatTimeline(items, ChatActivityLayout.Chronological).map(ChatTimelineItem::id),
        )
    }

    @Test
    fun `tool activity group keeps its key after more than four calls`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatUiItem.User("user-1", "Check the build"),
                *(1..5).map { number ->
                    ChatUiItem.Tool("tool-$number", "terminal", "step $number", running = number == 5)
                }.toTypedArray(),
            ),
        )

        val group = timeline.single { it is ChatTimelineItem.ToolGroup } as ChatTimelineItem.ToolGroup
        assertEquals("tools:tool-1", group.id)
        assertEquals(5, group.tools.size)
    }

    @Test
    fun `chronological layout starts a new tool card after a meaningful status`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatUiItem.User("user-1", "Check the build"),
                ChatUiItem.Tool("tool-1", "terminal", "./gradlew test", running = false),
                ChatUiItem.Reasoning("reasoning-1", listOf("Checking results")),
                ChatUiItem.Tool("tool-2", "search", "Find lint report", running = false),
                ChatUiItem.Assistant("assistant-1", "The build passed."),
            ),
            ChatActivityLayout.Chronological,
        )

        assertEquals(5, timeline.size)
        assertEquals(listOf("tool-1"), (timeline[1] as ChatTimelineItem.ToolGroup).tools.map { it.id })
        assertEquals(listOf("tool-2"), (timeline[3] as ChatTimelineItem.ToolGroup).tools.map { it.id })
    }

    @Test
    fun `tool cards condense only matching consecutive calls and redact previews`() {
        val tools = listOf(
            ChatUiItem.Tool("tool-1", "terminal", "echo ok", running = false),
            ChatUiItem.Tool("tool-2", "terminal", "echo ok", running = false),
            ChatUiItem.Tool("tool-3", "terminal", "echo next", running = false),
        )

        assertEquals(listOf(2, 1), condensedToolActivity(tools).map(CondensedToolActivity::repeatCount))
        assertEquals("token=[redacted]", safeActivityPreview("token=very-secret-value"))
        assertEquals("authorization=[redacted]", safeActivityPreview("authorization: Bearer very-secret-value"))
        assertEquals("--api-key [redacted]", safeActivityPreview("--api-key very-secret-value"))
    }

    @Test
    fun `visible tool activity stays capped while calls change state`() {
        val calls = (1..5).map { number ->
            ChatUiItem.Tool("tool-$number", "terminal", "step $number", running = false)
        }

        assertEquals(
            listOf("tool-2", "tool-3", "tool-4", "tool-5"),
            visibleToolActivity(condensedToolActivity(calls)).map { it.item.id },
        )
        assertEquals(
            listOf("tool-2", "tool-3", "tool-4", "tool-5"),
            visibleToolActivity(condensedToolActivity(calls.dropLast(1) + calls.last().copy(running = true)))
                .map { it.item.id },
        )
    }

    @Test
    fun `duplicate transcript ids receive unique compose keys`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatUiItem.User("same", "First render"),
                ChatUiItem.Assistant("same", "Hydrated again"),
                ChatUiItem.Tool("same", "terminal", "echo ok", running = false),
                ChatUiItem.User("tools:same", "Collides with the tool group prefix"),
            ),
        )

        assertEquals(timeline.size, timeline.map(ChatTimelineItem::id).toSet().size)
    }

    @Test
    fun `run banner prefers the current session name over a stale run label`() {
        val host = HostProfile("host-1", "Host", "http://host.test", "key", allowInsecureHttp = true)
        val run = ActiveRun(host, "session-1", "API session", "run-1")
        val session = HermesSession("session-1", "Release verification", null, null, null, null, null)

        assertEquals("Release verification", displayRunSessionName(run, listOf(session)))
    }

    private fun event(type: String) = MobilePushEvent(
        event = type,
        hostProfileId = "host-1",
        sessionId = "session-1",
        runId = "run-1",
        title = "Fix the Android overlay",
        state = "running",
        activeCount = 1,
    )
}
