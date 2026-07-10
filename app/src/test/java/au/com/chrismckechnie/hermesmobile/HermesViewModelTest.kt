package au.com.chrismckechnie.hermesmobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HermesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var gateway: FakeGateway
    private lateinit var store: FakeStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeGateway()
        store = FakeStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HermesViewModel(gateway, store)

    @Test
    fun `stream error event clears streaming flag and surfaces message`() = runTest(dispatcher) {
        gateway.streamEvents = listOf(
            HermesStreamEvent.RunStarted("run-1"),
            HermesStreamEvent.AssistantDelta("Partial answer"),
            HermesStreamEvent.Failed("stream exploded"),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.setComposerText("hello")
        vm.sendMessage()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("stream exploded", state.errorMessage)
        assertFalse(state.isSending)
        assertTrue(state.messages.filterIsInstance<ChatUiItem.Assistant>().none { it.streaming })
        assertEquals("Partial answer", state.messages.filterIsInstance<ChatUiItem.Assistant>().single().text)
    }

    @Test
    fun `stream ending with no content drops the empty bubble`() = runTest(dispatcher) {
        gateway.streamEvents = listOf(HermesStreamEvent.RunStarted("run-1"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.setComposerText("hello")
        vm.sendMessage()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.messages.filterIsInstance<ChatUiItem.Assistant>().isEmpty())
        assertFalse(state.isSending)
    }

    @Test
    fun `send reuses active session id even when absent from cached list`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectSession("ghost-session")
        advanceUntilIdle()
        vm.setComposerText("hello")
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals(0, gateway.createSessionCalls)
        assertEquals("ghost-session", gateway.lastStreamSessionId)
    }

    @Test
    fun `send with no active session creates one first`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setComposerText("hello")
        vm.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.createSessionCalls)
        assertNotNull(gateway.lastStreamSessionId)
    }

    @Test
    fun `cancelRun stops the run and resets sending state`() = runTest(dispatcher) {
        gateway.streamEvents = listOf(HermesStreamEvent.RunStarted("run-9"))
        gateway.hangAfterEvents = true
        val vm = viewModel()
        advanceUntilIdle()

        vm.setComposerText("long task")
        vm.sendMessage()
        advanceUntilIdle()
        assertTrue(vm.state.value.isSending)

        vm.cancelRun()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isSending)
        assertEquals(listOf("run-9"), gateway.stopRunCalls)
        assertTrue(state.messages.filterIsInstance<ChatUiItem.Assistant>().none { it.streaming })
    }

    @Test
    fun `approval event renders card and respond resolves via gateway`() = runTest(dispatcher) {
        gateway.streamEvents = listOf(
            HermesStreamEvent.RunStarted("run-1"),
            HermesStreamEvent.ApprovalRequested("appr-1", "terminal", "Run ls?", "run-1"),
            HermesStreamEvent.Completed("done", null),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.setComposerText("do something gated")
        vm.sendMessage()
        advanceUntilIdle()

        val approval = vm.state.value.messages.filterIsInstance<ChatUiItem.Approval>().single()
        assertNull(approval.decision)

        vm.respondToApproval(approval.id, true)
        advanceUntilIdle()

        assertEquals(listOf(Triple("run-1", "appr-1", true)), gateway.approvalCalls)
        val resolved = vm.state.value.messages.filterIsInstance<ChatUiItem.Approval>().single()
        assertEquals(true, resolved.decision)
    }

    @Test
    fun `send failure restores composer text`() = runTest(dispatcher) {
        gateway.streamError = IOException("network down")
        val vm = viewModel()
        advanceUntilIdle()

        vm.setComposerText("a long carefully typed prompt")
        vm.sendMessage()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("a long carefully typed prompt", state.composerText)
        assertNotNull(state.errorMessage)
        assertFalse(state.isSending)
    }

    @Test
    fun `keystore unlock failure surfaces a notice instead of silent wipe`() = runTest(dispatcher) {
        store.unlockFailed = true
        store.snapshot = HostSnapshot()
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.errorMessage.orEmpty().contains("unlocked"))
    }

    @Test
    fun `deleteHost selects the next remaining host`() = runTest(dispatcher) {
        val second = testHost("h2", "Second")
        store.snapshot = HostSnapshot(listOf(testHost(), second), "h1")
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteHost("h1")
        advanceUntilIdle()

        assertEquals("h2", vm.state.value.activeHostId)
    }

    @Test
    fun `duplicate tool completion only closes the oldest running card`() = runTest(dispatcher) {
        gateway.streamEvents = listOf(
            HermesStreamEvent.RunStarted("run-1"),
            HermesStreamEvent.ToolStarted("terminal", "first"),
            HermesStreamEvent.ToolStarted("terminal", "second"),
            HermesStreamEvent.ToolCompleted("terminal", "first done"),
            HermesStreamEvent.Completed("done", null),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.setComposerText("run tools")
        vm.sendMessage()
        advanceUntilIdle()

        val tools = vm.state.value.messages.filterIsInstance<ChatUiItem.Tool>()
        assertEquals(2, tools.size)
        assertFalse(tools[0].running)
        assertEquals("first done", tools[0].preview)
        assertTrue(tools[1].running)
    }
}

