package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePushEventTest {
    @Test
    fun `parses privacy safe host session status payload`() {
        val event = MobilePushEvent.from(mapOf(
            "event" to "session.completed",
            "host_profile_id" to "host-1",
            "session_id" to "session-1",
            "title" to "Release checks",
            "state" to "completed",
            "active_count" to "2",
            "latest_status" to "  Finished verification\nwithout errors  ",
            "updated_at" to "1784160000",
            "tasks_completed" to "4",
            "tasks_total" to "5",
            "active_subagents" to "1",
            "error_category" to "tool_failure",
        ))!!

        assertEquals("Release checks", event.title)
        assertEquals(2, event.activeCount)
        assertEquals("Finished verification without errors", event.latestStatus)
        assertEquals(1_784_160_000_000L, event.updatedAtMillis)
        assertEquals(4, event.tasksCompleted)
        assertEquals(5, event.tasksTotal)
        assertEquals(1, event.activeSubagents)
        assertEquals(MobileErrorCategory.ToolFailure, event.errorCategory)
        assertTrue(event.isTerminal)
    }

    @Test
    fun `bounds and sanitizes optional push status fields`() {
        val event = MobilePushEvent.from(mapOf(
            "event" to "session.failed",
            "host_profile_id" to "host-1",
            "session_id" to "session-1",
            "latest_status" to "x".repeat(300),
            "tasks_completed" to "-4",
            "tasks_total" to "999999",
            "active_subagents" to "bad",
            "error_category" to "raw_secret_exception",
        ))!!

        assertEquals(180, event.latestStatus?.length)
        assertEquals(0, event.tasksCompleted)
        assertEquals(999, event.tasksTotal)
        assertEquals(0, event.activeSubagents)
        assertEquals(MobileErrorCategory.Unknown, event.errorCategory)
        assertFalse(event.isActive)
    }

    @Test
    fun `notification identity is scoped by host session and run`() {
        val first = mobileNotificationId("host-a", "session", "run")

        assertFalse(first == mobileNotificationId("host-b", "session", "run"))
        assertFalse(first == mobileNotificationId("host-a", "session", "other-run"))
        assertEquals(first, mobileNotificationId("host-a", "session", "run"))
    }

    @Test
    fun `routes active approval failure and completion to separate lanes`() {
        fun event(kind: String) = MobilePushEvent.from(mapOf(
            "event" to kind,
            "host_profile_id" to "host",
            "session_id" to "session",
        ))!!

        assertEquals(NotificationLane.Active, notificationLane(event("session.started")))
        assertEquals(NotificationLane.Action, notificationLane(event("approval.required")))
        assertEquals(NotificationLane.Action, notificationLane(event("session.failed")))
        assertEquals(NotificationLane.Result, notificationLane(event("session.completed")))
    }

    @Test
    fun `notification copy includes safe progress and alerts for completion`() {
        val event = MobilePushEvent.from(mapOf(
            "event" to "session.completed",
            "host_profile_id" to "host",
            "session_id" to "session",
            "title" to "Release",
            "latest_status" to "Verification passed",
            "tasks_completed" to "5",
            "tasks_total" to "5",
        ))!!

        val copy = mobileNotificationCopy(event)

        assertTrue(copy.text.contains("Verification passed"))
        assertTrue(copy.text.contains("5/5 tasks"))
        assertFalse(copy.silent)
    }

    @Test
    fun `rejects payload without routing coordinates`() {
        assertNull(MobilePushEvent.from(mapOf("event" to "session.started")))
    }
}
