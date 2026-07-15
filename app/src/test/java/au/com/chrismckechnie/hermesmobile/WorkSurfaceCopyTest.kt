package au.com.chrismckechnie.hermesmobile

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSurfaceCopyTest {
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
    fun `only session outcomes that need review create attention`() {
        assertTrue(event("approval.required").requiresAttention)
        assertTrue(event("session.completed").requiresAttention)
        assertFalse(event("session.started").requiresAttention)
        assertFalse(event("job.failed").requiresAttention)
    }

    @Test
    fun `tool activity summary remains one compact line`() {
        assertEquals(
            "terminal · Running tests now",
            compactToolSummary(ChatUiItem.Tool("tool-1", "terminal", "Running\n tests now", running = true)),
        )
        assertEquals(
            "search · Completed",
            compactToolSummary(ChatUiItem.Tool("tool-2", "search", null, running = false)),
        )
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
        assertEquals("Working · terminal", toolActivitySummary(activity.tools))
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
