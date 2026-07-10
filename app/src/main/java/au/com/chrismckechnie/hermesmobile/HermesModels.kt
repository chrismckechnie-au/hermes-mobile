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
)

data class HermesSession(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val model: String?,
    val lastActive: String?,
    val messageCount: Int?,
)

data class HermesSessionPage(
    val sessions: List<HermesSession>,
    val hasMore: Boolean,
)

data class HermesMessage(
    val id: String?,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val timestamp: String? = null,
)

data class HermesJob(
    val id: String,
    val name: String,
    val schedule: String,
    val enabled: Boolean,
    val deliver: String?,
)

sealed interface HermesStreamEvent {
    data class RunStarted(val runId: String?) : HermesStreamEvent
    data class AssistantDelta(val text: String) : HermesStreamEvent
    data class ToolStarted(val toolName: String, val preview: String?) : HermesStreamEvent
    data class ToolCompleted(val toolName: String, val preview: String?, val failed: Boolean = false) : HermesStreamEvent
    data class ApprovalRequested(
        val approvalId: String,
        val toolName: String?,
        val message: String?,
        val runId: String?,
    ) : HermesStreamEvent
    data class Completed(val content: String, val sessionId: String?) : HermesStreamEvent
    data class Failed(val message: String) : HermesStreamEvent
    data class Unknown(val name: String) : HermesStreamEvent
    data object Done : HermesStreamEvent
}

class HermesApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

interface HermesGateway {
    suspend fun probe(host: HostProfile): HermesCapabilities
    suspend fun listSessions(host: HostProfile, limit: Int = 50, offset: Int = 0): HermesSessionPage
    suspend fun createSession(host: HostProfile, title: String? = null): HermesSession
    suspend fun loadMessages(host: HostProfile, sessionId: String): List<HermesMessage>
    suspend fun renameSession(host: HostProfile, sessionId: String, title: String)
    suspend fun deleteSession(host: HostProfile, sessionId: String)
    suspend fun listJobs(host: HostProfile): List<HermesJob>
    suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean)
    suspend fun runJob(host: HostProfile, jobId: String)
    suspend fun stopRun(host: HostProfile, runId: String)
    suspend fun resolveApproval(host: HostProfile, runId: String, approvalId: String, approve: Boolean)
    suspend fun streamSessionChat(
        host: HostProfile,
        sessionId: String,
        input: String,
        onEvent: (HermesStreamEvent) -> Unit,
    )
}
