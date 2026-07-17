package au.com.chrismckechnie.hermesmobile

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
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
    "run_slash_commands",
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
    var jobs = emptyList<HermesJob>()
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
    val submitErrors = mutableListOf<Throwable>()
    var createError: Throwable? = null
    val reservedTitles = mutableSetOf<String>()
    var approvalError: Throwable? = null
    var approvalGate: Channel<Unit>? = null
    var approvalAttempts = 0
    val loadMessageErrors = mutableMapOf<String, Throwable>()
    val loadMessageFailures = mutableMapOf<String, MutableList<Throwable>>()
    val loadMessageGates = mutableMapOf<String, Channel<Unit>>()
    val listSessionGates = mutableMapOf<String, Channel<Unit>>()
    val listSessionErrorsByHost = mutableMapOf<String, Throwable>()
    val listSessionErrorsByOffset = mutableMapOf<Int, Throwable>()
    var jobsError: Throwable? = null
    var skillsError: Throwable? = null
    var toolsetsError: Throwable? = null
    var modelsError: Throwable? = null
    var jobsCalls = 0
    var skillsCalls = 0
    var toolsetsCalls = 0
    var modelsCalls = 0
    val submits = mutableListOf<SubmitCall>()
    val submitAttempts = mutableListOf<SubmitCall>()
    val approvals = mutableListOf<String>()
    val stops = mutableListOf<String>()
    val statusRequests = mutableListOf<String>()
    val streamErrors = mutableListOf<Throwable>()
    val renames = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<String>()
    val forks = mutableListOf<String>()
    val created = mutableListOf<HermesSession>()
    val streamCursors = mutableListOf<Long?>()
    val clearedLeases = mutableListOf<String>()
    val submitIdempotencyKeys = mutableListOf<String?>()

    data class SubmitCall(
        val sessionId: String,
        val input: String,
        val history: List<HermesMessage>,
        val model: String?,
        val reasoningEffort: String?,
        val permissionMode: String?,
        val idempotencyKey: String?,
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
    override suspend fun listSessions(host: HostProfile, limit: Int, offset: Int): HermesSessionPage {
        listSessionGates[host.id]?.receive()
        listSessionErrorsByHost[host.id]?.let { throw it }
        listSessionErrorsByOffset[offset]?.let { throw it }
        return HermesSessionPage(
            sessions = sessions.drop(offset).take(limit),
            hasMore = offset + limit < sessions.size,
        )
    }
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
        loadMessageGates[sessionId]?.receive()
        loadMessageFailures[sessionId]?.removeFirstOrNull()?.let { throw it }
        loadMessageErrors[sessionId]?.let { throw it }
        val resolved = resolvedIds[sessionId] ?: sessionId
        return HermesMessagesPage(resolved, messages[resolved]?.toList() ?: emptyList())
    }

    override suspend fun listJobs(host: HostProfile): List<HermesJob> {
        jobsCalls += 1
        jobsError?.let { throw it }
        return jobs
    }
    override suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean) = Unit
    override suspend fun runJob(host: HostProfile, jobId: String) = Unit
    override suspend fun listSkills(host: HostProfile): List<HermesSkill> {
        skillsCalls += 1
        skillsError?.let { throw it }
        return skills
    }
    override suspend fun listToolsets(host: HostProfile): List<HermesToolset> {
        toolsetsCalls += 1
        toolsetsError?.let { throw it }
        return toolsets
    }
    override suspend fun listModels(host: HostProfile): List<String> {
        modelsCalls += 1
        modelsError?.let { throw it }
        return models
    }
    override suspend fun listActiveSessions(host: HostProfile) = activeSessionsByHost[host.id] ?: activeSessions
    override suspend fun clearStaleActiveSession(host: HostProfile, leaseId: String) {
        clearedLeases += leaseId
        activeSessions = activeSessions.filterNot { it.leaseId == leaseId }
        activeSessionsByHost[host.id] = activeSessionsByHost[host.id].orEmpty()
            .filterNot { it.leaseId == leaseId }
    }

    override suspend fun submitRun(
        host: HostProfile,
        sessionId: String,
        input: String,
        history: List<HermesMessage>,
        model: String?,
        reasoningEffort: String?,
        permissionMode: String?,
        idempotencyKey: String?,
    ): String {
        submitIdempotencyKeys += idempotencyKey
        val call = SubmitCall(sessionId, input, history, model, reasoningEffort, permissionMode, idempotencyKey)
        submitAttempts += call
        if (submitErrors.isNotEmpty()) throw submitErrors.removeAt(0)
        submitError?.let { throw it }
        submits += call
        return "run-${submits.size}"
    }

    override suspend fun streamRunEvents(
        host: HostProfile,
        runId: String,
        afterEventId: Long?,
        onEvent: (HermesRunEvent) -> Unit,
    ) {
        streamCursors += afterEventId
        if (streamErrors.isNotEmpty()) throw streamErrors.removeAt(0)
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
    var chatActivityLayout = ChatActivityLayout.Grouped
    var checkpoints = emptyList<RunCheckpoint>()
    var checkpoint: RunCheckpoint?
        get() = checkpoints.firstOrNull()
        set(value) { checkpoints = listOfNotNull(value) }
    val runStatuses = mutableMapOf<String, String>()
    var attentionItems = emptyList<AttentionItem>()
    var unknownOutcomeRecords = emptyList<UnknownOutcomeRecord>()
    var queuedInterruptRecords = emptyList<QueuedInterruptRecord>()
    var crashReportingEnabled = false
    var failRecoveredStateLoads = false

    override fun loadThemeMode(): ThemeMode = initialMode
    override fun saveThemeMode(mode: ThemeMode) {
        savedMode = mode
    }
    override fun loadChatActivityLayout(): ChatActivityLayout = chatActivityLayout
    override fun saveChatActivityLayout(layout: ChatActivityLayout) { chatActivityLayout = layout }
    override fun loadRunCheckpoints(): List<RunCheckpoint> = checkpoints
    override fun saveRunCheckpoints(checkpoints: List<RunCheckpoint>) { this.checkpoints = checkpoints.distinct() }
    override fun loadRunCheckpoint(): RunCheckpoint? = checkpoint
    override fun saveRunCheckpoint(checkpoint: RunCheckpoint) { this.checkpoint = checkpoint }
    override fun clearRunCheckpoint() { checkpoint = null }
    override fun loadRunStatus(runId: String): String? = runStatuses[runId]
    override fun saveRunStatus(runId: String, status: String) { runStatuses[runId] = status }
    override fun clearRunStatus(runId: String) { runStatuses.remove(runId) }
    override fun loadUnknownOutcomeRecords(): List<UnknownOutcomeRecord> {
        check(!failRecoveredStateLoads)
        return unknownOutcomeRecords
    }
    override fun saveUnknownOutcomeRecords(records: List<UnknownOutcomeRecord>) {
        unknownOutcomeRecords = records
    }
    override fun loadQueuedInterruptRecords(): List<QueuedInterruptRecord> {
        check(!failRecoveredStateLoads)
        return queuedInterruptRecords
    }
    override fun saveQueuedInterruptRecords(records: List<QueuedInterruptRecord>) {
        queuedInterruptRecords = records
    }
    override fun loadAttentionItems(): List<AttentionItem> {
        check(!failRecoveredStateLoads)
        return attentionItems
    }
    override fun saveAttentionItems(items: List<AttentionItem>) { attentionItems = items }
    override fun loadCrashReportingEnabled(): Boolean = crashReportingEnabled
    override fun saveCrashReportingEnabled(enabled: Boolean) { crashReportingEnabled = enabled }
}

