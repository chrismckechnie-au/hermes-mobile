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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class HostConnectionPhase { NoHost, Connecting, Connected, Failed }

enum class ThemeMode { System, Dark, Light }

interface SettingsStore {
    fun loadThemeMode(): ThemeMode
    fun saveThemeMode(mode: ThemeMode)
    fun loadChatActivityLayout(): ChatActivityLayout = ChatActivityLayout.Grouped
    fun saveChatActivityLayout(layout: ChatActivityLayout) = Unit
    fun loadCompletedActivityDigests(): List<CompletedActivityDigest> = emptyList()
    fun saveCompletedActivityDigests(digests: List<CompletedActivityDigest>) = Unit
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
    fun loadUnknownOutcomeRecords(): List<UnknownOutcomeRecord> = emptyList()
    fun saveUnknownOutcomeRecords(records: List<UnknownOutcomeRecord>) = Unit
    fun loadQueuedInterruptRecords(): List<QueuedInterruptRecord> = emptyList()
    fun saveQueuedInterruptRecords(records: List<QueuedInterruptRecord>) = Unit
    fun loadNotificationHostIds(): Set<String> = emptySet()
    fun saveNotificationHostIds(hostIds: Set<String>) = Unit
    fun loadMonitoredHostIds(): Set<String> = loadNotificationHostIds()
    fun saveMonitoredHostIds(hostIds: Set<String>) = Unit
    fun loadOverlayEnabled(): Boolean = false
    fun saveOverlayEnabled(enabled: Boolean) = Unit
    fun loadCrashReportingEnabled(): Boolean = false
    fun saveCrashReportingEnabled(enabled: Boolean) = Unit
    fun loadAttentionItems(): List<AttentionItem> = emptyList()
    fun saveAttentionItems(items: List<AttentionItem>) = Unit
    fun recordActivity(item: ActivityEntry, nowMillis: Long = System.currentTimeMillis()) {
        saveAttentionItems(recordActivityEntry(loadAttentionItems(), item, nowMillis))
    }
    fun markAttention(item: AttentionItem) {
        recordActivity(item)
    }
    fun markActivityRead(hostId: String, sessionId: String? = null, entryId: String? = null) {
        saveAttentionItems(markActivityRead(loadAttentionItems(), hostId, sessionId, entryId))
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
    private var activityLayout = ChatActivityLayout.Grouped
    private var completedActivityDigests = emptyList<CompletedActivityDigest>()
    private var checkpoints = emptyList<RunCheckpoint>()
    private val runStatuses = mutableMapOf<String, String>()
    private var unknownOutcomeRecords = emptyList<UnknownOutcomeRecord>()
    private var queuedInterruptRecords = emptyList<QueuedInterruptRecord>()
    private var notificationHosts = emptySet<String>()
    private var monitoredHosts: Set<String>? = null
    private var overlay = false
    private var crashReporting = false
    private var attentionItems = emptyList<AttentionItem>()
    override fun loadThemeMode(): ThemeMode = mode
    override fun saveThemeMode(mode: ThemeMode) {
        this.mode = mode
    }
    override fun loadChatActivityLayout(): ChatActivityLayout = activityLayout
    override fun saveChatActivityLayout(layout: ChatActivityLayout) { activityLayout = layout }
    override fun loadCompletedActivityDigests(): List<CompletedActivityDigest> = completedActivityDigests
    override fun saveCompletedActivityDigests(digests: List<CompletedActivityDigest>) {
        completedActivityDigests = boundedActivityDigests(digests)
    }
    override fun loadRunCheckpoints(): List<RunCheckpoint> = checkpoints
    override fun saveRunCheckpoints(checkpoints: List<RunCheckpoint>) { this.checkpoints = checkpoints.distinct() }
    override fun loadRunCheckpoint(): RunCheckpoint? = checkpoints.firstOrNull()
    override fun saveRunCheckpoint(checkpoint: RunCheckpoint) { checkpoints = listOf(checkpoint) }
    override fun clearRunCheckpoint() { checkpoints = emptyList() }
    override fun loadRunStatus(runId: String): String? = runStatuses[runId]
    override fun saveRunStatus(runId: String, status: String) { runStatuses[runId] = status }
    override fun clearRunStatus(runId: String) { runStatuses.remove(runId) }
    override fun loadUnknownOutcomeRecords(): List<UnknownOutcomeRecord> = unknownOutcomeRecords
    override fun saveUnknownOutcomeRecords(records: List<UnknownOutcomeRecord>) {
        unknownOutcomeRecords = records.distinctBy { it.hostId to it.sessionId }
    }
    override fun loadQueuedInterruptRecords(): List<QueuedInterruptRecord> = queuedInterruptRecords
    override fun saveQueuedInterruptRecords(records: List<QueuedInterruptRecord>) {
        queuedInterruptRecords = records.distinctBy(QueuedInterruptRecord::runId)
    }
    override fun loadNotificationHostIds(): Set<String> = notificationHosts
    override fun saveNotificationHostIds(hostIds: Set<String>) { notificationHosts = hostIds }
    override fun loadMonitoredHostIds(): Set<String> = monitoredHosts ?: notificationHosts
    override fun saveMonitoredHostIds(hostIds: Set<String>) { monitoredHosts = hostIds }
    override fun loadOverlayEnabled(): Boolean = overlay
    override fun saveOverlayEnabled(enabled: Boolean) { overlay = enabled }
    override fun loadCrashReportingEnabled(): Boolean = crashReporting
    override fun saveCrashReportingEnabled(enabled: Boolean) { crashReporting = enabled }
    override fun loadAttentionItems(): List<AttentionItem> = attentionItems
    override fun saveAttentionItems(items: List<AttentionItem>) { attentionItems = items }
}

/** Durable, non-secret coordinates used to reconnect to a host-owned run. */
data class RunCheckpoint(
    val hostId: String,
    val sessionId: String,
    val runId: String,
    val lastEventId: Long? = null,
)

data class UnknownOutcomeRecord(
    val hostId: String,
    val sessionId: String,
    val baselineCount: Int,
    val text: String,
    val evidence: Boolean = false,
    val timedOut: Boolean = false,
)

data class QueuedInterruptRecord(
    val hostId: String,
    val sessionId: String,
    val runId: String,
    val text: String,
    val mode: FollowUpMode = FollowUpMode.Interrupt,
)

enum class FollowUpMode { Queue, Interrupt }

data class QueuedInterrupt(
    val runId: String,
    val text: String,
    val mode: FollowUpMode = FollowUpMode.Interrupt,
    val requiresAcknowledgement: Boolean = false,
)

data class PendingFollowUpChoice(
    val hostId: String,
    val sessionId: String,
    val runId: String,
    val text: String,
) {
    val sessionKey: SessionKey get() = SessionKey(hostId, sessionId)
}

private val RUN_CHECKPOINTS_LOCK = Any()

internal fun SettingsStore.updateRunCheckpoints(
    transform: (List<RunCheckpoint>) -> List<RunCheckpoint>,
) {
    synchronized(RUN_CHECKPOINTS_LOCK) {
        saveRunCheckpoints(transform(loadRunCheckpoints()))
    }
}

data class FullAccessConfirmation(
    val hostId: String,
    val sessionId: String?,
)

/** A session identifier is only unique inside its Hermes host. */
data class SessionKey(
    val hostId: String,
    val sessionId: String,
)

internal fun sortSessionsByActivity(
    sessions: List<HermesSession>,
    activityUpdatedAt: Map<String, Long> = emptyMap(),
): List<HermesSession> = normalizeSessions(sessions)
    .sortedWith(
        compareByDescending<HermesSession> { session ->
            maxOf(
                sessionActivityMillis(session.lastActive),
                activityUpdatedAt[session.id]?.let(::epochValueMillis) ?: Long.MIN_VALUE,
            )
        }
            .thenBy { it.title.orEmpty().lowercase() },
    )

internal fun normalizeSessions(sessions: List<HermesSession>): List<HermesSession> {
    val latestById = linkedMapOf<String, HermesSession>()
    sessions.forEach { session ->
        val existing = latestById[session.id]
        if (existing == null || sessionActivityMillis(session.lastActive) >= sessionActivityMillis(existing.lastActive)) {
            latestById[session.id] = session
        }
    }
    return latestById.values.toList()
}

/** Filters only the sessions already loaded from the selected host. */
internal fun filterSessions(
    sessions: List<HermesSession>,
    query: String,
    filter: SessionFilter = SessionFilter.All,
    activeSessionIds: Set<String> = emptySet(),
    approvalSessionIds: Set<String> = emptySet(),
    stalledSessionIds: Set<String> = emptySet(),
    defaultModel: String? = null,
): List<HermesSession> {
    val needle = query.trim().lowercase()
    return sessions.filter { session ->
        val matchesFilter = when (filter) {
            SessionFilter.All -> true
            SessionFilter.Running -> session.id in activeSessionIds
            SessionFilter.Approval -> session.id in approvalSessionIds
            SessionFilter.Stalled -> session.id in stalledSessionIds
            SessionFilter.Mobile -> session.source.isMobileSessionSource()
            SessionFilter.Desktop -> !session.source.isMobileSessionSource()
        }
        matchesFilter && (needle.isBlank() ||
            listOf(
                session.title,
                session.preview,
                session.source,
                sessionSourceLabel(session.source),
                displaySessionModel(session.model, defaultModel),
            )
                .any { field -> field?.lowercase()?.contains(needle) == true })
    }
}

internal enum class SessionFilter(val label: String) {
    All("All"),
    Running("Running"),
    Approval("Approval"),
    Stalled("Stalled"),
    Mobile("Mobile"),
    Desktop("Desktop"),
}

internal val HermesActiveSession.isStalledActivity: Boolean
    get() = state.trim().lowercase() in setOf("unresponsive", "stalled")

internal fun displaySessionModel(sessionModel: String?, defaultModel: String?): String? {
    val raw = sessionModel?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (raw.lowercase() !in setOf("hermes-default", "hermes-agent", "default")) return raw
    val configured = defaultModel?.trim()?.takeIf(String::isNotEmpty)
    return configured?.takeUnless {
        it.lowercase() in setOf("hermes-default", "hermes-agent", "default")
    } ?: "Host default"
}

private fun String?.isMobileSessionSource(): Boolean = when (this?.trim()?.lowercase()) {
    "api_server", "mobile", "android" -> true
    else -> false
}

/** Derives a local, deterministic session label without sending extra request content to the host. */
internal fun automaticSessionTitle(input: String): String {
    val normalized = input.trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return "New conversation"
    val maxLength = 56
    return if (normalized.length <= maxLength) normalized
    else normalized.take(maxLength - 1).trimEnd() + "…"
}

internal fun uniqueAutomaticSessionTitle(input: String, sessions: List<HermesSession>): String {
    val base = automaticSessionTitle(input)
    val existing = sessions.mapNotNull(HermesSession::title).toHashSet()
    if (base !in existing) return base
    var suffix = 2
    while ("$base #$suffix" in existing) suffix += 1
    return "$base #$suffix"
}

internal fun taskProgressLabel(tasks: List<HermesTask>): String =
    "${tasks.count(HermesTask::isComplete)} / ${tasks.size} tasks"

internal fun isAutomaticUntitledSessionTitle(title: String?): Boolean =
    title.isNullOrBlank() || title.trim().lowercase() in setOf(
        "hermes mobile",
        "hermes session",
        "new conversation",
        "new session",
        "untitled",
        "untitled session",
    )

internal fun sessionActivityMillis(value: String?): Long {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return Long.MIN_VALUE
    raw.toLongOrNull()?.let { epoch ->
        if (epoch < 0L) return Long.MIN_VALUE
        return if (epoch < 100_000_000_000L) epoch * 1_000L else epoch
    }
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}

private fun epochValueMillis(value: Long): Long =
    when {
        value < 0L -> Long.MIN_VALUE
        value < 100_000_000_000L -> value * 1_000L
        else -> value
    }

internal fun sessionSourceLabel(source: String?): String = when (source?.trim()?.lowercase()) {
    "api_server", "mobile" -> "Mobile"
    "desktop", "tui" -> "Desktop"
    "cli" -> "CLI"
    "discord" -> "Discord"
    "cron" -> "Scheduled"
    "subagent" -> "Delegated"
    null, "" -> "Hermes"
    else -> source.trim().replace('_', ' ').replace('-', ' ')
        .split(Regex("\\s+"))
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
}

internal fun sessionDisplayTitle(session: HermesSession): String {
    session.title?.trim()?.takeIf { !isAutomaticUntitledSessionTitle(it) }?.let { return it }
    val kind = when (session.source?.trim()?.lowercase()) {
        "discord" -> "Discord chat"
        "cron" -> "Scheduled run"
        "subagent" -> "Delegated task"
        "api_server", "mobile" -> "Mobile session"
        "desktop", "tui" -> "Desktop session"
        "cli" -> "CLI session"
        else -> "Hermes session"
    }
    return "$kind · ${session.id.take(8)}"
}

internal fun formatSessionUpdatedAt(
    value: String?,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val updatedMillis = sessionActivityMillis(value)
    if (updatedMillis == Long.MIN_VALUE) return null
    return runCatching {
        val updated = Instant.ofEpochMilli(updatedMillis).atZone(zoneId)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        when {
            updated.toLocalDate() == today -> DateTimeFormatter.ofPattern("h:mm a", locale).format(updated)
            updated.toLocalDate() == today.minusDays(1) -> "Yesterday"
            updated.year == today.year -> DateTimeFormatter.ofPattern("d MMM", locale).format(updated)
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(updated)
        }
    }.getOrNull()
}

sealed interface ChatUiItem {
    val id: String

    data class User(
        override val id: String,
        val text: String,
        val lifecycle: PromptLifecycle? = null,
    ) : ChatUiItem

    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
        val safeStatus: String? = null,
        val safeStatusHistory: List<String> = emptyList(),
        val usage: HermesRunUsage? = null,
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
        val durationSeconds: Double? = null,
        val workspaceChanges: List<HermesWorkspaceChange> = emptyList(),
    ) : ChatUiItem

    /** Compact, expandable work trace hydrated from the host activity journal. */
    data class Activity(
        override val id: String,
        val turns: List<SessionActivityTurn>,
    ) : ChatUiItem

    /** Local, sanitized terminal Run summary retained when host activity is unavailable. */
    data class CompletedActivity(
        override val id: String,
        val digest: CompletedActivityDigest,
        val showTools: Boolean,
    ) : ChatUiItem

    data class Approval(
        override val id: String,
        val runRef: RunRef,
        val command: String?,
        val submitting: Boolean,
    ) : ChatUiItem
}

private fun List<ChatUiItem>.withRunUsage(usageByMessageId: Map<String, HermesRunUsage>): List<ChatUiItem> =
    map { item ->
        if (item is ChatUiItem.Assistant && item.usage == null) {
            usageByMessageId[item.id]?.let { usage -> item.copy(usage = usage) } ?: item
        } else {
            item
        }
    }

