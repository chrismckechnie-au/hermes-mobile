package au.com.chrismckechnie.hermesmobile

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

private val RUN_BUNDLE = setOf(
    "run_submission", "run_events_sse", "run_stop", "approval_events", "run_approval_response",
    "skills_api", "session_resources", "session_fork", "run_reasoning_effort",
)

private class FakeGateway : HermesGateway {
    var capabilities = HermesCapabilities("hermes-agent", "hermes-agent", RUN_BUNDLE)
    val sessions = mutableListOf(HermesSession("s1", "First", null, "api_server", null, null, 2))
    val messages = mutableMapOf(
        "s1" to mutableListOf(
            HermesMessage("m1", "user", "hello"),
            HermesMessage("m2", "assistant", "hi"),
        ),
    )
    val resolvedIds = mutableMapOf<String, String>()
    var skills = listOf(HermesSkill("grill-me", "A relentless interview"))
    var toolsets = listOf(HermesToolset("terminal", "Terminal", "Run commands", enabled = true, configured = true, tools = listOf("shell_command")))
    var models = listOf("hermes-agent", "hermes-fast", "gpt-5.6-terra")
    var activeSessions = emptyList<HermesActiveSession>()
    private val eventStreams = mutableMapOf<String, Channel<HermesRunEvent>>()
    val events: Channel<HermesRunEvent> get() = eventsFor("run-1")
    var runStatus = HermesRunStatus("run-1", "completed")
    var submitError: Throwable? = null
    val submits = mutableListOf<SubmitCall>()
    val approvals = mutableListOf<String>()
    val stops = mutableListOf<String>()
    val statusRequests = mutableListOf<String>()
    val renames = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<String>()
    val forks = mutableListOf<String>()
    val created = mutableListOf<HermesSession>()

    data class SubmitCall(val sessionId: String, val input: String, val history: List<HermesMessage>, val model: String?, val reasoningEffort: String?)

    fun eventsFor(runId: String): Channel<HermesRunEvent> =
        eventStreams.getOrPut(runId) { Channel(Channel.UNLIMITED) }

    override suspend fun probe(host: HostProfile) = capabilities
    override suspend fun listSessions(host: HostProfile, limit: Int, offset: Int) = HermesSessionPage(
        sessions = sessions.drop(offset).take(limit),
        hasMore = offset + limit < sessions.size,
    )
    override suspend fun createSession(host: HostProfile, title: String?): HermesSession {
        val session = HermesSession("new-${created.size}", title, null, "api_server", null, null, 0)
        created += session
        sessions.add(0, session)
        messages[session.id] = mutableListOf()
        return session
    }

    override suspend fun loadMessages(host: HostProfile, sessionId: String): HermesMessagesPage {
        val resolved = resolvedIds[sessionId] ?: sessionId
        return HermesMessagesPage(resolved, messages[resolved]?.toList() ?: emptyList())
    }

    override suspend fun listJobs(host: HostProfile) = emptyList<HermesJob>()
    override suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean) = Unit
    override suspend fun runJob(host: HostProfile, jobId: String) = Unit
    override suspend fun listSkills(host: HostProfile) = skills
    override suspend fun listToolsets(host: HostProfile) = toolsets
    override suspend fun listModels(host: HostProfile) = models
    override suspend fun listActiveSessions(host: HostProfile) = activeSessions

    override suspend fun submitRun(host: HostProfile, sessionId: String, input: String, history: List<HermesMessage>, model: String?, reasoningEffort: String?): String {
        submitError?.let { throw it }
        submits += SubmitCall(sessionId, input, history, model, reasoningEffort)
        return "run-${submits.size}"
    }

    override suspend fun streamRunEvents(host: HostProfile, runId: String, onEvent: (HermesRunEvent) -> Unit) {
        for (event in eventsFor(runId)) onEvent(event)
    }

    override suspend fun getRunStatus(host: HostProfile, runId: String): HermesRunStatus {
        statusRequests += runId
        return runStatus
    }
    override suspend fun respondApproval(host: HostProfile, runId: String, choice: String) { approvals += choice }
    override suspend fun stopRun(host: HostProfile, runId: String) { stops += runId }

    override suspend fun renameSession(host: HostProfile, sessionId: String, title: String): HermesSession {
        renames += sessionId to title
        val renamed = sessions.first { it.id == sessionId }.copy(title = title)
        sessions.replaceAll { if (it.id == sessionId) renamed else it }
        return renamed
    }

    override suspend fun deleteSession(host: HostProfile, sessionId: String) {
        deletes += sessionId
        sessions.removeAll { it.id == sessionId }
    }

    override suspend fun forkSession(host: HostProfile, sessionId: String): HermesSession {
        forks += sessionId
        val child = HermesSession("fork-of-$sessionId", "fork", null, "api_server", null, null, 0)
        sessions.add(0, child)
        messages[child.id] = (messages[sessionId] ?: mutableListOf()).toMutableList()
        return child
    }
}

