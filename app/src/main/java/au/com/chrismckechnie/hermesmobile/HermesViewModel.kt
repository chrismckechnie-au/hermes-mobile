package au.com.chrismckechnie.hermesmobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

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
        val approvalId: String,
        val runId: String?,
        val toolName: String?,
        val message: String?,
        /** null = pending, true = approved, false = denied */
        val decision: Boolean? = null,
    ) : ChatUiItem
}

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
    val composerText: String = "",
    val isSending: Boolean = false,
    val isRefreshing: Boolean = false,
    val activeRunId: String? = null,
    val errorMessage: String? = null,
) {
    val activeHost: HostProfile? get() = hosts.firstOrNull { it.id == activeHostId }
    val activeSession: HermesSession? get() = sessions.firstOrNull { it.id == activeSessionId }
    val editingHost: HostProfile? get() = hosts.firstOrNull { it.id == editingHostId }
}

class HermesViewModel(
    private val gateway: HermesGateway,
    private val hostStore: HostStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HermesUiState())
    val state: StateFlow<HermesUiState> = mutableState.asStateFlow()

    private var sendJob: Job? = null

    init {
        val result = hostStore.load()
        val snapshot = result.snapshot
        val selected = snapshot.selectedHostId?.takeIf { id -> snapshot.hosts.any { it.id == id } }
        mutableState.value = HermesUiState(
            hosts = snapshot.hosts,
            activeHostId = selected,
            showHostPicker = snapshot.hosts.isEmpty(),
            connectionPhase = if (selected == null) HostConnectionPhase.NoHost else HostConnectionPhase.Connecting,
            errorMessage = if (result.unlockFailed) {
                "Saved hosts could not be unlocked on this device (Keystore key changed). Re-add your host and API key."
            } else null,
        )
        if (selected != null) connect(selected)
    }

    fun selectScreen(screen: DeckScreen) {
        mutableState.update { it.copy(screen = screen) }
    }

    fun setComposerText(value: String) {
        mutableState.update { it.copy(composerText = value.take(8_000)) }
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
        val existing = existingId?.let { id -> mutableState.value.hosts.firstOrNull { it.id == id } }
        // Editing keeps the stored key when the field is left blank (keys are never re-shown).
        val effectiveKey = if (apiKey.isBlank() && existing != null) existing.apiKey else apiKey
        val profile = runCatching {
            HostProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
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
        persist(HostSnapshot(mutableState.value.hosts, hostId))
        mutableState.update {
            it.copy(
                activeHostId = hostId,
                showHostPicker = false,
                editingHostId = null,
                connectionPhase = HostConnectionPhase.Connecting,
                capabilities = null,
                sessions = emptyList(),
                sessionsHasMore = false,
                activeSessionId = null,
                messages = emptyList(),
                jobs = emptyList(),
                errorMessage = null,
            )
        }
        connect(hostId)
    }

    fun deleteHost(hostId: String) {
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

    fun refresh() {
        val host = mutableState.value.activeHost ?: return
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val page = runCatching { gateway.listSessions(host) }.getOrNull()
            val jobs = runCatching { gateway.listJobs(host) }.getOrNull()
            mutableState.update {
                if (it.activeHostId != host.id) it.copy(isRefreshing = false)
                else it.copy(
                    isRefreshing = false,
                    sessions = page?.sessions ?: it.sessions,
                    sessionsHasMore = page?.hasMore ?: it.sessionsHasMore,
                    jobs = jobs ?: it.jobs,
                )
            }
        }
    }

    fun loadMoreSessions() {
        val host = mutableState.value.activeHost ?: return
        if (!mutableState.value.sessionsHasMore) return
        val offset = mutableState.value.sessions.size
        viewModelScope.launch {
            runCatching { gateway.listSessions(host, offset = offset) }
                .onSuccess { page ->
                    mutableState.update { state ->
                        if (state.activeHostId != host.id) state
                        else state.copy(
                            sessions = state.sessions + page.sessions.filterNot { new -> state.sessions.any { it.id == new.id } },
                            sessionsHasMore = page.hasMore,
                        )
                    }
                }
                .onFailure(::showFailure)
        }
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
                .onSuccess { messages ->
                    mutableState.update { state ->
                        if (state.activeSessionId != sessionId) state
                        else state.copy(messages = messages.mapNotNull(::mapStoredMessage))
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val host = mutableState.value.activeHost ?: return
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            runCatching { gateway.renameSession(host, sessionId, trimmed) }
                .onSuccess {
                    mutableState.update { state ->
                        state.copy(sessions = state.sessions.map { if (it.id == sessionId) it.copy(title = trimmed) else it })
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun deleteSession(sessionId: String) {
        val host = mutableState.value.activeHost ?: return
        viewModelScope.launch {
            runCatching { gateway.deleteSession(host, sessionId) }
                .onSuccess {
                    mutableState.update { state ->
                        state.copy(
                            sessions = state.sessions.filterNot { it.id == sessionId },
                            activeSessionId = state.activeSessionId?.takeIf { it != sessionId },
                            messages = if (state.activeSessionId == sessionId) emptyList() else state.messages,
                        )
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun toggleJob(job: HermesJob) {
        val host = mutableState.value.activeHost ?: return
        val target = !job.enabled
        // Optimistic flip; reverted on failure.
        mutableState.update { state ->
            state.copy(jobs = state.jobs.map { if (it.id == job.id) it.copy(enabled = target) else it })
        }
        viewModelScope.launch {
            runCatching { gateway.setJobEnabled(host, job.id, target) }
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

    fun cancelRun() {
        val host = mutableState.value.activeHost
        val runId = mutableState.value.activeRunId
        sendJob?.cancel()
        if (host != null && runId != null) {
            viewModelScope.launch {
                runCatching { gateway.stopRun(host, runId) }
            }
        }
    }

    fun respondToApproval(itemId: String, approve: Boolean) {
        val host = mutableState.value.activeHost ?: return
        val item = mutableState.value.messages.firstOrNull { it.id == itemId } as? ChatUiItem.Approval ?: return
        if (item.decision != null) return
        val runId = item.runId ?: mutableState.value.activeRunId ?: return
        setApprovalDecision(itemId, approve)
        viewModelScope.launch {
            runCatching { gateway.resolveApproval(host, runId, item.approvalId, approve) }
                .onFailure { error ->
                    setApprovalDecision(itemId, null)
                    showFailure(error)
                }
        }
    }

    private fun setApprovalDecision(itemId: String, decision: Boolean?) {
        mutableState.update { state ->
            state.copy(messages = state.messages.map {
                if (it is ChatUiItem.Approval && it.id == itemId) it.copy(decision = decision) else it
            })
        }
    }

    fun sendMessage() {
        val text = mutableState.value.composerText.trim()
        val host = mutableState.value.activeHost ?: return
        if (text.isBlank() || mutableState.value.isSending || mutableState.value.connectionPhase != HostConnectionPhase.Connected) return

        mutableState.update {
            it.copy(
                composerText = "",
                isSending = true,
                messages = it.messages + ChatUiItem.User(UUID.randomUUID().toString(), text),
                errorMessage = null,
            )
        }

        val assistantId = UUID.randomUUID().toString()
        sendJob = viewModelScope.launch {
            try {
                // The stream endpoint only needs the id; do NOT require the session to be
                // present in the cached list (it may have arrived via a Completed event).
                val sessionId = mutableState.value.activeSessionId
                    ?: gateway.createSession(host, text.take(44)).also { created ->
                        mutableState.update {
                            it.copy(
                                sessions = listOf(created) + it.sessions,
                                activeSessionId = created.id,
                            )
                        }
                    }.id
                mutableState.update { it.copy(messages = it.messages + ChatUiItem.Assistant(assistantId, "", streaming = true)) }
                gateway.streamSessionChat(host, sessionId, text) { event -> handleStreamEvent(assistantId, event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Give the typed message back rather than silently losing it.
                mutableState.update { state ->
                    state.copy(
                        errorMessage = friendlyMessage(error),
                        composerText = state.composerText.ifBlank { text },
                    )
                }
            } finally {
                finishStreaming(assistantId)
                mutableState.update { it.copy(isSending = false, activeRunId = null) }
                refreshSessionsAndJobs(host)
            }
        }
    }

    /** Clears the streaming flag no matter how the stream ended, so the spinner can never get stuck. */
    private fun finishStreaming(assistantId: String) {
        mutableState.update { state ->
            state.copy(messages = state.messages.mapNotNull { item ->
                when {
                    item !is ChatUiItem.Assistant || item.id != assistantId -> item
                    // Drop an empty bubble that never received any content.
                    item.text.isBlank() && item.streaming -> null
                    else -> item.copy(streaming = false)
                }
            })
        }
    }

    private fun connect(hostId: String) {
        val host = mutableState.value.hosts.firstOrNull { it.id == hostId } ?: return
        mutableState.update { it.copy(connectionPhase = HostConnectionPhase.Connecting, errorMessage = null) }
        viewModelScope.launch {
            try {
                val capabilities = gateway.probe(host)
                val (page, jobs) = coroutineScope {
                    val sessionsDeferred = async { gateway.listSessions(host) }
                    val jobsDeferred = async { runCatching { gateway.listJobs(host) }.getOrDefault(emptyList()) }
                    sessionsDeferred.await() to jobsDeferred.await()
                }
                if (mutableState.value.activeHostId != hostId) return@launch
                mutableState.update {
                    it.copy(
                        connectionPhase = HostConnectionPhase.Connected,
                        capabilities = capabilities,
                        sessions = page.sessions,
                        sessionsHasMore = page.hasMore,
                        jobs = jobs,
                        activeSessionId = it.activeSessionId?.takeIf { id -> page.sessions.any { session -> session.id == id } },
                        errorMessage = null,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
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

    private fun refreshSessionsAndJobs(host: HostProfile) {
        viewModelScope.launch {
            val page = runCatching { gateway.listSessions(host) }.getOrNull()
            val jobs = runCatching { gateway.listJobs(host) }.getOrNull()
            if (mutableState.value.activeHostId == host.id) {
                mutableState.update {
                    it.copy(
                        sessions = page?.sessions ?: it.sessions,
                        sessionsHasMore = page?.hasMore ?: it.sessionsHasMore,
                        jobs = jobs ?: it.jobs,
                    )
                }
            }
        }
    }

    private fun handleStreamEvent(assistantId: String, event: HermesStreamEvent) {
        mutableState.update { state ->
            when (event) {
                is HermesStreamEvent.RunStarted -> state.copy(activeRunId = event.runId)
                is HermesStreamEvent.AssistantDelta -> state.copy(
                    messages = state.messages.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == assistantId) item.copy(text = item.text + event.text) else item
                    }
                )
                is HermesStreamEvent.ToolStarted -> state.copy(
                    messages = state.messages + ChatUiItem.Tool(
                        id = "${state.activeRunId ?: assistantId}:${event.toolName}:${state.messages.size}",
                        name = event.toolName,
                        preview = event.preview,
                        running = true,
                    )
                )
                is HermesStreamEvent.ToolCompleted -> {
                    // Complete only the OLDEST running card with this name; the agent can
                    // legitimately run the same tool several times in one turn.
                    val index = state.messages.indexOfFirst { it is ChatUiItem.Tool && it.name == event.toolName && it.running }
                    if (index == -1) state
                    else state.copy(messages = state.messages.toMutableList().also { list ->
                        val item = list[index] as ChatUiItem.Tool
                        list[index] = item.copy(preview = event.preview ?: item.preview, running = false, failed = event.failed)
                    })
                }
                is HermesStreamEvent.ApprovalRequested -> {
                    if (event.approvalId.isBlank()) state
                    else state.copy(
                        messages = state.messages + ChatUiItem.Approval(
                            id = "approval:${event.approvalId}",
                            approvalId = event.approvalId,
                            runId = event.runId ?: state.activeRunId,
                            toolName = event.toolName,
                            message = event.message,
                        )
                    )
                }
                is HermesStreamEvent.Completed -> state.copy(
                    activeSessionId = event.sessionId ?: state.activeSessionId,
                    messages = state.messages.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == assistantId) item.copy(text = event.content.ifBlank { item.text }, streaming = false) else item
                    },
                )
                is HermesStreamEvent.Failed -> state.copy(errorMessage = event.message)
                is HermesStreamEvent.Unknown -> state
                HermesStreamEvent.Done -> state
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
        if (error is CancellationException) return
        mutableState.update { it.copy(errorMessage = friendlyMessage(error), isSending = false) }
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is HermesApiException -> error.message
        is IOException -> "Could not reach this Hermes host. Check the URL, private network, and API server bind address."
        is IllegalArgumentException -> error.message ?: "Host details are invalid."
        else -> "Hermes connection failed: ${error.message ?: error.javaClass.simpleName}"
    }
}
