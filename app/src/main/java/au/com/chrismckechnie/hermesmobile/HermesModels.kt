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
) {
    // The full Run-control bundle; anything less recreates the silent-approval
    // hang the /v1/runs transport exists to fix (docs/adr/0001).
    val supportsRuns: Boolean
        get() = features.containsAll(
            listOf("run_submission", "run_events_sse", "run_stop", "approval_events", "run_approval_response")
        )
    val supportsSkills: Boolean get() = "skills_api" in features
    val supportsReasoningEffort: Boolean get() = "run_reasoning_effort" in features
    val supportsSessionEdit: Boolean get() = "session_resources" in features
    val supportsSessionFork: Boolean get() = "session_fork" in features
}

data class HermesSession(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val model: String?,
    val lastActive: String?,
    val messageCount: Int?,
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

data class HermesActiveSession(
    val sessionId: String,
    val runId: String?,
    val title: String,
    val state: String,
    val surface: String,
)

/** Events on `GET /v1/runs/{run_id}/events` — data-only SSE, name in the JSON `event` field. */
sealed interface HermesRunEvent {
    data class MessageDelta(val delta: String) : HermesRunEvent
    data class ToolStarted(val tool: String, val preview: String?) : HermesRunEvent
    data class ToolCompleted(val tool: String, val failed: Boolean) : HermesRunEvent
    data class ApprovalRequested(val command: String?) : HermesRunEvent
    data class ApprovalResponded(val choice: String?) : HermesRunEvent
    data class Completed(val output: String) : HermesRunEvent
    data class Failed(val error: String) : HermesRunEvent
    data object Cancelled : HermesRunEvent
}

class HermesApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

interface HermesGateway {
    suspend fun probe(host: HostProfile): HermesCapabilities
    suspend fun listSessions(host: HostProfile, limit: Int = 50, offset: Int = 0): HermesSessionPage
    suspend fun createSession(host: HostProfile, title: String? = null): HermesSession
    suspend fun loadMessages(host: HostProfile, sessionId: String): HermesMessagesPage
    suspend fun listJobs(host: HostProfile): List<HermesJob>
    suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean)
    suspend fun runJob(host: HostProfile, jobId: String)
    suspend fun listSkills(host: HostProfile): List<HermesSkill>
    suspend fun listModels(host: HostProfile): List<String>
    suspend fun submitRun(
        host: HostProfile,
        sessionId: String,
        input: String,
        history: List<HermesMessage>,
        model: String? = null,
        reasoningEffort: String? = null,
    ): String
    suspend fun streamRunEvents(host: HostProfile, runId: String, onEvent: (HermesRunEvent) -> Unit)
    suspend fun getRunStatus(host: HostProfile, runId: String): HermesRunStatus
    suspend fun respondApproval(host: HostProfile, runId: String, choice: String)
    suspend fun stopRun(host: HostProfile, runId: String)
    suspend fun renameSession(host: HostProfile, sessionId: String, title: String): HermesSession
    suspend fun deleteSession(host: HostProfile, sessionId: String)
    suspend fun forkSession(host: HostProfile, sessionId: String): HermesSession
    suspend fun listActiveSessions(host: HostProfile): List<HermesActiveSession> = emptyList()
    suspend fun registerMobileDevice(
        host: HostProfile,
        installationId: String,
        token: String,
        appVersion: String,
        overlayEnabled: Boolean,
    ) = Unit
    suspend fun unregisterMobileDevice(host: HostProfile, installationId: String) = Unit
}