private class FakeHostStore(
    var snapshot: HostSnapshot,
    var unlockFailed: Boolean = false,
) : HostStore {
    override fun load() = HostLoadResult(snapshot, unlockFailed)
    override fun save(snapshot: HostSnapshot) { this.snapshot = snapshot }
}

private class FakeSettingsStore(private val initialMode: ThemeMode) : SettingsStore {
    var savedMode: ThemeMode? = null
    var checkpoints = emptyList<RunCheckpoint>()
    var checkpoint: RunCheckpoint?
        get() = checkpoints.firstOrNull()
        set(value) { checkpoints = listOfNotNull(value) }
    val runStatuses = mutableMapOf<String, String>()
    var attentionItems = emptyList<AttentionItem>()

    override fun loadThemeMode(): ThemeMode = initialMode
    override fun saveThemeMode(mode: ThemeMode) {
        savedMode = mode
    }
    override fun loadRunCheckpoints(): List<RunCheckpoint> = checkpoints
    override fun saveRunCheckpoints(checkpoints: List<RunCheckpoint>) { this.checkpoints = checkpoints.distinct() }
    override fun loadRunCheckpoint(): RunCheckpoint? = checkpoint
    override fun saveRunCheckpoint(checkpoint: RunCheckpoint) { this.checkpoint = checkpoint }
    override fun clearRunCheckpoint() { checkpoint = null }
    override fun loadRunStatus(runId: String): String? = runStatuses[runId]
    override fun saveRunStatus(runId: String, status: String) { runStatuses[runId] = status }
    override fun clearRunStatus(runId: String) { runStatuses.remove(runId) }
    override fun loadAttentionItems(): List<AttentionItem> = attentionItems
    override fun saveAttentionItems(items: List<AttentionItem>) { attentionItems = items }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HermesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val hostA = HostProfile("h1", "Host A", "http://a.test", "key", allowInsecureHttp = true)
    private val hostB = HostProfile("h2", "Host B", "http://b.test", "key", allowInsecureHttp = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.buildViewModel(
        gateway: FakeGateway = FakeGateway(),
        savedState: SavedStateHandle = SavedStateHandle(),
        store: FakeHostStore = FakeHostStore(HostSnapshot(listOf(hostA, hostB), "h1")),
        settingsStore: SettingsStore = FakeSettingsStore(ThemeMode.System),
    ): Pair<HermesViewModel, FakeGateway> {
        val viewModel = HermesViewModel(gateway, store, savedState, settingsStore)
        advanceUntilIdle()
        return viewModel to gateway
    }

    @Test
    fun `theme mode loads from settings and saves changes`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.Light)
        val (viewModel, _) = buildViewModel(settingsStore = settings)

        assertEquals(ThemeMode.Light, viewModel.state.value.themeMode)

        viewModel.setThemeMode(ThemeMode.Dark)

