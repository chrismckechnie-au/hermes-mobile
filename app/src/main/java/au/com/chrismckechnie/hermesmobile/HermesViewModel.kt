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

enum class ThemeMode { System, Dark, Light }

interface SettingsStore {
    fun loadThemeMode(): ThemeMode
    fun saveThemeMode(mode: ThemeMode)
    /**
     * All host-owned Runs that still need recovery after Android tears down the
     * process. The singular methods remain as a migration seam for existing
     * stores and tests.
     */
    fun loadRunCheckpoints(): List<RunCheckpoint> = listOfNotNull(loadRunCheckpoint())
    fun saveRunCheckpoints(checkpoints: List<RunCheckpoint>) {
        checkpoints.firstOrNull()?.let(::saveRunCheckpoint) ?: clearRunCheckpoint()
    }
    fun loadRunCheckpoint(): RunCheckpoint? = null
    fun saveRunCheckpoint(checkpoint: RunCheckpoint) = Unit
    fun clearRunCheckpoint() = Unit
    fun loadRunStatus(runId: String): String? = null
    fun saveRunStatus(runId: String, status: String) = Unit
    fun clearRunStatus(runId: String) = Unit
    fun loadNotificationHostIds(): Set<String> = emptySet()
    fun saveNotificationHostIds(hostIds: Set<String>) = Unit
    fun loadOverlayEnabled(): Boolean = false
    fun saveOverlayEnabled(enabled: Boolean) = Unit
    fun loadAttentionItems(): List<AttentionItem> = emptyList()
    fun saveAttentionItems(items: List<AttentionItem>) = Unit
    fun markAttention(item: AttentionItem) {
        saveAttentionItems(loadAttentionItems().filterNot {
            it.hostId == item.hostId && it.sessionId == item.sessionId
        } + item)
    }
    fun clearAttention(hostId: String, sessionId: String) {
        saveAttentionItems(loadAttentionItems().filterNot {
            it.hostId == hostId && it.sessionId == sessionId
        })
    }
    fun loadInstallationId(): String? = null
    fun getOrCreateInstallationId(): String = UUID.randomUUID().toString()
}

private class InMemorySettingsStore : SettingsStore {
    private var mode = ThemeMode.System
    private var checkpoints = emptyList<RunCheckpoint>()
    private val runStatuses = mutableMapOf<String, String>()
    private var notificationHosts = emptySet<String>()
    private var overlay = false
    private var attentionItems = emptyList<AttentionItem>()
    override fun loadThemeMode(): ThemeMode = mode
    override fun saveThemeMode(mode: ThemeMode) {
        this.mode = mode
    }
    override fun loadRunCheckpoints(): List<RunCheckpoint> = checkpoints
    override fun saveRunCheckpoints(checkpoints: List<RunCheckpoint>) { this.checkpoints = checkpoints.distinct() }
    override fun loadRunCheckpoint(): RunCheckpoint? = checkpoints.firstOrNull()
    override fun saveRunCheckpoint(checkpoint: RunCheckpoint) { checkpoints = listOf(checkpoint) }
    override fun clearRunCheckpoint() { checkpoints = emptyList() }
    override fun loadRunStatus(runId: String): String? = runStatuses[runId]
    override fun saveRunStatus(runId: String, status: String) { runStatuses[runId] = status }
    override fun clearRunStatus(runId: String) { runStatuses.remove(runId) }
    override fun loadNotificationHostIds(): Set<String> = notificationHosts
    override fun saveNotificationHostIds(hostIds: Set<String>) { notificationHosts = hostIds }
    override fun loadOverlayEnabled(): Boolean = overlay
    override fun saveOverlayEnabled(enabled: Boolean) { overlay = enabled }
    override fun loadAttentionItems(): List<AttentionItem> = attentionItems
    override fun saveAttentionItems(items: List<AttentionItem>) { attentionItems = items.distinctBy { it.hostId to it.sessionId } }
}

/** Durable, non-secret coordinates used to reconnect to a host-owned run. */
data class RunCheckpoint(
    val hostId: String,
    val sessionId: String,
    val runId: String,
)

/** A durable, non-secret indication that a session has an update to review. */
data class AttentionItem(
    val hostId: String,
    val sessionId: String,
    val title: String,
    val state: String,
)

