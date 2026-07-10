package au.com.chrismckechnie.hermesmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
}

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
    val composerText: String = "",
    val isSending: Boolean = false,
    val activeRunId: String? = null,
    val errorMessage: String? = null,
) {
    val activeHost: HostProfile? get() = hosts.firstOrNull { it.id == activeHostId }
    val activeSession: HermesSession? get() = sessions.firstOrNull { it.id == activeSessionId }
}

class HermesViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway: HermesGateway = HermesHttpGateway()
    private val hostStore: HostStore = SecureHostStore(application)
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

        viewModelScope.launch {
            try {
                val session = mutableState.value.activeSessionId?.let { id ->
                    mutableState.value.sessions.firstOrNull { it.id == id }
                } ?: gateway.createSession(host, text.take(44)).also { created ->
                    mutableState.update {
                        it.copy(
                            sessions = listOf(created) + it.sessions,
                            activeSessionId = created.id,
                        )
                    }
                }
                val assistantId = UUID.randomUUID().toString()
                mutableState.update { it.copy(messages = it.messages + ChatUiItem.Assistant(assistantId, "", streaming = true)) }
                gateway.streamSessionChat(host, session.id, text) { event -> handleStreamEvent(assistantId, event) }
            } catch (error: Throwable) {
                showFailure(error)
            } finally {
                mutableState.update { it.copy(isSending = false, activeRunId = null) }
                refreshSessionsAndJobs(host)
            }
        }
    }

    private fun connect(hostId: String) {
        val host = mutableState.value.hosts.firstOrNull { it.id == hostId } ?: return
        mutableState.update { it.copy(connectionPhase = HostConnectionPhase.Connecting, errorMessage = null) }
        viewModelScope.launch {
            try {
                val capabilities = gateway.probe(host)
                val (sessions, jobs) = coroutineScope {
                    val sessionsDeferred = async { gateway.listSessions(host) }
                    val jobsDeferred = async { runCatching { gateway.listJobs(host) }.getOrDefault(emptyList()) }
                    sessionsDeferred.await() to jobsDeferred.await()
                }
                if (mutableState.value.activeHostId != hostId) return@launch
                mutableState.update {
                    it.copy(
                        connectionPhase = HostConnectionPhase.Connected,
                        capabilities = capabilities,
                        sessions = sessions,
                        jobs = jobs,
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
                is HermesStreamEvent.ToolCompleted -> state.copy(
                    messages = state.messages.map { item ->
                        if (item is ChatUiItem.Tool && item.name == event.toolName && item.running) {
                            item.copy(preview = event.preview ?: item.preview, running = false, failed = event.failed)
                        } else item
                    }
                )
                is HermesStreamEvent.Completed -> state.copy(
                    activeSessionId = event.sessionId ?: state.activeSessionId,
                    messages = state.messages.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == assistantId) item.copy(text = event.content.ifBlank { item.text }, streaming = false) else item
                    },
                )
                is HermesStreamEvent.Failed -> state.copy(errorMessage = event.message)
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
        mutableState.update { it.copy(errorMessage = friendlyMessage(error), isSending = false) }
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is HermesApiException -> error.message
        is IOException -> "Could not reach this Hermes host. Check the URL, private network, and API server bind address."
        is IllegalArgumentException -> error.message ?: "Host details are invalid."
        else -> "Hermes connection failed: ${error.message ?: error.javaClass.simpleName}"
    }
}
