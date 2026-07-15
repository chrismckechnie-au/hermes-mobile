package au.com.chrismckechnie.hermesmobile

import java.net.URI

data class HostProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val allowInsecureHttp: Boolean = false,
) {
    fun validated(): HostProfile {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Give this host a name." }
        val normalizedUrl = normalizeHermesBaseUrl(baseUrl)
        val scheme = URI(normalizedUrl).scheme.lowercase()
        require(scheme == "https" || allowInsecureHttp) {
            "HTTP is unencrypted. Enable private-network HTTP only for a trusted LAN or VPN."
        }
        require(apiKey.isNotBlank()) { "Hermes API key is required." }
        return copy(name = normalizedName, baseUrl = normalizedUrl, apiKey = apiKey.trim())
    }

    // Never leak the bearer key through logs or debug output.
    override fun toString(): String = "HostProfile(id=$id, name=$name, baseUrl=$baseUrl)"
}

fun normalizeHermesBaseUrl(raw: String): String {
    var value = raw.trim().trimEnd('/')
    if (value.endsWith("/v1", ignoreCase = true)) value = value.dropLast(3).trimEnd('/')
    val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Enter a valid Hermes host URL.") }
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
        "Enter a full URL such as https://hermes.example.com."
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Host URL must not contain credentials, a query, or a fragment."
    }
    require(uri.path.isNullOrBlank() || uri.path == "/") {
        "Use the Hermes server root; /v1 is accepted and removed automatically."
    }
    return URI(uri.scheme.lowercase(), null, uri.host, uri.port, null, null, null).toString().trimEnd('/')
}

data class HostSnapshot(
    val hosts: List<HostProfile> = emptyList(),
    val selectedHostId: String? = null,
)

data class HermesCapabilities(
    val model: String,
    val platform: String,
    val features: Set<String>,
    val version: String? = null,
    val defaultModel: String? = null,
) {
    // The full Run-control bundle; anything less recreates the silent-approval
    // hang the /v1/runs transport exists to fix (docs/adr/0001).
    val supportsRuns: Boolean
        get() = features.containsAll(
            listOf("run_submission", "run_events_sse", "run_stop", "approval_events", "run_approval_response")
        )
    val supportsSkills: Boolean get() = "skills_api" in features
    val supportsReasoningEffort: Boolean get() = "run_reasoning_effort" in features
    val supportsPermissionMode: Boolean get() = "run_permission_mode" in features
    val supportsSessionEdit: Boolean get() = "session_resources" in features
    val supportsSessionFork: Boolean get() = "session_fork" in features
    val supportsRunTaskUpdates: Boolean get() = "run_task_updates" in features
    val supportsRunSubagentUpdates: Boolean get() = "run_subagent_updates" in features
    val supportsRunWorkspaceUpdates: Boolean get() = "run_workspace_updates" in features
    val supportsRunEventReplay: Boolean get() = "run_event_replay" in features
    val supportsRunSubmissionIdempotency: Boolean get() = "run_submission_idempotency" in features
    val supportsActiveSessionCleanup: Boolean get() = "active_session_cleanup" in features
    val supportsRunSlashCommands: Boolean get() = "run_slash_commands" in features
    /** The host explicitly opts in before mobile exposes a remote updater. */
    val supportsHostUpdate: Boolean get() = "host_update_api" in features
}

/** A host-reported update check. `canApply` is false for managed installs such as Docker. */
data class HermesHostUpdate(
    val currentVersion: String,
    val updateAvailable: Boolean,
    val canApply: Boolean,
    val message: String? = null,
    val updateCommand: String? = null,
)

/** The acknowledgement received before the host restarts itself to apply an update. */
data class HermesHostUpdateStart(
    val accepted: Boolean,
    val message: String? = null,
)

data class HermesSession(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val model: String?,
    val lastActive: String?,
    val messageCount: Int?,
    /** Mirrors the desktop session list's recent unfinished-work indicator. */
    val isActive: Boolean = false,
)