/** A session identifier is only unique inside its Hermes host. */
data class SessionKey(
    val hostId: String,
    val sessionId: String,
)

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

    /** Collapsible, host-provided progress summaries for the active Run. */
    data class Reasoning(
        override val id: String,
        val updates: List<String>,
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
    val editingHostId: String? = null,
    val connectionPhase: HostConnectionPhase = HostConnectionPhase.NoHost,
    val capabilities: HermesCapabilities? = null,
    val sessions: List<HermesSession> = emptyList(),
    val sessionsHasMore: Boolean = false,
    val activeSessionId: String? = null,
    val messages: List<ChatUiItem> = emptyList(),
    val jobs: List<HermesJob> = emptyList(),
    val skills: List<HermesSkill> = emptyList(),
    val models: List<String> = emptyList(),
    val modelSelections: Map<SessionKey, String> = emptyMap(),
    val reasoningSelections: Map<SessionKey, String> = emptyMap(),
    val composerDrafts: Map<SessionKey, String> = emptyMap(),
    val newSessionDrafts: Map<String, String> = emptyMap(),
    val sendingSessions: Set<SessionKey> = emptySet(),
    val creatingRunHosts: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val activeRuns: Map<SessionKey, ActiveRun> = emptyMap(),
    val queuedInterrupts: Map<SessionKey, String> = emptyMap(),
    val unknownOutcomes: Map<SessionKey, UnknownOutcome> = emptyMap(),
    val sessionActionsFor: String? = null,
    val confirmDeleteSessionId: String? = null,
    val errorMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val notificationHostIds: Set<String> = emptySet(),
    val overlayEnabled: Boolean = false,
) {
    val activeHost: HostProfile? get() = hosts.firstOrNull { it.id == activeHostId }
    val activeSession: HermesSession? get() = sessions.firstOrNull { it.id == activeSessionId }
    val editingHost: HostProfile? get() = hosts.firstOrNull { it.id == editingHostId }
    val activeSessionKey: SessionKey? get() = activeHostId?.let { hostId ->
        activeSessionId?.let { sessionId -> SessionKey(hostId, sessionId) }
    }
    val selectedModel: String? get() = activeSessionKey?.let(modelSelections::get)
    val selectedReasoningEffort: String? get() = activeSessionKey?.let(reasoningSelections::get)
    val composerText: String get() = activeSessionKey?.let { composerDrafts[it] }
        ?: activeHostId?.let { newSessionDrafts[it] }
        .orEmpty()
    val isSending: Boolean get() = activeSessionKey?.let(sendingSessions::contains) == true ||
        (activeSessionKey == null && activeHostId in creatingRunHosts)
    val activeRun: ActiveRun? get() = activeSessionKey?.let(activeRuns::get)
    val unknownOutcome: UnknownOutcome? get() = activeSessionKey?.let(unknownOutcomes::get)
    val otherActiveRuns: List<ActiveRun>
        get() = activeRuns.filterKeys { it != activeSessionKey }.values.toList()

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
        get() = otherActiveRuns.isNotEmpty() || (activeRun != null && screen != DeckScreen.Chat)

    fun isSessionBusy(hostId: String, sessionId: String): Boolean =
        SessionKey(hostId, sessionId) in activeRuns

    fun hasActiveRunOnHost(hostId: String): Boolean = activeRuns.keys.any { it.hostId == hostId }

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
    private val settingsStore: SettingsStore = InMemorySettingsStore(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(HermesUiState())
    val state: StateFlow<HermesUiState> = mutableState.asStateFlow()

    private fun ActiveRun.key(): SessionKey = SessionKey(host.id, sessionId)

    private fun HermesUiState.withDraft(key: SessionKey?, value: String): HermesUiState {
        val trimmed = value.take(MAX_COMPOSER_LENGTH)
        if (key != null) return copy(composerDrafts = composerDrafts + (key to trimmed))
        val hostId = activeHostId ?: return this
        return copy(newSessionDrafts = newSessionDrafts + (hostId to trimmed))
    }

    private fun HermesUiState.withModelSelection(key: SessionKey?, model: String?): HermesUiState {
        if (key == null) return this
        val nextModels = model?.let { modelSelections + (key to it) } ?: modelSelections - key
        return copy(modelSelections = nextModels)
    }

    private fun HermesUiState.withReasoningSelection(key: SessionKey?, reasoning: String?): HermesUiState {
        if (key == null) return this
        val nextReasoning = reasoning?.let { reasoningSelections + (key to it) } ?: reasoningSelections - key
        return copy(reasoningSelections = nextReasoning)
    }

    private fun HermesUiState.withSending(key: SessionKey, sending: Boolean): HermesUiState = copy(
        sendingSessions = if (sending) sendingSessions + key else sendingSessions - key,
    )

    private fun HermesUiState.withNewSessionSending(hostId: String, sending: Boolean): HermesUiState = copy(
        creatingRunHosts = if (sending) creatingRunHosts + hostId else creatingRunHosts - hostId,
    )

    private fun HermesUiState.withRun(key: SessionKey, run: ActiveRun?): HermesUiState = copy(
        activeRuns = if (run == null) activeRuns - key else activeRuns + (key to run),
    )

    private fun HermesUiState.withUnknownOutcome(key: SessionKey, outcome: UnknownOutcome?): HermesUiState = copy(
        unknownOutcomes = if (outcome == null) unknownOutcomes - key else unknownOutcomes + (key to outcome),
    )

    private fun HermesUiState.rekeySession(oldKey: SessionKey, newKey: SessionKey): HermesUiState {
        if (oldKey == newKey) return this
        fun <T> Map<SessionKey, T>.move(value: (T) -> T = { it }): Map<SessionKey, T> =
            this[oldKey]?.let { (this - oldKey) + (newKey to value(it)) } ?: this
        return copy(
            activeSessionId = if (activeHostId == oldKey.hostId && activeSessionId == oldKey.sessionId) newKey.sessionId else activeSessionId,
            modelSelections = modelSelections.move(),
            reasoningSelections = reasoningSelections.move(),
            composerDrafts = composerDrafts.move(),
            sendingSessions = if (oldKey in sendingSessions) (sendingSessions - oldKey) + newKey else sendingSessions,
            activeRuns = activeRuns.move { run -> run.copy(sessionId = newKey.sessionId) },
            queuedInterrupts = queuedInterrupts.move(),
            unknownOutcomes = unknownOutcomes.move { pending -> pending.copy(sessionId = newKey.sessionId) },
        )
    }

    init {
        val loadResult = hostStore.load()
        val snapshot = loadResult.snapshot
        val selected = snapshot.selectedHostId?.takeIf { id -> snapshot.hosts.any { it.id == id } }
        mutableState.value = HermesUiState(
            hosts = snapshot.hosts,
            activeHostId = selected,
            showHostPicker = snapshot.hosts.isEmpty(),
            connectionPhase = if (selected == null) HostConnectionPhase.NoHost else HostConnectionPhase.Connecting,
            themeMode = settingsStore.loadThemeMode(),
            notificationHostIds = settingsStore.loadNotificationHostIds().intersect(snapshot.hosts.map { it.id }.toSet()),
            overlayEnabled = settingsStore.loadOverlayEnabled(),
            errorMessage = if (loadResult.unlockFailed) {
                "Saved hosts could not be unlocked on this device (Keystore key changed). Re-add your host and API key."
            } else null,
        )
        if (selected != null) connect(selected)
        recoverRunAfterProcessDeath(snapshot)
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsStore.saveThemeMode(mode)
        mutableState.update { it.copy(themeMode = mode) }
    }

    fun setHostNotificationsEnabled(hostId: String, enabled: Boolean) {
        val ids = mutableState.value.notificationHostIds.toMutableSet().apply {
            if (enabled) add(hostId) else remove(hostId)
        }
        settingsStore.saveNotificationHostIds(ids)
        mutableState.update { it.copy(notificationHostIds = ids) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        settingsStore.saveOverlayEnabled(enabled)
        mutableState.update { it.copy(overlayEnabled = enabled) }
    }

    fun selectScreen(screen: DeckScreen) {
        mutableState.update { it.copy(screen = screen) }
    }

    fun openSessionFromNotification(hostId: String, sessionId: String) {
        val host = mutableState.value.hosts.firstOrNull { it.id == hostId } ?: return
        settingsStore.clearAttention(hostId, sessionId)
        if (mutableState.value.activeHostId != hostId) {
            mutableState.update {
                it.copy(activeHostId = hostId, screen = DeckScreen.Chat, activeSessionId = sessionId, messages = emptyList())
            }
            connect(hostId)
        } else {
            mutableState.update { it.copy(screen = DeckScreen.Chat, activeSessionId = sessionId) }
        }
        viewModelScope.launch {
            runCatching { gateway.loadMessages(host, sessionId) }
                .onSuccess { applyLoadedMessages(sessionId, it) }
                .onFailure(::showFailure)
        }
    }

    fun setComposerText(value: String) {
        mutableState.update { state -> state.withDraft(state.activeSessionKey, value) }
    }

    fun showHostPicker() {
        mutableState.update { it.copy(showHostPicker = true, editingHostId = null, errorMessage = null) }
    }

    fun editHost(hostId: String) {
        mutableState.update { it.copy(showHostPicker = true, editingHostId = hostId, errorMessage = null) }
    }

    fun hideHostPicker() {
        if (mutableState.value.hosts.isNotEmpty()) {
            mutableState.update { it.copy(showHostPicker = false, editingHostId = null, errorMessage = null) }
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
        if (existingId != null && mutableState.value.hasActiveRunOnHost(existingId)) {
            mutableState.update { it.copy(errorMessage = "A run is active on this host. Stop it before editing the host.") }
            return
        }
        val existing = existingId?.let { id -> mutableState.value.hosts.firstOrNull { it.id == id } }
        val effectiveKey = if (apiKey.isBlank() && existing != null) existing.apiKey else apiKey
        val profile = runCatching {
            HostProfile(
                id = existingId ?: UUID.randomUUID().toString(),
                name = name,
                baseUrl = baseUrl,
                apiKey = effectiveKey,
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
                editingHostId = null,
                connectionPhase = HostConnectionPhase.Connecting,
                errorMessage = null,
            )
        }
        connect(profile.id)
    }

    fun selectHost(hostId: String) {
        if (mutableState.value.hosts.none { it.id == hostId }) return
        if (mutableState.value.activeHostId == hostId) {
            mutableState.update { it.copy(showHostPicker = false, editingHostId = null, errorMessage = null) }
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
                sessionsHasMore = false,
                activeSessionId = null,
                messages = emptyList(),
                jobs = emptyList(),
                skills = emptyList(),
                models = emptyList(),
                errorMessage = null,
            )
        }
        connect(hostId)
    }

    fun deleteHost(hostId: String) {
        if (mutableState.value.hasActiveRunOnHost(hostId)) {
            mutableState.update { it.copy(errorMessage = "A run is active on this host. Stop it before deleting the host.") }
            return
        }
        val removedHost = mutableState.value.hosts.firstOrNull { it.id == hostId }
        if (removedHost != null && hostId in mutableState.value.notificationHostIds) {
            settingsStore.loadInstallationId()?.let { installationId ->
                viewModelScope.launch {
                    runCatching { gateway.unregisterMobileDevice(removedHost, installationId) }
                }
            }
        }
        val hosts = mutableState.value.hosts.filterNot { it.id == hostId }
        val notificationHostIds = mutableState.value.notificationHostIds - hostId
        settingsStore.saveNotificationHostIds(notificationHostIds)
        if (notificationHostIds.isEmpty()) settingsStore.saveOverlayEnabled(false)
        val selected = mutableState.value.activeHostId?.takeIf { it != hostId && hosts.any { host -> host.id == it } }
            ?: hosts.firstOrNull()?.id
        persist(HostSnapshot(hosts, selected))
        val activeHostChanged = selected != mutableState.value.activeHostId
        mutableState.update { state ->
            if (!activeHostChanged) {
                state.copy(
                    hosts = hosts,
                    editingHostId = state.editingHostId?.takeIf { it != hostId },
                    showHostPicker = hosts.isEmpty(),
                    notificationHostIds = notificationHostIds,
                )
            } else {
                state.copy(
                    hosts = hosts,
                    activeHostId = selected,
                    showHostPicker = hosts.isEmpty(),
                    connectionPhase = if (selected == null) HostConnectionPhase.NoHost else HostConnectionPhase.Connecting,
                    capabilities = null,
                    sessions = emptyList(),
                    sessionsHasMore = false,
                    activeSessionId = null,
                    messages = emptyList(),
                    jobs = emptyList(),
                    skills = emptyList(),
                    models = emptyList(),
                    notificationHostIds = notificationHostIds,
                    overlayEnabled = state.overlayEnabled && notificationHostIds.isNotEmpty(),
                )
            }
        }
        if (activeHostChanged && selected != null) connect(selected)
    }

    fun retryConnection() {
        mutableState.value.activeHostId?.let(::connect)
    }

    fun refresh() {
        val host = mutableState.value.activeHost ?: return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val sessions = runCatching { gateway.listSessions(host) }.getOrNull()
            val jobs = runCatching { gateway.listJobs(host) }.getOrNull()
            if (mutableState.value.activeHostId == host.id) {
                mutableState.update {
                    it.copy(
                        sessions = sessions?.sessions ?: it.sessions,
                        sessionsHasMore = sessions?.hasMore ?: it.sessionsHasMore,
                        jobs = jobs ?: it.jobs,
                        isRefreshing = false,
                    )
                }
            }
        }
    }

    fun loadMoreSessions() {
        val snapshot = mutableState.value
        val host = snapshot.activeHost ?: return
        if (!snapshot.sessionsHasMore || snapshot.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            runCatching { gateway.listSessions(host, offset = snapshot.sessions.size) }
                .onSuccess { page ->
                    if (mutableState.value.activeHostId == host.id) {
                        mutableState.update {
                            it.copy(
                                sessions = (it.sessions + page.sessions).distinctBy(HermesSession::id),
                                sessionsHasMore = page.hasMore,
                                isRefreshing = false,
                            )
                        }
                    }
                }
                .onFailure {
                    mutableState.update { state -> state.copy(isRefreshing = false) }
                    showFailure(it)
                }
        }
    }

    fun createSession() {
        val host = mutableState.value.activeHost ?: return
        if (mutableState.value.connectionPhase != HostConnectionPhase.Connected) return
        viewModelScope.launch {
            runCatching { gateway.createSession(host, null) }
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
        settingsStore.clearAttention(host.id, sessionId)
        mutableState.update { it.copy(activeSessionId = sessionId, screen = DeckScreen.Chat, messages = emptyList(), errorMessage = null) }
        viewModelScope.launch {
            runCatching { gateway.loadMessages(host, sessionId) }
                .onSuccess { page -> applyLoadedMessages(requestedId = sessionId, page = page) }
                .onFailure(::showFailure)
        }
    }

    /** null selects the Host default; unknown aliases fall back host-side anyway. */
    fun selectModel(model: String?) {
        mutableState.update { state ->
            state.withModelSelection(state.activeSessionKey, model?.takeIf { it in state.models })
        }
    }

    /** null selects the Host default reasoning effort. */
    fun selectReasoningEffort(effort: String?) {
        mutableState.update { state ->
            state.withReasoningSelection(state.activeSessionKey, effort?.takeIf { it in REASONING_EFFORTS })
        }
    }

    fun toggleJob(job: HermesJob) {
        val host = mutableState.value.activeHost ?: return
        val enabled = !job.enabled
        mutableState.update { state ->
            state.copy(jobs = state.jobs.map { if (it.id == job.id) it.copy(enabled = enabled) else it })
        }
        viewModelScope.launch {
            runCatching { gateway.setJobEnabled(host, job.id, enabled) }
                .onFailure { error ->
                    mutableState.update { state ->
                        state.copy(jobs = state.jobs.map { if (it.id == job.id) it.copy(enabled = job.enabled) else it })
                    }
                    showFailure(error)
                }
        }
    }

    fun runJobNow(jobId: String) {
        val host = mutableState.value.activeHost ?: return
        viewModelScope.launch {
            runCatching { gateway.runJob(host, jobId) }.onFailure(::showFailure)
        }
    }

    fun returnToRunSession() {
        val run = mutableState.value.otherActiveRuns.firstOrNull() ?: mutableState.value.activeRun ?: return
        if (mutableState.value.activeHostId == run.host.id && mutableState.value.activeSessionId == run.sessionId) {
            mutableState.update { it.copy(screen = DeckScreen.Chat) }
        } else {
            openSessionFromNotification(run.host.id, run.sessionId)
        }
    }

    // ------------------------------------------------------------------
    // Sending and slash dispatch
    // ------------------------------------------------------------------

    fun sendMessage() {
        val text = mutableState.value.composerText.trim()
        if (text.isBlank()) return
        if (text.startsWith("/") && dispatchLocalCommand(text)) return
        mutableState.value.activeRun?.let { run ->
            interruptAndSubmit(run, text)
            return
        }
        submitChat(text)
    }

    private fun interruptAndSubmit(run: ActiveRun, text: String) {
        val key = run.key()
        mutableState.update { state ->
            state.withDraft(key, "").copy(
                queuedInterrupts = state.queuedInterrupts + (key to text),
                errorMessage = null,
            )
        }
        if (!run.stopping) requestStop(run)
    }

    fun applySuggestion(suggestion: SlashSuggestion) {
        when (suggestion.kind) {
            // Typed dispatch: a Skill row never re-parses slash text, so a
            // skill named like a reserved command stays reachable.
            SlashKind.Skill -> mutableState.update { state ->
                state.withDraft(state.activeSessionKey, "Use the ${suggestion.name} skill: ")
            }
            SlashKind.Command -> when (suggestion.name) {
                "rename" -> mutableState.update { state -> state.withDraft(state.activeSessionKey, "/rename ") }
                else -> {
                    mutableState.update { state -> state.withDraft(state.activeSessionKey, "") }
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

        mutableState.update { state -> state.withDraft(state.activeSessionKey, "") }
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
            mutableState.update { it.copy(errorMessage = "This session is still running. You can keep drafting and send when it finishes.") }
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

        val initialKey = snapshot.activeSessionKey
        mutableState.update { state ->
            if (initialKey == null) state.withNewSessionSending(host.id, true)
                .copy(newSessionDrafts = state.newSessionDrafts - host.id, errorMessage = null)
            else state.withDraft(initialKey, "").withSending(initialKey, true).copy(errorMessage = null)
        }

        viewModelScope.launch {
            try {
                val session = snapshot.activeSessionId?.let { id -> snapshot.sessions.firstOrNull { it.id == id } }
                    ?: gateway.createSession(host, text.take(44)).also { created ->
                        mutableState.update {
                            it.copy(sessions = listOf(created) + it.sessions, activeSessionId = created.id)
                        }
                    }

                var runKey = SessionKey(host.id, session.id)
                mutableState.update { state ->
                    state.withNewSessionSending(host.id, false).withSending(runKey, true)
                }

                // Pre-submit refresh: narrows multi-writer staleness, resolves a
                // rotated session, and is the exact history the run executes on.
                val page = gateway.loadMessages(host, session.id)
                applyLoadedMessages(requestedId = session.id, page = page)
                val resolvedKey = SessionKey(host.id, page.sessionId)
                if (resolvedKey != runKey) {
                    mutableState.update { state -> state.rekeySession(runKey, resolvedKey) }
                    runKey = resolvedKey
                }

                val current = mutableState.value
                val runId = try {
                    gateway.submitRun(
                        host,
                        page.sessionId,
                        text,
                        page.messages,
                        current.modelSelections[runKey],
                        current.reasoningSelections[runKey].takeIf { capabilities.supportsReasoningEffort },
                    )
                } catch (error: HermesApiException) {
                    // The host answered: the run was not accepted.
                    showFailure(error)
                    mutableState.update { state -> state.withSending(runKey, false).withNewSessionSending(host.id, false) }
                    return@launch
                } catch (error: Throwable) {
                    // Response lost — the host may be executing the turn.
                    logRun("submit outcome unknown session=${page.sessionId}")
                    mutableState.update { state ->
                        state.withSending(runKey, false)
                            .withNewSessionSending(host.id, false)
                            .withUnknownOutcome(runKey, UnknownOutcome(page.sessionId, page.messages.size, text))
                    }
                    watchUnknownOutcome(host, runKey)
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
                updateRunStatus(run.runId, "Starting task…")
                logRun("run started runId=$runId sessionId=${run.sessionId}")
                mutableState.update { state -> state.withSending(runKey, false).withRun(runKey, run) }
                driveRun(run)
            } catch (error: Throwable) {
                showFailure(error)
                mutableState.update { state ->
                    initialKey?.let { state.withSending(it, false) }?.withNewSessionSending(host.id, false)
                        ?: state.withNewSessionSending(host.id, false)
                }
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
            if (!pollUntilTerminal(run)) return
        }
        finalizeRun(run)
    }

    /** Returns true for terminal events. Ignores events for a Run that is no longer active. */
    private fun handleRunEvent(run: ActiveRun, event: HermesRunEvent): Boolean {
        var terminal = false
        val key = run.key()
        runStatusText(event)?.let { updateRunStatus(run.runId, it) }
        mutableState.update { state ->
            val current = state.activeRuns[key]
            if (current == null || current.runId != run.runId) return@update state
            when (event) {
                is HermesRunEvent.MessageDelta -> state.copy(
                    activeRuns = state.activeRuns + (key to current.copy(tail = current.tail.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == current.assistantId) item.copy(text = item.text + event.delta) else item
                    }))
                )
                is HermesRunEvent.ReasoningAvailable -> {
                    val text = event.text.trim().take(MAX_REASONING_UPDATE_LENGTH)
                    if (text.isBlank()) state else state.withRun(key, current.copy(tail = upsertReasoning(current, text)))
                }
                is HermesRunEvent.ToolStarted -> state.withRun(key, current.copy(tail = insertBeforeAssistant(current, ChatUiItem.Tool(
                        id = "${current.runId}:${event.tool}:${current.tail.size}",
                        name = event.tool,
                        preview = event.preview,
                        running = true,
                    ))))
                is HermesRunEvent.ToolCompleted -> state.withRun(key, current.copy(tail = current.tail.map { item ->
                        if (item is ChatUiItem.Tool && item.name == event.tool && item.running) {
                            item.copy(running = false, failed = event.failed)
                        } else item
                    }))
                is HermesRunEvent.ApprovalRequested -> state.withRun(key,
                    // One actionable card per Run: a second request keeps the
                    // first card until it resolves (host queue is FIFO).
                    if (current.awaitingApproval) current
                    else current.copy(awaitingApproval = true, approvalCommand = event.command, approvalDetailsLost = false)
                )
                is HermesRunEvent.ApprovalResponded -> state.withRun(key, current.copy(awaitingApproval = false, approvalCommand = null))
                is HermesRunEvent.Completed -> {
                    terminal = true
                    state.withRun(key, current.copy(tail = current.tail.map { item ->
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

    private fun runStatusText(event: HermesRunEvent): String? = when (event) {
        is HermesRunEvent.ReasoningAvailable -> event.text
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_OVERLAY_STATUS_LENGTH)
            .takeIf { it.isNotBlank() }
        is HermesRunEvent.ToolStarted -> "Using ${event.tool.replace('_', ' ').replace('-', ' ')}…"
        is HermesRunEvent.ToolCompleted -> if (event.failed) "A tool needs attention…" else "Continuing after ${event.tool.replace('_', ' ')}…"
        is HermesRunEvent.ApprovalRequested -> "Waiting for your approval"
        is HermesRunEvent.ApprovalResponded -> "Continuing after approval…"
        is HermesRunEvent.MessageDelta -> "Writing the response…"
        is HermesRunEvent.Completed -> "Finishing the task…"
        is HermesRunEvent.Failed -> "The task hit an issue"
        HermesRunEvent.Cancelled -> "The task was stopped"
    }

    private fun updateRunStatus(runId: String, status: String) {
        if (settingsStore.loadRunStatus(runId) != status) settingsStore.saveRunStatus(runId, status)
    }

    private fun insertBeforeAssistant(run: ActiveRun, item: ChatUiItem): List<ChatUiItem> {
        val index = run.tail.indexOfLast { it is ChatUiItem.Assistant && it.id == run.assistantId }
        return if (index < 0) run.tail + item
        else run.tail.subList(0, index) + item + run.tail.subList(index, run.tail.size)
    }

    private fun upsertReasoning(run: ActiveRun, text: String): List<ChatUiItem> {
        val id = "reasoning:${run.runId}"
        val existing = run.tail.filterIsInstance<ChatUiItem.Reasoning>().firstOrNull { it.id == id }
        if (existing == null) {
            return insertBeforeAssistant(run, ChatUiItem.Reasoning(id, listOf(text)))
        }
        if (existing.updates.lastOrNull() == text) return run.tail
        return run.tail.map { item ->
            if (item.id == id && item is ChatUiItem.Reasoning) {
                item.copy(updates = (item.updates + text).takeLast(MAX_REASONING_UPDATES))
            } else item
        }
    }

    /** Capped-backoff status polling. Returns true when the Run reached a terminal state. */
    private suspend fun pollUntilTerminal(run: ActiveRun): Boolean {
        var delayMs = 2_000L
        val key = run.key()
        // The run belongs to the host, not the Activity. Keep reconciling when
        // Android backgrounds the UI; durable coordinates restore this loop
        // after process death.
        while (mutableState.value.activeRuns[key]?.runId == run.runId) {
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
            val result = runCatching { gateway.getRunStatus(run.host, run.runId) }
            if ((result.exceptionOrNull() as? HermesApiException)?.statusCode == 404) return true
            val status = result.getOrNull() ?: continue
            logRun("run status runId=${run.runId} status=${status.status}")
            if (status.isTerminal) return true
            if (status.isWaitingForApproval) {
                // The SSE queue died with the approval payload; only the host
                // (or Stop) can resolve this safely.
                mutableState.update { state ->
                    val current = state.activeRuns[key]
                    if (current?.runId != run.runId) state
                    else state.withRun(key, current.copy(awaitingApproval = true, approvalDetailsLost = true))
                }
            }
        }
        return false
    }

    private suspend fun finalizeRun(run: ActiveRun) {
        logRun("run finalize runId=${run.runId}")
        val key = run.key()
        clearRunCoordinates(run)
        val page = runCatching { gateway.loadMessages(run.host, run.sessionId) }.getOrNull()
        var queuedText: String? = null
        mutableState.update { state ->
            val cleared = if (state.activeRuns[key]?.runId == run.runId) {
                queuedText = state.queuedInterrupts[key]
                state.withRun(key, null)
                    .withSending(key, false)
                    .copy(queuedInterrupts = state.queuedInterrupts - key)
            } else state
            if (page != null && cleared.activeHostId == run.host.id && cleared.activeSessionId == run.sessionId) {
                cleared.copy(
                    activeSessionId = page.sessionId,
                    messages = page.messages.mapNotNull(::mapStoredMessage),
                )
            } else cleared
        }
        refreshSessionsAndJobs(run.host)
        queuedText?.let { text ->
            if (mutableState.value.activeSessionKey == key) submitChat(text)
            else mutableState.update { state -> state.withDraft(key, text) }
        }
    }

    fun respondApproval(choice: String) {
        val run = mutableState.value.activeRun ?: return
        val key = run.key()
        viewModelScope.launch {
            logRun("approval response runId=${run.runId} choice=$choice")
            runCatching { gateway.respondApproval(run.host, run.runId, choice) }
                .onFailure { error ->
                    // 409 = already resolved (possibly by another client); the
                    // approval.responded event clears the card either way.
                    if ((error as? HermesApiException)?.statusCode != 409) showFailure(error)
                }
            mutableState.update { state ->
                val current = state.activeRuns[key]
                if (current?.runId != run.runId) state
                else state.withRun(key, current.copy(awaitingApproval = false, approvalCommand = null))
            }
        }
    }

    fun respondToApproval(itemId: String, approve: Boolean) {
        if (itemId.isNotBlank()) respondApproval(if (approve) "once" else "deny")
    }

    fun stopActiveRun() {
        val run = mutableState.value.activeRun
        if (run == null) {
            mutableState.update { it.copy(errorMessage = "No run is active.") }
            return
        }
        requestStop(run)
    }

    private fun requestStop(run: ActiveRun) {
        val key = run.key()
        val current = mutableState.value.activeRuns[key]
        if (current?.runId != run.runId || current.stopping) return
        mutableState.update { state ->
            val active = state.activeRuns[key]
            if (active?.runId != run.runId || active.stopping) state
            else state.withRun(key, active.copy(stopping = true))
        }
        viewModelScope.launch {
            logRun("stop requested runId=${run.runId}")
            runCatching { gateway.stopRun(run.host, run.runId) }.onFailure { error ->
                showFailure(error)
                mutableState.update { state ->
                    val current = state.activeRuns[key]
                    if (current?.runId != run.runId) state else state.withRun(key, current.copy(stopping = false))
                }
            }
            // Stop is not terminal ("stopping"): the live stream/poll drives to
            // run.cancelled. If the stream is already gone, poll here.
            if (mutableState.value.activeRuns[key]?.runId == run.runId && run.approvalDetailsLost) {
                if (pollUntilTerminal(run)) finalizeRun(run)
            }
        }
    }

    fun cancelRun() = stopActiveRun()

    // ------------------------------------------------------------------
    // Unknown submission outcome
    // ------------------------------------------------------------------

    private fun watchUnknownOutcome(host: HostProfile, key: SessionKey) {
        viewModelScope.launch {
            var delayMs = 2_000L
            var waited = 0L
            while (waited < 120_000L) {
                val pending = mutableState.value.unknownOutcomes[key] ?: return@launch
                delay(delayMs)
                waited += delayMs
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
                val page = runCatching { gateway.loadMessages(host, pending.sessionId) }.getOrNull() ?: continue
                // Evidence = new messages beyond the pre-submit baseline; an
                // older identical text proves nothing.
                if (page.messages.size > pending.baselineCount) {
                    mutableState.update { state ->
                        state.unknownOutcomes[key]?.let { state.withUnknownOutcome(key, it.copy(evidence = true)) } ?: state
                    }
                    return@launch
                }
            }
            mutableState.update { state ->
                state.unknownOutcomes[key]?.let { state.withUnknownOutcome(key, it.copy(timedOut = true)) } ?: state
            }
        }
    }

    /** Explicit user acknowledgement — the only way the unknown-outcome lock releases. */
    fun acknowledgeUnknownOutcome() {
        val pending = mutableState.value.unknownOutcome ?: return
        val host = mutableState.value.activeHost
        val key = mutableState.value.activeSessionKey ?: return
        mutableState.update { state -> state.withUnknownOutcome(key, null) }
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
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) {
            mutableState.update { it.copy(errorMessage = "Session name cannot be empty.") }
            return
        }
        mutableState.update { it.copy(sessionActionsFor = null) }
        viewModelScope.launch {
            runCatching { gateway.renameSession(host, sessionId, trimmedTitle) }
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

    fun deleteSession(sessionId: String) {
        requestDeleteSession(sessionId)
        confirmDeleteSession()
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
        val hostId = mutableState.value.activeHostId
        if (hostId != null && mutableState.value.isSessionBusy(hostId, sessionId)) {
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
                val (sessionPage, jobs, extras) = coroutineScope {
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
                        sessions = sessionPage.sessions,
                        sessionsHasMore = sessionPage.hasMore,
                        jobs = jobs,
                        skills = skills,
                        models = models,
                        modelSelections = it.modelSelections.filter { (key, model) -> key.hostId != hostId || model in models },
                        activeSessionId = it.activeSessionId?.takeIf { id -> sessionPage.sessions.any { session -> session.id == id } },
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
        val legacy = savedState.get<String>(KEY_RUN_HOST)?.let { hostId ->
            val sessionId = savedState.get<String>(KEY_RUN_SESSION)
            val runId = savedState.get<String>(KEY_RUN_ID)
            if (!sessionId.isNullOrBlank() && !runId.isNullOrBlank()) RunCheckpoint(hostId, sessionId, runId) else null
        }
        val checkpoints = (settingsStore.loadRunCheckpoints() + listOfNotNull(legacy))
            .distinctBy(RunCheckpoint::runId)
        if (checkpoints.isEmpty()) return

        checkpoints.forEach { checkpoint ->
            val host = snapshot.hosts.firstOrNull { it.id == checkpoint.hostId }
            if (host == null) {
                clearRunCoordinates(checkpoint)
                return@forEach
            }
            val run = ActiveRun(
                host = host,
                sessionId = checkpoint.sessionId,
                sessionTitle = null,
                runId = checkpoint.runId,
                recovered = true,
                // The SSE queue died with the process; live output and approval
                // payloads are unrecoverable. Status polling + Stop remain.
                approvalDetailsLost = true,
            )
            val key = run.key()
            logRun("recovering run after process death runId=${run.runId}")
            mutableState.update { state -> state.withRun(key, run) }
            if (settingsStore.loadRunStatus(run.runId).isNullOrBlank()) {
                updateRunStatus(run.runId, "Reconnected to the active task…")
            }
            viewModelScope.launch {
                val result = runCatching { gateway.getRunStatus(host, run.runId) }
                val status = result.getOrNull()
                if (status?.isTerminal == true || (result.exceptionOrNull() as? HermesApiException)?.statusCode == 404) {
                    finalizeRun(run)
                    return@launch
                }
                if (pollUntilTerminal(run)) finalizeRun(run)
            }
        }
    }

    private fun persistRunCoordinates(run: ActiveRun) {
        val checkpoints = (mutableState.value.activeRuns.values + run)
            .map { active -> RunCheckpoint(active.host.id, active.sessionId, active.runId) }
            .distinctBy(RunCheckpoint::runId)
        settingsStore.saveRunCheckpoints(checkpoints)
    }

    private fun clearRunCoordinates(run: ActiveRun) {
        clearRunCoordinates(RunCheckpoint(run.host.id, run.sessionId, run.runId))
    }

    private fun clearRunCoordinates(checkpoint: RunCheckpoint) {
        val remaining = settingsStore.loadRunCheckpoints().filterNot { it.runId == checkpoint.runId }
        settingsStore.saveRunCheckpoints(remaining)
        settingsStore.clearRunStatus(checkpoint.runId)
        if (savedState.get<String>(KEY_RUN_ID) == checkpoint.runId) {
            savedState.remove<String>(KEY_RUN_HOST)
            savedState.remove<String>(KEY_RUN_SESSION)
            savedState.remove<String>(KEY_RUN_ID)
        }
    }

    private fun applyLoadedMessages(requestedId: String, page: HermesMessagesPage) {
        val hostId = mutableState.value.activeHostId
        mutableState.update { state ->
            val requestedKey = hostId?.let { SessionKey(it, requestedId) }
            val resolvedKey = hostId?.let { SessionKey(it, page.sessionId) }
            val rekeyed = if (requestedKey != null && resolvedKey != null) state.rekeySession(requestedKey, resolvedKey) else state
            // Adopt the resolved id only when the user still displays the
            // Session that was loaded — a background load must not hijack
            // navigation, only re-key.
            if (rekeyed.activeHostId != hostId || rekeyed.activeSessionId != page.sessionId) rekeyed
            else rekeyed.copy(
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
                        sessions = sessions?.sessions ?: it.sessions,
                        sessionsHasMore = sessions?.hasMore ?: it.sessionsHasMore,
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
        mutableState.update { it.copy(errorMessage = friendlyMessage(error)) }
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
        private const val MAX_REASONING_UPDATES = 12
        private const val MAX_REASONING_UPDATE_LENGTH = 8_000
        private const val MAX_OVERLAY_STATUS_LENGTH = 180
        private const val MAX_COMPOSER_LENGTH = 8_000
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
                    settingsStore = PreferencesSettingsStore(application),
                )
            }
        }
    }
}