internal fun mergeStoredAndLiveTail(
    storedMessages: List<ChatUiItem>,
    liveTail: List<ChatUiItem>,
    baselineMessageCount: Int?,
): List<ChatUiItem> {
    val baseline = baselineMessageCount ?: return storedMessages + liveTail
    val livePrompt = liveTail.filterIsInstance<ChatUiItem.User>().firstOrNull()
        ?: return storedMessages + liveTail
    val storedPromptIndex = storedMessages.withIndex().indexOfLast { (index, item) ->
        index >= baseline && item is ChatUiItem.User && item.text == livePrompt.text
    }
    if (storedPromptIndex < 0) return storedMessages + liveTail

    val persistedToolCounts = storedMessages.drop(storedPromptIndex + 1)
        .filterIsInstance<ChatUiItem.Tool>()
        .groupingBy(ChatUiItem.Tool::name)
        .eachCount()
        .toMutableMap()
    val reconciledTail = liveTail.filterNot { item ->
        when {
            item === livePrompt -> true
            item is ChatUiItem.Tool && persistedToolCounts.getOrDefault(item.name, 0) > 0 -> {
                persistedToolCounts[item.name] = persistedToolCounts.getValue(item.name) - 1
                true
            }
            else -> false
        }
    }
    return storedMessages + reconciledTail
}

internal fun withInferredPromptLifecycles(items: List<ChatUiItem>): List<ChatUiItem> =
    items.mapIndexed { index, item ->
        val user = item as? ChatUiItem.User ?: return@mapIndexed item
        val nextUser = items.subList(index + 1, items.size).indexOfFirst { it is ChatUiItem.User }
            .let { relative -> if (relative < 0) items.size else index + 1 + relative }
        val following = items.subList(index + 1, nextUser)
        val inferred = when {
            following.any { it is ChatUiItem.Approval } -> PromptLifecycle.Approval
            following.filterIsInstance<ChatUiItem.Assistant>().any { it.text.isNotBlank() } -> PromptLifecycle.Completed
            else -> user.lifecycle
        }
        if (inferred == user.lifecycle) user else user.copy(lifecycle = inferred)
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
    val baselineMessageCount: Int? = null,
    val tail: List<ChatUiItem> = emptyList(),
    val tasks: List<HermesTask> = emptyList(),
    val subagents: Map<String, HermesSubagent> = emptyMap(),
    val workspaceUpdate: HermesWorkspaceUpdate? = null,
    val assistantId: String = "",
    val awaitingApproval: Boolean = false,
    val approvalCommand: String? = null,
    val approvalDetailsLost: Boolean = false,
    val approvalSubmitting: Boolean = false,
    val stopping: Boolean = false,
    val recovered: Boolean = false,
    val reconcilingTranscript: Boolean = false,
    val terminalUnsynced: Boolean = false,
    val lastEventId: Long? = null,
    val terminalOutcome: ActivityOutcome? = null,
) {
    val ref: RunRef get() = RunRef(host.id, sessionId, runId)

    override fun toString(): String = "ActiveRun(runId=$runId, sessionId=$sessionId)"
}

internal fun safeRunStatusText(event: HermesRunEvent): String? = when (event) {
    is HermesRunEvent.ReasoningAvailable -> event.text
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(180)
        .takeIf(::isUsefulProgressUpdate)
    is HermesRunEvent.ToolStarted -> null
    is HermesRunEvent.ToolCompleted -> if (event.failed) {
        "A tool needs attention…"
    } else {
        null
    }
    is HermesRunEvent.TasksUpdated -> event.tasks.takeIf { it.isNotEmpty() }?.let { tasks ->
        val active = tasks.firstOrNull { it.status == "in_progress" }?.content
            ?.trim()?.replace(Regex("\\s+"), " ")?.take(120)
        listOfNotNull(active, taskProgressLabel(tasks)).joinToString(" · ")
    }
    is HermesRunEvent.SubagentUpdated -> event.subagent.activity
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(180)
        ?.ifBlank { null }
        ?: "A subagent is ${event.subagent.status.replace('_', ' ')}…"
    is HermesRunEvent.WorkspaceUpdated -> when (event.update.files.size) {
        0 -> "Checked the workspace…"
        1 -> "Updated ${event.update.files.single().path.substringAfterLast('/')}…"
        else -> "Updated ${event.update.files.size} workspace files…"
    }
    is HermesRunEvent.ApprovalRequested -> "Waiting for your approval"
    is HermesRunEvent.ApprovalResponded -> "Continuing after approval…"
    is HermesRunEvent.MessageDelta -> "Writing the response…"
    is HermesRunEvent.Completed -> "Finishing the task…"
    is HermesRunEvent.Failed -> "The task hit an issue"
    HermesRunEvent.Cancelled -> "The task was stopped"
}

internal fun appendSafeStatus(history: List<String>, status: String): List<String> {
    val clean = status.trim().replace(Regex("\\s+"), " ").take(180)
    if (clean.isBlank() || history.lastOrNull() == clean) return history
    return (history + clean).takeLast(10)
}

enum class PromptLifecycle(val emoji: String) {
    Working("👀"),
    Approval("⚠️"),
    Completed("✅"),
    Failed("❌"),
    Cancelled("❌"),
}

internal fun isUsefulProgressUpdate(text: String): Boolean {
    val normalized = text.trim().lowercase().trimEnd('.', '…').trim()
    if (normalized in setOf(
            "starting task",
            "finishing the task",
            "finishing transcript sync",
            "writing the response",
            "checked the workspace",
        )
    ) return false
    val lifecycleTail = when {
        normalized.startsWith("using ") -> normalized.removePrefix("using ")
        normalized.startsWith("continuing after ") -> normalized.removePrefix("continuing after ")
        else -> return normalized.isNotBlank()
    }.trimEnd('.', '…').trim()
    return lifecycleTail.split(Regex("\\s+")).size > 3
}

/** A submitRun whose response was lost: the Host may or may not be executing the turn. */
data class UnknownOutcome(
    val sessionId: String,
    val baselineCount: Int,
    val text: String,
    val evidence: Boolean = false,
    val timedOut: Boolean = false,
)

private data class HostConnectionData(
    val sessions: HermesSessionPage,
    val jobs: ResourceState<List<HermesJob>>,
    val skills: ResourceState<List<HermesSkill>>,
    val toolsets: ResourceState<List<HermesToolset>>,
    val models: ResourceState<List<String>>,
    val version: String?,
    val update: HermesHostUpdate?,
)

enum class SlashKind { Command, HostCommand, Skill }

data class SlashSuggestion(
    val kind: SlashKind,
    val name: String,
    val description: String,
)

/** Host-valid reasoning effort levels (hermes_constants.VALID_REASONING_EFFORTS). */
val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
private const val FULL_ACCESS_MODE = "full-access"
private val PERMISSION_MODES = listOf(FULL_ACCESS_MODE)

val LOCAL_COMMANDS = listOf(
    SlashSuggestion(SlashKind.Command, "new", "Start a new session"),
    SlashSuggestion(SlashKind.Command, "rename", "Rename this session — /rename <title>"),
    SlashSuggestion(SlashKind.Command, "fork", "Fork this session into a new branch"),
    SlashSuggestion(SlashKind.Command, "delete", "Delete this session"),
    SlashSuggestion(SlashKind.Command, "stop", "Stop the active run"),
)

val HOST_COMMANDS = listOf(
    SlashSuggestion(SlashKind.HostCommand, "goal", "Set or manage a persistent Hermes goal"),
    SlashSuggestion(SlashKind.HostCommand, "plan", "Create an implementation plan with the host plan skill"),
)

data class HermesUiState(
    val screen: DeckScreen = DeckScreen.Chat,
    val hosts: List<HostProfile> = emptyList(),
    val activeHostId: String? = null,
    val showHostPicker: Boolean = false,
    val editingHostId: String? = null,
    val connectionPhase: HostConnectionPhase = HostConnectionPhase.NoHost,
    val capabilities: HermesCapabilities? = null,
    val hostUpdate: HermesHostUpdate? = null,
    val hostUpdateChecking: Boolean = false,
    val hostUpdateStarting: Boolean = false,
    val sessionsResource: ResourceState<List<HermesSession>> = ResourceState.Empty(),
    val sessionsHasMore: Boolean = false,
    val sessionsNextOffset: Int = 0,
    val sessionsLoadingMore: Boolean = false,
    val sessionsLoadMoreError: String? = null,
    val activeSessionId: String? = null,
    val pendingSessionTarget: SessionKey? = null,
    val transcriptResource: ResourceState<List<ChatUiItem>> = ResourceState.Empty(),
    val runUsageByMessage: Map<SessionKey, Map<String, HermesRunUsage>> = emptyMap(),
    val jobsResource: ResourceState<List<HermesJob>> = ResourceState.Empty(),
    val jobActionsInFlight: Set<String> = emptySet(),
    val jobActionMessage: String? = null,
    val jobRuns: Map<String, ResourceState<List<HermesJobRun>>> = emptyMap(),
    val skillsResource: ResourceState<List<HermesSkill>> = ResourceState.Empty(),
    val toolsetsResource: ResourceState<List<HermesToolset>> = ResourceState.Empty(),
    val modelsResource: ResourceState<List<String>> = ResourceState.Empty(),
    val modelSelections: Map<SessionKey, String> = emptyMap(),
    val reasoningSelections: Map<SessionKey, String> = emptyMap(),
    val permissionSelections: Map<SessionKey, String> = emptyMap(),
    val newSessionModelSelections: Map<String, String> = emptyMap(),
    val newSessionReasoningSelections: Map<String, String> = emptyMap(),
    val newSessionPermissionSelections: Map<String, String> = emptyMap(),
    val composerDrafts: Map<SessionKey, String> = emptyMap(),
    val newSessionDrafts: Map<String, String> = emptyMap(),
    val sendingSessions: Set<SessionKey> = emptySet(),
    val creatingRunHosts: Set<String> = emptySet(),
    val activeRuns: Map<SessionKey, ActiveRun> = emptyMap(),
    val workspaceUpdates: Map<SessionKey, HermesWorkspaceUpdate> = emptyMap(),
    val activeHostSessions: Map<SessionKey, HermesActiveSession> = emptyMap(),
    val sessionActivity: Map<SessionKey, SessionActivityState> = emptyMap(),
    val queuedInterrupts: Map<SessionKey, QueuedInterrupt> = emptyMap(),
    val pendingFollowUpChoice: PendingFollowUpChoice? = null,
    val unknownOutcomes: Map<SessionKey, UnknownOutcome> = emptyMap(),
    val pendingFullAccessConfirmation: FullAccessConfirmation? = null,
    val sessionActionsFor: String? = null,
    val confirmDeleteSessionId: String? = null,
    val errorMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val chatActivityLayout: ChatActivityLayout = ChatActivityLayout.Grouped,
    val completedActivityDigests: List<CompletedActivityDigest> = emptyList(),
    val notificationHostIds: Set<String> = emptySet(),
    val monitoredHostIds: Set<String> = emptySet(),
    val overlayEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val activityEntries: List<ActivityEntry> = emptyList(),
    val notificationTestHostIds: Set<String> = emptySet(),
    val notificationTestMessage: String? = null,
    val pendingPairing: MobilePairingRequest? = null,
) {
    val sessions: List<HermesSession> get() = normalizeSessions(sessionsResource.itemsOrEmpty())
    val messages: List<ChatUiItem> get() = transcriptResource.itemsOrEmpty()
    val jobs: List<HermesJob> get() = jobsResource.itemsOrEmpty()
    val skills: List<HermesSkill> get() = skillsResource.itemsOrEmpty()
    val toolsets: List<HermesToolset> get() = toolsetsResource.itemsOrEmpty()
    val models: List<String> get() = modelsResource.itemsOrEmpty()
    val sessionsRefreshing: Boolean get() = sessionsResource.isRefreshing
    val jobsRefreshing: Boolean get() = jobsResource.isRefreshing
    val isRefreshing: Boolean get() = sessionsRefreshing || jobsRefreshing
    val unreadActivityCount: Int get() = activityUnreadCount(activityEntries)
    val activeHost: HostProfile? get() = hosts.firstOrNull { it.id == activeHostId }
    val activeSession: HermesSession? get() = sessions.firstOrNull { it.id == activeSessionId }
    val editingHost: HostProfile? get() = hosts.firstOrNull { it.id == editingHostId }
    val activeSessionKey: SessionKey? get() = activeHostId?.let { hostId ->
        activeSessionId?.let { sessionId -> SessionKey(hostId, sessionId) }
    }
    val selectedModel: String? get() = activeSessionKey
        ?.let(modelSelections::get)
        ?: activeHostId?.takeIf { activeSessionKey == null }?.let(newSessionModelSelections::get)
    val selectedReasoningEffort: String? get() = activeSessionKey
        ?.let(reasoningSelections::get)
        ?: activeHostId?.takeIf { activeSessionKey == null }?.let(newSessionReasoningSelections::get)
    val selectedPermissionMode: String? get() = activeSessionKey
        ?.let(permissionSelections::get)
        ?: activeHostId?.takeIf { activeSessionKey == null }?.let(newSessionPermissionSelections::get)
    val composerText: String get() = activeSessionKey?.let { composerDrafts[it] }
        ?: activeHostId?.let { newSessionDrafts[it] }
        .orEmpty()
    val isSending: Boolean get() = activeSessionKey?.let(sendingSessions::contains) == true ||
        (activeSessionKey == null && activeHostId in creatingRunHosts)
    val activeRun: ActiveRun? get() = activeSessionKey?.let(activeRuns::get)
    val unknownOutcome: UnknownOutcome? get() = activeSessionKey?.let(unknownOutcomes::get)
    val queuedInterrupt: QueuedInterrupt? get() = activeSessionKey?.let(queuedInterrupts::get)
    val isFullAccessConfirmationPending: Boolean
        get() = pendingFullAccessConfirmation == activeHostId?.let { FullAccessConfirmation(it, activeSessionId) }
    val otherActiveRuns: List<ActiveRun>
        get() = activeRuns.filterKeys { it != activeSessionKey }.values.toList()
    val activeSessionIds: Set<String>
        get() {
            val stalled = activeHostSessions.filterValues { it.isStalledActivity }.keys
            val hostReportedActive = sessions
                .filter(HermesSession::isActive)
                .map { SessionKey(activeHostId.orEmpty(), it.id) }
                .filterNot(stalled::contains)
                .map(SessionKey::sessionId)
            val locallyActive = (
                activeHostSessions.filterValues { !it.isStalledActivity }.keys + activeRuns.keys
            )
                .filterNot(stalled::contains)
                .filter { it.hostId == activeHostId }
                .map(SessionKey::sessionId)
            return (hostReportedActive + locallyActive).toSet()
        }
    val orderedSessions: List<HermesSession>
        get() = sortSessionsByActivity(
            sessions = sessions,
            activityUpdatedAt = activeHostSessions
                .filterKeys { it.hostId == activeHostId }
                .mapNotNull { (key, activity) -> activity.updatedAt?.let { key.sessionId to it } }
                .toMap(),
        )

    fun activityFor(session: HermesSession): HermesActiveSession? {
        val key = activeHostId?.let { hostId -> SessionKey(hostId, session.id) }
        val localRun = key?.let(activeRuns::get)
        if (localRun != null) {
            val hostReported = key?.let(activeHostSessions::get)
            return HermesActiveSession(
                sessionId = session.id,
                runId = localRun.runId,
                title = session.title?.takeIf { it.isNotBlank() } ?: "Hermes task",
                state = when {
                    localRun.reconcilingTranscript -> "syncing"
                    localRun.terminalUnsynced -> "sync_required"
                    localRun.stopping -> "stopping"
                    else -> hostReported?.state ?: "working"
                },
                surface = "mobile",
                latestStatus = hostReported?.latestStatus,
                updatedAt = hostReported?.updatedAt,
            )
        }
        val hostId = activeHostId ?: return null
        return activeHostSessions[SessionKey(hostId, session.id)]
            ?: session.takeIf(HermesSession::isActive)?.let {
                HermesActiveSession(
                    sessionId = it.id,
                    runId = null,
                    title = it.title?.takeIf(String::isNotBlank) ?: "Hermes session",
                    state = "working",
                    surface = it.source?.takeIf(String::isNotBlank) ?: "desktop",
                )
            }
    }

    /** Loaded messages plus the live Run tail (and Approval card) when its Session is displayed. */
    val displayedMessages: List<ChatUiItem>
        get() {
            val storedMessages = messages.withRunUsage(activeSessionKey?.let(runUsageByMessage::get).orEmpty())
            val run = activeRun
            if (run == null || run.sessionId != activeSessionId) {
                val key = activeSessionKey ?: return withInferredPromptLifecycles(storedMessages)
                val durableTurns = sessionActivity[key]?.turns.orEmpty()
                val withDurableActivity = if (durableTurns.isEmpty()) storedMessages else {
                    storedMessages + ChatUiItem.Activity(
                        id = "activity:${key.hostId}:${key.sessionId}",
                        turns = durableTurns,
                    )
                }
                val hostRunIds = durableTurns.map(SessionActivityTurn::turnId).toSet()
                val hostAlreadySuppliesTools = durableTurns.any { it.tools.isNotEmpty() } ||
                    storedMessages.any { it is ChatUiItem.Tool }
                val withLocalActivity = withDurableActivity + completedActivityDigests
                    .asSequence()
                    .filter { digest ->
                        digest.hostId == key.hostId &&
                            digest.sessionId == key.sessionId &&
                            digest.runId !in hostRunIds
                    }
                    .sortedBy(CompletedActivityDigest::completedAtMillis)
                    .map { digest ->
                        ChatUiItem.CompletedActivity(
                            id = "local-activity:${digest.hostId}:${digest.sessionId}:${digest.runId}",
                            digest = digest,
                            showTools = !hostAlreadySuppliesTools,
                        )
                    }
                    .toList()
                val activity = activeHostSessions[key]
                    ?.takeUnless(HermesActiveSession::isStalledActivity)
                    ?: return withInferredPromptLifecycles(withLocalActivity)
                val updates = (activity.statusHistory + listOfNotNull(activity.latestStatus))
                    .map { it.trim() }
                    .filter(::isUsefulProgressUpdate)
                    .distinct()
                    .takeLast(12)
                return withInferredPromptLifecycles(if (updates.isEmpty()) withLocalActivity else withLocalActivity + ChatUiItem.Reasoning(
                    id = "host-activity:${key.hostId}:${key.sessionId}",
                    updates = updates,
                ))
            }
            val tail = mergeStoredAndLiveTail(storedMessages, run.tail, run.baselineMessageCount)
            return withInferredPromptLifecycles(if (run.awaitingApproval && !run.approvalDetailsLost) {
                tail + ChatUiItem.Approval(
                    id = "approval:${run.runId}",
                    runRef = run.ref,
                    command = run.approvalCommand,
                    submitting = run.approvalSubmitting,
                )
            } else tail)
        }

    /** Run banner is shown whenever the Run's Session is not the visible chat. */
    val runBannerVisible: Boolean
        get() = otherActiveRuns.isNotEmpty() || (activeRun != null && screen != DeckScreen.Chat)

    fun isSessionBusy(hostId: String, sessionId: String): Boolean {
        val key = SessionKey(hostId, sessionId)
        val hostActivity = activeHostSessions[key]
        if (hostActivity?.isStalledActivity == true) return false
        return key in activeRuns ||
            hostActivity?.isStalledActivity == false ||
            (hostId == activeHostId && hostActivity == null && sessions.any { it.id == sessionId && it.isActive })
    }

    fun isSessionDeleteBlocked(hostId: String, sessionId: String): Boolean {
        val key = SessionKey(hostId, sessionId)
        if (activeHostSessions[key]?.isStalledActivity == true) return false
        if (key in activeRuns || activeHostSessions[key]?.isStalledActivity == false) return true
        val session = sessions.firstOrNull { it.id == sessionId } ?: return false
        return session.isActive && session.messageCount != 0
    }

    fun hasActiveRunOnHost(hostId: String): Boolean =
        activeRuns.keys.any { key ->
            key.hostId == hostId && activeHostSessions[key]?.isStalledActivity != true
        } ||
            activeHostSessions.any { (key, activity) -> key.hostId == hostId && !activity.isStalledActivity } ||
            (hostId == activeHostId && sessions.any(HermesSession::isActive))

    fun slashSuggestions(): List<SlashSuggestion> {
        if (!composerText.startsWith("/") || composerText.contains(' ') || composerText.contains('\n')) return emptyList()
        val query = composerText.drop(1).lowercase()
        val hostCommands = HOST_COMMANDS
            .takeIf { capabilities?.supportsRunSlashCommands == true }
            .orEmpty()
            .filter { command ->
                command.name != "plan" || skills.any { it.name.equals("plan", ignoreCase = true) }
            }
        val commands = (LOCAL_COMMANDS + hostCommands).filter { it.name.startsWith(query) }
        val skillMatches = if (capabilities?.supportsSkills == true) {
            skills.filter { it.name.lowercase().contains(query) }
                .map { SlashSuggestion(SlashKind.Skill, it.name, it.description ?: "Host skill") }
        } else emptyList()
        return (commands + skillMatches)
            .distinctBy { it.name.lowercase() }
            .take(10)
    }
}