data class HermesMessage(
    val id: String?,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val timestamp: String? = null,
)

data class HermesMessagesPage(
    val sessionId: String,
    val messages: List<HermesMessage>,
)

data class HermesSessionPage(
    val sessions: List<HermesSession>,
    val hasMore: Boolean,
)

data class HermesJob(
    val id: String,
    val name: String,
    val schedule: String,
    val enabled: Boolean,
    val deliver: String?,
)

data class HermesSkill(
    val name: String,
    val description: String?,
)

/** A host-configured group of tools. It may be backed by a plugin. */
data class HermesToolset(
    val name: String,
    val label: String,
    val description: String?,
    val enabled: Boolean,
    val configured: Boolean,
    val tools: List<String>,
)

data class HermesRunStatus(
    val runId: String,
    val status: String,
) {
    val isTerminal: Boolean get() = status in TERMINAL
    val isWaitingForApproval: Boolean get() = status == "waiting_for_approval"

    private companion object {
        val TERMINAL = setOf("completed", "failed", "cancelled")
    }
}

/** Token counts reported by Hermes when a Run reaches a terminal state. */
data class HermesRunUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
) {
    val isEmpty: Boolean get() = inputTokens == null && outputTokens == null && totalTokens == null
}

/** Compact, locale-neutral usage copy intended for the small chat metadata line. */
internal fun formatRunUsage(usage: HermesRunUsage?): String? {
    usage ?: return null
    if (usage.isEmpty) return null
    val fields = buildList {
        usage.inputTokens?.let { add("${formatTokenCount(it)} in") }
        usage.outputTokens?.let { add("${formatTokenCount(it)} out") }
        usage.totalTokens?.let { add("${formatTokenCount(it)} tokens") }
    }
    return fields.joinToString(" · ").takeIf(String::isNotBlank)
}

private fun formatTokenCount(value: Long): String = when {
    value < 1_000L -> value.toString()
    value < 10_000L -> "%.1f".format(java.util.Locale.ROOT, value / 1_000.0)
        .removeSuffix(".0") + "k"
    else -> "${value / 1_000L}k"
}

/** Non-secret coordinates for targeting a run action without relying on current navigation. */
data class RunRef(
    val hostId: String,
    val sessionId: String,
    val runId: String,
)

data class HermesActiveSession(
    val sessionId: String,
    val runId: String?,
    val title: String,
    val state: String,
    val surface: String,
    val latestStatus: String? = null,
    val updatedAt: Long? = null,
    val leaseId: String? = null,
)

/** A bounded, host-approved todo item for the active Run. */
data class HermesTask(
    val id: String,
    val content: String,
    val status: String,
) {
    val isComplete: Boolean get() = status == "completed"
}

/** A bounded, host-approved delegated-work status for the active Run. */
data class HermesSubagent(
    val id: String,
    val status: String,
    val taskIndex: Int = 0,
    val taskCount: Int = 0,
    val toolCount: Int = 0,
    val goal: String? = null,
    val activity: String? = null,
) {
    val isWorking: Boolean get() = status in ACTIVE_SUBAGENT_STATUSES
}

private val ACTIVE_SUBAGENT_STATUSES = setOf("running", "working", "thinking")

/** Bounded, host-reported workspace changes for a Run. Diff text may be omitted. */
data class HermesWorkspaceChange(
    val path: String,
    val status: String,
    val additions: Int? = null,
    val deletions: Int? = null,
    val diff: String? = null,
)

data class HermesWorkspaceUpdate(
    val files: List<HermesWorkspaceChange>,
    val truncated: Boolean = false,
)

/** Events on `GET /v1/runs/{run_id}/events` — data-only SSE, name in the JSON `event` field. */
sealed interface HermesRunEvent {
    val eventId: Long? get() = null

