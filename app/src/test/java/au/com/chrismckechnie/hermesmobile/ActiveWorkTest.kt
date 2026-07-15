package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWorkTest {
    private val hostA = HostProfile("h1", "Desktop", "https://a.example.com", "key")
    private val hostB = HostProfile("h2", "Server", "https://b.example.com", "key")

    @Test
    fun `active work includes every run and prioritises attention`() {
        val working = ActiveRun(hostA, "s1", "Build", "r1")
        val approval = ActiveRun(hostB, "s1", "Deploy", "r2", awaitingApproval = true)
        val state = HermesUiState(
            hosts = listOf(hostA, hostB),
            activeHostId = hostA.id,
            activeSessionId = "s1",
            activeRuns = mapOf(SessionKey("h1", "s1") to working, SessionKey("h2", "s1") to approval),
        )

        val items = state.activeWorkItems()

        assertEquals(listOf("r2", "r1"), items.map { it.ref?.runId })
        assertEquals(listOf("Deploy", "Build"), items.map(ActiveWorkItem::title))
        assertEquals("1 needs attention · 2 active", activeWorkSummary(items))
    }

    @Test
    fun `local and host reported work deduplicate by host and session`() {
        val key = SessionKey(hostA.id, "s1")
        val state = HermesUiState(
            hosts = listOf(hostA),
            activeHostId = hostA.id,
            activeRuns = mapOf(key to ActiveRun(hostA, "s1", "Local title", "run-1")),
            activeHostSessions = mapOf(key to HermesActiveSession("s1", "run-1", "Remote title", "working", "mobile")),
        )

        val item = state.activeWorkItems().single()

        assertEquals("Local title", item.title)
        assertEquals("run-1", item.ref?.runId)
    }

    @Test
    fun `remote only work remains visible without a stop action`() {
        val key = SessionKey(hostA.id, "remote")
        val state = HermesUiState(
            hosts = listOf(hostA),
            activeHostId = hostA.id,
            activeHostSessions = mapOf(key to HermesActiveSession("remote", null, "Desktop task", "working", "desktop")),
        )

        val item = state.activeWorkItems().single()

        assertNull(item.ref)
        assertEquals("Desktop task", item.title)
        assertTrue(activeWorkSummary(listOf(item)).contains("1 active run"))
    }
}
