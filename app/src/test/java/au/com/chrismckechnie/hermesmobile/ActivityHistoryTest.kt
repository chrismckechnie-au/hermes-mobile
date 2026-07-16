package au.com.chrismckechnie.hermesmobile

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityHistoryTest {
    @Test
    fun `records bounded sanitized activity and deduplicates event id`() {
        val now = 10_000_000L
        val initial = ActivityEntry("host", "session", "Old", "working", eventId = "event-1", updatedAtMillis = now - 1)
        val replacement = initial.copy(title = "  New  ", latestStatus = "x".repeat(300), updatedAtMillis = now)

        val result = recordActivityEntry(listOf(initial), replacement, now)

        assertEquals(1, result.size)
        assertEquals("New", result.single().title)
        assertEquals(180, result.single().latestStatus?.length)
    }

    @Test
    fun `prunes history to seven days and one hundred entries`() {
        val now = TimeUnit.DAYS.toMillis(20)
        val recent = (0 until 120).map { index ->
            ActivityEntry(
                hostId = "host",
                sessionId = "session-$index",
                title = "Task $index",
                state = "completed",
                event = "session.completed",
                eventId = "event-$index",
                updatedAtMillis = now - index,
            )
        }
        val stale = ActivityEntry(
            "host", "stale", "Stale", "completed",
            event = "session.completed",
            eventId = "stale",
            updatedAtMillis = now - TimeUnit.DAYS.toMillis(8),
        )

        val result = recordActivityEntry(recent + stale, recent.first(), now)

        assertEquals(ACTIVITY_MAX_ENTRIES, result.size)
        assertFalse(result.any { it.eventId == "stale" })
    }

    @Test
    fun `mark read preserves history while clearing unread count`() {
        val item = ActivityEntry(
            "host", "session", "Task", "completed",
            event = "session.completed",
            updatedAtMillis = 1,
        )

        val read = markActivityRead(listOf(item), "host", "session")

        assertTrue(read.single().read)
        assertEquals(0, activityUnreadCount(read))
    }

    @Test
    fun `legacy attention json migrates as unread history`() {
        val decoded = ActivityEntryCodec.decode(
            """[{"hostId":"host","sessionId":"session","title":"Done","state":"completed"}]""",
            nowMillis = 1234,
        )

        assertEquals(1234, decoded.single().updatedAtMillis)
        assertFalse(decoded.single().read)
    }
}
