package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompletedActivityDigestCodecTest {
    @Test
    fun `codec retains bounded safe terminal activity only`() {
        val now = System.currentTimeMillis()
        val digest = CompletedActivityDigest(
            hostId = "host-1",
            sessionId = "session-1",
            runId = "run-1",
            milestones = listOf("Checked the build", "Reviewed lint", "Prepared response", "Ignored"),
            tools = listOf(
                CompletedToolDigest("terminal", "token=secret-value", failed = false, durationSeconds = 1.25),
            ),
            outcome = ActivityOutcome.Completed,
            completedAtMillis = now,
        )

        val decoded = CompletedActivityDigestCodec.decode(CompletedActivityDigestCodec.encode(listOf(digest))).single()

        assertEquals(listOf("Reviewed lint", "Prepared response", "Ignored"), decoded.milestones)
        assertEquals("token=[redacted]", decoded.tools.single().preview)
        assertEquals(ActivityOutcome.Completed, decoded.outcome)
    }

    @Test
    fun `bounded digests discard expired activity and cap a session`() {
        val now = System.currentTimeMillis()
        val expired = CompletedActivityDigest("h", "s", "expired", emptyList(), emptyList(), ActivityOutcome.Completed, 0)
        val current = (0..ACTIVITY_DIGESTS_PER_SESSION).map { index ->
            CompletedActivityDigest("h", "s", "run-$index", emptyList(), emptyList(), ActivityOutcome.Completed, now - index)
        }

        val bounded = boundedActivityDigests(listOf(expired) + current, now)

        assertEquals(ACTIVITY_DIGESTS_PER_SESSION, bounded.size)
        assertFalse(bounded.any { it.runId == "expired" })
    }
}
