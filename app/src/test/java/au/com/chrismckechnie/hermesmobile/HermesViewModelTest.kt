package au.com.chrismckechnie.hermesmobile

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    "skills_api", "session_resources", "session_fork", "run_reasoning_effort", "run_permission_mode", "host_update_api",
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
    val activeSessionsByHost = mutableMapOf<String, List<HermesActiveSession>>()
    var hostVersion: String? = "2026.7.15"
    var hostUpdate: HermesHostUpdate? = HermesHostUpdate(
        currentVersion = "2026.7.15",
        updateAvailable = true,
        canApply = true,
        message = "Update available",
    )
    var hostUpdateStarts = 0
    private val eventStreams = mutableMapOf<String, Channel<HermesRunEvent>>()
    val events: Channel<HermesRunEvent> get() = eventsFor("run-1")
    var runStatus = HermesRunStatus("run-1", "completed")
    var submitError: Throwable? = null
    var createError: Throwable? = null
    val reservedTitles = mutableSetOf<String>()
    var approvalError: Throwable? = null
    var approvalGate: Channel<Unit>? = null
    var approvalAttempts = 0
    val loadMessageErrors = mutableMapOf<String, Throwable>()
    val listSessionGates = mutableMapOf<String, Channel<Unit>>()
    val submits = mutableListOf<SubmitCall>()
    val approvals = mutableListOf<String>()
    val stops = mutableListOf<String>()
    val statusRequests = mutableListOf<String>()
    val renames = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<String>()
    val forks = mutableListOf<String>()
    val created = mutableListOf<HermesSession>()

    data class SubmitCall(
        val sessionId: String,
        val input: String,
        val history: List<HermesMessage>,
        val model: String?,
        val reasoningEffort: String?,
        val permissionMode: String?,
    )

    fun eventsFor(runId: String): Channel<HermesRunEvent> =
        eventStreams.getOrPut(runId) { Channel(Channel.UNLIMITED) }

    override suspend fun probe(host: HostProfile) = capabilities
    override suspend fun getHostVersion(host: HostProfile) = hostVersion
    override suspend fun getHostUpdate(host: HostProfile, force: Boolean) = hostUpdate
    override suspend fun updateHost(host: HostProfile): HermesHostUpdateStart {
        hostUpdateStarts += 1
        return HermesHostUpdateStart(accepted = true, message = "Update started")
    }
    override suspend fun listSessions(host: HostProfile, limit: Int, offset: Int) = HermesSessionPage(
        sessions = sessions.also { listSessionGates[host.id]?.receive() }.drop(offset).take(limit),
        hasMore = offset + limit < sessions.size,
    )
    override suspend fun createSession(host: HostProfile, title: String?): HermesSession {
        createError?.let { throw it }
        if (title != null && (title in reservedTitles || sessions.any { it.title == title })) {
            throw HermesApiException(400, "Title '$title' is already in use")
        }
        val session = HermesSession("new-${created.size}", title, null, "api_server", null, null, 0)
        created += session
        sessions.add(0, session)
        messages[session.id] = mutableListOf()
        return session
    }

    override suspend fun loadMessages(host: HostProfile, sessionId: String): HermesMessagesPage {
        loadMessageErrors[sessionId]?.let { throw it }
        val resolved = resolvedIds[sessionId] ?: sessionId
        return HermesMessagesPage(resolved, messages[resolved]?.toList() ?: emptyList())
    }

    override suspend fun listJobs(host: HostProfile) = emptyList<HermesJob>()
    override suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean) = Unit
    override suspend fun runJob(host: HostProfile, jobId: String) = Unit
    override suspend fun listSkills(host: HostProfile) = skills
    override suspend fun listToolsets(host: HostProfile) = toolsets
    override suspend fun listModels(host: HostProfile) = models
    override suspend fun listActiveSessions(host: HostProfile) = activeSessionsByHost[host.id] ?: activeSessions

    override suspend fun submitRun(
        host: HostProfile,
        sessionId: String,
        input: String,
        history: List<HermesMessage>,
        model: String?,
        reasoningEffort: String?,
        permissionMode: String?,
    ): String {
        submitError?.let { throw it }
        submits += SubmitCall(sessionId, input, history, model, reasoningEffort, permissionMode)
        return "run-${submits.size}"
    }

    override suspend fun streamRunEvents(host: HostProfile, runId: String, onEvent: (HermesRunEvent) -> Unit) {
        for (event in eventsFor(runId)) onEvent(event)
    }

    override suspend fun getRunStatus(host: HostProfile, runId: String): HermesRunStatus {
        statusRequests += runId
        return runStatus
    }
    override suspend fun respondApproval(host: HostProfile, runId: String, choice: String) {
        approvalAttempts += 1
        approvalGate?.receive()
        approvalError?.let { throw it }
        approvals += choice
    }
    override suspend fun stopRun(host: HostProfile, runId: String) { stops += runId }

    override suspend fun renameSession(host: HostProfile, sessionId: String, title: String): HermesSession {
        if (title in reservedTitles || sessions.any { it.id != sessionId && it.title == title }) {
            throw HermesApiException(400, "Title '$title' is already in use")
        }
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
    var unknownOutcomeRecords = emptyList<UnknownOutcomeRecord>()
    var queuedInterruptRecords = emptyList<QueuedInterruptRecord>()

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
    override fun loadUnknownOutcomeRecords(): List<UnknownOutcomeRecord> = unknownOutcomeRecords
    override fun saveUnknownOutcomeRecords(records: List<UnknownOutcomeRecord>) {
        unknownOutcomeRecords = records
    }
    override fun loadQueuedInterruptRecords(): List<QueuedInterruptRecord> = queuedInterruptRecords
    override fun saveQueuedInterruptRecords(records: List<QueuedInterruptRecord>) {
        queuedInterruptRecords = records
    }
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
        assertEquals("2026.7.15", state.capabilities?.version)
        assertTrue(state.hostUpdate?.updateAvailable == true)
    }

    @Test
    fun `host update is checked and cannot start while work is active`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()

        viewModel.checkHostUpdate()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.hostUpdate?.updateAvailable == true)

        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("keep working")
        viewModel.sendMessage()
        advanceUntilIdle()
        viewModel.updateHost()

        assertEquals(0, gateway.hostUpdateStarts)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("Stop active work"))
    }

    @Test
    fun `starting a skill opens chat with a prepared skill prompt`() = runVmTest {
        val (viewModel, _) = buildViewModel()
        viewModel.selectScreen(DeckScreen.Host)
        val sessionIdsBefore = viewModel.state.value.sessions.map(HermesSession::id)
        val activeSessionBefore = viewModel.state.value.activeSessionId

        viewModel.startSkill("grill-me")

        assertEquals(DeckScreen.Chat, viewModel.state.value.screen)
        assertEquals("Use the grill-me skill: ", viewModel.state.value.composerText)
        assertEquals(sessionIdsBefore, viewModel.state.value.sessions.map(HermesSession::id))
        assertEquals(activeSessionBefore, viewModel.state.value.activeSessionId)
    }

    @Test
    fun `reloading a transcript drops empty persisted assistant rows`() = runVmTest {
        val gateway = FakeGateway().apply {
            messages["s1"] = mutableListOf(
                HermesMessage("u1", "user", "Run checks"),
                HermesMessage("t1", "tool", "done", "terminal"),
                HermesMessage("blank", "assistant", ""),
                HermesMessage("a1", "assistant", "Checks passed"),
            )
        }
        val (viewModel, _) = buildViewModel(gateway = gateway)

        viewModel.selectSession("s1")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.messages.any { it is ChatUiItem.Assistant && it.text.isBlank() })
        assertEquals(3, viewModel.state.value.messages.size)
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
    fun `desktop-reported active sessions remain visible without mobile run registry`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions.clear()
            sessions += HermesSession("recent", "Recent", null, "desktop", null, "1800000000", 1)
            sessions += HermesSession("desktop-running", "Desktop work", null, "desktop", null, "1700000000", 1, isActive = true)
        }
        val (viewModel, _) = buildViewModel(gateway = gateway)

        val state = viewModel.state.value
        val desktopSession = state.sessions.single { it.id == "desktop-running" }
        assertEquals(listOf("desktop-running", "recent"), state.orderedSessions.map { it.id })
        assertEquals("working", state.activityFor(desktopSession)?.state)
        assertTrue(state.isSessionBusy("h1", "desktop-running"))
    }

    @Test
    fun `new chats receive a request-derived title`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()

        viewModel.setComposerText("  Investigate\n the active desktop session list  ")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("Investigate the active desktop session list", gateway.created.single().title)
        assertEquals("Investigate the active desktop session list", viewModel.state.value.activeSession?.title)

        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `empty default sessions are titled from their first request`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.createSession()
        advanceUntilIdle()

        assertTrue(gateway.created.isEmpty())
        assertNull(viewModel.state.value.activeSessionId)

        viewModel.setComposerText("Make the session list show desktop work")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("Make the session list show desktop work", gateway.created.single().title)
        assertTrue(gateway.renames.isEmpty())
        assertEquals("Make the session list show desktop work", viewModel.state.value.activeSession?.title)

        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `run work updates retain tasks and merge subagent context`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("Coordinate the release")
        viewModel.sendMessage()
        advanceUntilIdle()

        gateway.events.send(
            HermesRunEvent.TasksUpdated(
                listOf(
                    HermesTask("plan", "Plan the release", "completed"),
                    HermesTask("ship", "Ship the release", "in_progress"),
                ),
            ),
        )
        gateway.events.send(
            HermesRunEvent.SubagentUpdated(
                HermesSubagent("subagent-1", "running", 0, 2, goal = "Inspect the API"),
            ),
        )
        gateway.events.send(
            HermesRunEvent.SubagentUpdated(
                HermesSubagent("subagent-1", "working", 0, 2, toolCount = 2, activity = "Reading run events"),
            ),
        )
        advanceUntilIdle()

        val run = viewModel.state.value.activeRun
        assertEquals("1 / 2 tasks", taskProgressLabel(run?.tasks.orEmpty()))
        assertEquals("Inspect the API", run?.subagents?.get("subagent-1")?.goal)
        assertEquals("Reading run events", run?.subagents?.get("subagent-1")?.activity)
        assertEquals(2, run?.subagents?.get("subagent-1")?.toolCount)

        gateway.events.send(HermesRunEvent.Cancelled)
        gateway.events.close()
        advanceUntilIdle()
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
        gateway.events.send(
            HermesRunEvent.Completed(
                output = "Done",
                usage = HermesRunUsage(inputTokens = 120, outputTokens = 80, totalTokens = 200),
            ),
        )
        gateway.events.close()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.activeRun)
        assertEquals(listOf("m1", "m2", "m3", "m4"), state.messages.map { it.id })
        assertEquals(
            HermesRunUsage(inputTokens = 120, outputTokens = 80, totalTokens = 200),
            (state.displayedMessages.last() as ChatUiItem.Assistant).usage,
        )
    }

    @Test
    fun `completed usage remains with its session while another session is open`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions += HermesSession("s2", "Second", null, "api_server", null, null, 0)
            messages["s2"] = mutableListOf()
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("show usage")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.selectSession("s2")
        advanceUntilIdle()
        gateway.messages.getValue("s1") += HermesMessage("m3", "user", "show usage")
        gateway.messages.getValue("s1") += HermesMessage("m4", "assistant", "Done")
        gateway.events.send(
            HermesRunEvent.Completed(
                output = "Done",
                usage = HermesRunUsage(totalTokens = 321),
            ),
        )
        gateway.events.close()
        advanceUntilIdle()

        assertEquals("s2", viewModel.state.value.activeSessionId)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        assertEquals(
            HermesRunUsage(totalTokens = 321),
            (viewModel.state.value.displayedMessages.last() as ChatUiItem.Assistant).usage,
        )
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
    fun `run settings selected before a session transfer to the created session`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()

        assertNull(viewModel.state.value.activeSessionId)
        viewModel.selectModel("gpt-5.6-terra")
        viewModel.selectReasoningEffort("high")
        viewModel.selectPermissionMode("full-access")
        assertEquals("gpt-5.6-terra", viewModel.state.value.selectedModel)
        assertEquals("high", viewModel.state.value.selectedReasoningEffort)
        assertEquals("full-access", viewModel.state.value.selectedPermissionMode)

        viewModel.setComposerText("start with these settings")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("new-0", viewModel.state.value.activeSessionId)
        assertEquals("gpt-5.6-terra", gateway.submits.single().model)
        assertEquals("high", gateway.submits.single().reasoningEffort)
        assertEquals("full-access", gateway.submits.single().permissionMode)
        assertEquals("gpt-5.6-terra", viewModel.state.value.selectedModel)
        assertEquals("high", viewModel.state.value.selectedReasoningEffort)
        assertEquals("full-access", viewModel.state.value.selectedPermissionMode)
        assertTrue(viewModel.state.value.newSessionModelSelections.isEmpty())
        assertTrue(viewModel.state.value.newSessionReasoningSelections.isEmpty())
        assertTrue(viewModel.state.value.newSessionPermissionSelections.isEmpty())

        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `pre-submit failures restore the draft`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        gateway.loadMessageErrors["s1"] = IOException("history unavailable")

        viewModel.setComposerText("do not lose this")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("do not lose this", viewModel.state.value.composerText)
        assertTrue(gateway.submits.isEmpty())

        gateway.loadMessageErrors.clear()
        gateway.submitError = HermesApiException(400, "invalid request")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("do not lose this", viewModel.state.value.composerText)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("invalid request"))
    }

    @Test
    fun `failed initial session creation keeps draft settings recoverable`() = runVmTest {
        val gateway = FakeGateway().apply { createError = IOException("create unavailable") }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectModel("gpt-5.6-terra")
        viewModel.selectReasoningEffort("xhigh")
        viewModel.setComposerText("create this when the host returns")

        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeSessionId)
        assertEquals("create this when the host returns", viewModel.state.value.composerText)
        assertEquals("gpt-5.6-terra", viewModel.state.value.selectedModel)
        assertEquals("xhigh", viewModel.state.value.selectedReasoningEffort)
        assertTrue(gateway.submits.isEmpty())
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
    fun `cross-host notification preserves a session outside the first page`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions.clear()
            messages.clear()
            repeat(60) { index ->
                val number = index + 1
                val id = "s$number"
                sessions += HermesSession(id, "Session $number", null, "api_server", null, number.toString(), 1)
                messages[id] = mutableListOf(HermesMessage("m$number", "assistant", "Message $number"))
            }
        }
        val (viewModel, _) = buildViewModel(gateway = gateway)
        assertFalse(viewModel.state.value.sessions.any { it.id == "s60" })

        viewModel.openSessionFromNotification("h2", "s60")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("h2", state.activeHostId)
        assertEquals("s60", state.activeSessionId)
        assertEquals("Session 60", state.activeSession?.title)
        assertEquals(listOf("m60"), state.messages.map(ChatUiItem::id))
        assertNull(state.pendingSessionTarget)
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
    fun `active session refresh clears work that the host no longer reports`() = runVmTest {
        val gateway = FakeGateway().apply {
            activeSessions = listOf(HermesActiveSession("s1", "run-remote", "First", "running", "desktop"))
        }
        val (viewModel, _) = buildViewModel(gateway = gateway)
        assertTrue(viewModel.state.value.activeSessionIds.contains("s1"))

        gateway.activeSessions = emptyList()
        viewModel.refreshHostActivity()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.activeSessionIds.contains("s1"))
    }

    @Test
    fun `run reconciliation finishes work when a terminal event was missed`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("finish even if the stream stalls")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.activeRun)

        gateway.runStatus = HermesRunStatus("run-1", "completed")
        viewModel.reconcileActiveRuns()
        advanceUntilIdle()

        assertNull(viewModel.state.value.activeRun)
    }

    @Test
    fun `repeated first prompts receive unique titles and still start`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions.add(HermesSession("previous", "Repeat this task", null, "api_server", null, null, 2))
        }
        val (viewModel, _) = buildViewModel(gateway)

        viewModel.setComposerText("Repeat this task")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("Repeat this task #2", gateway.created.single().title)
        assertEquals("Repeat this task #2", viewModel.state.value.activeSession?.title)
        assertEquals(1, gateway.submits.size)

        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `unloaded title collisions fall back to an untitled session and still start`() = runVmTest {
        val gateway = FakeGateway().apply { reservedTitles += "Hidden duplicate" }
        val (viewModel, _) = buildViewModel(gateway)

        viewModel.setComposerText("Hidden duplicate")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(gateway.created.single().title)
        assertEquals(1, gateway.submits.size)

        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `selected permission mode rides the run submission`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectPermissionMode("unsafe")
        assertNull(viewModel.state.value.selectedPermissionMode)

        viewModel.selectPermissionMode("full-access")
        viewModel.setComposerText("handle this")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("full-access", gateway.submits.single().permissionMode)
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `permission mode is dropped when the host lacks the capability`() = runVmTest {
        val gateway = FakeGateway()
        gateway.capabilities = HermesCapabilities("m", "p", RUN_BUNDLE - "run_permission_mode")
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectPermissionMode("full-access")
        viewModel.setComposerText("handle this")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(gateway.submits.single().permissionMode)
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
    fun `slash new opens a local draft and slash rename validates args`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()

        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.setComposerText("/new")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(gateway.created.isEmpty())
        assertNull(viewModel.state.value.activeSessionId)

        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.setComposerText("/rename")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.errorMessage!!.contains("Usage"))

        viewModel.setComposerText("/rename Sharp Title")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertEquals("s1" to "Sharp Title", gateway.renames.single())
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
    fun `failed approval response keeps the card until success or conflict`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("requires approval")
        viewModel.sendMessage()
        advanceUntilIdle()
        gateway.events.send(HermesRunEvent.ApprovalRequested("sensitive command"))
        advanceUntilIdle()
        val ref = viewModel.state.value.activeRun!!.ref

        gateway.approvalError = IOException("offline")
        viewModel.respondApproval(ref, "once")
        advanceUntilIdle()
        val failedCard = viewModel.state.value.displayedMessages.filterIsInstance<ChatUiItem.Approval>().single()
        assertEquals(ref, failedCard.runRef)
        assertFalse(failedCard.submitting)

        gateway.approvalError = HermesApiException(409, "already resolved")
        viewModel.respondApproval(ref, "once")
        advanceUntilIdle()
        assertFalse(viewModel.state.value.displayedMessages.any { it is ChatUiItem.Approval })

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `live assistant status starts immediately and only uses safe tool metadata`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("work in the background")
        viewModel.sendMessage()
        advanceUntilIdle()

        var assistant = viewModel.state.value.activeRun!!.tail.filterIsInstance<ChatUiItem.Assistant>().single()
        assertTrue(assistant.streaming)
        assertTrue(assistant.text.isBlank())
        assertEquals("Starting task…", assistant.safeStatus)

        gateway.events.send(HermesRunEvent.ToolStarted("terminal", "secret command payload"))
        advanceUntilIdle()
        assistant = viewModel.state.value.activeRun!!.tail.filterIsInstance<ChatUiItem.Assistant>().single()
        assertEquals("Using terminal…", assistant.safeStatus)
        assertFalse(assistant.safeStatus.orEmpty().contains("secret command payload"))
        assertEquals(listOf("Starting task…", "Using terminal…"), assistant.safeStatusHistory)
        assertEquals(
            "Host-provided progress",
            safeRunStatusText(HermesRunEvent.ReasoningAvailable("Host-provided progress")),
        )

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `safe status history is compact bounded and ignores duplicate updates`() {
        var history = emptyList<String>()
        repeat(12) { index -> history = appendSafeStatus(history, " Step   $index ") }
        history = appendSafeStatus(history, "Step 11")

        assertEquals(10, history.size)
        assertEquals("Step 2", history.first())
        assertEquals("Step 11", history.last())
    }

    @Test
    fun `returning to a running session reconciles persisted prompt and tools`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions += HermesSession("s2", "Second", null, "api_server", null, null, 0)
            messages["s2"] = mutableListOf()
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("work while backgrounded")
        viewModel.sendMessage()
        advanceUntilIdle()

        gateway.events.send(HermesRunEvent.ToolStarted("terminal", "first command"))
        gateway.events.send(HermesRunEvent.ToolCompleted("terminal", failed = false))
        advanceUntilIdle()
        gateway.messages.getValue("s1") += listOf(
            HermesMessage("m3", "user", "work while backgrounded"),
            HermesMessage("m4", "tool", "first command completed", "terminal"),
        )

        viewModel.selectSession("s2")
        advanceUntilIdle()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        var displayed = viewModel.state.value.displayedMessages
        assertEquals(1, displayed.filterIsInstance<ChatUiItem.User>().count { it.text == "work while backgrounded" })
        assertEquals(1, displayed.filterIsInstance<ChatUiItem.Tool>().count { it.name == "terminal" })

        gateway.events.send(HermesRunEvent.ToolStarted("browser", "next step"))
        advanceUntilIdle()
        displayed = viewModel.state.value.displayedMessages
        val currentToolGroup = groupChatTimeline(displayed)
            .filterIsInstance<ChatTimelineItem.ToolGroup>()
            .last()
        assertEquals(listOf("terminal", "browser"), currentToolGroup.tools.map { it.name })

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `an identical historical prompt does not hide the active prompt`() = runVmTest {
        val gateway = FakeGateway().apply {
            messages["s1"] = mutableListOf(
                HermesMessage("m1", "user", "repeat within this session"),
                HermesMessage("m2", "assistant", "previous answer"),
            )
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("repeat within this session")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(
            2,
            viewModel.state.value.displayedMessages
                .filterIsInstance<ChatUiItem.User>()
                .count { it.text == "repeat within this session" },
        )

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `approval submission is targeted and single flight`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("requires approval")
        viewModel.sendMessage()
        advanceUntilIdle()
        gateway.events.send(HermesRunEvent.ApprovalRequested("sensitive command"))
        advanceUntilIdle()
        val ref = viewModel.state.value.activeRun!!.ref
        val gate = Channel<Unit>(Channel.RENDEZVOUS)
        gateway.approvalGate = gate

        viewModel.respondApproval(ref, "once")
        runCurrent()
        assertTrue(viewModel.state.value.displayedMessages.filterIsInstance<ChatUiItem.Approval>().single().submitting)

        viewModel.respondApproval(ref, "deny")
        runCurrent()
        assertEquals(1, gateway.approvalAttempts)

        gate.send(Unit)
        advanceUntilIdle()
        assertEquals(listOf("once"), gateway.approvals)
        assertFalse(viewModel.state.value.displayedMessages.any { it is ChatUiItem.Approval })

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()
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
    fun `targeted stop and return use immutable run coordinates`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions += HermesSession("s2", "Second", null, "api_server", null, null, 0)
            messages["s2"] = mutableListOf()
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first")
        viewModel.sendMessage()
        advanceUntilIdle()
        viewModel.selectSession("s2")
        advanceUntilIdle()
        viewModel.setComposerText("second")
        viewModel.sendMessage()
        advanceUntilIdle()

        val first = RunRef("h1", "s1", "run-1")
        viewModel.stopRun(first)
        advanceUntilIdle()

        assertEquals(listOf("run-1"), gateway.stops)
        assertTrue(viewModel.state.value.activeRuns.getValue(SessionKey("h1", "s1")).stopping)
        assertFalse(viewModel.state.value.activeRuns.getValue(SessionKey("h1", "s2")).stopping)

        viewModel.returnToRunSession(first)
        advanceUntilIdle()
        assertEquals("h1", viewModel.state.value.activeHostId)
        assertEquals("s1", viewModel.state.value.activeSessionId)

        gateway.eventsFor("run-1").send(HermesRunEvent.Cancelled)
        gateway.eventsFor("run-2").send(HermesRunEvent.Completed("done"))
        gateway.eventsFor("run-1").close()
        gateway.eventsFor("run-2").close()
        advanceUntilIdle()
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
    fun `terminal run coordinates remain durable until transcript reconciliation succeeds`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System)
        val (viewModel, gateway) = buildViewModel(settingsStore = settings)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("finish durably")
        viewModel.sendMessage()
        advanceUntilIdle()
        gateway.loadMessageErrors["s1"] = IOException("transcript unavailable")

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()

        val run = viewModel.state.value.activeRuns.getValue(SessionKey("h1", "s1"))
        assertTrue(run.terminalUnsynced)
        assertEquals(RunCheckpoint("h1", "s1", "run-1"), settings.checkpoint)

        viewModel.setComposerText("follow up after sync")
        viewModel.sendMessage()
        assertEquals("follow up after sync", viewModel.state.value.composerText)
        assertTrue(gateway.stops.isEmpty())

        gateway.loadMessageErrors.clear()
        viewModel.retryRunReconciliation(run.ref)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.activeRuns.isEmpty())
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
    fun `recovered active run immediately shows persisted safe working bubble`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            checkpoint = RunCheckpoint("h1", "s1", "run-recovered")
            runStatuses["run-recovered"] = "Using browser…"
        }
        val gateway = FakeGateway().apply {
            runStatus = HermesRunStatus("run-recovered", "running")
        }

        val viewModel = HermesViewModel(
            gateway,
            FakeHostStore(HostSnapshot(listOf(hostA, hostB), "h1")),
            SavedStateHandle(),
            settings,
        )

        val run = requireNotNull(viewModel.state.value.activeRuns[SessionKey("h1", "s1")])
        val assistant = requireNotNull(run.tail.singleOrNull() as? ChatUiItem.Assistant)
        assertTrue(assistant.streaming)
        assertEquals("", assistant.text)
        assertEquals("Using browser…", assistant.safeStatus)

        // Let the recovered polling job terminate so runTest does not keep
        // advancing its backoff forever after this UI-state assertion.
        gateway.runStatus = HermesRunStatus("run-recovered", "completed")
        advanceUntilIdle()
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
    fun `empty session with only heuristic activity can be deleted`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions.clear()
            sessions += HermesSession("empty", null, null, "api_server", null, null, 0, isActive = true)
        }
        val (viewModel, _) = buildViewModel(gateway)

        assertTrue(viewModel.state.value.isSessionBusy("h1", "empty"))
        assertFalse(viewModel.state.value.isSessionDeleteBlocked("h1", "empty"))
        viewModel.requestDeleteSession("empty")
        assertEquals("empty", viewModel.state.value.confirmDeleteSessionId)
        viewModel.confirmDeleteSession()
        advanceUntilIdle()

        assertEquals(listOf("empty"), gateway.deletes)
    }

    @Test
    fun `remote active sessions block destructive session and host actions`() = runVmTest {
        val gateway = FakeGateway().apply {
            activeSessionsByHost["h1"] = listOf(HermesActiveSession("s1", "remote-run", "First", "working", "desktop"))
            activeSessionsByHost["h2"] = emptyList()
        }
        val store = FakeHostStore(HostSnapshot(listOf(hostA, hostB), "h1"))
        val (viewModel, _) = buildViewModel(gateway = gateway, store = store)

        assertTrue(viewModel.state.value.isSessionBusy("h1", "s1"))
        assertTrue(viewModel.state.value.hasActiveRunOnHost("h1"))
        viewModel.requestDeleteSession("s1")
        assertNull(viewModel.state.value.confirmDeleteSessionId)

        viewModel.selectHost("h2")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.hasActiveRunOnHost("h1"))
        viewModel.deleteHost("h1")
        assertTrue(store.snapshot.hosts.any { it.id == "h1" })
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("active"))

        viewModel.saveHost(
            existingId = "h1",
            name = "Edited host",
            baseUrl = hostA.baseUrl,
            apiKey = "",
            allowInsecureHttp = true,
        )
        assertEquals("Host A", viewModel.state.value.hosts.first { it.id == "h1" }.name)

        viewModel.selectHost("h1")
        advanceUntilIdle()
        viewModel.updateHost()
        assertEquals(0, gateway.hostUpdateStarts)
    }

    @Test
    fun `switching hosts resets an in-flight refresh without stale completion`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        val refreshGate = Channel<Unit>(Channel.RENDEZVOUS)
        gateway.listSessionGates["h1"] = refreshGate

        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.state.value.isRefreshing)

        viewModel.selectHost("h2")
        runCurrent()
        assertEquals("h2", viewModel.state.value.activeHostId)
        assertFalse(viewModel.state.value.isRefreshing)

        refreshGate.send(Unit)
        advanceUntilIdle()
        assertEquals("h2", viewModel.state.value.activeHostId)
        assertFalse(viewModel.state.value.isRefreshing)
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
        val settings = FakeSettingsStore(ThemeMode.System)
        val (viewModel, gateway) = buildViewModel(settingsStore = settings)
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
        assertEquals("did this land?", settings.unknownOutcomeRecords.single().text)
        assertTrue(settings.unknownOutcomeRecords.single().timedOut)

        // Sending is refused while locked.
        gateway.submitError = null
        viewModel.setComposerText("second try")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertTrue(gateway.submits.isEmpty())

        viewModel.acknowledgeUnknownOutcome()
        advanceUntilIdle()
        assertNull(viewModel.state.value.unknownOutcome)
        assertEquals("did this land?", viewModel.state.value.composerText)
        assertTrue(settings.unknownOutcomeRecords.isEmpty())
    }

    @Test
    fun `unknown submit record recovers after process death without resubmission`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            unknownOutcomeRecords = listOf(
                UnknownOutcomeRecord("h1", "s1", baselineCount = 2, text = "recover this prompt", timedOut = true),
            )
        }
        val (viewModel, gateway) = buildViewModel(settingsStore = settings)

        viewModel.selectSession("s1")
        advanceUntilIdle()

        assertEquals("recover this prompt", viewModel.state.value.unknownOutcome?.text)
        assertTrue(gateway.submits.isEmpty())
        viewModel.acknowledgeUnknownOutcome()
        advanceUntilIdle()
        assertEquals("recover this prompt", viewModel.state.value.composerText)
        assertTrue(settings.unknownOutcomeRecords.isEmpty())
        assertTrue(gateway.submits.isEmpty())
    }

    @Test
    fun `queued interrupt recovers as an acknowledged draft without auto resubmission`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            checkpoint = RunCheckpoint("h1", "s1", "run-recovered")
            queuedInterruptRecords = listOf(
                QueuedInterruptRecord("h1", "s1", "run-recovered", "change direction safely"),
            )
        }
        val gateway = FakeGateway().apply {
            runStatus = HermesRunStatus("run-recovered", "completed")
        }
        val (viewModel, _) = buildViewModel(gateway = gateway, settingsStore = settings)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        assertTrue(gateway.submits.isEmpty())
        assertTrue(viewModel.state.value.queuedInterrupt?.requiresAcknowledgement == true)
        assertEquals("change direction safely", viewModel.state.value.queuedInterrupt?.text)
        assertEquals("change direction safely", settings.queuedInterruptRecords.single().text)

        viewModel.acknowledgeQueuedInterrupt(useDraft = true)

        assertNull(viewModel.state.value.queuedInterrupt)
        assertEquals("change direction safely", viewModel.state.value.composerText)
        assertTrue(settings.queuedInterruptRecords.isEmpty())
        assertTrue(gateway.submits.isEmpty())
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
