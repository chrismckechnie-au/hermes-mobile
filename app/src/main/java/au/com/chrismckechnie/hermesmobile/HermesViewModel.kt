package au.com.chrismckechnie.hermesmobile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class HostConnectionPhase { NoHost, Connecting, Connected, Failed }

sealed interface ChatUiItem {
    val id: String

    data class User(
        override val id: String,
        val text: String,
    ) : ChatUiItem

    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatUiItem

    data class Tool(
        override val id: String,
        val name: String,
        val preview: String?,
        val running: Boolean,
        val failed: Boolean = false,
    ) : ChatUiItem

    data class Approval(
        override val id: String,
        val command: String?,
    ) : ChatUiItem
}

/**
 * Immutable coordinates of the Run in flight. Holds the authenticated Host
 * snapshot so Stop/approval/reconciliation keep working even if the profile
 * is edited or deleted mid-run. Never stringified — logs use ids only.
 */
data class ActiveRun(
    val host: HostProfile,
    val sessionId: String,
    val sessionTitle: String?,
    val runId: String,
    val tail: List<ChatUiItem> = emptyList(),
    val assistantId: String = "",
    val awaitingApproval: Boolean = false,
    val approvalCommand: String? = null,
    val approvalDetailsLost: Boolean = false,
    val stopping: Boolean = false,
    val recovered: Boolean = false,
) {
    override fun toString(): String = "ActiveRun(runId=$runId, sessionId=$sessionId)"
}

/** A submitRun whose response was lost: the Host may or may not be executing the turn. */
data class UnknownOutcome(
    val sessionId: String,
    val baselineCount: Int,
    val text: String,
    val evidence: Boolean = false,
    val timedOut: Boolean = false,
)

enum class SlashKind { Command, Skill }

data class SlashSuggestion(
    val kind: SlashKind,
    val name: String,
    val description: String,
)

/** Host-valid reasoning effort levels (hermes_constants.VALID_REASONING_EFFORTS). */
val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

val LOCAL_COMMANDS = listOf(
    SlashSuggestion(SlashKind.Command, "new", "Start a new session"),
    SlashSuggestion(SlashKind.Command, "rename", "Rename this session — /rename <title>"),
    SlashSuggestion(SlashKind.Command, "fork", "Fork this session into a new branch"),
    SlashSuggestion(SlashKind.Command, "delete", "Delete this session"),
    SlashSuggestion(SlashKind.Command, "stop", "Stop the active run"),
)

data class HermesUiState(
    val screen: DeckScreen = DeckScreen.Chat,
    val hosts: List<HostProfile> = emptyList(),
    val activeHostId: String? = null,
    val showHostPicker: Boolean = false,
    val connectionPhase: HostConnectionPhase = HostConnectionPhase.NoHost,
    val capabilities: HermesCapabilities? = null,
    val sessions: List<HermesSession> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatUiItem> = emptyList(),
    val jobs: List<HermesJob> = emptyList(),
    val skills: List<HermesSkill> = emptyList(),
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    val selectedReasoningEffort: String? = null,
    val composerText: String = "",
    val isSending: Boolean = false,
    val activeRun: ActiveRun? = null,
    val unknownOutcome: UnknownOutcome? = null,
    val sessionActionsFor: String? = null,
    val confirmDeleteSessionId: String? = null,
    val errorMessage: String? = null,
) {
    val activeHost: HostProfile? get() = hosts.firstOrNull { it.id == activeHostId }
    val activeSession: HermesSession? get() = sessions.firstOrNull { it.id == activeSessionId }

    /** Loaded messages plus the live Run tail (and Approval card) when its Session is displayed. */
    val displayedMessages: List<ChatUiItem>
        get() {
            val run = activeRun ?: return messages
            if (run.sessionId != activeSessionId) return messages
            val tail = messages + run.tail
            return if (run.awaitingApproval && !run.approvalDetailsLost) {
                tail + ChatUiItem.Approval("approval:${run.runId}", run.approvalCommand)
            } else tail
        }

    /** Run banner is shown whenever the Run's Session is not the visible chat. */
    val runBannerVisible: Boolean
        get() = activeRun != null && (screen != DeckScreen.Chat || activeRun.sessionId != activeSessionId)

    fun slashSuggestions(): List<SlashSuggestion> {
        if (!composerText.startsWith("/") || composerText.contains(' ') || composerText.contains('\n')) return emptyList()
        val query = composerText.drop(1).lowercase()
        val commands = LOCAL_COMMANDS.filter { it.name.startsWith(query) }
        val skillMatches = if (capabilities?.supportsSkills == true) {
            skills.filter { it.name.lowercase().contains(query) }
                .map { SlashSuggestion(SlashKind.Skill, it.name, it.description ?: "Host skill") }
        } else emptyList()
        return (commands + skillMatches).take(10)
    }
}