        assertEquals(ThemeMode.Dark, viewModel.state.value.themeMode)
        assertEquals(ThemeMode.Dark, settings.savedMode)
    }

    @Test
    fun `keystore unlock failure surfaces a notice`() = runVmTest {
        val store = FakeHostStore(HostSnapshot(), unlockFailed = true)
        val (viewModel, _) = buildViewModel(store = store)

        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("unlocked"))
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(dispatcher) { block() }

    @Test
    fun `connect loads capabilities sessions skills and models`() = runVmTest {
        val (viewModel, _) = buildViewModel()

        val state = viewModel.state.value
        assertEquals(HostConnectionPhase.Connected, state.connectionPhase)
        assertTrue(state.capabilities!!.supportsRuns)
        assertEquals(listOf("s1"), state.sessions.map { it.id })
        assertEquals(listOf("grill-me"), state.skills.map { it.name })
        assertEquals(listOf("terminal"), state.toolsets.map { it.name })
        assertEquals(listOf("hermes-agent", "hermes-fast", "gpt-5.6-terra"), state.models)
    }

    @Test
    fun `starting a skill opens chat with a prepared skill prompt`() = runVmTest {
        val (viewModel, _) = buildViewModel()
        viewModel.selectScreen(DeckScreen.Host)

        viewModel.startSkill("grill-me")

        assertEquals(DeckScreen.Chat, viewModel.state.value.screen)
        assertEquals("Use the grill-me skill: ", viewModel.state.value.composerText)
    }

    @Test
    fun `refreshes host activity for session status and ordering`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions.clear()
            sessions += HermesSession("older", "Older", null, "api_server", null, "1700000000", 1)
            sessions += HermesSession("active", "Active", null, "api_server", null, "1600000000", 1)
            sessions += HermesSession("newer", "Newer", null, "api_server", null, "1800000000", 1)
            activeSessions = listOf(HermesActiveSession("active", "run-1", "Active", "waiting_for_approval", "desktop"))
        }
        val (viewModel, _) = buildViewModel(gateway = gateway)

        val state = viewModel.state.value
        assertEquals(listOf("active", "newer", "older"), state.orderedSessions.map { it.id })
        assertEquals("waiting_for_approval", state.activityFor(state.sessions.single { it.id == "active" })?.state)
    }

    @Test
    fun `send drives a run to completion without duplicating the user message`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.setComposerText("what next?")
        viewModel.sendMessage()
        advanceUntilIdle()

        // History is the server page, the new text rides only in input.
        val submit = gateway.submits.single()
        assertEquals("s1", submit.sessionId)
        assertEquals("what next?", submit.input)
        assertEquals(listOf("m1", "m2"), submit.history.map { it.id })
        assertNull(submit.model)

        // Optimistic tail is displayed while streaming.
        assertNotNull(viewModel.state.value.activeRun)
        gateway.events.send(HermesRunEvent.MessageDelta("Working"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.displayedMessages.any { it is ChatUiItem.Assistant && it.text == "Working" && it.streaming })

        // Terminal event reconciles from the host and clears the run.
        gateway.messages.getValue("s1").add(HermesMessage("m3", "user", "what next?"))
        gateway.messages.getValue("s1").add(HermesMessage("m4", "assistant", "Done"))
        gateway.events.send(HermesRunEvent.Completed("Done"))
        gateway.events.close()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.activeRun)
        assertEquals(listOf("m1", "m2", "m3", "m4"), state.messages.map { it.id })
    }

    @Test
    fun `selected model rides the run submission`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectModel("hermes-fast")
        viewModel.setComposerText("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("hermes-fast", gateway.submits.single().model)
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `opening a session clears its durable attention marker`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            attentionItems = listOf(AttentionItem("h1", "s1", "Task complete", "completed"))
        }
        val (viewModel, _) = buildViewModel(settingsStore = settings)

        viewModel.selectSession("s1")

        assertTrue(settings.attentionItems.isEmpty())
    }

    @Test
    fun `reasoning progress is grouped into one live activity item`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System)
        val (viewModel, gateway) = buildViewModel(settingsStore = settings)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("think through this")
        viewModel.sendMessage()
        advanceUntilIdle()

        gateway.events.send(HermesRunEvent.ReasoningAvailable("Checking the current state"))
        gateway.events.send(HermesRunEvent.ReasoningAvailable("Comparing the available options"))
        gateway.events.send(HermesRunEvent.ReasoningAvailable("Comparing the available options"))
        advanceUntilIdle()

        val reasoning = viewModel.state.value.displayedMessages.filterIsInstance<ChatUiItem.Reasoning>()
        assertEquals(1, reasoning.size)
        assertEquals(
            listOf("Checking the current state", "Comparing the available options"),
            reasoning.single().updates,
        )
        assertEquals("Comparing the available options", settings.runStatuses["run-1"])

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `selectModel rejects unknown models`() = runVmTest {
        val (viewModel, _) = buildViewModel()

        viewModel.selectModel("not-a-model")

        assertNull(viewModel.state.value.selectedModel)
    }

    @Test
    fun `selected reasoning effort rides the run submission and gates on capability`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectReasoningEffort("bogus")
        assertNull(viewModel.state.value.selectedReasoningEffort)

        viewModel.selectReasoningEffort("high")
        viewModel.setComposerText("think hard")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertEquals("high", gateway.submits.single().reasoningEffort)
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `reasoning effort is dropped when the host lacks the capability`() = runVmTest {
        val gateway = FakeGateway()
        gateway.capabilities = HermesCapabilities("m", "p", RUN_BUNDLE - "run_reasoning_effort")
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectReasoningEffort("high")
        viewModel.setComposerText("think hard")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(gateway.submits.single().reasoningEffort)
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `send is refused without the full run control bundle`() = runVmTest {
        val gateway = FakeGateway()
        gateway.capabilities = HermesCapabilities("m", "p", RUN_BUNDLE - "approval_events")
        val (viewModel, _) = buildViewModel(gateway)

        viewModel.setComposerText("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue(gateway.submits.isEmpty())
        assertTrue(viewModel.state.value.errorMessage!!.contains("run control"))
    }

    @Test
    fun `slash new creates a session and slash rename validates args`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()

        viewModel.setComposerText("/new")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertEquals(1, gateway.created.size)

        viewModel.setComposerText("/rename")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.errorMessage!!.contains("Usage"))

        viewModel.setComposerText("/rename Sharp Title")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertEquals(viewModel.state.value.activeSessionId!! to "Sharp Title", gateway.renames.single())
    }

    @Test
    fun `direct rename rejects an empty title and updates the active session`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.renameSession("s1", "   ")
        assertTrue(gateway.renames.isEmpty())
        assertTrue(viewModel.state.value.errorMessage!!.contains("cannot be empty"))

        viewModel.renameSession("s1", "Release follow-up")
        advanceUntilIdle()
        assertEquals("s1" to "Release follow-up", gateway.renames.single())
        assertEquals("Release follow-up", viewModel.state.value.activeSession?.title)
    }

    @Test
    fun `rename updates an active run session without interrupting it`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("keep working")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.renameSession("s1", "Release follow-up")
        advanceUntilIdle()

        assertEquals("s1" to "Release follow-up", gateway.renames.single())
        assertEquals("Release follow-up", viewModel.state.value.activeRun?.sessionTitle)
        assertEquals("run-1", viewModel.state.value.activeRun?.runId)

        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `approval card appears and responding posts the choice`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("do something dangerous")
        viewModel.sendMessage()
        advanceUntilIdle()

        gateway.events.send(HermesRunEvent.ApprovalRequested("rm -rf /tmp/x"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.displayedMessages.any { it is ChatUiItem.Approval && it.command == "rm -rf /tmp/x" })

        viewModel.respondApproval("once")
        advanceUntilIdle()
        assertEquals(listOf("once"), gateway.approvals)
        assertFalse(viewModel.state.value.displayedMessages.any { it is ChatUiItem.Approval })

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
        assertNull(viewModel.state.value.activeRun)
    }

    @Test
    fun `stop posts to the host and cancellation finalizes the run`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("long task")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.stopActiveRun()
        advanceUntilIdle()
        assertEquals(listOf("run-1"), gateway.stops)
        assertTrue(viewModel.state.value.activeRun!!.stopping)

        gateway.events.send(HermesRunEvent.Cancelled)
        gateway.events.close()
        advanceUntilIdle()
        assertNull(viewModel.state.value.activeRun)
    }

    @Test
    fun `run checkpoint is durable until the host reports terminal`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System)
        val (viewModel, gateway) = buildViewModel(settingsStore = settings)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.setComposerText("continue after the phone closes")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(RunCheckpoint("h1", "s1", "run-1"), settings.checkpoint)
        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
        assertNull(settings.checkpoint)
    }

    @Test
    fun `durable checkpoint reconciles after Android process recreation`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            checkpoint = RunCheckpoint("h1", "s1", "run-recovered")
        }
        val gateway = FakeGateway().apply {
            runStatus = HermesRunStatus("run-recovered", "completed")
        }

        val (viewModel, _) = buildViewModel(gateway = gateway, settingsStore = settings)

        assertEquals(listOf("run-recovered"), gateway.statusRequests)
        assertNull(viewModel.state.value.activeRun)
        assertNull(settings.checkpoint)
    }

    @Test
    fun `host switching allows an independent run while another host is active`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("busy work")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.selectHost("h2")
        advanceUntilIdle()
        assertEquals("h2", viewModel.state.value.activeHostId)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("independent work")
        assertEquals("independent work", viewModel.state.value.composerText)
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(listOf("run-1", "run-2"), viewModel.state.value.activeRuns.values.map { it.runId }.sorted())
        assertEquals("run-2", viewModel.state.value.activeRun?.runId)
        assertEquals("run-1", viewModel.state.value.otherActiveRuns.single().runId)

        gateway.eventsFor("run-1").send(HermesRunEvent.Completed("done"))
        gateway.eventsFor("run-2").send(HermesRunEvent.Completed("done"))
        gateway.eventsFor("run-1").close()
        gateway.eventsFor("run-2").close()
        advanceUntilIdle()
    }

    @Test
    fun `different sessions on the same host run independently`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions += HermesSession("s2", "Second", null, "api_server", null, null, 0)
            messages["s2"] = mutableListOf()
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first task")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.selectSession("s2")
        advanceUntilIdle()
        viewModel.setComposerText("second task")
        assertEquals("second task", viewModel.state.value.composerText)
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(setOf(SessionKey("h1", "s1"), SessionKey("h1", "s2")), viewModel.state.value.activeRuns.keys)
        assertEquals("run-2", viewModel.state.value.activeRun?.runId)
        assertEquals("run-1", viewModel.state.value.otherActiveRuns.single().runId)

        gateway.eventsFor("run-1").send(HermesRunEvent.Completed("first done"))
        gateway.eventsFor("run-1").close()
        advanceUntilIdle()

        assertEquals("s2", viewModel.state.value.activeSessionId)
        assertEquals("run-2", viewModel.state.value.activeRun?.runId)
        assertTrue(SessionKey("h1", "s1") !in viewModel.state.value.activeRuns)

        gateway.eventsFor("run-2").send(HermesRunEvent.Completed("second done"))
        gateway.eventsFor("run-2").close()
        advanceUntilIdle()
    }

    @Test
    fun `sending a follow-up interrupts then starts the next run in the same session`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first task")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.setComposerText("change direction")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(listOf("run-1"), gateway.stops)
        assertTrue(viewModel.state.value.activeRun!!.stopping)
        assertTrue(viewModel.state.value.composerText.isEmpty())

        gateway.eventsFor("run-1").send(HermesRunEvent.Cancelled)
        gateway.eventsFor("run-1").close()
        advanceUntilIdle()

        assertEquals(listOf("first task", "change direction"), gateway.submits.map { it.input })
        assertEquals("run-2", viewModel.state.value.activeRun?.runId)
        gateway.eventsFor("run-2").close()
        advanceUntilIdle()
    }

    @Test
    fun `queued follow-up stays with its session when navigation changes during interruption`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions += HermesSession("s2", "Second", null, "api_server", null, null, 0)
            messages["s2"] = mutableListOf()
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first task")
        viewModel.sendMessage()
        advanceUntilIdle()
        viewModel.setComposerText("change direction")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.selectSession("s2")
        advanceUntilIdle()
        gateway.eventsFor("run-1").send(HermesRunEvent.Cancelled)
        gateway.eventsFor("run-1").close()
        advanceUntilIdle()

        assertEquals(listOf("first task"), gateway.submits.map { it.input })
        viewModel.selectSession("s1")
        advanceUntilIdle()
        assertEquals("change direction", viewModel.state.value.composerText)
    }

    @Test
    fun `deleting another host preserves the active run`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("busy work")
        viewModel.sendMessage()
        advanceUntilIdle()
        val activeRunId = viewModel.state.value.activeRun?.runId

        viewModel.deleteHost("h2")

        assertEquals(activeRunId, viewModel.state.value.activeRun?.runId)
        assertEquals("h1", viewModel.state.value.activeHostId)
        assertFalse(viewModel.state.value.hosts.any { it.id == "h2" })

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `session actions are blocked for the running session`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("busy work")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.requestDeleteSession("s1")
        assertNull(viewModel.state.value.confirmDeleteSessionId)
        assertTrue(viewModel.state.value.errorMessage!!.contains("Stop it"))

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `deleting the current session returns to the deck`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.requestDeleteSession("s1")
        assertEquals("s1", viewModel.state.value.confirmDeleteSessionId)
        viewModel.confirmDeleteSession()
        advanceUntilIdle()

        assertEquals(listOf("s1"), gateway.deletes)
        val state = viewModel.state.value
        assertNull(state.activeSessionId)
        assertEquals(DeckScreen.Sessions, state.screen)
    }

    @Test
    fun `fork selects the child session`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.forkSession("s1")
        advanceUntilIdle()

        assertEquals(listOf("s1"), gateway.forks)
        assertEquals("fork-of-s1", viewModel.state.value.activeSessionId)
    }

    @Test
    fun `resolved session id is adopted for the displayed session`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        gateway.resolvedIds["s1"] = "s1-rotated"
        gateway.messages["s1-rotated"] = mutableListOf(HermesMessage("r1", "assistant", "rotated"))

        viewModel.selectSession("s1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("s1-rotated", state.activeSessionId)
        assertEquals(listOf("r1"), state.messages.map { it.id })
    }

    @Test
    fun `lost submission enters unknown outcome and only explicit ack releases it`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        gateway.submitError = IOException("socket closed")

        viewModel.setComposerText("did this land?")
        viewModel.sendMessage()
        advanceUntilIdle()

        val pending = viewModel.state.value.unknownOutcome
        assertNotNull(pending)
        // Watcher timed out (virtual time) without new messages; lock persists.
        assertTrue(viewModel.state.value.unknownOutcome!!.timedOut)

        // Sending is refused while locked.
        gateway.submitError = null
        viewModel.setComposerText("second try")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(gateway.submits.isEmpty())

        viewModel.acknowledgeUnknownOutcome()
        advanceUntilIdle()
        assertNull(viewModel.state.value.unknownOutcome)
    }

    @Test
    fun `slash suggestions combine reserved commands and skills`() = runVmTest {
        val (viewModel, _) = buildViewModel()

        viewModel.setComposerText("/")
        val all = viewModel.state.value.slashSuggestions()
        assertTrue(all.any { it.kind == SlashKind.Command && it.name == "new" })
        assertTrue(all.any { it.kind == SlashKind.Skill && it.name == "grill-me" })

        viewModel.setComposerText("/gri")
        val filtered = viewModel.state.value.slashSuggestions()
        assertEquals(listOf("grill-me"), filtered.map { it.name })
    }
}