class HermesViewModel(
    private val gateway: HermesGateway,
    private val hostStore: HostStore,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val settingsStore: SettingsStore = InMemorySettingsStore(),
    private val diagnostics: AppDiagnostics = NoOpAppDiagnostics,
    private val activityPollingEnabled: Boolean = false,
    private val safeStartup: Boolean = false,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        HermesUiState(
            composerDrafts = if (safeStartup) emptyMap() else {
                savedState.get<HashMap<String, String>>(SAVED_SESSION_DRAFTS)
                    .orEmpty()
                    .mapNotNull { (encoded, draft) -> decodeDraftKey(encoded)?.let { it to draft } }
                    .toMap()
            },
            newSessionDrafts = if (safeStartup) {
                emptyMap()
            } else {
                savedState.get<HashMap<String, String>>(SAVED_NEW_SESSION_DRAFTS).orEmpty()
            },
        ),
    )
    val state: StateFlow<HermesUiState> = mutableState.asStateFlow()
    private val runStatusChecks = mutableSetOf<String>()
    private val hostActivityChecks = mutableSetOf<String>()
    private var sessionActivityStream: Job? = null
    private var sessionActivityStreamKey: SessionKey? = null

    private fun ActiveRun.key(): SessionKey = SessionKey(host.id, sessionId)

    private fun HermesUiState.withDraft(key: SessionKey?, value: String): HermesUiState {
        val trimmed = value.take(MAX_COMPOSER_LENGTH)
        val next = if (key != null) {
            copy(composerDrafts = if (trimmed.isBlank()) composerDrafts - key else composerDrafts + (key to trimmed))
        } else {
            val hostId = activeHostId ?: return this
            copy(newSessionDrafts = if (trimmed.isBlank()) newSessionDrafts - hostId else newSessionDrafts + (hostId to trimmed))
        }
        persistDrafts(next)
        return next
    }

    private fun persistDrafts(state: HermesUiState) {
        savedState[SAVED_SESSION_DRAFTS] = HashMap(
            state.composerDrafts.mapKeys { (key, _) -> encodeDraftKey(key) },
        )
        savedState[SAVED_NEW_SESSION_DRAFTS] = HashMap(state.newSessionDrafts)
    }

    private fun encodeDraftKey(key: SessionKey): String = "${key.hostId}$DRAFT_KEY_SEPARATOR${key.sessionId}"

    private fun decodeDraftKey(encoded: String): SessionKey? {
        val separator = encoded.indexOf(DRAFT_KEY_SEPARATOR)
        if (separator <= 0 || separator == encoded.lastIndex) return null
        return SessionKey(encoded.substring(0, separator), encoded.substring(separator + 1))
    }

    private fun HermesUiState.clearDraftIfMatches(key: SessionKey, text: String): HermesUiState =
        if (composerDrafts[key]?.trim() == text) withDraft(key, "") else this

    private fun HermesUiState.withModelSelection(key: SessionKey?, model: String?): HermesUiState {
        if (key != null) {
            val nextModels = model?.let { modelSelections + (key to it) } ?: modelSelections - key
            return copy(modelSelections = nextModels)
        }
        val hostId = activeHostId ?: return this
        val nextModels = model?.let { newSessionModelSelections + (hostId to it) }
            ?: newSessionModelSelections - hostId
        return copy(newSessionModelSelections = nextModels)
    }

    private fun HermesUiState.withReasoningSelection(key: SessionKey?, reasoning: String?): HermesUiState {
        if (key != null) {
            val nextReasoning = reasoning?.let { reasoningSelections + (key to it) } ?: reasoningSelections - key
            return copy(reasoningSelections = nextReasoning)
        }
        val hostId = activeHostId ?: return this
        val nextReasoning = reasoning?.let { newSessionReasoningSelections + (hostId to it) }
            ?: newSessionReasoningSelections - hostId
        return copy(newSessionReasoningSelections = nextReasoning)
    }

    private fun HermesUiState.withPermissionSelection(key: SessionKey?, permission: String?): HermesUiState {
        if (key != null) {
            val nextPermissions = permission?.let { permissionSelections + (key to it) } ?: permissionSelections - key
            return copy(permissionSelections = nextPermissions)
        }
        val hostId = activeHostId ?: return this
        val nextPermissions = permission?.let { newSessionPermissionSelections + (hostId to it) }
            ?: newSessionPermissionSelections - hostId
        return copy(newSessionPermissionSelections = nextPermissions)
    }

    private fun HermesUiState.transferNewSessionSelections(hostId: String, sessionId: String): HermesUiState {
        val key = SessionKey(hostId, sessionId)
        val model = newSessionModelSelections[hostId]
        val reasoning = newSessionReasoningSelections[hostId]
        val permission = newSessionPermissionSelections[hostId]
        return copy(
            modelSelections = if (model == null) modelSelections else modelSelections + (key to model),
            reasoningSelections = if (reasoning == null) reasoningSelections else reasoningSelections + (key to reasoning),
            permissionSelections = if (permission == null) permissionSelections else permissionSelections + (key to permission),
            newSessionModelSelections = newSessionModelSelections - hostId,
            newSessionReasoningSelections = newSessionReasoningSelections - hostId,
            newSessionPermissionSelections = newSessionPermissionSelections - hostId,
        )
    }

    private fun HermesUiState.restoreDraft(
        hostId: String,
        key: SessionKey?,
        text: String,
    ): HermesUiState = (if (key == null) {
        copy(newSessionDrafts = newSessionDrafts + (hostId to text.take(MAX_COMPOSER_LENGTH)))
    } else {
        copy(composerDrafts = composerDrafts + (key to text.take(MAX_COMPOSER_LENGTH)))
    }).also(::persistDrafts)

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

    private fun HermesUiState.withHostActivity(
        hostId: String,
        sessions: List<HermesActiveSession>?,
    ): HermesUiState {
        if (sessions == null) return this
        val retained = activeHostSessions.filterKeys { it.hostId != hostId }
        val updated = sessions.associateBy { SessionKey(hostId, it.sessionId) }
        return copy(activeHostSessions = retained + updated)
    }

    private fun persistPromptRecords(state: HermesUiState = mutableState.value) {
        settingsStore.saveUnknownOutcomeRecords(state.unknownOutcomes.map { (key, outcome) ->
            UnknownOutcomeRecord(
                hostId = key.hostId,
                sessionId = key.sessionId,
                baselineCount = outcome.baselineCount,
                text = outcome.text,
                evidence = outcome.evidence,
                timedOut = outcome.timedOut,
            )
        })
        settingsStore.saveQueuedInterruptRecords(state.queuedInterrupts.map { (key, queued) ->
            QueuedInterruptRecord(key.hostId, key.sessionId, queued.runId, queued.text, queued.mode)
        })
    }

    private fun HermesUiState.rekeySession(oldKey: SessionKey, newKey: SessionKey): HermesUiState {
        if (oldKey == newKey) return this
        fun <T> Map<SessionKey, T>.move(value: (T) -> T = { it }): Map<SessionKey, T> =
            this[oldKey]?.let { (this - oldKey) + (newKey to value(it)) } ?: this
        return copy(
            activeSessionId = if (activeHostId == oldKey.hostId && activeSessionId == oldKey.sessionId) newKey.sessionId else activeSessionId,
            pendingSessionTarget = if (pendingSessionTarget == oldKey) newKey else pendingSessionTarget,
            sessionsResource = if (activeHostId == oldKey.hostId) {
                sessionsResource.withItems(
                    normalizeSessions(sessions.map { session ->
                        if (session.id == oldKey.sessionId) session.copy(id = newKey.sessionId) else session
                    }),
                )
            } else {
                sessionsResource
            },
            modelSelections = modelSelections.move(),
            reasoningSelections = reasoningSelections.move(),
            permissionSelections = permissionSelections.move(),
            composerDrafts = composerDrafts.move(),
            sendingSessions = if (oldKey in sendingSessions) (sendingSessions - oldKey) + newKey else sendingSessions,
            activeRuns = activeRuns.move { run -> run.copy(sessionId = newKey.sessionId) },
            workspaceUpdates = workspaceUpdates.move(),
            sessionActivity = sessionActivity.move(),
            queuedInterrupts = queuedInterrupts.move(),
            pendingFollowUpChoice = pendingFollowUpChoice?.let { pending ->
                if (pending.sessionKey == oldKey) pending.copy(sessionId = newKey.sessionId) else pending
            },
            unknownOutcomes = unknownOutcomes.move { pending -> pending.copy(sessionId = newKey.sessionId) },
            pendingFullAccessConfirmation = pendingFullAccessConfirmation?.let { pending ->
                if (pending == FullAccessConfirmation(oldKey.hostId, oldKey.sessionId)) {
                    FullAccessConfirmation(newKey.hostId, newKey.sessionId)
                } else {
                    pending
                }
            },
            runUsageByMessage = runUsageByMessage.move(),
        ).also(::persistDrafts)
    }

    init {
        val restoredDrafts = mutableState.value
        val loadResult = hostStore.load()
        val snapshot = loadResult.snapshot
        val selected = snapshot.selectedHostId?.takeIf { id -> snapshot.hosts.any { it.id == id } }
        val recoveredUnknownOutcomes = if (safeStartup) emptyMap() else {
            settingsStore.loadUnknownOutcomeRecords().associate { record ->
                SessionKey(record.hostId, record.sessionId) to UnknownOutcome(
                    sessionId = record.sessionId,
                    baselineCount = record.baselineCount,
                    text = record.text,
                    evidence = record.evidence,
                    timedOut = record.timedOut,
                )
            }
        }
        val recoveredQueuedInterrupts = if (safeStartup) emptyMap() else {
            settingsStore.loadQueuedInterruptRecords().associate { record ->
                SessionKey(record.hostId, record.sessionId) to QueuedInterrupt(
                    runId = record.runId,
                    text = record.text,
                    mode = record.mode,
                    requiresAcknowledgement = true,
                )
            }
        }
        mutableState.value = HermesUiState(
            hosts = snapshot.hosts,
            activeHostId = selected,
            showHostPicker = snapshot.hosts.isEmpty(),
            connectionPhase = when {
                selected == null -> HostConnectionPhase.NoHost
                safeStartup -> HostConnectionPhase.Failed
                else -> HostConnectionPhase.Connecting
            },
            sessionsResource = if (selected == null || safeStartup) ResourceState.Empty() else ResourceState.Loading,
            jobsResource = if (selected == null || safeStartup) ResourceState.Empty() else ResourceState.Loading,
            skillsResource = if (selected == null || safeStartup) ResourceState.Empty() else ResourceState.Loading,
            toolsetsResource = if (selected == null || safeStartup) ResourceState.Empty() else ResourceState.Loading,
            modelsResource = if (selected == null || safeStartup) ResourceState.Empty() else ResourceState.Loading,
            themeMode = settingsStore.loadThemeMode(),
            chatActivityLayout = settingsStore.loadChatActivityLayout(),
            completedActivityDigests = if (safeStartup) emptyList() else boundedActivityDigests(
                settingsStore.loadCompletedActivityDigests(),
            ),
            notificationHostIds = if (safeStartup) emptySet() else settingsStore.loadNotificationHostIds().intersect(snapshot.hosts.map { it.id }.toSet()),
            monitoredHostIds = if (safeStartup) emptySet() else settingsStore.loadMonitoredHostIds().intersect(snapshot.hosts.map { it.id }.toSet()),
            overlayEnabled = !safeStartup && settingsStore.loadOverlayEnabled(),
            crashReportingEnabled = settingsStore.loadCrashReportingEnabled(),
            activityEntries = if (safeStartup) emptyList() else settingsStore.loadAttentionItems(),
            unknownOutcomes = recoveredUnknownOutcomes,
            queuedInterrupts = recoveredQueuedInterrupts,
            composerDrafts = restoredDrafts.composerDrafts,
            newSessionDrafts = restoredDrafts.newSessionDrafts,
            errorMessage = when {
                loadResult.unlockFailed ->
                    "Saved hosts could not be unlocked on this device (Keystore key changed). Re-add your host and API key."
                safeStartup ->
                    "Recovered background state was skipped after the previous crash. Tap Retry to reconnect without it."
                else -> null
            },
        )
        if (!safeStartup) {
            if (selected != null) connect(selected)
            recoveredUnknownOutcomes.forEach { (key, outcome) ->
                if (!outcome.evidence && !outcome.timedOut) {
                    snapshot.hosts.firstOrNull { it.id == key.hostId }?.let { host -> watchUnknownOutcome(host, key) }
                }
            }
            recoverRunAfterProcessDeath(snapshot)
            if (activityPollingEnabled) {
                viewModelScope.launch {
                    while (true) {
                        refreshHostActivity()
                        reconcileActiveRuns()
                        delay(if (mutableState.value.activeRuns.isEmpty()) 30_000L else 5_000L)
                    }
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsStore.saveThemeMode(mode)
        mutableState.update { it.copy(themeMode = mode) }
    }

    fun setChatActivityLayout(layout: ChatActivityLayout) {
        settingsStore.saveChatActivityLayout(layout)
        mutableState.update { it.copy(chatActivityLayout = layout) }
    }

    fun setHostNotificationsEnabled(hostId: String, enabled: Boolean) {
        val ids = mutableState.value.notificationHostIds.toMutableSet().apply {
            if (enabled) add(hostId) else remove(hostId)
        }
        settingsStore.saveNotificationHostIds(ids)
        mutableState.update { it.copy(notificationHostIds = ids) }
    }

    fun refreshActivityHistory() {
        mutableState.update { it.copy(activityEntries = settingsStore.loadAttentionItems()) }
    }

    fun markActivityRead(hostId: String, sessionId: String? = null, entryId: String? = null) {
        settingsStore.markActivityRead(hostId, sessionId, entryId)
        refreshActivityHistory()
    }

    fun setHostMonitoringEnabled(hostId: String, enabled: Boolean) {
        val ids = mutableState.value.monitoredHostIds.toMutableSet().apply {
            if (enabled) add(hostId) else remove(hostId)
        }
        settingsStore.saveMonitoredHostIds(ids)
        mutableState.update { state ->
            state.copy(
                monitoredHostIds = ids,
                overlayEnabled = state.overlayEnabled && ids.isNotEmpty(),
            )
        }
        if (ids.isEmpty()) settingsStore.saveOverlayEnabled(false)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        settingsStore.saveOverlayEnabled(enabled)
        mutableState.update { it.copy(overlayEnabled = enabled) }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        settingsStore.saveCrashReportingEnabled(enabled)
        diagnostics.setCollectionEnabled(enabled)
        mutableState.update { it.copy(crashReportingEnabled = enabled) }
    }

    fun selectScreen(screen: DeckScreen) {
        mutableState.update { it.copy(screen = screen) }
    }

    fun openSessionFromNotification(hostId: String, sessionId: String) {
        val host = mutableState.value.hosts.firstOrNull { it.id == hostId } ?: return
        val attentionTitle = settingsStore.loadAttentionItems()
            .firstOrNull { it.hostId == hostId && it.sessionId == sessionId }
            ?.title
            ?.takeIf { it.isNotBlank() }
        settingsStore.markActivityRead(hostId, sessionId)
        refreshActivityHistory()
        val target = SessionKey(hostId, sessionId)
        val hostChanged = mutableState.value.activeHostId != hostId
        val reconnectExistingHost = !hostChanged && mutableState.value.connectionPhase == HostConnectionPhase.Failed
        val placeholder = mutableState.value.sessions
            .firstOrNull { !hostChanged && it.id == sessionId }
            ?: HermesSession(
                id = sessionId,
                title = attentionTitle ?: "Hermes session",
                preview = null,
                source = "notification",
                model = null,
                lastActive = null,
                messageCount = null,
            )
        if (hostChanged) {
            persist(HostSnapshot(mutableState.value.hosts, hostId))
            mutableState.update { state ->
                state.copy(
                    activeHostId = hostId,
                    screen = DeckScreen.Chat,
                    connectionPhase = HostConnectionPhase.Connecting,
                    capabilities = null,
                    hostUpdate = null,
                    sessionsResource = listOf(placeholder).asResourceState(),
                    sessionsHasMore = false,
                    sessionsNextOffset = 0,
                    sessionsLoadingMore = false,
                    sessionsLoadMoreError = null,
                    activeSessionId = sessionId,
                    pendingSessionTarget = target,
                    transcriptResource = ResourceState.Loading,
                    jobsResource = ResourceState.Loading,
                    skillsResource = ResourceState.Loading,
                    toolsetsResource = ResourceState.Loading,
                    modelsResource = ResourceState.Loading,
                    errorMessage = null,
                )
            }
            connect(hostId)
        } else {
            mutableState.update { state ->
                state.copy(
                    screen = DeckScreen.Chat,
                    connectionPhase = if (reconnectExistingHost) HostConnectionPhase.Connecting else state.connectionPhase,
                    sessionsResource = state.sessionsResource.withItems(
                        listOf(placeholder) + state.sessions.filterNot { it.id == sessionId },
                    ),
                    activeSessionId = sessionId,
                    pendingSessionTarget = target,
                    transcriptResource = ResourceState.Loading,
                    errorMessage = null,
                )
            }
            if (reconnectExistingHost) connect(hostId)
        }
        viewModelScope.launch {
            val (messagesResult, metadata) = coroutineScope {
                val messagesDeferred = async { runCatching { gateway.loadMessages(host, sessionId) } }
                val metadataDeferred = async { findSessionMetadata(host, sessionId) }
                messagesDeferred.await() to metadataDeferred.await()
            }
            if (metadata != null && mutableState.value.activeHostId == host.id) {
                mutableState.update { state ->
                    state.copy(
                        sessionsResource = state.sessionsResource.withItems(
                            listOf(metadata) + state.sessions.filterNot { it.id == metadata.id },
                        ),
                    )
                }
            }
            messagesResult
                .onSuccess { applyLoadedMessages(host.id, sessionId, it) }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.activeHostId != host.id || state.activeSessionId != sessionId) state
                        else state.copy(transcriptResource = ResourceState.Error(friendlyMessage(error)))
                    }
                }
        }
    }

    fun setComposerText(value: String) {
        mutableState.update { state -> state.withDraft(state.activeSessionKey, value) }
    }

    fun showHostPicker() {
        mutableState.update { it.copy(showHostPicker = true, editingHostId = null, errorMessage = null) }
    }

    fun testHostNotification(hostId: String) {
        val host = mutableState.value.hosts.firstOrNull { it.id == hostId } ?: return
        if (hostId in mutableState.value.notificationTestHostIds) return
        mutableState.update {
            it.copy(
                notificationTestHostIds = it.notificationTestHostIds + hostId,
                notificationTestMessage = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                gateway.sendMobileNotificationTest(host, settingsStore.getOrCreateInstallationId())
            }
            mutableState.update { state ->
                state.copy(
                    notificationTestHostIds = state.notificationTestHostIds - hostId,
                    notificationTestMessage = result.fold(
                        onSuccess = { "Test sent from ${host.name}." },
                        onFailure = { "Test failed: ${friendlyMessage(it)}" },
                    ),
                )
            }
        }
    }

    fun offerPairing(rawUri: String) {
        val pairing = parseMobilePairingUri(rawUri)
        if (pairing == null) {
            mutableState.update { it.copy(errorMessage = "That Hermes pairing link is invalid or incomplete.") }
            return
        }
        mutableState.update { it.copy(pendingPairing = pairing, errorMessage = null) }
    }

    fun dismissPairing() {
        mutableState.update { it.copy(pendingPairing = null) }
    }

    fun confirmPairing() {
        val pairing = mutableState.value.pendingPairing ?: return
        mutableState.update { it.copy(connectionPhase = HostConnectionPhase.Connecting, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                gateway.exchangeMobilePairing(
                    pairing,
                    settingsStore.getOrCreateInstallationId(),
                    "Hermes Mobile",
                )
            }.onSuccess { result ->
                dismissPairing()
                val hostName = runCatching { java.net.URI(pairing.baseUrl).host }.getOrNull()
                    ?.substringBefore('.')?.replaceFirstChar(Char::uppercase)
                    ?.let { "$it Hermes" }
                    ?: "Paired Hermes"
                saveHost(
                    name = hostName,
                    baseUrl = pairing.baseUrl,
                    apiKey = result.token,
                    allowInsecureHttp = pairing.baseUrl.startsWith("http://"),
                )
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        connectionPhase = if (it.hosts.isEmpty()) HostConnectionPhase.NoHost else HostConnectionPhase.Failed,
                        errorMessage = friendlyMessage(error),
                        pendingPairing = null,
                    )
                }
            }
        }
    }

    fun editHost(hostId: String) {
        mutableState.update { it.copy(showHostPicker = true, editingHostId = hostId, errorMessage = null) }
    }

    fun hideHostPicker() {
        mutableState.update { it.copy(showHostPicker = false, editingHostId = null, errorMessage = null) }
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
                capabilities = null,
                hostUpdate = null,
                hostUpdateChecking = false,
                hostUpdateStarting = false,
                activeHostSessions = it.activeHostSessions.filterKeys { key -> key.hostId != profile.id },
                sessionsResource = ResourceState.Loading,
                sessionsHasMore = false,
                sessionsNextOffset = 0,
                sessionsLoadingMore = false,
                sessionsLoadMoreError = null,
                activeSessionId = null,
                pendingSessionTarget = null,
                transcriptResource = ResourceState.Empty(),
                jobsResource = ResourceState.Loading,
                skillsResource = ResourceState.Loading,
                toolsetsResource = ResourceState.Loading,
                modelsResource = ResourceState.Loading,
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
                hostUpdate = null,
                hostUpdateChecking = false,
                hostUpdateStarting = false,
                sessionsResource = ResourceState.Loading,
                sessionsHasMore = false,
                sessionsNextOffset = 0,
                sessionsLoadingMore = false,
                sessionsLoadMoreError = null,
                activeSessionId = null,
                pendingSessionTarget = null,
                transcriptResource = ResourceState.Empty(),
                jobsResource = ResourceState.Loading,
                skillsResource = ResourceState.Loading,
                toolsetsResource = ResourceState.Loading,
                modelsResource = ResourceState.Loading,
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
        val monitoredHostIds = mutableState.value.monitoredHostIds - hostId
        settingsStore.saveNotificationHostIds(notificationHostIds)
        settingsStore.saveMonitoredHostIds(monitoredHostIds)
        if (monitoredHostIds.isEmpty()) settingsStore.saveOverlayEnabled(false)
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
                    monitoredHostIds = monitoredHostIds,
                    activeHostSessions = state.activeHostSessions.filterKeys { key -> key.hostId != hostId },
                )
            } else {
                state.copy(
                    hosts = hosts,
                    activeHostId = selected,
                    showHostPicker = hosts.isEmpty(),
                    connectionPhase = if (selected == null) HostConnectionPhase.NoHost else HostConnectionPhase.Connecting,
                    capabilities = null,
                    hostUpdate = null,
                    hostUpdateChecking = false,
                    hostUpdateStarting = false,
                    sessionsResource = if (selected == null) ResourceState.Empty() else ResourceState.Loading,
                    sessionsHasMore = false,
                    sessionsNextOffset = 0,
                    sessionsLoadingMore = false,
                    sessionsLoadMoreError = null,
                    activeSessionId = null,
                    pendingSessionTarget = null,
                    activeHostSessions = state.activeHostSessions.filterKeys { key -> key.hostId != hostId },
                    transcriptResource = ResourceState.Empty(),
                    jobsResource = if (selected == null) ResourceState.Empty() else ResourceState.Loading,
                    skillsResource = if (selected == null) ResourceState.Empty() else ResourceState.Loading,
                    toolsetsResource = if (selected == null) ResourceState.Empty() else ResourceState.Loading,
                    modelsResource = if (selected == null) ResourceState.Empty() else ResourceState.Loading,
                    notificationHostIds = notificationHostIds,
                    monitoredHostIds = monitoredHostIds,
                    overlayEnabled = state.overlayEnabled && monitoredHostIds.isNotEmpty(),
                )
            }
        }
        if (activeHostChanged && selected != null) connect(selected)
    }

    fun retryConnection() {
        mutableState.value.activeHostId?.let(::connect)
    }

    fun checkHostUpdate(force: Boolean = true) {
        val snapshot = mutableState.value
        val host = snapshot.activeHost ?: return
        if (snapshot.capabilities?.supportsHostUpdate != true || snapshot.hostUpdateChecking) return
        mutableState.update { it.copy(hostUpdateChecking = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { gateway.getHostUpdate(host, force) }
                .onSuccess { update ->
                    if (mutableState.value.activeHostId == host.id) {
                        mutableState.update { it.copy(hostUpdate = update, hostUpdateChecking = false) }
                    }
                }
                .onFailure { error ->
                    if (mutableState.value.activeHostId == host.id) {
                        mutableState.update { it.copy(hostUpdateChecking = false, errorMessage = friendlyMessage(error)) }
                    }
                }
        }
    }

    fun updateHost() {
        val snapshot = mutableState.value
        val host = snapshot.activeHost ?: return
        if (snapshot.capabilities?.supportsHostUpdate != true || snapshot.hostUpdateStarting) return
        if (snapshot.hasActiveRunOnHost(host.id)) {
            mutableState.update { it.copy(errorMessage = "Stop active work before updating the Hermes host.") }
            return
        }
        mutableState.update { it.copy(hostUpdateStarting = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { gateway.updateHost(host) }
                .onSuccess { result ->
                    if (mutableState.value.activeHostId == host.id) {
                        val message = result?.message ?: "Host update started. Hermes may briefly disconnect while it restarts."
                        mutableState.update {
                            if (result?.accepted == false) {
                                it.copy(hostUpdateStarting = false, errorMessage = message)
                            } else {
                                it.copy(hostUpdateStarting = false, hostUpdate = it.hostUpdate?.copy(message = message))
                            }
                        }
                    }
                }
                .onFailure { error ->
                    if (mutableState.value.activeHostId == host.id) {
                        mutableState.update { it.copy(hostUpdateStarting = false, errorMessage = friendlyMessage(error)) }
                    }
                }
        }
    }

    fun refresh() {
        val snapshot = mutableState.value
        val host = snapshot.activeHost ?: return
        val refreshJobs = snapshot.jobsResource !is ResourceState.Unsupported
        mutableState.update { state ->
            state.copy(
                sessionsResource = state.sessionsResource.beginRefresh(),
                jobsResource = if (refreshJobs) state.jobsResource.beginRefresh() else state.jobsResource,
            )
        }
        viewModelScope.launch {
            val sessions = runCatching { gateway.listSessions(host) }
            val jobs = if (refreshJobs) runCatching { gateway.listJobs(host) } else null
            val activeSessions = runCatching { gateway.listActiveSessions(host) }.getOrNull()
            if (mutableState.value.activeHostId == host.id) {
                mutableState.update { state ->
                    val sessionPage = sessions.getOrNull()
                    state.withHostActivity(host.id, activeSessions).copy(
                        sessionsResource = sessionPage?.sessions?.asResourceState()
                            ?: state.sessionsResource.refreshError(friendlyMessage(checkNotNull(sessions.exceptionOrNull()))),
                        sessionsHasMore = sessionPage?.hasMore ?: state.sessionsHasMore,
                        sessionsNextOffset = sessionPage?.sessions?.size ?: state.sessionsNextOffset,
                        jobsResource = jobs?.fold(
                            onSuccess = { it.asResourceState() },
                            onFailure = { error ->
                                if (error.isUnsupportedResourceEndpoint()) ResourceState.Unsupported
                                else state.jobsResource.refreshError(friendlyMessage(error))
                            },
                        ) ?: state.jobsResource,
                    )
                }
            }
        }
    }

    fun loadMoreSessions() {
        val snapshot = mutableState.value
        val host = snapshot.activeHost ?: return
        if (!snapshot.sessionsHasMore || snapshot.sessionsLoadingMore || snapshot.sessionsRefreshing) return
        mutableState.update { it.copy(sessionsLoadingMore = true, sessionsLoadMoreError = null) }
        viewModelScope.launch {
            runCatching { gateway.listSessions(host, offset = snapshot.sessionsNextOffset) }
                .onSuccess { page ->
                    if (mutableState.value.activeHostId == host.id) {
                        mutableState.update { state ->
                            state.copy(
                                sessionsResource = state.sessionsResource.withItems(
                                    state.sessionsResource.itemsOrEmpty() + page.sessions,
                                ),
                                sessionsHasMore = page.hasMore,
                                sessionsNextOffset = state.sessionsNextOffset + page.sessions.size,
                                sessionsLoadingMore = false,
                                sessionsLoadMoreError = null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (mutableState.value.activeHostId == host.id) {
                        mutableState.update { state ->
                            state.copy(
                                sessionsLoadingMore = false,
                                sessionsLoadMoreError = friendlyMessage(error),
                            )
                        }
                    }
                }
        }
    }

    fun createSession() {
        val host = mutableState.value.activeHost ?: return
        if (mutableState.value.connectionPhase != HostConnectionPhase.Connected) return
        if (mutableState.value.activeSessionId == null) return
        sessionActivityStream?.cancel()
        sessionActivityStream = null
        sessionActivityStreamKey = null
        mutableState.update { state ->
            state.copy(
                activeSessionId = null,
                pendingSessionTarget = null,
                transcriptResource = ResourceState.Empty(),
                screen = DeckScreen.Chat,
                sessionActionsFor = null,
                errorMessage = null,
            )
        }
    }

    fun selectSession(sessionId: String) {
        val host = mutableState.value.activeHost ?: return
        settingsStore.markActivityRead(host.id, sessionId)
        refreshActivityHistory()
        mutableState.update {
            it.copy(
                activeSessionId = sessionId,
                pendingSessionTarget = null,
                screen = DeckScreen.Chat,
                transcriptResource = ResourceState.Loading,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { gateway.loadMessages(host, sessionId) }
                .onSuccess { page -> applyLoadedMessages(host.id, requestedId = sessionId, page = page) }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.activeHostId != host.id || state.activeSessionId != sessionId) state
                        else state.copy(transcriptResource = ResourceState.Error(friendlyMessage(error)))
                    }
                }
        }
        loadSessionActivity(host, sessionId)
    }

    fun retryTranscript() {
        mutableState.value.activeSessionId?.let(::selectSession)
    }

    private fun loadSessionActivity(host: HostProfile, sessionId: String) {
        val snapshot = mutableState.value
        if (snapshot.capabilities?.supportsSessionActivityHistory != true) return
        val key = SessionKey(host.id, sessionId)
        viewModelScope.launch {
            val page = runCatching { gateway.loadSessionActivity(host, sessionId) }.getOrElse { error ->
                // Older hosts and expired cursors retain the existing transcript;
                // activity is an enhancement, never a reason to hide chat.
                if (
                    (error !is HermesApiException || error.statusCode != 404) &&
                    mutableState.value.activeHostId == host.id &&
                    mutableState.value.activeSessionId == sessionId
                ) {
                    beginSessionActivityStream(host, key)
                }
                return@launch
            }
            if (mutableState.value.activeHostId != host.id || mutableState.value.activeSessionId != sessionId) {
                return@launch
            }
            mutableState.update { state ->
                val reduced = page.events.sortedBy(HermesSessionActivityEvent::eventId)
                    .fold(state.sessionActivity[key] ?: SessionActivityState(), ::reduceSessionActivity)
                state.copy(sessionActivity = state.sessionActivity + (key to reduced))
            }
            beginSessionActivityStream(host, key)
        }
    }

    private fun beginSessionActivityStream(host: HostProfile, key: SessionKey) {
        if (mutableState.value.capabilities?.supportsSessionActivityStream != true) return
        if (mutableState.value.activeHostId != key.hostId || mutableState.value.activeSessionId != key.sessionId) return
        if (sessionActivityStreamKey == key && sessionActivityStream?.isActive == true) return
        sessionActivityStream?.cancel()
        sessionActivityStreamKey = key
        sessionActivityStream = viewModelScope.launch {
            val after = mutableState.value.sessionActivity[key]?.lastEventId
            runCatching {
                gateway.streamSessionActivity(host, key.sessionId, after) { event ->
                    mutableState.update { state ->
                        val current = state.sessionActivity[key] ?: SessionActivityState()
                        state.copy(sessionActivity = state.sessionActivity + (key to reduceSessionActivity(current, event)))
                    }
                }
            }.onFailure { error ->
                // A retained-history cursor expired while disconnected. Reload
                // from the latest retained window instead of looping on 409.
                if (error is HermesApiException && error.statusCode == 409) {
                    sessionActivityStreamKey = null
                    loadSessionActivity(host, key.sessionId)
                }
            }
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

    fun refreshHostActivity() {
        val host = mutableState.value.activeHost ?: return
        if (!hostActivityChecks.add(host.id)) return
        viewModelScope.launch {
            try {
                val sessions = gateway.listActiveSessions(host)
                if (mutableState.value.activeHostId == host.id) {
                    mutableState.update { it.withHostActivity(host.id, sessions) }
                }
            } catch (_: Throwable) {
                // Keep the last known state during a transient connection loss.
            } finally {
                hostActivityChecks.remove(host.id)
            }
        }
    }

    fun clearStaleActivity(sessionId: String) {
        val state = mutableState.value
        val host = state.activeHost ?: return
        val key = SessionKey(host.id, sessionId)
        val activity = state.activeHostSessions[key]
        val leaseId = activity?.leaseId
        if (
            activity?.isStalledActivity != true ||
            leaseId.isNullOrBlank() ||
            state.capabilities?.supportsActiveSessionCleanup != true
        ) return

        viewModelScope.launch {
            try {
                gateway.clearStaleActiveSession(host, leaseId)
                val localRun = mutableState.value.activeRuns[key]
                mutableState.update { current ->
                    current.withRun(key, null).copy(
                        activeHostSessions = current.activeHostSessions - key,
                        errorMessage = null,
                    )
                }
                if (localRun != null) clearRunCoordinates(localRun)
                refreshHostActivity()
            } catch (error: Throwable) {
                showFailure(error)
            }
        }
    }

    fun reconcileActiveRuns() {
        mutableState.value.activeRuns.values.forEach { run ->
            if (!runStatusChecks.add(run.runId)) return@forEach
            viewModelScope.launch {
                try {
                    val result = runCatching { gateway.getRunStatus(run.host, run.runId) }
                    val status = result.getOrNull()
                    val missing = (result.exceptionOrNull() as? HermesApiException)?.statusCode in setOf(404, 410)
                    if (status?.isTerminal == true || missing) {
                        finalizeRun(run)
                    } else if (status?.isWaitingForApproval == true) {
                        val key = run.key()
                        mutableState.update { state ->
                            val current = state.activeRuns[key]
                            if (current?.runId != run.runId) state
                            else state.withRun(key, current.copy(awaitingApproval = true, approvalDetailsLost = true))
                        }
                    }
                } finally {
                    runStatusChecks.remove(run.runId)
                }
            }
        }
    }

    /**
     * null uses the Host policy. Full Access is never armed by selection alone:
     * the UI must confirm the explicit one-shot request first.
     */
    fun selectPermissionMode(mode: String?) {
        mutableState.update { state ->
            val selected = mode?.takeIf { it in PERMISSION_MODES }
            if (selected == FULL_ACCESS_MODE) {
                val hostId = state.activeHostId ?: return@update state
                if (state.selectedPermissionMode == FULL_ACCESS_MODE) state else state.copy(
                    pendingFullAccessConfirmation = FullAccessConfirmation(hostId, state.activeSessionId),
                    errorMessage = null,
                )
            } else {
                state.withPermissionSelection(state.activeSessionKey, null).copy(
                    pendingFullAccessConfirmation = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun confirmFullAccessForNextRun() {
        mutableState.update { state ->
            val pending = state.pendingFullAccessConfirmation ?: return@update state
            val current = state.activeHostId?.let { FullAccessConfirmation(it, state.activeSessionId) }
            if (pending != current) {
                state.copy(
                    pendingFullAccessConfirmation = null,
                    errorMessage = "The selected conversation changed. Confirm Full Access again if it is still needed.",
                )
            } else {
                state.withPermissionSelection(state.activeSessionKey, FULL_ACCESS_MODE).copy(
                    pendingFullAccessConfirmation = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun cancelFullAccessConfirmation() {
        mutableState.update { it.copy(pendingFullAccessConfirmation = null) }
    }

    fun toggleJob(job: HermesJob) {
        val host = mutableState.value.activeHost ?: return
        val enabled = !job.enabled
        val actionKey = "${host.id}:toggle:${job.id}"
        if (actionKey in mutableState.value.jobActionsInFlight) return
        mutableState.update { state ->
            state.copy(
                jobActionsInFlight = state.jobActionsInFlight + actionKey,
                jobActionMessage = null,
                jobsResource = state.jobsResource.withItems(
                    state.jobs.map { if (it.id == job.id) it.copy(enabled = enabled) else it },
                ),
            )
        }
        viewModelScope.launch {
            runCatching { gateway.setJobEnabled(host, job.id, enabled) }
                .onSuccess {
                    mutableState.update { state ->
                        val cleared = state.copy(jobActionsInFlight = state.jobActionsInFlight - actionKey)
                        if (state.activeHostId != host.id) cleared else cleared.copy(
                            jobActionMessage = if (enabled) "${job.name} resumed" else "${job.name} paused",
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        val cleared = state.copy(jobActionsInFlight = state.jobActionsInFlight - actionKey)
                        if (state.activeHostId != host.id) cleared else cleared.copy(
                            jobActionMessage = "Could not update ${job.name}",
                            jobsResource = state.jobsResource.withItems(
                                state.jobs.map { if (it.id == job.id) it.copy(enabled = job.enabled) else it },
                            ),
                        )
                    }
                    showFailure(error)
                }
        }
    }

    fun runJobNow(jobId: String) {
        val host = mutableState.value.activeHost ?: return
        val actionKey = "${host.id}:run:$jobId"
        if (actionKey in mutableState.value.jobActionsInFlight) return
        mutableState.update {
            it.copy(jobActionsInFlight = it.jobActionsInFlight + actionKey, jobActionMessage = null)
        }
        viewModelScope.launch {
            runCatching { gateway.runJob(host, jobId) }
                .onSuccess {
                    mutableState.update { state ->
                        val cleared = state.copy(jobActionsInFlight = state.jobActionsInFlight - actionKey)
                        if (state.activeHostId != host.id) cleared else cleared.copy(jobActionMessage = "Job started")
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        val cleared = state.copy(jobActionsInFlight = state.jobActionsInFlight - actionKey)
                        if (state.activeHostId != host.id) cleared else cleared.copy(jobActionMessage = "Could not start job")
                    }
                    if (mutableState.value.activeHostId == host.id) showFailure(error)
                }
        }
    }

    fun loadJobRuns(jobId: String) {
        val host = mutableState.value.activeHost ?: return
        if (mutableState.value.jobRuns[jobId] is ResourceState.Loading) return
        mutableState.update { it.copy(jobRuns = it.jobRuns + (jobId to ResourceState.Loading)) }
        viewModelScope.launch {
            val result = runCatching { gateway.listJobRuns(host, jobId) }
            mutableState.update { state ->
                if (state.activeHostId != host.id) state else state.copy(
                    jobRuns = state.jobRuns + (jobId to result.fold(
                        onSuccess = { it.asResourceState() },
                        onFailure = { ResourceState.Error(friendlyMessage(it)) },
                    )),
                )
            }
        }
    }

    fun returnToRunSession(ref: RunRef) {
        val run = activeRun(ref) ?: return
        if (mutableState.value.activeHostId == run.host.id && mutableState.value.activeSessionId == run.sessionId) {
            mutableState.update { it.copy(screen = DeckScreen.Chat) }
        } else {
            openSessionFromNotification(run.host.id, run.sessionId)
        }
    }

    /** Compatibility hook for the current single-action banner. New UI should pass its exact RunRef. */
    fun returnToRunSession() {
        val run = mutableState.value.otherActiveRuns.firstOrNull() ?: mutableState.value.activeRun ?: return
        returnToRunSession(run.ref)
    }

    // ------------------------------------------------------------------
    // Sending and slash dispatch
    // ------------------------------------------------------------------

    fun sendMessage() {
        val text = mutableState.value.composerText.trim()
        if (text.isBlank()) return
        if (text.startsWith("/") && dispatchLocalCommand(text)) return
        val snapshot = mutableState.value
        diagnostics.recordPhase(
            DiagnosticPhase.SendValidate,
            DiagnosticContext(
                sendRoute = when {
                    snapshot.activeRun != null -> DiagnosticSendRoute.FollowUp
                    snapshot.activeSessionId == null -> DiagnosticSendRoute.NewSession
                    else -> DiagnosticSendRoute.ExistingSession
                },
                messageLength = text.length,
                activeRunCount = snapshot.activeRuns.size,
                overlayEnabled = snapshot.overlayEnabled,
            ),
        )
        if (snapshot.pendingFullAccessConfirmation != null) {
            mutableState.update { it.copy(errorMessage = "Confirm or cancel Full Access before sending.") }
            return
        }
        if (snapshot.pendingFollowUpChoice != null) {
            mutableState.update { it.copy(errorMessage = "Choose Queue or Interrupt for the pending follow-up first.") }
            return
        }
        val queuedFollowUp = snapshot.queuedInterrupt
        if (queuedFollowUp != null) {
            val message = if (queuedFollowUp.requiresAcknowledgement) {
                "Review the recovered follow-up before sending another message."
            } else {
                "A follow-up is already queued for this conversation."
            }
            mutableState.update { it.copy(errorMessage = message) }
            return
        }
        snapshot.activeRun?.let { run ->
            if (run.terminalUnsynced || run.reconcilingTranscript) {
                mutableState.update { it.copy(errorMessage = "This run has finished. Sync its transcript before sending another message.") }
                return
            }
            stageFollowUpChoice(run, text)
            return
        }
        submitChat(text)
    }

    private fun stageFollowUpChoice(run: ActiveRun, text: String) {
        val key = run.key()
        mutableState.update { state ->
            if (state.activeRuns[key]?.runId != run.runId || state.pendingFollowUpChoice != null) return@update state
            state.copy(
                pendingFollowUpChoice = PendingFollowUpChoice(
                    hostId = key.hostId,
                    sessionId = key.sessionId,
                    runId = run.runId,
                    text = text,
                ),
                errorMessage = null,
            )
        }
    }

    fun queuePendingFollowUp() {
        commitPendingFollowUp(FollowUpMode.Queue)
    }

    fun interruptPendingFollowUp() {
        commitPendingFollowUp(FollowUpMode.Interrupt)
    }

    fun cancelPendingFollowUp() {
        mutableState.update { state ->
            if (state.pendingFollowUpChoice == null) state else state.copy(
                pendingFollowUpChoice = null,
                errorMessage = null,
            )
        }
    }

    private fun commitPendingFollowUp(mode: FollowUpMode) {
        val pending = mutableState.value.pendingFollowUpChoice ?: return
        val key = pending.sessionKey
        val run = mutableState.value.activeRuns[key]
        if (run?.runId != pending.runId || run.terminalUnsynced || run.reconcilingTranscript) {
            mutableState.update { state ->
                if (state.pendingFollowUpChoice != pending) state else state.copy(
                    pendingFollowUpChoice = null,
                    errorMessage = "That run has already finished. Review the draft before sending it.",
                )
            }
            return
        }
        if (key in mutableState.value.queuedInterrupts) {
            mutableState.update { state ->
                if (state.pendingFollowUpChoice != pending) state else state.copy(
                    pendingFollowUpChoice = null,
                    errorMessage = "A follow-up is already queued for this conversation.",
                )
            }
            return
        }

        val queued = QueuedInterrupt(runId = run.runId, text = pending.text, mode = mode)
        mutableState.update { state ->
            if (
                state.pendingFollowUpChoice != pending ||
                state.activeRuns[key]?.runId != run.runId ||
                key in state.queuedInterrupts
            ) {
                state
            } else {
                state.clearDraftIfMatches(key, pending.text).copy(
                    queuedInterrupts = state.queuedInterrupts + (key to queued),
                    pendingFollowUpChoice = null,
                    errorMessage = null,
                )
            }
        }
        val committed = mutableState.value.queuedInterrupts[key] == queued &&
            mutableState.value.pendingFollowUpChoice == null
        if (!committed) return
        persistPromptRecords()
        if (mode == FollowUpMode.Interrupt && !run.stopping) requestStop(run)
    }

    fun applySuggestion(suggestion: SlashSuggestion) {
        when (suggestion.kind) {
            // Typed dispatch: a Skill row never re-parses slash text, so a
            // skill named like a reserved command stays reachable.
            SlashKind.Skill -> mutableState.update { state ->
                state.withDraft(state.activeSessionKey, "Use the ${suggestion.name} skill: ")
            }
            SlashKind.HostCommand -> mutableState.update { state ->
                state.withDraft(state.activeSessionKey, "/${suggestion.name} ")
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
                .withDraft(null, "").copy(errorMessage = null)
            else state.withDraft(initialKey, "").withSending(initialKey, true).copy(errorMessage = null)
        }

        viewModelScope.launch {
            var draftKey = initialKey
            var currentPhase = DiagnosticPhase.SendValidate
            try {
                val automaticTitle = uniqueAutomaticSessionTitle(text, snapshot.sessions)
                var session = snapshot.activeSessionId?.let { id -> snapshot.sessions.firstOrNull { it.id == id } }
                    ?: try {
                        currentPhase = DiagnosticPhase.SessionCreate
                        diagnostics.recordPhase(currentPhase)
                        gateway.createSession(host, automaticTitle)
                    } catch (error: HermesApiException) {
                        if (error.statusCode != 400 || !error.message.contains("already in use", ignoreCase = true)) throw error
                        gateway.createSession(host, null)
                    }.also { created ->
                        mutableState.update { state ->
                            state.copy(
                                sessionsResource = state.sessionsResource.withItems(
                                    listOf(created) + state.sessions.filterNot { it.id == created.id },
                                ),
                                activeSessionId = created.id,
                            ).transferNewSessionSelections(host.id, created.id)
                        }
                    }

                var runKey = SessionKey(host.id, session.id)
                draftKey = runKey
                mutableState.update { state ->
                    state.withNewSessionSending(host.id, false).withSending(runKey, true)
                }

                // Pre-submit refresh: narrows multi-writer staleness, resolves a
                // rotated session, and is the exact history the run executes on.
                currentPhase = DiagnosticPhase.HistoryLoad
                diagnostics.recordPhase(currentPhase)
                val page = gateway.loadMessages(host, session.id)
                applyLoadedMessages(host.id, requestedId = session.id, page = page)
                val resolvedKey = SessionKey(host.id, page.sessionId)
                if (resolvedKey != runKey) {
                    mutableState.update { state -> state.rekeySession(runKey, resolvedKey) }
                    runKey = resolvedKey
                    draftKey = resolvedKey
                }

                if (
                    page.messages.isEmpty() &&
                    capabilities.supportsSessionEdit &&
                    isAutomaticUntitledSessionTitle(session.title)
                ) {
                    runCatching { gateway.renameSession(host, page.sessionId, automaticTitle) }
                        .getOrNull()
                        ?.let { renamed ->
                            session = renamed
                            mutableState.update { state ->
                                state.copy(
                                    sessionsResource = state.sessionsResource.withItems(
                                        state.sessions.map { existing ->
                                            if (existing.id == renamed.id) renamed else existing
                                        },
                                    ),
                                )
                            }
                        }
                }

                val current = mutableState.value
                val requestedPermissionMode = current.permissionSelections[runKey]
                    ?.takeIf { it in PERMISSION_MODES }
                val submittedPermissionMode = requestedPermissionMode
                    ?.takeIf { capabilities.supportsPermissionMode }
                if (requestedPermissionMode == FULL_ACCESS_MODE) {
                    // Consume before the network call. A lost response may mean
                    // the Host accepted the run, so unknown outcomes never
                    // restore this one-shot capability.
                    mutableState.update { state ->
                        if (state.permissionSelections[runKey] != FULL_ACCESS_MODE) state
                        else state.withPermissionSelection(runKey, null)
                    }
                }
                val idempotencyKey = UUID.randomUUID().toString()
                currentPhase = DiagnosticPhase.RunSubmit
                diagnostics.recordPhase(currentPhase)
                suspend fun submitOnce(): String = gateway.submitRun(
                        host,
                        page.sessionId,
                        text,
                        page.messages,
                        current.modelSelections[runKey],
                        current.reasoningSelections[runKey].takeIf { capabilities.supportsReasoningEffort },
                        submittedPermissionMode,
                        idempotencyKey.takeIf { capabilities.supportsRunSubmissionIdempotency },
                    )
                var submission = runCatching { submitOnce() }
                if (
                    submission.exceptionOrNull() !is HermesApiException &&
                    submission.isFailure &&
                    capabilities.supportsRunSubmissionIdempotency
                ) {
                    logRun("retrying idempotent submit session=${page.sessionId}")
                    delay(RUN_SUBMIT_RETRY_DELAY_MS)
                    submission = runCatching { submitOnce() }
                }
                val runId = try {
                    submission.getOrThrow()
                } catch (error: HermesApiException) {
                    // The host answered: the run was not accepted.
                    diagnostics.recordFailure(DiagnosticPhase.RunSubmit, error)
                    showFailure(error)
                    mutableState.update { state ->
                        val restored = if (
                            requestedPermissionMode == FULL_ACCESS_MODE &&
                            state.permissionSelections[runKey] == null
                        ) {
                            state.withPermissionSelection(runKey, FULL_ACCESS_MODE)
                        } else {
                            state
                        }
                        restored.withSending(runKey, false)
                            .withNewSessionSending(host.id, false)
                            .restoreDraft(host.id, runKey, text)
                    }
                    return@launch
                } catch (error: Throwable) {
                    // Response lost — the host may be executing the turn.
                    diagnostics.recordFailure(DiagnosticPhase.RunSubmit, error)
                    logRun("submit outcome unknown session=${page.sessionId}")
                    mutableState.update { state ->
                        state.withSending(runKey, false)
                            .withNewSessionSending(host.id, false)
                            .withUnknownOutcome(runKey, UnknownOutcome(page.sessionId, page.messages.size, text))
                    }
                    persistPromptRecords()
                    watchUnknownOutcome(host, runKey)
                    return@launch
                }

                val assistantId = UUID.randomUUID().toString()
                currentPhase = DiagnosticPhase.RunAccepted
                diagnostics.recordPhase(currentPhase)
                val run = ActiveRun(
                    host = host,
                    sessionId = page.sessionId,
                    sessionTitle = session.title,
                    runId = runId,
                    baselineMessageCount = page.messages.count { message ->
                        message.role == "user" || message.role == "assistant" || message.role == "tool"
                    },
                    assistantId = assistantId,
                    tail = listOf(
                        ChatUiItem.User(UUID.randomUUID().toString(), text, lifecycle = PromptLifecycle.Working),
                        ChatUiItem.Assistant(
                            id = assistantId,
                            text = "",
                            streaming = true,
                            safeStatus = "Starting task…",
                            safeStatusHistory = listOf("Starting task…"),
                        ),
                    ),
                )
                mutableState.update { state ->
                    state.withSending(runKey, false).withRun(runKey, run).copy(
                        workspaceUpdates = state.workspaceUpdates - runKey,
                    )
                }
                persistRunCoordinates(run)
                updateRunStatus(run.runId, "Starting task…")
                logRun("run started runId=$runId sessionId=${run.sessionId}")
                driveRun(run)
            } catch (error: Throwable) {
                diagnostics.recordFailure(currentPhase, error)
                showFailure(error)
                mutableState.update { state ->
                    val notSending = draftKey?.let { state.withSending(it, false) }
                        ?: state
                    notSending.withNewSessionSending(host.id, false)
                        .restoreDraft(host.id, draftKey, text)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Run lifecycle
    // ------------------------------------------------------------------

    private suspend fun driveRun(run: ActiveRun) {
        diagnostics.recordPhase(DiagnosticPhase.RunStream)
        val terminal = AtomicBoolean(false)
        var replaySupport: Boolean? = true.takeIf { run.lastEventId != null }
        var reconnectDelayMs = RUN_STREAM_RETRY_DELAY_MS
        var streamAttempts = 0
        while (mutableState.value.activeRuns[run.key()]?.runId == run.runId) {
            if (replaySupport == null) {
                runCatching { gateway.probe(run.host) }
                    .onSuccess { replaySupport = it.supportsRunEventReplay }
            }
            if (replaySupport == false && streamAttempts > 0) {
                logRun("run host does not support event replay runId=${run.runId}; polling")
                if (!pollUntilTerminal(run)) return
                terminal.set(true)
                break
            }
            val cursor = mutableState.value.activeRuns[run.key()]?.lastEventId ?: run.lastEventId
            val streamResult = runCatching {
                gateway.streamRunEvents(
                    run.host,
                    run.runId,
                    onEvent = { event ->
                        if (handleRunEvent(run, event)) terminal.set(true)
                    },
                    afterEventId = cursor.takeIf { replaySupport == true },
                )
            }
            streamAttempts += 1
            if (terminal.get()) break
            streamResult.fold(
                onSuccess = { logRun("run stream ended before terminal event runId=${run.runId}") },
                onFailure = {
                    logRun("run stream dropped runId=${run.runId}: ${it.javaClass.simpleName}")
                    if (streamAttempts == 1) diagnostics.recordFailure(DiagnosticPhase.RunStream, it)
                },
            )
            val statusResult = runCatching { gateway.getRunStatus(run.host, run.runId) }
            val missing = (statusResult.exceptionOrNull() as? HermesApiException)?.statusCode in setOf(404, 410)
            if (missing || statusResult.getOrNull()?.isTerminal == true) {
                terminal.set(true)
                break
            }
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RUN_STREAM_RETRY_DELAY_MS)
        }

        if (!terminal.get() && mutableState.value.activeRuns[run.key()]?.runId == run.runId) {
            logRun("run stream ended without terminal event runId=${run.runId}; polling")
            if (!pollUntilTerminal(run)) return
            terminal.set(true)
        }
        if (terminal.get()) finalizeRun(run)
    }

    /** Returns true for terminal events. Ignores events for a Run that is no longer active. */
    private fun handleRunEvent(run: ActiveRun, event: HermesRunEvent): Boolean {
        var terminal = false
        var applied = false
        val key = run.key()
        val eventId = event.eventId
        val safeStatus = safeRunStatusText(event)
        mutableState.update { state ->
            val existing = state.activeRuns[key]
            if (existing == null || existing.runId != run.runId) return@update state
            if (eventId != null && eventId <= (existing.lastEventId ?: 0L)) return@update state
            applied = true
            val statusTail = if (safeStatus == null) existing.tail else existing.tail.map { item ->
                if (item is ChatUiItem.Assistant && item.id == existing.assistantId) {
                    item.copy(
                        safeStatus = safeStatus,
                        safeStatusHistory = appendSafeStatus(item.safeStatusHistory, safeStatus),
                    )
                } else {
                    item
                }
            }
            val current = existing.copy(
                lastEventId = maxOf(existing.lastEventId ?: 0L, eventId ?: 0L)
                    .takeIf { it > 0L },
                tail = statusTail,
            )
            when (event) {
                is HermesRunEvent.MessageDelta -> state.copy(
                    activeRuns = state.activeRuns + (key to current.copy(tail = current.tail.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == current.assistantId) item.copy(text = item.text + event.delta) else item
                    }))
                )
                is HermesRunEvent.ReasoningAvailable -> {
                    val text = event.text.trim().take(MAX_REASONING_UPDATE_LENGTH)
                    if (!isUsefulProgressUpdate(text)) state
                    else state.withRun(key, current.copy(tail = upsertReasoning(current, text)))
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
                is HermesRunEvent.TasksUpdated -> state.withRun(key, current.copy(tasks = event.tasks))
                is HermesRunEvent.SubagentUpdated -> {
                    val previous = current.subagents[event.subagent.id]
                    val merged = event.subagent.copy(
                        goal = event.subagent.goal ?: previous?.goal,
                        activity = event.subagent.activity ?: previous?.activity,
                    )
                    state.withRun(key, current.copy(subagents = current.subagents + (merged.id to merged)))
                }
                is HermesRunEvent.WorkspaceUpdated -> state.withRun(
                    key,
                    current.copy(workspaceUpdate = event.update),
                ).copy(workspaceUpdates = state.workspaceUpdates + (key to event.update))
                is HermesRunEvent.ApprovalRequested -> state.withRun(key,
                    // One actionable card per Run: a second request keeps the
                    // first card until it resolves (host queue is FIFO).
                    if (current.awaitingApproval) current
                    else current.copy(
                        awaitingApproval = true,
                        approvalCommand = event.command,
                        approvalDetailsLost = false,
                        approvalSubmitting = false,
                    )
                )
                is HermesRunEvent.ApprovalResponded -> state.withRun(
                    key,
                    current.copy(awaitingApproval = false, approvalCommand = null, approvalSubmitting = false),
                )
                is HermesRunEvent.Completed -> {
                    terminal = true
                    state.withRun(key, current.copy(terminalOutcome = ActivityOutcome.Completed, tail = current.tail.map { item ->
                        if (item is ChatUiItem.Assistant && item.id == current.assistantId) {
                            item.copy(
                                text = event.output.ifBlank { item.text },
                                streaming = false,
                                usage = event.usage,
                            )
                        } else item
                    }))
                }
                is HermesRunEvent.Failed -> {
                    terminal = true
                    state.withRun(
                        key,
                        current.copy(
                            terminalOutcome = ActivityOutcome.Failed,
                            tail = current.tail.withPromptLifecycle(PromptLifecycle.Failed),
                        ),
                    ).copy(errorMessage = event.error)
                }
                HermesRunEvent.Cancelled -> {
                    terminal = true
                    state.withRun(
                        key,
                        current.copy(
                            terminalOutcome = ActivityOutcome.Cancelled,
                            tail = current.tail.withPromptLifecycle(PromptLifecycle.Cancelled),
                        ),
                    )
                }
            }
        }
        if (!applied) return false
        safeStatus?.let { updateRunStatus(run.runId, it) }
        if (eventId != null) {
            mutableState.value.activeRuns[key]
                ?.takeIf { it.runId == run.runId }
                ?.let(::persistRunCoordinates)
        }
        return terminal
    }

    private fun updateRunStatus(runId: String, status: String) {
        if (settingsStore.loadRunStatus(runId) != status) settingsStore.saveRunStatus(runId, status)
    }

    private fun completedActivityDigest(run: ActiveRun, nowMillis: Long = System.currentTimeMillis()): CompletedActivityDigest? {
        val milestones = buildList {
            run.tail.filterIsInstance<ChatUiItem.Reasoning>()
                .flatMap(ChatUiItem.Reasoning::updates)
                .forEach(::add)
            run.tail.filterIsInstance<ChatUiItem.Assistant>()
                .flatMap(ChatUiItem.Assistant::safeStatusHistory)
                .forEach(::add)
        }
            .map { it.trim().replace(Regex("\\s+"), " ").take(180) }
            .filter(::isUsefulProgressUpdate)
            .distinct()
            .takeLast(3)
        val tools = run.tail.filterIsInstance<ChatUiItem.Tool>().map { tool ->
            CompletedToolDigest(
                name = tool.name.take(120),
                preview = safeActivityPreview(tool.preview),
                failed = tool.failed,
                durationSeconds = tool.durationSeconds,
            )
        }
        if (milestones.isEmpty() && tools.isEmpty()) return null
        return CompletedActivityDigest(
            hostId = run.host.id,
            sessionId = run.sessionId,
            runId = run.runId,
            milestones = milestones,
            tools = tools,
            outcome = run.terminalOutcome ?: ActivityOutcome.Completed,
            completedAtMillis = nowMillis,
        )
    }

    private fun retainCompletedActivity(digest: CompletedActivityDigest) {
        mutableState.update { state ->
            state.copy(
                completedActivityDigests = boundedActivityDigests(
                    state.completedActivityDigests.filterNot {
                        it.hostId == digest.hostId && it.sessionId == digest.sessionId && it.runId == digest.runId
                    } + digest,
                ),
            )
        }
        settingsStore.saveCompletedActivityDigests(mutableState.value.completedActivityDigests)
    }

    private fun insertBeforeAssistant(run: ActiveRun, item: ChatUiItem): List<ChatUiItem> {
        val index = run.tail.indexOfLast { it is ChatUiItem.Assistant && it.id == run.assistantId }
        return if (index < 0) run.tail + item
        else run.tail.subList(0, index) + item + run.tail.subList(index, run.tail.size)
    }

    private fun List<ChatUiItem>.withPromptLifecycle(lifecycle: PromptLifecycle): List<ChatUiItem> = map { item ->
        if (item is ChatUiItem.User) item.copy(lifecycle = lifecycle) else item
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
        val current = mutableState.value.activeRuns[key]
        if (current?.runId != run.runId || current.reconcilingTranscript) return
        completedActivityDigest(current)?.let(::retainCompletedActivity)
        diagnostics.recordPhase(DiagnosticPhase.RunTerminal)
        clearPendingFollowUpChoice(run)
        mutableState.update { state ->
            val active = state.activeRuns[key]
            if (active?.runId != run.runId) state else state.withRun(
                key,
                active.copy(
                    awaitingApproval = false,
                    approvalCommand = null,
                    approvalSubmitting = false,
                    stopping = false,
                    reconcilingTranscript = true,
                    terminalUnsynced = false,
                    tail = active.tail.map { item ->
                        if (item is ChatUiItem.Assistant) item.copy(streaming = false) else item
                    },
                ),
            )
        }
        updateRunStatus(run.runId, "Finishing transcript sync…")

        val result = runCatching { gateway.loadMessages(run.host, run.sessionId) }
        val page = result.getOrNull()
        if (page == null) {
            logRun("transcript sync failed runId=${run.runId}: ${result.exceptionOrNull()?.javaClass?.simpleName}")
            updateRunStatus(run.runId, "Finished — transcript sync required")
            mutableState.update { state ->
                val active = state.activeRuns[key]
                if (active?.runId != run.runId) state else state.withRun(
                    key,
                    active.copy(reconcilingTranscript = false, terminalUnsynced = true),
                ).copy(errorMessage = "The run finished, but its transcript could not be refreshed. Retry transcript sync.")
            }
            return
        }

        var queuedInterrupt: QueuedInterrupt? = null
        var shouldAutoSubmitQueued = false
        var reconciled = false
        val resolvedKey = SessionKey(run.host.id, page.sessionId)
        val reconciledMessages = page.messages.mapNotNull(::mapStoredMessage)
        val completedAssistant = current.tail
            .filterIsInstance<ChatUiItem.Assistant>()
            .firstOrNull { it.id == current.assistantId }
        val completedUsageMessageId = completedUsageMessageId(
            messages = reconciledMessages,
            completedUsage = completedAssistant?.usage,
            completedText = completedAssistant?.text,
        )
        mutableState.update { state ->
            val rekeyed = state.rekeySession(key, resolvedKey)
            if (rekeyed.activeRuns[resolvedKey]?.runId != run.runId) return@update rekeyed
            val usageAttached = if (completedAssistant?.usage != null && completedUsageMessageId != null) {
                rekeyed.copy(
                    runUsageByMessage = rekeyed.runUsageByMessage + (
                        resolvedKey to (rekeyed.runUsageByMessage[resolvedKey].orEmpty() +
                            (completedUsageMessageId to completedAssistant.usage))
                    ),
                )
            } else {
                rekeyed
            }
            queuedInterrupt = usageAttached.queuedInterrupts[resolvedKey]
            reconciled = true
            shouldAutoSubmitQueued = queuedInterrupt != null &&
                queuedInterrupt?.requiresAcknowledgement == false &&
                usageAttached.activeSessionKey == resolvedKey
            val keepQueueForReview = queuedInterrupt != null && !shouldAutoSubmitQueued
            val nextQueues = if (keepQueueForReview) {
                usageAttached.queuedInterrupts + (
                    resolvedKey to checkNotNull(queuedInterrupt).copy(requiresAcknowledgement = true)
                )
            } else {
                usageAttached.queuedInterrupts - resolvedKey
            }
            var cleared = usageAttached.withRun(resolvedKey, null)
                .withSending(resolvedKey, false)
                .copy(queuedInterrupts = nextQueues)
            if (keepQueueForReview && queuedInterrupt?.requiresAcknowledgement == false) {
                cleared = cleared.withDraft(resolvedKey, checkNotNull(queuedInterrupt).text)
            }
            if (cleared.activeHostId == run.host.id && cleared.activeSessionId == page.sessionId) {
                cleared.copy(
                    activeSessionId = page.sessionId,
                    transcriptResource = reconciledMessages.asResourceState(),
                    errorMessage = null,
                )
            } else cleared
        }
        if (!reconciled) return
        clearRunCoordinates(run)
        persistPromptRecords()
        refreshSessionsAndJobs(run.host)
        queuedInterrupt?.text?.takeIf { shouldAutoSubmitQueued }?.let { text ->
            submitChat(text)
        }
    }

    fun retryRunReconciliation(ref: RunRef) {
        val run = activeRun(ref) ?: return
        if (!run.terminalUnsynced || run.reconcilingTranscript) return
        viewModelScope.launch { finalizeRun(run) }
    }

    fun startSkill(name: String) {
        if (name !in mutableState.value.skills.map(HermesSkill::name)) return
        mutableState.update { state ->
            state.withDraft(state.activeSessionKey, "Use the $name skill: ").copy(screen = DeckScreen.Chat)
        }
    }

    fun respondApproval(ref: RunRef, choice: String) {
        val run = activeRun(ref) ?: return
        val key = run.key()
        val current = mutableState.value.activeRuns[key]
        if (current?.runId != run.runId || !current.awaitingApproval || current.approvalSubmitting) return
        mutableState.update { state ->
            val active = state.activeRuns[key]
            if (active?.runId != run.runId || !active.awaitingApproval || active.approvalSubmitting) state
            else state.withRun(key, active.copy(approvalSubmitting = true))
        }
        viewModelScope.launch {
            logRun("approval response runId=${run.runId} choice=$choice")
            val result = runCatching { gateway.respondApproval(run.host, run.runId, choice) }
            val error = result.exceptionOrNull()
            val resolved = result.isSuccess || (error as? HermesApiException)?.statusCode == 409
            if (!resolved) {
                mutableState.update { state ->
                    val active = state.activeRuns[key]
                    if (active?.runId != run.runId) state
                    else state.withRun(key, active.copy(approvalSubmitting = false))
                }
                showFailure(checkNotNull(error))
                return@launch
            }
            mutableState.update { state ->
                val current = state.activeRuns[key]
                if (current?.runId != run.runId) state
                else state.withRun(
                    key,
                    current.copy(awaitingApproval = false, approvalCommand = null, approvalSubmitting = false),
                )
            }
        }
    }

    fun respondApproval(choice: String) {
        mutableState.value.activeRun?.ref?.let { respondApproval(it, choice) }
    }

    fun respondToApproval(itemId: String, approve: Boolean) {
        val run = mutableState.value.activeRuns.values.firstOrNull { "approval:${it.runId}" == itemId } ?: return
        respondApproval(run.ref, if (approve) "once" else "deny")
    }

    fun stopActiveRun() {
        val run = mutableState.value.activeRun
        if (run == null) {
            mutableState.update { it.copy(errorMessage = "No run is active.") }
            return
        }
        requestStop(run)
    }

    fun stopRun(ref: RunRef) {
        val run = activeRun(ref)
        if (run == null) {
            mutableState.update { it.copy(errorMessage = "That run is no longer active.") }
            return
        }
        requestStop(run)
    }

    private fun activeRun(ref: RunRef): ActiveRun? =
        mutableState.value.activeRuns[SessionKey(ref.hostId, ref.sessionId)]
            ?.takeIf { it.runId == ref.runId }

    private fun requestStop(run: ActiveRun) {
        val key = run.key()
        clearPendingFollowUpChoice(run)
        val current = mutableState.value.activeRuns[key]
        if (current?.runId != run.runId || current.stopping) return
        if (current.terminalUnsynced || current.reconcilingTranscript) {
            mutableState.update { it.copy(errorMessage = "This run has finished. Retry transcript sync instead.") }
            return
        }
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

    private fun clearPendingFollowUpChoice(run: ActiveRun) {
        mutableState.update { state ->
            val pending = state.pendingFollowUpChoice
            if (pending?.runId != run.runId || pending.sessionKey != run.key()) state
            else state.copy(pendingFollowUpChoice = null)
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
                    persistPromptRecords()
                    return@launch
                }
            }
            mutableState.update { state ->
                state.unknownOutcomes[key]?.let { state.withUnknownOutcome(key, it.copy(timedOut = true)) } ?: state
            }
            persistPromptRecords()
        }
    }

    /** Explicit user acknowledgement — the only way the unknown-outcome lock releases. */
    fun acknowledgeUnknownOutcome() {
        val pending = mutableState.value.unknownOutcome ?: return
        val host = mutableState.value.activeHost
        val key = mutableState.value.activeSessionKey ?: return
        mutableState.update { state ->
            val cleared = state.withUnknownOutcome(key, null)
            if (pending.evidence) cleared else cleared.restoreDraft(key.hostId, key, pending.text)
        }
        persistPromptRecords()
        if (host != null && mutableState.value.activeSessionId == pending.sessionId) {
            selectSession(pending.sessionId)
        }
    }

    fun acknowledgeQueuedInterrupt(useDraft: Boolean) {
        val key = mutableState.value.activeSessionKey ?: return
        val queued = mutableState.value.queuedInterrupts[key]
            ?.takeIf(QueuedInterrupt::requiresAcknowledgement)
            ?: return
        mutableState.update { state ->
            val cleared = state.copy(
                queuedInterrupts = state.queuedInterrupts - key,
                errorMessage = null,
            )
            if (useDraft) cleared.withDraft(key, queued.text) else cleared
        }
        persistPromptRecords()
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
                        state.copy(
                            sessionsResource = state.sessionsResource.withItems(
                                state.sessions.map { if (it.id == updated.id) updated else it },
                            ),
                            activeRuns = state.activeRuns.mapValues { (_, run) ->
                                if (run.host.id == host.id && run.sessionId == updated.id) run.copy(sessionTitle = updated.title) else run
                            },
                            errorMessage = null,
                        )
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
                            sessionsResource = state.sessionsResource.withItems(
                                listOf(child) + state.sessions.filterNot { it.id == child.id },
                            ),
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
        val hostId = mutableState.value.activeHostId
        if (hostId != null && mutableState.value.isSessionDeleteBlocked(hostId, sessionId)) {
            mutableState.update { it.copy(errorMessage = "A run is active in this session. Stop it first.", sessionActionsFor = null) }
            return
        }
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
                            sessionsResource = state.sessionsResource.withItems(
                                state.sessions.filterNot { it.id == sessionId },
                            ),
                            activeSessionId = if (wasActive) null else state.activeSessionId,
                            transcriptResource = if (wasActive) ResourceState.Empty() else state.transcriptResource,
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

    private fun <T> listResource(
        result: Result<List<T>>,
        unsupportedEndpoint: Boolean,
    ): ResourceState<List<T>> = result.fold(
        onSuccess = { it.asResourceState() },
        onFailure = { error ->
            if (unsupportedEndpoint && error.isUnsupportedResourceEndpoint()) ResourceState.Unsupported
            else ResourceState.Error(friendlyMessage(error))
        },
    )

    private fun connect(hostId: String) {
        val host = mutableState.value.hosts.firstOrNull { it.id == hostId } ?: return
        mutableState.update { state ->
            val preservePendingSession = state.pendingSessionTarget?.hostId == hostId && state.sessions.isNotEmpty()
            state.copy(
                connectionPhase = HostConnectionPhase.Connecting,
                sessionsResource = if (preservePendingSession) state.sessionsResource else ResourceState.Loading,
                sessionsHasMore = false,
                sessionsNextOffset = 0,
                sessionsLoadingMore = false,
                sessionsLoadMoreError = null,
                jobsResource = ResourceState.Loading,
                skillsResource = ResourceState.Loading,
                toolsetsResource = ResourceState.Loading,
                modelsResource = ResourceState.Loading,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val discoveredCapabilities = gateway.probe(host)
                val loaded = coroutineScope {
                    val sessionsDeferred = async { gateway.listSessions(host) }
                    val jobsDeferred = async {
                        listResource(runCatching { gateway.listJobs(host) }, unsupportedEndpoint = true)
                    }
                    val skillsDeferred = async {
                        if (discoveredCapabilities.supportsSkills) {
                            listResource(runCatching { gateway.listSkills(host) }, unsupportedEndpoint = false)
                        } else {
                            ResourceState.Unsupported
                        }
                    }
                    val toolsetsDeferred = async {
                        listResource(runCatching { gateway.listToolsets(host) }, unsupportedEndpoint = true)
                    }
                    val modelsDeferred = async {
                        listResource(runCatching { gateway.listModels(host) }, unsupportedEndpoint = true)
                    }
                    val versionDeferred = async { runCatching { gateway.getHostVersion(host) }.getOrNull() }
                    val updateDeferred = async {
                        if (discoveredCapabilities.supportsHostUpdate) runCatching { gateway.getHostUpdate(host) }.getOrNull() else null
                    }
                    HostConnectionData(
                        sessions = sessionsDeferred.await(),
                        jobs = jobsDeferred.await(),
                        skills = skillsDeferred.await(),
                        toolsets = toolsetsDeferred.await(),
                        models = modelsDeferred.await(),
                        version = versionDeferred.await(),
                        update = updateDeferred.await(),
                    )
                }
                val capabilities = discoveredCapabilities.copy(version = loaded.version ?: discoveredCapabilities.version)
                val activeSessions = runCatching { gateway.listActiveSessions(host) }.getOrNull()
                if (mutableState.value.activeHostId != hostId) return@launch
                mutableState.update { state ->
                    val pendingTarget = state.pendingSessionTarget?.takeIf { it.hostId == hostId }
                    val pendingSession = pendingTarget?.let { target ->
                        state.sessions.firstOrNull { it.id == target.sessionId }
                    }
                    val connectedSessions = loaded.sessions.sessions + listOfNotNull(pendingSession)
                    val availableModels = loaded.models.loadedItemsOrNull()
                    val connectedActiveSessionId = pendingTarget?.sessionId
                        ?: state.activeSessionId?.takeIf { id -> connectedSessions.any { session -> session.id == id } }
                    state.withHostActivity(hostId, activeSessions).copy(
                        connectionPhase = HostConnectionPhase.Connected,
                        capabilities = capabilities,
                        hostUpdate = loaded.update,
                        hostUpdateChecking = false,
                        hostUpdateStarting = false,
                        sessionsResource = connectedSessions.asResourceState(),
                        sessionsHasMore = loaded.sessions.hasMore,
                        sessionsNextOffset = loaded.sessions.sessions.size,
                        sessionsLoadingMore = false,
                        sessionsLoadMoreError = null,
                        jobsResource = loaded.jobs,
                        skillsResource = loaded.skills,
                        toolsetsResource = loaded.toolsets,
                        modelsResource = loaded.models,
                        modelSelections = availableModels?.let { models ->
                            state.modelSelections.filter { (key, model) -> key.hostId != hostId || model in models }
                        } ?: state.modelSelections,
                        newSessionModelSelections = availableModels?.let { models ->
                            state.newSessionModelSelections.filter { (pendingHostId, model) ->
                                pendingHostId != hostId || model in models
                            }
                        } ?: state.newSessionModelSelections,
                        activeSessionId = connectedActiveSessionId,
                        transcriptResource = if (
                            connectedActiveSessionId == null && state.activeSessionId != null
                        ) {
                            ResourceState.Empty()
                        } else {
                            state.transcriptResource
                        },
                        pendingSessionTarget = state.pendingSessionTarget?.takeUnless { it == pendingTarget },
                        errorMessage = null,
                    )
                }
            } catch (error: Throwable) {
                if (mutableState.value.activeHostId == hostId) {
                    mutableState.update { state ->
                        val message = friendlyMessage(error)
                        state.copy(
                            connectionPhase = HostConnectionPhase.Failed,
                            capabilities = null,
                            hostUpdate = null,
                            hostUpdateChecking = false,
                            hostUpdateStarting = false,
                            sessionsResource = ResourceState.Error(message, state.sessionsResource.valueOrNull()),
                            jobsResource = ResourceState.Error(message, state.jobsResource.valueOrNull()),
                            skillsResource = ResourceState.Error(message, state.skillsResource.valueOrNull()),
                            toolsetsResource = ResourceState.Error(message, state.toolsetsResource.valueOrNull()),
                            modelsResource = ResourceState.Error(message, state.modelsResource.valueOrNull()),
                            errorMessage = message,
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
            val safeStatus = settingsStore.loadRunStatus(checkpoint.runId)
                ?.trim()
                ?.take(240)
                ?.takeIf(String::isNotBlank)
                ?: "Reconnected to the active task…"
            val assistantId = "recovered-assistant:${checkpoint.runId}"
            val run = ActiveRun(
                host = host,
                sessionId = checkpoint.sessionId,
                sessionTitle = null,
                runId = checkpoint.runId,
                assistantId = assistantId,
                tail = listOf(
                    ChatUiItem.Assistant(
                        id = assistantId,
                        text = "",
                        streaming = true,
                        safeStatus = safeStatus,
                    ),
                ),
                recovered = true,
                lastEventId = checkpoint.lastEventId,
                approvalDetailsLost = checkpoint.lastEventId == null,
            )
            val key = run.key()
            logRun("recovering run after process death runId=${run.runId}")
            mutableState.update { state -> state.withRun(key, run) }
            updateRunStatus(run.runId, safeStatus)
            viewModelScope.launch {
                val result = runCatching { gateway.getRunStatus(host, run.runId) }
                val status = result.getOrNull()
                if (status?.isTerminal == true || (result.exceptionOrNull() as? HermesApiException)?.statusCode == 404) {
                    finalizeRun(run)
                    return@launch
                }
                driveRun(run)
            }
        }
    }

    private fun persistRunCoordinates(run: ActiveRun) {
        val checkpoint = RunCheckpoint(run.host.id, run.sessionId, run.runId, run.lastEventId)
        settingsStore.updateRunCheckpoints { checkpoints ->
            checkpoints.filterNot { it.runId == run.runId } + checkpoint
        }
    }

    private fun clearRunCoordinates(run: ActiveRun) {
        clearRunCoordinates(RunCheckpoint(run.host.id, run.sessionId, run.runId))
    }

    private fun clearRunCoordinates(checkpoint: RunCheckpoint) {
        synchronized(RUN_CHECKPOINTS_LOCK) {
            settingsStore.updateRunCheckpoints { checkpoints ->
                checkpoints.filterNot { it.runId == checkpoint.runId }
            }
            settingsStore.clearRunStatus(checkpoint.runId)
        }
        if (savedState.get<String>(KEY_RUN_ID) == checkpoint.runId) {
            savedState.remove<String>(KEY_RUN_HOST)
            savedState.remove<String>(KEY_RUN_SESSION)
            savedState.remove<String>(KEY_RUN_ID)
        }
    }

    private suspend fun findSessionMetadata(host: HostProfile, sessionId: String): HermesSession? {
        var offset = 0
        repeat(MAX_DEEP_LINK_PAGES) {
            val page = runCatching { gateway.listSessions(host, limit = SESSION_PAGE_SIZE, offset = offset) }
                .getOrNull()
                ?: return null
            page.sessions.firstOrNull { it.id == sessionId }?.let { return it }
            if (!page.hasMore || page.sessions.isEmpty()) return null
            offset += page.sessions.size
        }
        return null
    }

    private fun applyLoadedMessages(hostId: String, requestedId: String, page: HermesMessagesPage) {
        val requestedKey = SessionKey(hostId, requestedId)
        val promptRecordsNeedRekey = requestedId != page.sessionId && (
            requestedKey in mutableState.value.unknownOutcomes || requestedKey in mutableState.value.queuedInterrupts
        )
        mutableState.update { state ->
            val resolvedKey = SessionKey(hostId, page.sessionId)
            val rekeyed = state.rekeySession(requestedKey, resolvedKey)
            // Adopt the resolved id only when the user still displays the
            // Session that was loaded — a background load must not hijack
            // navigation, only re-key.
            if (rekeyed.activeHostId != hostId || rekeyed.activeSessionId != page.sessionId) rekeyed
            else rekeyed.copy(
                activeSessionId = page.sessionId,
                transcriptResource = page.messages.mapNotNull(::mapStoredMessage).asResourceState(),
                pendingSessionTarget = rekeyed.pendingSessionTarget?.takeUnless { target ->
                    target == resolvedKey && rekeyed.connectionPhase == HostConnectionPhase.Connected
                },
            )
        }
        if (promptRecordsNeedRekey) persistPromptRecords()
    }

    private fun refreshSessionsAndJobs(host: HostProfile) {
        viewModelScope.launch {
            val sessions = runCatching { gateway.listSessions(host) }
            val refreshJobs = mutableState.value.jobsResource !is ResourceState.Unsupported
            val jobs = if (refreshJobs) runCatching { gateway.listJobs(host) } else null
            val activeSessions = runCatching { gateway.listActiveSessions(host) }.getOrNull()
            if (mutableState.value.activeHostId == host.id) {
                mutableState.update { state ->
                    val sessionPage = sessions.getOrNull()
                    state.withHostActivity(host.id, activeSessions).copy(
                        sessionsResource = sessionPage?.sessions?.asResourceState()
                            ?: state.sessionsResource.refreshError(friendlyMessage(checkNotNull(sessions.exceptionOrNull()))),
                        sessionsHasMore = sessionPage?.hasMore ?: state.sessionsHasMore,
                        sessionsNextOffset = sessionPage?.sessions?.size ?: state.sessionsNextOffset,
                        jobsResource = jobs?.fold(
                            onSuccess = { it.asResourceState() },
                            onFailure = { error ->
                                if (error.isUnsupportedResourceEndpoint()) ResourceState.Unsupported
                                else state.jobsResource.refreshError(friendlyMessage(error))
                            },
                        ) ?: state.jobsResource,
                    )
                }
            }
        }
    }

    private fun mapStoredMessage(message: HermesMessage): ChatUiItem? {
        val id = message.id ?: UUID.randomUUID().toString()
        return when (message.role) {
            "user" -> ChatUiItem.User(id, message.content)
            "assistant" -> message.content.takeIf(String::isNotBlank)?.let { ChatUiItem.Assistant(id, it) }
            "tool" -> ChatUiItem.Tool(id, message.toolName ?: "tool", message.content.take(180), running = false)
            else -> null
        }
    }

    /** The messages endpoint has no usage field, so never guess an association by position. */
    private fun completedUsageMessageId(
        messages: List<ChatUiItem>,
        completedUsage: HermesRunUsage?,
        completedText: String?,
    ): String? {
        completedUsage ?: return null
        val finalText = completedText?.trim()?.takeIf(String::isNotBlank) ?: return null
        val index = messages.indexOfLast { item ->
            item is ChatUiItem.Assistant && item.text.trim() == finalText
        }
        return (messages.getOrNull(index) as? ChatUiItem.Assistant)?.id
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

    private fun Throwable.isUnsupportedResourceEndpoint(): Boolean =
        this is HermesApiException && statusCode in setOf(404, 405, 501)

    // Structured, id-only run lifecycle trail. android.util.Log is stubbed in
    // JVM unit tests; swallow the stub's exception.
    private fun logRun(message: String) {
        runCatching { android.util.Log.i("HermesRun", message) }
    }

    companion object {
        private const val MAX_REASONING_UPDATES = 12
        private const val MAX_REASONING_UPDATE_LENGTH = 8_000
        private const val MAX_COMPOSER_LENGTH = 8_000
        private const val SESSION_PAGE_SIZE = 50
        private const val MAX_DEEP_LINK_PAGES = 20
        private const val RUN_STREAM_RETRY_DELAY_MS = 500L
        private const val MAX_RUN_STREAM_RETRY_DELAY_MS = 30_000L
        private const val RUN_SUBMIT_RETRY_DELAY_MS = 300L
        private const val SAVED_SESSION_DRAFTS = "composer.sessionDrafts"
        private const val SAVED_NEW_SESSION_DRAFTS = "composer.newSessionDrafts"
        private const val DRAFT_KEY_SEPARATOR = '\u001F'
        private const val KEY_RUN_HOST = "run.hostId"
        private const val KEY_RUN_SESSION = "run.sessionId"
        private const val KEY_RUN_ID = "run.runId"

        fun factory(safeStartup: Boolean = false): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                HermesViewModel(
                    gateway = HermesHttpGateway(),
                    hostStore = SecureHostStore(application),
                    savedState = createSavedStateHandle(),
                    settingsStore = PreferencesSettingsStore(application),
                    diagnostics = AppDiagnosticsRegistry.recorder,
                    activityPollingEnabled = true,
                    safeStartup = safeStartup,
                )
            }
        }

        val Factory: ViewModelProvider.Factory = factory()
    }
}