class HermesViewModel(
    private val gateway: HermesGateway,
    private val hostStore: HostStore,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(HermesUiState())
    val state: StateFlow<HermesUiState> = mutableState.asStateFlow()

    init {
        val snapshot = hostStore.load()
        val selected = snapshot.selectedHostId?.takeIf { id -> snapshot.hosts.any { it.id == id } }
        mutableState.value = HermesUiState(
            hosts = snapshot.hosts,
            activeHostId = selected,
            showHostPicker = snapshot.hosts.isEmpty(),
            connectionPhase = if (selected == null) HostConnectionPhase.NoHost else HostConnectionPhase.Connecting,
        )
        if (selected != null) connect(selected)
        recoverRunAfterProcessDeath(snapshot)
    }

    fun selectScreen(screen: DeckScreen) {
        mutableState.update { it.copy(screen = screen) }
    }

    fun setComposerText(value: String) {
        mutableState.update { it.copy(composerText = value.take(8_000)) }
    }

    fun showHostPicker() {
        mutableState.update { it.copy(showHostPicker = true, errorMessage = null) }
    }

    fun hideHostPicker() {
        if (mutableState.value.hosts.isNotEmpty()) {
            mutableState.update { it.copy(showHostPicker = false, errorMessage = null) }
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    fun saveHost(
        existingId: String? = null,
        name: String,
        baseUrl: String,
        apiKey: String,
        allowInsecureHttp: Boolean,
    ) {
        if (mutableState.value.activeRun != null) {
            // Saving always switches the active host; that orphans the Run.
            mutableState.update { it.copy(errorMessage = "A run is still active. Stop it before changing hosts.") }
            return
        }
        val profile = runCatching {
            HostProfile(
                id = existingId ?: UUID.randomUUID().toString(),
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                allowInsecureHttp = allowInsecureHttp,
            ).validated()
        }.getOrElse { error ->
            mutableState.update { it.copy(errorMessage = error.message ?: "Host details are invalid.") }
            return
        }

        val hosts = mutableState.value.hosts.filterNot { it.id == profile.id } + profile
        persist(HostSnapshot(hosts, profile.id))
        mutableState.update {
            it.copy(
                hosts = hosts,
                activeHostId = profile.id,
                showHostPicker = false,
                connectionPhase = HostConnectionPhase.Connecting,
                errorMessage = null,
            )
        }
        connect(profile.id)
    }

    fun selectHost(hostId: String) {
        if (mutableState.value.hosts.none { it.id == hostId }) return
        if (mutableState.value.activeRun != null && mutableState.value.activeHostId != hostId) {
            mutableState.update { it.copy(errorMessage = "A run is still active. Stop it before switching hosts.") }
            return
        }
        persist(HostSnapshot(mutableState.value.hosts, hostId))
        mutableState.update {
            it.copy(
                activeHostId = hostId,
                showHostPicker = false,
                connectionPhase = HostConnectionPhase.Connecting,
                capabilities = null,
                sessions = emptyList(),
                activeSessionId = null,
                messages = emptyList(),
                jobs = emptyList(),
                skills = emptyList(),
                errorMessage = null,
            )
        }
        connect(hostId)
    }

    fun deleteHost(hostId: String) {
        if (hostId == mutableState.value.activeRun?.host?.id) {
            mutableState.update { it.copy(errorMessage = "A run is active on this host. Stop it before deleting the host.") }
            return
        }
        val hosts = mutableState.value.hosts.filterNot { it.id == hostId }
        val selected = mutableState.value.activeHostId?.takeIf { it != hostId && hosts.any { host -> host.id == it } }
            ?: hosts.firstOrNull()?.id
        persist(HostSnapshot(hosts, selected))
        mutableState.update {
            HermesUiState(
                hosts = hosts,
                activeHostId = selected,
                showHostPicker = hosts.isEmpty(),
                connectionPhase = if (selected == null) HostConnectionPhase.NoHost else HostConnectionPhase.Connecting,
            )
        }
        if (selected != null) connect(selected)
    }

    fun retryConnection() {
        mutableState.value.activeHostId?.let(::connect)
    }

    fun createSession() {
        val host = mutableState.value.activeHost ?: return
        if (mutableState.value.connectionPhase != HostConnectionPhase.Connected) return
        viewModelScope.launch {
            runCatching { gateway.createSession(host, "Hermes Mobile") }
                .onSuccess { session ->
                    mutableState.update {
                        it.copy(
                            sessions = listOf(session) + it.sessions.filterNot { item -> item.id == session.id },
                            activeSessionId = session.id,
                            messages = emptyList(),
                            screen = DeckScreen.Chat,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun selectSession(sessionId: String) {
        val host = mutableState.value.activeHost ?: return
        mutableState.update { it.copy(activeSessionId = sessionId, screen = DeckScreen.Chat, messages = emptyList(), errorMessage = null) }
        viewModelScope.launch {
            runCatching { gateway.loadMessages(host, sessionId) }
                .onSuccess { page -> applyLoadedMessages(requestedId = sessionId, page = page) }
                .onFailure(::showFailure)
        }
    }

    /** null selects the Host default; unknown aliases fall back host-side anyway. */
    fun selectModel(model: String?) {
        mutableState.update { it.copy(selectedModel = model?.takeIf { m -> m in it.models }) }
    }

    /** null selects the Host default reasoning effort. */
    fun selectReasoningEffort(effort: String?) {
        mutableState.update { it.copy(selectedReasoningEffort = effort?.takeIf { e -> e in REASONING_EFFORTS }) }
    }

    fun returnToRunSession() {
        val run = mutableState.value.activeRun ?: return
        if (mutableState.value.activeSessionId == run.sessionId) {
            mutableState.update { it.copy(screen = DeckScreen.Chat) }
        } else {
            selectSession(run.sessionId)
        }
    }

    // ------------------------------------------------------------------
    // Sending and slash dispatch
    // ------------------------------------------------------------------

    fun sendMessage() {
        val text = mutableState.value.composerText.trim()
        if (text.isBlank()) return
        if (text.startsWith("/") && dispatchLocalCommand(text)) return
        submitChat(text)
    }

    fun applySuggestion(suggestion: SlashSuggestion) {
        when (suggestion.kind) {
            // Typed dispatch: a Skill row never re-parses slash text, so a
            // skill named like a reserved command stays reachable.
            SlashKind.Skill -> mutableState.update { it.copy(composerText = "Use the ${suggestion.name} skill: ") }
            SlashKind.Command -> when (suggestion.name) {
                "rename" -> mutableState.update { it.copy(composerText = "/rename ") }
                else -> {
                    mutableState.update { it.copy(composerText = "") }
                    dispatchLocalCommand("/${suggestion.name}")
                }
            }
        }
    }

    /** Returns true when the text was handled as a Local Command. */
    private fun dispatchLocalCommand(text: String): Boolean {
        val command = text.removePrefix("/").substringBefore(' ').lowercase()
        val args = text.substringAfter(' ', "").trim()
        if (LOCAL_COMMANDS.none { it.name == command }) return false

        val run = mutableState.value.activeRun
        if (run != null && command != "stop") {
            mutableState.update { it.copy(errorMessage = "A run is in progress. Use /stop to interrupt it first.") }
            return true
        }

        mutableState.update { it.copy(composerText = "") }
        when (command) {
            "new" -> createSession()
            "stop" -> stopActiveRun()
            "fork" -> currentSessionOrError()?.let(::forkSession)
            "delete" -> currentSessionOrError()?.let(::requestDeleteSession)
            "rename" -> {
                val sessionId = currentSessionOrError() ?: return true
                if (args.isBlank()) {
                    mutableState.update { it.copy(errorMessage = "Usage: /rename <new title>") }
                } else renameSession(sessionId, args)
            }
        }
        return true
    }

    private fun currentSessionOrError(): String? {
        val id = mutableState.value.activeSessionId
        if (id == null) mutableState.update { it.copy(errorMessage = "No session is selected.") }
        return id
    }

    private fun submitChat(text: String) {
        val snapshot = mutableState.value
        val host = snapshot.activeHost ?: return
        if (snapshot.connectionPhase != HostConnectionPhase.Connected) return
        if (snapshot.activeRun != null) {
            mutableState.update { it.copy(errorMessage = "A run is in progress. Use /stop to interrupt it first.") }
            return
        }
        if (snapshot.unknownOutcome != null) return
        val capabilities = snapshot.capabilities
        if (capabilities == null || !capabilities.supportsRuns) {
            mutableState.update {
                it.copy(errorMessage = "This Hermes host does not expose run control (stop and approvals). Update the host to use chat from mobile.")
            }
            return
        }

        mutableState.update { it.copy(composerText = "", isSending = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val session = snapshot.activeSessionId?.let { id -> snapshot.sessions.firstOrNull { it.id == id } }
                    ?: gateway.createSession(host, text.take(44)).also { created ->
                        mutableState.update {
                            it.copy(sessions = listOf(created) + it.sessions, activeSessionId = created.id)
                        }
                    }

                // Pre-submit refresh: narrows multi-writer staleness, resolves a
                // rotated session, and is the exact history the run executes on.
                val page = gateway.loadMessages(host, session.id)
                applyLoadedMessages(requestedId = session.id, page = page)

                val current = mutableState.value
                val runId = try {
                    gateway.submitRun(
                        host,
                        page.sessionId,
                        text,
                        page.messages,
                        current.selectedModel,
                        current.selectedReasoningEffort.takeIf { capabilities.supportsReasoningEffort },
                    )
                } catch (error: HermesApiException) {
                    // The host answered: the run was not accepted.
                    showFailure(error)
                    mutableState.update { it.copy(isSending = false) }
                    return@launch
                } catch (error: Throwable) {
                    // Response lost — the host may be executing the turn.
                    logRun("submit outcome unknown session=${page.sessionId}")
                    mutableState.update {
                        it.copy(
                            isSending = false,
                            unknownOutcome = UnknownOutcome(page.sessionId, page.messages.size, text),
                        )
                    }
                    watchUnknownOutcome(host)
                    return@launch
                }

                val assistantId = UUID.randomUUID().toString()
                val run = ActiveRun(
                    host = host,
                    sessionId = page.sessionId,
                    sessionTitle = session.title,
                    runId = runId,
                    assistantId = assistantId,
                    tail = listOf(
                        ChatUiItem.User(UUID.randomUUID().toString(), text),
                        ChatUiItem.Assistant(assistantId, "", streaming = true),
                    ),
                )
                persistRunCoordinates(run)
                logRun("run started runId=$runId sessionId=${run.sessionId}")
                mutableState.update { it.copy(isSending = false, activeRun = run) }
                driveRun(run)
            } catch (error: Throwable) {
                showFailure(error)
                mutableState.update { it.copy(isSending = false) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Run lifecycle
    // ------------------------------------------------------------------

    private suspend fun driveRun(run: ActiveRun) {
        val terminal = AtomicBoolean(false)
        runCatching {
            gateway.streamRunEvents(run.host, run.runId) { event ->
                if (handleRunEvent(run, event)) terminal.set(true)
            }
        }.onFailure { logRun("run stream dropped runId=${run.runId}: ${it.javaClass.simpleName}") }

        if (!terminal.get()) {
            logRun("run stream ended without terminal event runId=${run.runId}; polling")
            if (!pollUntilTerminal(run)) {
                mutableState.update { state ->
                    if (state.activeRun?.runId != run.runId) state
                    else state.copy(
                        activeRun = state.activeRun.copy(approvalDetailsLost = true),
                        errorMessage = "Run state is unknown. Approve or inspect it on the host, or stop the run.",
                    )
                }
                return
            }
        }
        finalizeRun(run)
    }

    /** Returns true for terminal events. Ignores events for a Run that is no longer active. */
    private fun handleRunEvent(run: ActiveRun, event: HermesRunEvent): Boolean {
        var terminal = false
        mutableState.update { state ->
            val current = state.activeRun
            if (current == null || current.runId != run.runId) return@update state
            when (event) {
                is HermesRunEvent.MessageDelta -> state.copy(
                    activeRun = current.copy(tail = current.tail.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == current.assistantId) item.copy(text = item.text + event.delta) else item
                    })
                )
                is HermesRunEvent.ToolStarted -> state.copy(
                    activeRun = current.copy(tail = insertBeforeAssistant(current, ChatUiItem.Tool(
                        id = "${current.runId}:${event.tool}:${current.tail.size}",
                        name = event.tool,
                        preview = event.preview,
                        running = true,
                    )))
                )
                is HermesRunEvent.ToolCompleted -> state.copy(
                    activeRun = current.copy(tail = current.tail.map { item ->
                        if (item is ChatUiItem.Tool && item.name == event.tool && item.running) {
                            item.copy(running = false, failed = event.failed)
                        } else item
                    })
                )
                is HermesRunEvent.ApprovalRequested -> state.copy(
                    // One actionable card per Run: a second request keeps the
                    // first card until it resolves (host queue is FIFO).
                    activeRun = if (current.awaitingApproval) current
                    else current.copy(awaitingApproval = true, approvalCommand = event.command, approvalDetailsLost = false)
                )
                is HermesRunEvent.ApprovalResponded -> state.copy(
                    activeRun = current.copy(awaitingApproval = false, approvalCommand = null)
                )
                is HermesRunEvent.Completed -> {
                    terminal = true
                    state.copy(activeRun = current.copy(tail = current.tail.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == current.assistantId) {
                            item.copy(text = event.output.ifBlank { item.text }, streaming = false)
                        } else item
                    }))
                }
                is HermesRunEvent.Failed -> {
                    terminal = true
                    state.copy(errorMessage = event.error)
                }
                HermesRunEvent.Cancelled -> {
                    terminal = true
                    state
                }
            }
        }
        return terminal
    }

    private fun insertBeforeAssistant(run: ActiveRun, item: ChatUiItem): List<ChatUiItem> {
        val index = run.tail.indexOfLast { it is ChatUiItem.Assistant && it.id == run.assistantId }
        return if (index < 0) run.tail + item
        else run.tail.subList(0, index) + item + run.tail.subList(index, run.tail.size)
    }

    /** Bounded-backoff status polling. Returns true when the Run reached a terminal state. */
    private suspend fun pollUntilTerminal(run: ActiveRun): Boolean {
        var delayMs = 2_000L
        var waited = 0L
        while (waited < 120_000L) {
            delay(delayMs)
            waited += delayMs
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
            val status = runCatching { gateway.getRunStatus(run.host, run.runId) }.getOrNull() ?: continue
            logRun("run status runId=${run.runId} status=${status.status}")
            if (status.isTerminal) return true
            if (status.isWaitingForApproval) {
                // The SSE queue died with the approval payload; only the host
                // (or Stop) can resolve this safely.
                mutableState.update { state ->
                    if (state.activeRun?.runId != run.runId) state
                    else state.copy(activeRun = state.activeRun.copy(awaitingApproval = true, approvalDetailsLost = true))
                }
            }
        }
        return false
    }

    private suspend fun finalizeRun(run: ActiveRun) {
        logRun("run finalize runId=${run.runId}")
        clearRunCoordinates()
        val page = runCatching { gateway.loadMessages(run.host, run.sessionId) }.getOrNull()
        mutableState.update { state ->
            val cleared = if (state.activeRun?.runId == run.runId) state.copy(activeRun = null, isSending = false) else state
            if (page != null && cleared.activeSessionId == run.sessionId) {
                cleared.copy(
                    activeSessionId = page.sessionId,
                    messages = page.messages.mapNotNull(::mapStoredMessage),
                )
            } else cleared
        }
        refreshSessionsAndJobs(run.host)
    }

    fun respondApproval(choice: String) {
        val run = mutableState.value.activeRun ?: return
        viewModelScope.launch {
            logRun("approval response runId=${run.runId} choice=$choice")
            runCatching { gateway.respondApproval(run.host, run.runId, choice) }
                .onFailure { error ->
                    // 409 = already resolved (possibly by another client); the
                    // approval.responded event clears the card either way.
                    if ((error as? HermesApiException)?.statusCode != 409) showFailure(error)
                }
            mutableState.update { state ->
                if (state.activeRun?.runId != run.runId) state
                else state.copy(activeRun = state.activeRun.copy(awaitingApproval = false, approvalCommand = null))
            }
        }
    }

    fun stopActiveRun() {
        val run = mutableState.value.activeRun
        if (run == null) {
            mutableState.update { it.copy(errorMessage = "No run is active.") }
            return
        }
        mutableState.update { state ->
            if (state.activeRun?.runId != run.runId) state
            else state.copy(activeRun = state.activeRun.copy(stopping = true))
        }
        viewModelScope.launch {
            logRun("stop requested runId=${run.runId}")
            runCatching { gateway.stopRun(run.host, run.runId) }.onFailure(::showFailure)
            // Stop is not terminal ("stopping"): the live stream/poll drives to
            // run.cancelled. If the stream is already gone, poll here.
            if (mutableState.value.activeRun?.runId == run.runId && run.approvalDetailsLost) {
                if (pollUntilTerminal(run)) finalizeRun(run)
            }
        }
    }

    // ------------------------------------------------------------------
    // Unknown submission outcome
    // ------------------------------------------------------------------

    private fun watchUnknownOutcome(host: HostProfile) {
        viewModelScope.launch {
            var delayMs = 2_000L
            var waited = 0L
            while (waited < 120_000L) {
                val pending = mutableState.value.unknownOutcome ?: return@launch
                delay(delayMs)
                waited += delayMs
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
                val page = runCatching { gateway.loadMessages(host, pending.sessionId) }.getOrNull() ?: continue
                // Evidence = new messages beyond the pre-submit baseline; an
                // older identical text proves nothing.
                if (page.messages.size > pending.baselineCount) {
                    mutableState.update { state ->
                        state.unknownOutcome?.let { state.copy(unknownOutcome = it.copy(evidence = true)) } ?: state
                    }
                    return@launch
                }
            }
            mutableState.update { state ->
                state.unknownOutcome?.let { state.copy(unknownOutcome = it.copy(timedOut = true)) } ?: state
            }
        }
    }

    /** Explicit user acknowledgement — the only way the unknown-outcome lock releases. */
    fun acknowledgeUnknownOutcome() {
        val pending = mutableState.value.unknownOutcome ?: return
        val host = mutableState.value.activeHost
        mutableState.update { it.copy(unknownOutcome = null) }
        if (host != null && mutableState.value.activeSessionId == pending.sessionId) {
            selectSession(pending.sessionId)
        }
    }

    // ------------------------------------------------------------------
    // Session management
    // ------------------------------------------------------------------

    fun openSessionActions(sessionId: String) {
        mutableState.update { it.copy(sessionActionsFor = sessionId) }
    }

    fun dismissSessionActions() {
        mutableState.update { it.copy(sessionActionsFor = null) }
    }

    fun renameSession(sessionId: String, title: String) {
        val host = mutableState.value.activeHost ?: return
        if (guardSessionBusy(sessionId)) return
        mutableState.update { it.copy(sessionActionsFor = null) }
        viewModelScope.launch {
            runCatching { gateway.renameSession(host, sessionId, title.trim()) }
                .onSuccess { updated ->
                    mutableState.update { state ->
                        state.copy(sessions = state.sessions.map { if (it.id == updated.id) updated else it }, errorMessage = null)
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun forkSession(sessionId: String) {
        val host = mutableState.value.activeHost ?: return
        if (guardSessionBusy(sessionId)) return
        mutableState.update { it.copy(sessionActionsFor = null) }
        viewModelScope.launch {
            runCatching { gateway.forkSession(host, sessionId) }
                .onSuccess { child ->
                    mutableState.update { state ->
                        state.copy(
                            sessions = listOf(child) + state.sessions.filterNot { it.id == child.id },
                            errorMessage = null,
                        )
                    }
                    selectSession(child.id)
                    refreshSessionsAndJobs(host)
                }
                .onFailure(::showFailure)
        }
    }

    fun requestDeleteSession(sessionId: String) {
        if (guardSessionBusy(sessionId)) return
        mutableState.update { it.copy(confirmDeleteSessionId = sessionId, sessionActionsFor = null) }
    }

    fun dismissDeleteSession() {
        mutableState.update { it.copy(confirmDeleteSessionId = null) }
    }

    fun confirmDeleteSession() {
        val sessionId = mutableState.value.confirmDeleteSessionId ?: return
        val host = mutableState.value.activeHost ?: return
        mutableState.update { it.copy(confirmDeleteSessionId = null) }
        viewModelScope.launch {
            runCatching { gateway.deleteSession(host, sessionId) }
                .onSuccess {
                    mutableState.update { state ->
                        val wasActive = state.activeSessionId == sessionId
                        state.copy(
                            sessions = state.sessions.filterNot { it.id == sessionId },
                            activeSessionId = if (wasActive) null else state.activeSessionId,
                            messages = if (wasActive) emptyList() else state.messages,
                            screen = if (wasActive) DeckScreen.Sessions else state.screen,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure(::showFailure)
        }
    }

    private fun guardSessionBusy(sessionId: String): Boolean {
        if (mutableState.value.activeRun?.sessionId == sessionId) {
            mutableState.update { it.copy(errorMessage = "A run is active in this session. Stop it first.", sessionActionsFor = null) }
            return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // Connection, recovery, shared plumbing
    // ------------------------------------------------------------------

    private fun connect(hostId: String) {
        val host = mutableState.value.hosts.firstOrNull { it.id == hostId } ?: return
        mutableState.update { it.copy(connectionPhase = HostConnectionPhase.Connecting, errorMessage = null) }
        viewModelScope.launch {
            try {
                val capabilities = gateway.probe(host)
                val (sessions, jobs, extras) = coroutineScope {
                    val sessionsDeferred = async { gateway.listSessions(host) }
                    val jobsDeferred = async { runCatching { gateway.listJobs(host) }.getOrDefault(emptyList()) }
                    val skillsDeferred = async {
                        if (capabilities.supportsSkills) runCatching { gateway.listSkills(host) }.getOrDefault(emptyList())
                        else emptyList()
                    }
                    val modelsDeferred = async { runCatching { gateway.listModels(host) }.getOrDefault(emptyList()) }
                    Triple(sessionsDeferred.await(), jobsDeferred.await(), skillsDeferred.await() to modelsDeferred.await())
                }
                val (skills, models) = extras
                if (mutableState.value.activeHostId != hostId) return@launch
                mutableState.update {
                    it.copy(
                        connectionPhase = HostConnectionPhase.Connected,
                        capabilities = capabilities,
                        sessions = sessions,
                        jobs = jobs,
                        skills = skills,
                        models = models,
                        selectedModel = it.selectedModel?.takeIf { model -> model in models },
                        activeSessionId = it.activeSessionId?.takeIf { id -> sessions.any { session -> session.id == id } },
                        errorMessage = null,
                    )
                }
            } catch (error: Throwable) {
                if (mutableState.value.activeHostId == hostId) {
                    mutableState.update {
                        it.copy(
                            connectionPhase = HostConnectionPhase.Failed,
                            capabilities = null,
                            errorMessage = friendlyMessage(error),
                        )
                    }
                }
            }
        }
    }

    private fun recoverRunAfterProcessDeath(snapshot: HostSnapshot) {
        val hostId = savedState.get<String>(KEY_RUN_HOST) ?: return
        val sessionId = savedState.get<String>(KEY_RUN_SESSION) ?: return
        val runId = savedState.get<String>(KEY_RUN_ID) ?: return
        val host = snapshot.hosts.firstOrNull { it.id == hostId }
        if (host == null) {
            clearRunCoordinates()
            return
        }
        logRun("recovering run after process death runId=$runId")
        val run = ActiveRun(
            host = host,
            sessionId = sessionId,
            sessionTitle = null,
            runId = runId,
            recovered = true,
            // The SSE queue died with the process; live output and approval
            // payloads are unrecoverable. Status polling + Stop remain.
            approvalDetailsLost = true,
        )
        mutableState.update { it.copy(activeRun = run) }
        viewModelScope.launch {
            val status = runCatching { gateway.getRunStatus(host, runId) }.getOrNull()
            if (status == null || status.isTerminal) {
                finalizeRun(run)
                return@launch
            }
            if (pollUntilTerminal(run)) finalizeRun(run)
        }
    }

    private fun persistRunCoordinates(run: ActiveRun) {
        savedState[KEY_RUN_HOST] = run.host.id
        savedState[KEY_RUN_SESSION] = run.sessionId
        savedState[KEY_RUN_ID] = run.runId
    }

    private fun clearRunCoordinates() {
        savedState.remove<String>(KEY_RUN_HOST)
        savedState.remove<String>(KEY_RUN_SESSION)
        savedState.remove<String>(KEY_RUN_ID)
    }

    private fun applyLoadedMessages(requestedId: String, page: HermesMessagesPage) {
        mutableState.update { state ->
            // Adopt the resolved id only when the user still displays the
            // Session that was loaded — a background load must not hijack
            // navigation, only re-key.
            if (state.activeSessionId != requestedId) state
            else state.copy(
                activeSessionId = page.sessionId,
                messages = page.messages.mapNotNull(::mapStoredMessage),
            )
        }
    }

    private fun refreshSessionsAndJobs(host: HostProfile) {
        viewModelScope.launch {
            val sessions = runCatching { gateway.listSessions(host) }.getOrNull()
            val jobs = runCatching { gateway.listJobs(host) }.getOrNull()
            if (mutableState.value.activeHostId == host.id) {
                mutableState.update {
                    it.copy(
                        sessions = sessions ?: it.sessions,
                        jobs = jobs ?: it.jobs,
                    )
                }
            }
        }
    }

    private fun mapStoredMessage(message: HermesMessage): ChatUiItem? {
        val id = message.id ?: UUID.randomUUID().toString()
        return when (message.role) {
            "user" -> ChatUiItem.User(id, message.content)
            "assistant" -> ChatUiItem.Assistant(id, message.content)
            "tool" -> ChatUiItem.Tool(id, message.toolName ?: "tool", message.content.take(180), running = false)
            else -> null
        }
    }

    private fun persist(snapshot: HostSnapshot) {
        runCatching { hostStore.save(snapshot) }.onFailure(::showFailure)
    }

    private fun showFailure(error: Throwable) {
        mutableState.update { it.copy(errorMessage = friendlyMessage(error), isSending = false) }
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is HermesApiException -> error.message
        is IOException -> "Could not reach this Hermes host. Check the URL, private network, and API server bind address."
        is IllegalArgumentException -> error.message ?: "Host details are invalid."
        else -> "Hermes connection failed: ${error.message ?: error.javaClass.simpleName}"
    }

    // Structured, id-only run lifecycle trail. android.util.Log is stubbed in
    // JVM unit tests; swallow the stub's exception.
    private fun logRun(message: String) {
        runCatching { android.util.Log.i("HermesRun", message) }
    }

    companion object {
        private const val KEY_RUN_HOST = "run.hostId"
        private const val KEY_RUN_SESSION = "run.sessionId"
        private const val KEY_RUN_ID = "run.runId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                HermesViewModel(
                    gateway = HermesHttpGateway(),
                    hostStore = SecureHostStore(application),
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
