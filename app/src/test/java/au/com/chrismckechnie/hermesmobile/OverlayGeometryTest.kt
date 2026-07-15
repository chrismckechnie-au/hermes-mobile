package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test
    fun `overlay update prefers local status and adds relative freshness`() {
        assertEquals(
            "Checking tests · 2m ago",
            overlayLatestUpdate(
                localStatus = "Checking\n tests",
                remoteStatus = "Remote update",
                updatedAtSeconds = 1_000L,
                nowMillis = 1_120_000L,
            ),
        )
        assertEquals(
            "Last update 2h ago",
            overlayLatestUpdate(null, null, updatedAtSeconds = 1_000L, nowMillis = 8_200_000L),
        )
    }

    @Test
    fun `overlay uses the concrete local session name over a generic remote title`() {
        assertEquals("Release Android build", overlaySessionTitle("Hermes session", "Release Android build"))
        assertEquals("Host title", overlaySessionTitle("Host title", "Local title"))
    }

    @Test
    fun `overlay excludes terminal and unresponsive sessions`() {
        assertTrue(isOverlayActiveSession("running"))
        assertTrue(isOverlayActiveSession("approval"))
        assertFalse(isOverlayActiveSession("completed"))
        assertFalse(isOverlayActiveSession("unresponsive"))
        assertFalse(isOverlayActiveSession("stalled"))
    }

    @Test
    fun `icon snaps to the nearest screen edge`() {
        assertEquals(12, snapOverlayX(currentX = 30, chipWidth = 56, screenWidth = 400, margin = 12))
        assertEquals(332, snapOverlayX(currentX = 300, chipWidth = 56, screenWidth = 400, margin = 12))
    }

    @Test
    fun `icon dismisses only when dropped over the close target`() {
        assertEquals(
            true,
            isOverlayDismissDrop(
                chipX = 172,
                chipY = 704,
                chipSize = 56,
                targetX = 166,
                targetY = 700,
                targetSize = 68,
            ),
        )
        assertEquals(
            false,
            isOverlayDismissDrop(
                chipX = 12,
                chipY = 300,
                chipSize = 56,
                targetX = 166,
                targetY = 700,
                targetSize = 68,
            ),
        )
    }

    @Test
    fun `panel anchors below the icon and follows it`() {
        val first = anchoredPanelPosition(
            chipX = 12,
            chipY = 100,
            chipSize = 56,
            panelWidth = 320,
            panelHeight = 240,
            screenWidth = 400,
            screenHeight = 800,
            margin = 12,
            gap = 8,
        )
        val moved = anchoredPanelPosition(
            chipX = 332,
            chipY = 200,
            chipSize = 56,
            panelWidth = 320,
            panelHeight = 240,
            screenWidth = 400,
            screenHeight = 800,
            margin = 12,
            gap = 8,
        )

        assertEquals(OverlayPoint(12, 164), first)
        assertEquals(OverlayPoint(68, 264), moved)
        assertNotEquals(first, moved)
    }

    @Test
    fun `panel flips above an icon near the bottom edge`() {
        val point = anchoredPanelPosition(
            chipX = 332,
            chipY = 700,
            chipSize = 56,
            panelWidth = 320,
            panelHeight = 240,
            screenWidth = 400,
            screenHeight = 800,
            margin = 12,
            gap = 8,
        )

        assertEquals(OverlayPoint(68, 452), point)
    }
}
