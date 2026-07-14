package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
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
        ))!!

        assertEquals("Release checks", event.title)
        assertEquals(2, event.activeCount)
        assertTrue(event.isTerminal)
    }

    @Test
    fun `rejects payload without routing coordinates`() {
        assertNull(MobilePushEvent.from(mapOf("event" to "session.started")))
    }
}