private fun testHost(id: String = "h1", name: String = "Test Host") = HostProfile(
    id = id,
    name = name,
    baseUrl = "http://192.168.1.10:8642",
    apiKey = "secret",
    allowInsecureHttp = true,
)

private class FakeStore : HostStore {
    var snapshot = HostSnapshot(listOf(testHost()), "h1")
    var unlockFailed = false
    var saved: HostSnapshot? = null

    override fun load() = HostLoadResult(snapshot, unlockFailed)
    override fun save(snapshot: HostSnapshot) {
        saved = snapshot
    }
}

private class FakeGateway : HermesGateway {
    var sessions = listOf(fakeSession("s1"))
    var hasMore = false
    var streamEvents: List<HermesStreamEvent> = emptyList()
    var streamError: Throwable? = null
    var hangAfterEvents = false
    var createSessionCalls = 0
    var lastStreamSessionId: String? = null
    val stopRunCalls = mutableListOf<String>()
    val approvalCalls = mutableListOf<Triple<String, String, Boolean>>()

    override suspend fun probe(host: HostProfile) = HermesCapabilities("hermes-agent", "hermes-agent", setOf("session_chat_stream"))

    override suspend fun listSessions(host: HostProfile, limit: Int, offset: Int) = HermesSessionPage(sessions, hasMore)

    override suspend fun createSession(host: HostProfile, title: String?): HermesSession {
        createSessionCalls++
        return fakeSession("created-$createSessionCalls")
    }

    override suspend fun loadMessages(host: HostProfile, sessionId: String): List<HermesMessage> = emptyList()

    override suspend fun renameSession(host: HostProfile, sessionId: String, title: String) = Unit

    override suspend fun deleteSession(host: HostProfile, sessionId: String) = Unit

    override suspend fun listJobs(host: HostProfile): List<HermesJob> = emptyList()

    override suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean) = Unit

    override suspend fun runJob(host: HostProfile, jobId: String) = Unit

    override suspend fun stopRun(host: HostProfile, runId: String) {
        stopRunCalls += runId
    }

    override suspend fun resolveApproval(host: HostProfile, runId: String, approvalId: String, approve: Boolean) {
        approvalCalls += Triple(runId, approvalId, approve)
    }

    override suspend fun streamSessionChat(
        host: HostProfile,
        sessionId: String,
        input: String,
        onEvent: (HermesStreamEvent) -> Unit,
    ) {
        lastStreamSessionId = sessionId
        streamError?.let { throw it }
        streamEvents.forEach(onEvent)
        if (hangAfterEvents) awaitCancellation()
    }

    private fun fakeSession(id: String) = HermesSession(
        id = id,
        title = "Session $id",
        preview = null,
        source = "api_server",
        model = "hermes-agent",
        lastActive = null,
        messageCount = 0,
    )
}
