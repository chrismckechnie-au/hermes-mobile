package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayAttentionBadgeTest {
    @Test fun `approval failure and completion have distinct flags`() {
        assertEquals(
            OverlayBadgePresentation("!", OverlayBadgeKind.Approval),
            overlayAttentionBadge(listOf(entry("approval.required", "waiting_for_approval"))),
        )
        assertEquals(
            OverlayBadgePresentation("!", OverlayBadgeKind.Error),
            overlayAttentionBadge(listOf(entry("session.failed", "failed"))),
        )
        assertEquals(
            OverlayBadgePresentation("✓", OverlayBadgeKind.Done),
            overlayAttentionBadge(listOf(entry("session.completed", "completed"))),
        )
    }

    @Test fun `multiple unread updates show bounded count and latest severity`() {
        val result = overlayAttentionBadge((1..12).map { index ->
            entry(if (index == 12) "job.failed" else "session.completed", if (index == 12) "failed" else "completed", index.toLong())
        })
        assertEquals("9", result.text)
        assertEquals(OverlayBadgeKind.Error, result.kind)
    }

    private fun entry(event: String, state: String, time: Long = 1L) = ActivityEntry(
        hostId = "host",
        sessionId = "session-$time",
        title = "Session",
        state = state,
        event = event,
        updatedAtMillis = time,
    )
}