    data class MessageDelta(val delta: String, override val eventId: Long? = null) : HermesRunEvent
    /** Host-approved progress text; this is not the model's private chain of thought. */
    data class ReasoningAvailable(val text: String, override val eventId: Long? = null) : HermesRunEvent
    data class ToolStarted(val tool: String, val preview: String?, override val eventId: Long? = null) : HermesRunEvent
    data class ToolCompleted(val tool: String, val failed: Boolean, override val eventId: Long? = null) : HermesRunEvent
    data class TasksUpdated(val tasks: List<HermesTask>, override val eventId: Long? = null) : HermesRunEvent
    data class SubagentUpdated(val subagent: HermesSubagent, override val eventId: Long? = null) : HermesRunEvent
    data class WorkspaceUpdated(val update: HermesWorkspaceUpdate, override val eventId: Long? = null) : HermesRunEvent
    data class ApprovalRequested(val command: String?, override val eventId: Long? = null) : HermesRunEvent
    data class ApprovalResponded(val choice: String?, override val eventId: Long? = null) : HermesRunEvent
    data class Completed(
        val output: String,
        val usage: HermesRunUsage? = null,
        override val eventId: Long? = null,
    ) : HermesRunEvent
    data class Failed(val error: String, override val eventId: Long? = null) : HermesRunEvent
    data object Cancelled : HermesRunEvent
}

class HermesApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

interface HermesGateway {
    suspend fun probe(host: HostProfile): HermesCapabilities
    suspend fun getHostVersion(host: HostProfile): String? = null
    suspend fun getHostUpdate(host: HostProfile, force: Boolean = false): HermesHostUpdate? = null
    suspend fun updateHost(host: HostProfile): HermesHostUpdateStart? = null
    suspend fun listSessions(host: HostProfile, limit: Int = 50, offset: Int = 0): HermesSessionPage
    suspend fun createSession(host: HostProfile, title: String? = null): HermesSession
    suspend fun loadMessages(host: HostProfile, sessionId: String): HermesMessagesPage
    suspend fun listJobs(host: HostProfile): List<HermesJob>
    suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean)
    suspend fun runJob(host: HostProfile, jobId: String)
    suspend fun listSkills(host: HostProfile): List<HermesSkill>
    suspend fun listToolsets(host: HostProfile): List<HermesToolset> = emptyList()
    suspend fun listModels(host: HostProfile): List<String>
    suspend fun submitRun(
        host: HostProfile,
        sessionId: String,
        input: String,
        history: List<HermesMessage>,
        model: String? = null,
        reasoningEffort: String? = null,
        permissionMode: String? = null,
        idempotencyKey: String? = null,
    ): String
    suspend fun streamRunEvents(
        host: HostProfile,
        runId: String,
        onEvent: (HermesRunEvent) -> Unit,
    ) = streamRunEvents(host, runId, null, onEvent)
    suspend fun streamRunEvents(
        host: HostProfile,
        runId: String,
        afterEventId: Long?,
        onEvent: (HermesRunEvent) -> Unit,
    )
    suspend fun getRunStatus(host: HostProfile, runId: String): HermesRunStatus
    suspend fun respondApproval(host: HostProfile, runId: String, choice: String)
    suspend fun stopRun(host: HostProfile, runId: String)
    suspend fun renameSession(host: HostProfile, sessionId: String, title: String): HermesSession
    suspend fun deleteSession(host: HostProfile, sessionId: String)
    suspend fun forkSession(host: HostProfile, sessionId: String): HermesSession
    suspend fun listActiveSessions(host: HostProfile): List<HermesActiveSession> = emptyList()
    suspend fun clearStaleActiveSession(host: HostProfile, leaseId: String) = Unit
    suspend fun registerMobileDevice(
        host: HostProfile,
        installationId: String,
        token: String,
        appVersion: String,
        overlayEnabled: Boolean,
    ) = Unit
    suspend fun unregisterMobileDevice(host: HostProfile, installationId: String) = Unit
}
