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