private class FakeAppDiagnostics : AppDiagnostics {
    val collectionChanges = mutableListOf<Boolean>()
    val phases = mutableListOf<Pair<DiagnosticPhase, DiagnosticContext>>()
    val failures = mutableListOf<Pair<DiagnosticPhase, Throwable>>()

    override fun initialize() = Unit
    override fun setCollectionEnabled(enabled: Boolean) { collectionChanges += enabled }
    override fun recordPhase(phase: DiagnosticPhase, context: DiagnosticContext) { phases += phase to context }
    override fun recordFailure(phase: DiagnosticPhase, error: Throwable) { failures += phase to error }
    override fun latestExit(): ProcessExitDiagnostic? = null
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
        diagnostics: AppDiagnostics = NoOpAppDiagnostics,
        safeStartup: Boolean = false,
    ): Pair<HermesViewModel, FakeGateway> {
        val viewModel = HermesViewModel(gateway, store, savedState, settingsStore, diagnostics, safeStartup = safeStartup)
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
    fun `crash reporting consent loads from settings and updates diagnostics`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply { crashReportingEnabled = true }
        val diagnostics = FakeAppDiagnostics()
        val (viewModel, _) = buildViewModel(settingsStore = settings, diagnostics = diagnostics)

        assertTrue(viewModel.state.value.crashReportingEnabled)

        viewModel.setCrashReportingEnabled(false)

        assertFalse(viewModel.state.value.crashReportingEnabled)
        assertFalse(settings.crashReportingEnabled)
        assertEquals(listOf(false), diagnostics.collectionChanges)
    }

    @Test
    fun `keystore unlock failure surfaces a notice`() = runVmTest {
        val store = FakeHostStore(HostSnapshot(), unlockFailed = true)
        val (viewModel, _) = buildViewModel(store = store)

        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("unlocked"))
    }

    @Test
    fun `safe startup pauses automatic recovery until retry`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply { failRecoveredStateLoads = true }
        val savedState = SavedStateHandle(mapOf("composer.sessionDrafts" to "invalid"))
        val (viewModel, gateway) = buildViewModel(
            savedState = savedState,
            settingsStore = settings,
            safeStartup = true,
        )

        assertEquals(HostConnectionPhase.Failed, viewModel.state.value.connectionPhase)
        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("background state was skipped"))
        assertEquals(0, gateway.jobsCalls)

        viewModel.retryConnection()
        advanceUntilIdle()

        assertEquals(HostConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertEquals(1, gateway.jobsCalls)
    }

    @Test
    fun `host picker can close without saved hosts`() = runVmTest {
        val (viewModel, _) = buildViewModel(store = FakeHostStore(HostSnapshot()))

        assertTrue(viewModel.state.value.showHostPicker)
        viewModel.hideHostPicker()
        assertFalse(viewModel.state.value.showHostPicker)
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(dispatcher) { block() }

    @Test
    fun `connect loads capabilities sessions skills and models`() = runVmTest {
        val (viewModel, _) = buildViewModel()

        val state = viewModel.state.value
        assertEquals(HostConnectionPhase.Connected, state.connectionPhase)
        assertTrue(state.sessionsResource is ResourceState.Data)
        assertTrue(state.jobsResource is ResourceState.Empty)
        assertTrue(state.capabilities!!.supportsRuns)
        assertEquals(listOf("s1"), state.sessions.map { it.id })
        assertEquals(listOf("grill-me"), state.skills.map { it.name })
        assertEquals(listOf("terminal"), state.toolsets.map { it.name })
        assertEquals(listOf("hermes-agent", "hermes-fast", "gpt-5.6-terra"), state.models)
        assertEquals("2026.7.15", state.capabilities?.version)
        assertTrue(state.hostUpdate?.updateAvailable == true)
    }

    @Test
    fun `connect distinguishes unsupported optional resources from failures`() = runVmTest {
        val gateway = FakeGateway().apply {
            capabilities = capabilities.copy(features = RUN_BUNDLE - "skills_api")
            jobsError = HermesApiException(404, "Jobs are not available")
            toolsetsError = HermesApiException(500, "Toolsets failed")
            modelsError = IOException("models offline")
        }
        val (viewModel, connectedGateway) = buildViewModel(gateway = gateway)

        val state = viewModel.state.value
        assertEquals(HostConnectionPhase.Connected, state.connectionPhase)
        assertTrue(state.jobsResource is ResourceState.Unsupported)
        assertTrue(state.skillsResource is ResourceState.Unsupported)
        assertTrue(state.toolsetsResource is ResourceState.Error)
        assertTrue(state.modelsResource is ResourceState.Error)
        assertEquals(0, connectedGateway.skillsCalls)
        assertNull(state.errorMessage)
    }

    @Test
    fun `an advertised skills endpoint failure is not treated as unsupported`() = runVmTest {
        val gateway = FakeGateway().apply {
            skillsError = HermesApiException(404, "Skills failed")
        }

        val (viewModel, _) = buildViewModel(gateway = gateway)

        assertEquals(HostConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertTrue(viewModel.state.value.skillsResource is ResourceState.Error)
    }

    @Test
    fun `model selection survives a catalog retry failure`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectModel("hermes-fast")
        gateway.modelsError = IOException("catalog offline")

        viewModel.retryConnection()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(HostConnectionPhase.Connected, state.connectionPhase)
        assertTrue(state.modelsResource is ResourceState.Error)
        assertEquals("hermes-fast", state.selectedModel)
    }

    @Test
    fun `transcript exposes loading data empty and retryable error states`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        val firstGate = Channel<Unit>(Channel.RENDEZVOUS)
        gateway.loadMessageGates["s1"] = firstGate

        viewModel.selectSession("s1")
        runCurrent()
        assertTrue(viewModel.state.value.transcriptResource is ResourceState.Loading)

        firstGate.send(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.transcriptResource is ResourceState.Data)

        gateway.loadMessageGates.remove("s1")
        gateway.loadMessageErrors["s1"] = IOException("transcript offline")
        viewModel.selectSession("s1")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.transcriptResource is ResourceState.Error)
        assertNull(viewModel.state.value.errorMessage)

        gateway.loadMessageErrors.remove("s1")
        gateway.messages.getValue("s1").clear()
        viewModel.retryTranscript()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.transcriptResource is ResourceState.Empty)
    }

    @Test
    fun `a stale transcript result cannot replace the newly selected session`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        gateway.sessions += HermesSession("s2", "Second", null, "api_server", null, null, 1)
        gateway.messages["s2"] = mutableListOf(HermesMessage("s2-message", "assistant", "second"))
        val firstGate = Channel<Unit>(Channel.RENDEZVOUS)
        gateway.loadMessageGates["s1"] = firstGate

        viewModel.selectSession("s1")
        runCurrent()
        viewModel.selectSession("s2")
        runCurrent()
        assertEquals(listOf("s2-message"), viewModel.state.value.messages.map(ChatUiItem::id))

        firstGate.send(Unit)
        advanceUntilIdle()

        assertEquals("s2", viewModel.state.value.activeSessionId)
        assertEquals(listOf("s2-message"), viewModel.state.value.messages.map(ChatUiItem::id))
    }

    @Test
    fun `refresh failures preserve cached resource content without a global error`() = runVmTest {
        val gateway = FakeGateway().apply {
            jobs = listOf(HermesJob("job-1", "Daily", "daily", true, null))
        }
        val (viewModel, connectedGateway) = buildViewModel(gateway = gateway)
        connectedGateway.listSessionErrorsByHost["h1"] = IOException("sessions offline")
        connectedGateway.jobsError = HermesApiException(500, "Jobs failed")

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        val sessionsError = state.sessionsResource as ResourceState.Error
        val jobsError = state.jobsResource as ResourceState.Error
        assertEquals(listOf("s1"), sessionsError.cached.orEmpty().map(HermesSession::id))
        assertEquals(listOf("job-1"), jobsError.cached.orEmpty().map(HermesJob::id))
        assertEquals(listOf("s1"), state.sessions.map(HermesSession::id))
        assertEquals(listOf("job-1"), state.jobs.map(HermesJob::id))
        assertFalse(state.isRefreshing)
        assertNull(state.errorMessage)
    }

    @Test
    fun `pagination has an independent failure and retry state`() = runVmTest {
        val gateway = FakeGateway().apply {
            sessions.clear()
            sessions += (0 until 60).map { index ->
                HermesSession("s$index", "Session $index", null, "api_server", null, null, 0)
            }
        }
        val (viewModel, connectedGateway) = buildViewModel(gateway = gateway)
        assertEquals(50, viewModel.state.value.sessions.size)
        assertTrue(viewModel.state.value.sessionsHasMore)
        connectedGateway.listSessionErrorsByOffset[50] = IOException("next page offline")

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(50, viewModel.state.value.sessions.size)
        assertFalse(viewModel.state.value.sessionsLoadingMore)
        assertTrue(viewModel.state.value.sessionsLoadMoreError.orEmpty().contains("Could not reach"))
        assertNull(viewModel.state.value.errorMessage)

        connectedGateway.listSessionErrorsByOffset.remove(50)
        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(60, viewModel.state.value.sessions.size)
        assertFalse(viewModel.state.value.sessionsHasMore)
        assertNull(viewModel.state.value.sessionsLoadMoreError)
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
            activeSessions = listOf(
                HermesActiveSession(
                    "active",
                    "run-1",
                    "Active",
                    "waiting_for_approval",
                    "desktop",
                    updatedAt = 1_900_000_000L,
                ),
            )
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
        assertEquals(listOf("recent", "desktop-running"), state.orderedSessions.map { it.id })
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
        val workspaceUpdate = HermesWorkspaceUpdate(
            listOf(HermesWorkspaceChange("app/Main.kt", "modified", 3, 1, "@@ -1 +1 @@")),
        )
        gateway.events.send(HermesRunEvent.WorkspaceUpdated(workspaceUpdate))
        advanceUntilIdle()

        val run = viewModel.state.value.activeRun
        assertEquals("1 / 2 tasks", taskProgressLabel(run?.tasks.orEmpty()))
        assertEquals("Inspect the API", run?.subagents?.get("subagent-1")?.goal)
        assertEquals("Reading run events", run?.subagents?.get("subagent-1")?.activity)
        assertEquals(2, run?.subagents?.get("subagent-1")?.toolCount)
        assertEquals(workspaceUpdate, viewModel.state.value.workspaceUpdates[SessionKey("h1", "s1")])

        gateway.events.send(HermesRunEvent.Cancelled)
        gateway.events.close()
        advanceUntilIdle()
        assertEquals(workspaceUpdate, viewModel.state.value.workspaceUpdates[SessionKey("h1", "s1")])
    }

    @Test
    fun `send drives a run to completion without duplicating the user message`() = runVmTest {
        val diagnostics = FakeAppDiagnostics()
        val (viewModel, gateway) = buildViewModel(diagnostics = diagnostics)
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
        assertEquals(
            listOf(
                DiagnosticPhase.SendValidate,
                DiagnosticPhase.HistoryLoad,
                DiagnosticPhase.RunSubmit,
                DiagnosticPhase.RunAccepted,
                DiagnosticPhase.RunStream,
            ),
            diagnostics.phases.map { it.first },
        )
        assertEquals(DiagnosticSendRoute.ExistingSession, diagnostics.phases.first().second.sendRoute)
        assertEquals(10, diagnostics.phases.first().second.messageLength)

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
        assertEquals(DiagnosticPhase.RunTerminal, diagnostics.phases.last().first)
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
        assertTrue(viewModel.state.value.isFullAccessConfirmationPending)
        assertNull(viewModel.state.value.selectedPermissionMode)
        viewModel.confirmFullAccessForNextRun()
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
        assertNull(viewModel.state.value.selectedPermissionMode)
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
    fun `opening a session marks its durable activity as read`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            attentionItems = listOf(AttentionItem("h1", "s1", "Task complete", "completed"))
        }
        val (viewModel, _) = buildViewModel(settingsStore = settings)

        viewModel.selectSession("s1")

        assertEquals(1, settings.attentionItems.size)
        assertTrue(settings.attentionItems.single().read)
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
    fun `composer draft survives ViewModel recreation`() = runVmTest {
        val savedState = SavedStateHandle()
        val store = FakeHostStore(HostSnapshot(listOf(hostA), "h1"))
        val (first, _) = buildViewModel(savedState = savedState, store = store)
        first.selectSession("s1")
        advanceUntilIdle()
        first.setComposerText("Keep this after process death")

        val (restored, _) = buildViewModel(savedState = savedState, store = store)
        restored.selectSession("s1")
        advanceUntilIdle()

        assertEquals("Keep this after process death", restored.state.value.composerText)
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
    fun `desktop activity appears inside its open mobile chat`() = runVmTest {
        val gateway = FakeGateway().apply {
            activeSessions = listOf(
                HermesActiveSession(
                    sessionId = "s1",
                    runId = null,
                    title = "First",
                    state = "running",
                    surface = "desktop",
                    latestStatus = "Using terminal…",
                    statusHistory = listOf("Reviewing the request…", "Using terminal…"),
                ),
            )
        }
        val (viewModel, _) = buildViewModel(gateway = gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        val activity = viewModel.state.value.displayedMessages.filterIsInstance<ChatUiItem.Reasoning>().single()
        assertEquals(listOf("Reviewing the request…"), activity.updates)
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
        assertTrue(viewModel.state.value.isFullAccessConfirmationPending)
        assertNull(viewModel.state.value.selectedPermissionMode)
        viewModel.confirmFullAccessForNextRun()
        assertEquals("full-access", viewModel.state.value.selectedPermissionMode)
        viewModel.setComposerText("handle this")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("full-access", gateway.submits.single().permissionMode)
        assertNull(viewModel.state.value.selectedPermissionMode)
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `full access requires confirmation and cancellation leaves the next run at host policy`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectPermissionMode("full-access")
        assertTrue(viewModel.state.value.isFullAccessConfirmationPending)
        assertNull(viewModel.state.value.selectedPermissionMode)
        viewModel.cancelFullAccessConfirmation()
        viewModel.setComposerText("use host policy")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(gateway.submits.single().permissionMode)
        assertNull(viewModel.state.value.selectedPermissionMode)
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `confirmed API rejection restores one-shot full access`() = runVmTest {
        val gateway = FakeGateway().apply {
            submitError = HermesApiException(400, "request rejected")
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectPermissionMode("full-access")
        viewModel.confirmFullAccessForNextRun()
        viewModel.setComposerText("retry after rejection")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("full-access", gateway.submitAttempts.single().permissionMode)
        assertEquals("full-access", viewModel.state.value.selectedPermissionMode)
        assertEquals("retry after rejection", viewModel.state.value.composerText)
        assertNull(viewModel.state.value.unknownOutcome)
    }

    @Test
    fun `unknown submit outcome consumes one-shot full access`() = runVmTest {
        val gateway = FakeGateway().apply {
            submitError = IOException("response lost")
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectPermissionMode("full-access")
        viewModel.confirmFullAccessForNextRun()
        viewModel.setComposerText("do not replay elevated access")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("full-access", gateway.submitAttempts.single().permissionMode)
        assertNull(viewModel.state.value.selectedPermissionMode)
        assertEquals("do not replay elevated access", viewModel.state.value.unknownOutcome?.text)
    }

    @Test
    fun `permission mode is dropped when the host lacks the capability`() = runVmTest {
        val gateway = FakeGateway()
        gateway.capabilities = HermesCapabilities("m", "p", RUN_BUNDLE - "run_permission_mode")
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.selectPermissionMode("full-access")
        viewModel.confirmFullAccessForNextRun()
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
    fun `recovered approval remains visible without unsafe approval actions`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("requires approval")
        viewModel.sendMessage()
        advanceUntilIdle()

        gateway.runStatus = HermesRunStatus("run-1", "waiting_for_approval")
        viewModel.reconcileActiveRuns()
        runCurrent()

        val card = viewModel.state.value.displayedMessages.filterIsInstance<ChatUiItem.Approval>().single()
        assertTrue(card.detailsUnavailable)
        assertNull(card.command)
        assertEquals(
            "Approval details unavailable",
            viewModel.state.value.activeRun!!.tail
                .filterIsInstance<ChatUiItem.Assistant>()
                .single()
                .safeStatus,
        )
    }

    @Test
    fun `reconciliation preserves an approval received from the event stream`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("requires approval")
        viewModel.sendMessage()
        advanceUntilIdle()

        gateway.events.send(HermesRunEvent.ApprovalRequested("sensitive command"))
        advanceUntilIdle()
        gateway.runStatus = HermesRunStatus("run-1", "waiting_for_approval")
        viewModel.reconcileActiveRuns()
        runCurrent()

        val card = viewModel.state.value.displayedMessages.filterIsInstance<ChatUiItem.Approval>().single()
        assertFalse(card.detailsUnavailable)
        assertEquals("sensitive command", card.command)
    }

    @Test
    fun `approval event restores actions after details were unavailable`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("requires approval")
        viewModel.sendMessage()
        advanceUntilIdle()

        gateway.runStatus = HermesRunStatus("run-1", "waiting_for_approval")
        viewModel.reconcileActiveRuns()
        runCurrent()

        gateway.events.send(HermesRunEvent.ApprovalRequested("sensitive command"))
        advanceUntilIdle()

        val card = viewModel.state.value.displayedMessages.filterIsInstance<ChatUiItem.Approval>().single()
        assertFalse(card.detailsUnavailable)
        assertEquals("sensitive command", card.command)
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
    fun `live assistant status keeps meaningful progress and leaves tool chatter to the tool card`() = runVmTest {
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
        assertEquals("Starting task…", assistant.safeStatus)
        assertFalse(assistant.safeStatus.orEmpty().contains("secret command payload"))
        assertEquals(listOf("Starting task…"), assistant.safeStatusHistory)

        gateway.events.send(HermesRunEvent.ReasoningAvailable("Checking the test results"))
        gateway.events.send(HermesRunEvent.ToolCompleted("terminal", failed = false))
        advanceUntilIdle()
        assistant = viewModel.state.value.activeRun!!.tail.filterIsInstance<ChatUiItem.Assistant>().single()
        assertEquals("Checking the test results", assistant.safeStatus)
        assertEquals(listOf("Starting task…", "Checking the test results"), assistant.safeStatusHistory)
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
    fun `chat activity layout loads from settings and saves changes`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            chatActivityLayout = ChatActivityLayout.Chronological
        }
        val (viewModel, _) = buildViewModel(settingsStore = settings)

        assertEquals(ChatActivityLayout.Chronological, viewModel.state.value.chatActivityLayout)

        viewModel.setChatActivityLayout(ChatActivityLayout.Grouped)

        assertEquals(ChatActivityLayout.Grouped, settings.chatActivityLayout)
        assertEquals(ChatActivityLayout.Grouped, viewModel.state.value.chatActivityLayout)
    }

    @Test
    fun `generic tool lifecycle statuses are not useful progress`() {
        assertFalse(isUsefulProgressUpdate("Using process…"))
        assertFalse(isUsefulProgressUpdate("Continuing after terminal"))
        assertTrue(isUsefulProgressUpdate("Checking the failing tests"))
        assertTrue(isUsefulProgressUpdate("Using the test results to verify the fix"))
        assertTrue(isUsefulProgressUpdate("Continuing after the approval with deployment"))
        assertEquals(
            "Run the focused tests · 0 / 1 tasks",
            safeRunStatusText(HermesRunEvent.TasksUpdated(listOf(
                HermesTask("test", "Run the focused tests", "in_progress"),
            ))),
        )
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
    fun `terminal transcript sync retries transient host failures`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System)
        val (viewModel, gateway) = buildViewModel(settingsStore = settings)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("finish after a transient edge failure")
        viewModel.sendMessage()
        advanceUntilIdle()
        gateway.loadMessageFailures["s1"] = mutableListOf(
            IOException("transcript not ready"),
            HermesApiException(520, "edge response"),
        )

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.activeRuns.isEmpty())
        assertNull(viewModel.state.value.errorMessage)
        assertNull(settings.checkpoint)
    }

    @Test
    fun `terminal transcript sync exposes persistent host failure detail`() = runVmTest {
        val diagnostics = FakeAppDiagnostics()
        val (viewModel, gateway) = buildViewModel(diagnostics = diagnostics)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("finish with a large transcript")
        viewModel.sendMessage()
        advanceUntilIdle()
        gateway.loadMessageErrors["s1"] = HermesApiException(200, "Hermes returned a response that was too large.")

        gateway.events.send(HermesRunEvent.Completed("done"))
        gateway.events.close()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.errorMessage.orEmpty().contains("too large"))
        assertEquals(DiagnosticPhase.RunTerminal, diagnostics.failures.single().first)
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
    fun `sending a follow-up requires an explicit interrupt before starting the next run`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first task")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.setComposerText("change direction")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue(gateway.stops.isEmpty())
        assertEquals("change direction", viewModel.state.value.pendingFollowUpChoice?.text)
        assertEquals("change direction", viewModel.state.value.composerText)

        viewModel.interruptPendingFollowUp()
        advanceUntilIdle()

        assertEquals(listOf("run-1"), gateway.stops)
        assertTrue(viewModel.state.value.activeRun!!.stopping)
        assertTrue(viewModel.state.value.composerText.isEmpty())
        assertEquals(FollowUpMode.Interrupt, viewModel.state.value.queuedInterrupt?.mode)

        gateway.eventsFor("run-1").send(HermesRunEvent.Cancelled)
        gateway.eventsFor("run-1").close()
        advanceUntilIdle()

        assertEquals(listOf("first task", "change direction"), gateway.submits.map { it.input })
        assertEquals("run-2", viewModel.state.value.activeRun?.runId)
        gateway.eventsFor("run-2").close()
        advanceUntilIdle()
    }

    @Test
    fun `queued follow-up waits for the active run without stopping it`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System)
        val (viewModel, gateway) = buildViewModel(settingsStore = settings)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first task")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.setComposerText("continue after this")
        viewModel.sendMessage()
        viewModel.queuePendingFollowUp()
        advanceUntilIdle()

        assertTrue(gateway.stops.isEmpty())
        assertNull(viewModel.state.value.pendingFollowUpChoice)
        assertEquals(FollowUpMode.Queue, viewModel.state.value.queuedInterrupt?.mode)
        assertEquals(FollowUpMode.Queue, settings.queuedInterruptRecords.single().mode)
        assertTrue(viewModel.state.value.composerText.isEmpty())

        gateway.eventsFor("run-1").send(HermesRunEvent.Completed("first done"))
        gateway.eventsFor("run-1").close()
        advanceUntilIdle()

        assertEquals(listOf("first task", "continue after this"), gateway.submits.map { it.input })
        assertEquals("run-2", viewModel.state.value.activeRun?.runId)
        gateway.eventsFor("run-2").close()
        advanceUntilIdle()
    }

    @Test
    fun `cancelling a follow-up choice preserves the draft without stopping`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first task")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.setComposerText("keep this draft")
        viewModel.sendMessage()
        viewModel.cancelPendingFollowUp()

        assertNull(viewModel.state.value.pendingFollowUpChoice)
        assertNull(viewModel.state.value.queuedInterrupt)
        assertEquals("keep this draft", viewModel.state.value.composerText)
        assertTrue(gateway.stops.isEmpty())

        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `stopping while a follow-up choice is pending preserves it as a draft`() = runVmTest {
        val (viewModel, gateway) = buildViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("first task")
        viewModel.sendMessage()
        advanceUntilIdle()

        viewModel.setComposerText("review before sending")
        viewModel.sendMessage()
        viewModel.stopActiveRun()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingFollowUpChoice)
        assertNull(viewModel.state.value.queuedInterrupt)
        assertEquals("review before sending", viewModel.state.value.composerText)
        assertEquals(listOf("run-1"), gateway.stops)

        gateway.events.send(HermesRunEvent.Cancelled)
        gateway.events.close()
        advanceUntilIdle()

        assertEquals(listOf("first task"), gateway.submits.map { it.input })
        assertEquals("review before sending", viewModel.state.value.composerText)
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
        viewModel.interruptPendingFollowUp()
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
        assertEquals(FollowUpMode.Interrupt, viewModel.state.value.queuedInterrupt?.mode)
        assertEquals("change direction safely", settings.queuedInterruptRecords.single().text)

        viewModel.acknowledgeQueuedInterrupt(useDraft = true)

        assertNull(viewModel.state.value.queuedInterrupt)
        assertEquals("change direction safely", viewModel.state.value.composerText)
        assertTrue(settings.queuedInterruptRecords.isEmpty())
        assertTrue(gateway.submits.isEmpty())
    }

    @Test
    fun `retry-safe host reuses one idempotency key after a lost submit response`() = runVmTest {
        val gateway = FakeGateway().apply {
            capabilities = capabilities.copy(
                features = capabilities.features + "run_submission_idempotency",
            )
            submitErrors += IOException("response lost")
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()

        viewModel.setComposerText("retry without duplicating")
        viewModel.sendMessage()
        runCurrent()
        advanceTimeBy(301)
        runCurrent()

        assertEquals(2, gateway.submitIdempotencyKeys.size)
        assertEquals(gateway.submitIdempotencyKeys.first(), gateway.submitIdempotencyKeys.last())
        assertNotNull(gateway.submitIdempotencyKeys.first())
        assertEquals(1, gateway.submits.size)

        gateway.events.send(HermesRunEvent.Completed("done", eventId = 1))
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `recovered run resumes its stream after the durable event cursor`() = runVmTest {
        val settings = FakeSettingsStore(ThemeMode.System).apply {
            checkpoint = RunCheckpoint("h1", "s1", "run-recovered", lastEventId = 7)
        }
        val gateway = FakeGateway().apply {
            runStatus = HermesRunStatus("run-recovered", "running")
            eventsFor("run-recovered").trySend(HermesRunEvent.Completed("done", eventId = 8))
            eventsFor("run-recovered").close()
        }
        buildViewModel(gateway = gateway, settingsStore = settings)
        advanceUntilIdle()

        assertEquals(listOf(7L), gateway.streamCursors)
    }

    @Test
    fun `replayed event ids are applied only once`() = runVmTest {
        val gateway = FakeGateway().apply {
            capabilities = capabilities.copy(features = capabilities.features + "run_event_replay")
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("stream safely")
        viewModel.sendMessage()
        runCurrent()

        gateway.events.send(HermesRunEvent.MessageDelta("once", eventId = 1))
        gateway.events.send(HermesRunEvent.MessageDelta("once", eventId = 1))
        runCurrent()

        val assistant = viewModel.state.value.activeRun?.tail
            ?.filterIsInstance<ChatUiItem.Assistant>()
            ?.last()
        assertEquals("once", assistant?.text)
        gateway.events.send(HermesRunEvent.Cancelled)
        gateway.events.close()
        advanceUntilIdle()
    }

    @Test
    fun `replayable run reconnects after more than three stream failures`() = runVmTest {
        val gateway = FakeGateway().apply {
            capabilities = capabilities.copy(features = capabilities.features + "run_event_replay")
            runStatus = HermesRunStatus("run-1", "running")
            repeat(3) { streamErrors += IOException("network down") }
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("keep reconnecting")
        viewModel.sendMessage()
        runCurrent()

        advanceTimeBy(500)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        gateway.events.trySend(HermesRunEvent.Completed("done", eventId = 1))
        gateway.events.close()
        advanceTimeBy(2_000)
        advanceUntilIdle()

        assertTrue(gateway.streamCursors.size >= 4)
        assertTrue(viewModel.state.value.activeRuns.isEmpty())
    }

    @Test
    fun `stale host activity does not block session actions and can be cleared`() = runVmTest {
        val gateway = FakeGateway().apply {
            capabilities = capabilities.copy(features = capabilities.features + "active_session_cleanup")
            activeSessions = listOf(
                HermesActiveSession(
                    sessionId = "s1",
                    runId = "stale-run",
                    title = "First",
                    state = "unresponsive",
                    surface = "desktop",
                    leaseId = "lease-stale",
                ),
            )
        }
        val (viewModel, _) = buildViewModel(gateway)

        assertFalse(viewModel.state.value.isSessionBusy("h1", "s1"))
        assertFalse("s1" in viewModel.state.value.activeSessionIds)
        viewModel.clearStaleActivity("s1")
        advanceUntilIdle()

        assertEquals(listOf("lease-stale"), gateway.clearedLeases)
        assertNull(viewModel.state.value.activityFor(gateway.sessions.single()))
    }

    @Test
    fun `stale host activity overrides and clears a matching local run`() = runVmTest {
        val gateway = FakeGateway().apply {
            capabilities = capabilities.copy(features = capabilities.features + "active_session_cleanup")
        }
        val (viewModel, _) = buildViewModel(gateway)
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setComposerText("work that stalled")
        viewModel.sendMessage()
        runCurrent()
        assertTrue(SessionKey("h1", "s1") in viewModel.state.value.activeRuns)

        gateway.activeSessions = listOf(
            HermesActiveSession(
                sessionId = "s1",
                runId = "run-1",
                title = "First",
                state = "unresponsive",
                surface = "api_server",
                leaseId = "lease-stale",
            ),
        )
        viewModel.refreshHostActivity()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isSessionBusy("h1", "s1"))
        assertFalse("s1" in viewModel.state.value.activeSessionIds)
        viewModel.clearStaleActivity("s1")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.activeRuns.isEmpty())
        assertFalse(viewModel.state.value.isSessionDeleteBlocked("h1", "s1"))
        gateway.events.close()
    }

    @Test
    fun `slash suggestions combine reserved commands and skills`() = runVmTest {
        val gateway = FakeGateway().apply {
            skills = skills + HermesSkill("plan", "Create an implementation plan")
        }
        val (viewModel, _) = buildViewModel(gateway)

        viewModel.setComposerText("/")
        val all = viewModel.state.value.slashSuggestions()
        assertTrue(all.any { it.kind == SlashKind.Command && it.name == "new" })
        assertTrue(all.any { it.kind == SlashKind.HostCommand && it.name == "goal" })
        assertTrue(all.any { it.kind == SlashKind.HostCommand && it.name == "plan" })
        assertTrue(all.any { it.kind == SlashKind.Skill && it.name == "grill-me" })

        viewModel.applySuggestion(all.first { it.name == "goal" })
        assertEquals("/goal ", viewModel.state.value.composerText)

        viewModel.setComposerText("/gri")
        val filtered = viewModel.state.value.slashSuggestions()
        assertEquals(listOf("grill-me"), filtered.map { it.name })
    }
}
