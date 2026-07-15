package au.com.chrismckechnie.hermesmobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class HermesHttpGateway(
    internal val ordinaryClient: OkHttpClient = defaultOrdinaryClient(),
    internal val eventStreamClient: OkHttpClient = defaultEventStreamClient(ordinaryClient),
) : HermesGateway {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun probe(host: HostProfile): HermesCapabilities = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, listOf("v1", "capabilities")))
        val featuresJson = data.optJSONObject("features") ?: JSONObject()
        val features = buildSet {
            val keys = featuresJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = featuresJson.opt(key)) {
                    true -> add(key)
                    is String -> if (value.isNotBlank()) add(key)
                    is Number -> if (value.toInt() != 0) add(key)
                }
            }
        }
        HermesCapabilities(
            model = data.optNullableString("model") ?: "hermes-agent",
            platform = data.optNullableString("platform") ?: "hermes-agent",
            features = features,
            version = data.optNullableString("version"),
            defaultModel = data.optNullableString("default_model"),
        )
    }

    override suspend fun getHostVersion(host: HostProfile): String? = withContext(Dispatchers.IO) {
        executeJson(host, request(host, listOf("health"))).optNullableString("version")
    }

    override suspend fun getHostUpdate(host: HostProfile, force: Boolean): HermesHostUpdate = withContext(Dispatchers.IO) {
        val url = endpoint(host, "v1", "host-update").newBuilder()
            .apply { if (force) addQueryParameter("force", "true") }
            .build()
        val data = executeJson(host, request(host, url))
        HermesHostUpdate(
            currentVersion = data.optNullableString("current_version") ?: "Unknown",
            updateAvailable = data.optBoolean("update_available", false),
            canApply = data.optBoolean("can_apply", false),
            message = data.optNullableString("message"),
            updateCommand = data.optNullableString("update_command"),
        )
    }

    override suspend fun updateHost(host: HostProfile): HermesHostUpdateStart = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, endpoint(host, "v1", "host-update"), method = "POST", body = JSONObject()))
        HermesHostUpdateStart(
            accepted = data.optBoolean("accepted", data.optBoolean("ok", false)),
            message = data.optNullableString("message"),
        )
    }

    override suspend fun listSessions(host: HostProfile, limit: Int, offset: Int): HermesSessionPage = withContext(Dispatchers.IO) {
        val url = endpoint(host, "api", "sessions").newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 200).toString())
            .addQueryParameter("offset", offset.coerceAtLeast(0).toString())
            // Forked sessions are children; the host hides them by default.
            .addQueryParameter("include_children", "true")
            .build()
        val data = executeJson(host, request(host, url))
        val rows = data.optJSONArray("data")
            ?: throw HermesApiException(200, "Hermes returned an unexpected session list shape.")
        HermesSessionPage(
            sessions = rows.toObjectList(::parseSession),
            hasMore = data.optBoolean("has_more", false),
        )
    }

    override suspend fun createSession(host: HostProfile, title: String?): HermesSession = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            if (!title.isNullOrBlank()) put("title", title.trim())
        }
        val request = request(
            host,
            endpoint(host, "api", "sessions"),
            method = "POST",
            body = body,
        )
        val data = executeJson(host, request)
        parseSession(data.getJSONObject("session"))
    }

    override suspend fun loadMessages(host: HostProfile, sessionId: String): HermesMessagesPage = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, endpoint(host, "api", "sessions", sessionId, "messages")))
        HermesMessagesPage(
            // The envelope carries the resolved id — a rotated session resolves
            // to its continuation here.
            sessionId = data.optNullableString("session_id") ?: sessionId,
            messages = data.optJSONArray("data").toObjectList(::parseMessage),
        )
    }

    override suspend fun listJobs(host: HostProfile): List<HermesJob> = withContext(Dispatchers.IO) {
        val url = endpoint(host, "api", "jobs").newBuilder()
            .addQueryParameter("include_disabled", "true")
            .build()
        val data = executeJson(host, request(host, url))
        data.optJSONArray("jobs").toObjectList { json ->
            HermesJob(
                id = json.optString("id"),
                name = json.optString("name", "Scheduled job"),
                schedule = json.optString("schedule", "Unknown schedule"),
                enabled = json.optBoolean("enabled", true),
                deliver = json.optNullableString("deliver"),
            )
        }
    }

    override suspend fun setJobEnabled(host: HostProfile, jobId: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        val action = if (enabled) "resume" else "pause"
        executeIgnoringBody(request(host, endpoint(host, "api", "jobs", jobId, action), method = "POST"))
    }

    override suspend fun runJob(host: HostProfile, jobId: String): Unit = withContext(Dispatchers.IO) {
        executeIgnoringBody(request(host, endpoint(host, "api", "jobs", jobId, "run"), method = "POST"))
    }

    override suspend fun listSkills(host: HostProfile): List<HermesSkill> = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, listOf("v1", "skills")))
        data.optJSONArray("data").toObjectList { json ->
            HermesSkill(
                name = json.optString("name"),
                description = json.optNullableString("description"),
            )
        }.filter { it.name.isNotBlank() }
    }

    override suspend fun listToolsets(host: HostProfile): List<HermesToolset> = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, listOf("v1", "toolsets")))
        data.optJSONArray("data").toObjectList { json ->
            HermesToolset(
                name = json.optString("name"),
                label = json.optString("label").ifBlank { json.optString("name") },
                description = json.optNullableString("description"),
                enabled = json.optBoolean("enabled", false),
                configured = json.optBoolean("configured", false),
                tools = json.optJSONArray("tools").toStringList(),
            )
        }.filter { it.name.isNotBlank() }
    }

    override suspend fun listModels(host: HostProfile): List<String> = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, listOf("v1", "models")))
        data.optJSONArray("data").toObjectList { json -> json.optString("id") }
            .filter { it.isNotBlank() }
            .distinct()
    }

    override suspend fun submitRun(
        host: HostProfile,
        sessionId: String,
        input: String,
        history: List<HermesMessage>,
        model: String?,
        reasoningEffort: String?,
        permissionMode: String?,
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("input", input)
            put("session_id", sessionId)
            if (!model.isNullOrBlank()) put("model", model)
            if (!reasoningEffort.isNullOrBlank()) put("reasoning_effort", reasoningEffort)
            if (!permissionMode.isNullOrBlank()) put("permission_mode", permissionMode)
            put("conversation_history", JSONArray().apply {
                // Tool messages don't survive the host's {role, content}
                // reduction; send only substantive user/assistant turns.
                history.filter { it.role == "user" || it.role == "assistant" }
                    .filter { it.content.isNotBlank() }
                    .forEach { message ->
                        put(JSONObject().apply {
                            put("role", message.role)
                            put("content", message.content)
                        })
                    }
            })
        }
        val data = executeJson(host, request(host, endpoint(host, "v1", "runs"), method = "POST", body = body))
        data.optNullableString("run_id") ?: throw HermesApiException(502, "Hermes did not return a run id.")
    }

    override suspend fun streamRunEvents(
        host: HostProfile,
        runId: String,
        onEvent: (HermesRunEvent) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val streamRequest = request(host, endpoint(host, "v1", "runs", runId, "events"))
        val call = eventStreamClient.newCall(streamRequest)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        ensureSuccessful(response)
                        val source = response.body?.source()
                            ?: throw HermesApiException(response.code, "Hermes returned an empty stream.")
                        val dataLines = mutableListOf<String>()

                        fun dispatch() {
                            if (dataLines.isEmpty() || !continuation.isActive) return
                            val payloadText = dataLines.joinToString("\n")
                            dataLines.clear()
                            val payload = runCatching { JSONObject(payloadText) }.getOrNull() ?: return
                            parseRunEvent(payload)?.let(onEvent)
                        }

                        while (continuation.isActive && !source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isBlank() -> dispatch()
                                line.startsWith(":") -> Unit // keepalive / stream-closed comments
                                line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
                            }
                        }
                        dispatch()
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
        })
    }

    private fun parseRunEvent(payload: JSONObject): HermesRunEvent? = when (payload.optString("event")) {
        "message.delta" -> HermesRunEvent.MessageDelta(payload.optString("delta"))
        "reasoning.available" -> HermesRunEvent.ReasoningAvailable(payload.optString("text"))
        "tool.started" -> HermesRunEvent.ToolStarted(
            payload.optNullableString("tool") ?: "tool",
            payload.optNullableString("preview"),
        )
        "tool.completed" -> HermesRunEvent.ToolCompleted(
            payload.optNullableString("tool") ?: "tool",
            failed = payload.optBoolean("error", false),
        )
        "tasks.updated" -> HermesRunEvent.TasksUpdated(
            payload.optJSONArray("tasks").toObjectList(::parseTask).filterNotNull(),
        )
        "subagent.updated" -> payload.optJSONObject("subagent")
            ?.let(::parseSubagent)
            ?.let(HermesRunEvent::SubagentUpdated)
        "approval.request" -> HermesRunEvent.ApprovalRequested(payload.optNullableString("command"))
        "approval.responded" -> HermesRunEvent.ApprovalResponded(payload.optNullableString("choice"))
        "run.completed" -> HermesRunEvent.Completed(
            output = payload.optString("output"),
            usage = payload.optRunUsage(),
        )
        "run.failed" -> HermesRunEvent.Failed(payload.optString("error", "Hermes run failed."))
        "run.cancelled" -> HermesRunEvent.Cancelled
        else -> null // Future events remain forward-compatible.
    }

    override suspend fun getRunStatus(host: HostProfile, runId: String): HermesRunStatus = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, endpoint(host, "v1", "runs", runId)))
        HermesRunStatus(
            runId = data.optNullableString("run_id") ?: runId,
            status = data.optString("status", "unknown"),
        )
    }

    override suspend fun listActiveSessions(host: HostProfile): List<HermesActiveSession> = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, listOf("v1", "active-sessions")))
        data.optJSONArray("data").toObjectList { json ->
            HermesActiveSession(
                sessionId = json.optString("session_id"),
                runId = json.optNullableString("run_id"),
                title = json.optString("title", "Hermes session"),
                state = json.optString("state", "active"),
                surface = json.optString("surface", "unknown"),
                latestStatus = json.optNullableString("latest_status"),
                updatedAt = json.optNullableEpochSeconds("updated_at"),
            )
        }.filter { it.sessionId.isNotBlank() }
    }

    override suspend fun registerMobileDevice(
        host: HostProfile,
        installationId: String,
        token: String,
        appVersion: String,
        overlayEnabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("fid", token)
            put("host_profile_id", host.id)
            put("app_version", appVersion)
            put("capabilities", JSONObject().apply {
                put("notifications", true)
                put("bubbles", true)
                put("overlay", overlayEnabled)
            })
        }
        executeIgnoringBody(request(host, endpoint(host, "v1", "mobile", "devices", installationId), method = "PUT", body = body))
    }

    override suspend fun unregisterMobileDevice(host: HostProfile, installationId: String) = withContext(Dispatchers.IO) {
        executeIgnoringBody(request(host, endpoint(host, "v1", "mobile", "devices", installationId), method = "DELETE"))
    }

    override suspend fun respondApproval(host: HostProfile, runId: String, choice: String) {
        withContext(Dispatchers.IO) {
            executeJson(
                host,
                request(host, endpoint(host, "v1", "runs", runId, "approval"), method = "POST", body = JSONObject().put("choice", choice)),
            )
        }
    }

    override suspend fun stopRun(host: HostProfile, runId: String) {
        withContext(Dispatchers.IO) {
            executeJson(host, request(host, endpoint(host, "v1", "runs", runId, "stop"), method = "POST"))
        }
    }

    override suspend fun renameSession(host: HostProfile, sessionId: String, title: String): HermesSession = withContext(Dispatchers.IO) {
        val request = request(
            host,
            endpoint(host, "api", "sessions", sessionId),
            method = "PATCH",
            body = JSONObject().put("title", title),
        )
        parseSession(executeJson(host, request).getJSONObject("session"))
    }

    override suspend fun deleteSession(host: HostProfile, sessionId: String) {
        withContext(Dispatchers.IO) {
            executeJson(host, request(host, endpoint(host, "api", "sessions", sessionId), method = "DELETE"))
        }
    }

    override suspend fun forkSession(host: HostProfile, sessionId: String): HermesSession = withContext(Dispatchers.IO) {
        val request = request(
            host,
            endpoint(host, "api", "sessions", sessionId, "fork"),
            method = "POST",
        )
        parseSession(executeJson(host, request).getJSONObject("session"))
    }

    private fun request(
        host: HostProfile,
        path: List<String>,
        method: String = "GET",
        body: JSONObject? = null,
    ): Request = request(host, path.fold(host.validated().baseUrl.toHttpUrl().newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build(), method, body)

    private fun request(
        host: HostProfile,
        url: HttpUrl,
        method: String = "GET",
        body: JSONObject? = null,
    ): Request {
        val validated = host.validated()
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${validated.apiKey}")
            .header("Accept", if (url.encodedPath.endsWith("/events")) "text/event-stream" else "application/json")
            .header("X-Hermes-Session-Key", "hermes-mobile:${validated.id}")
        if (method == "GET") builder.get()
        else builder.method(method, (body ?: JSONObject()).toString().toRequestBody(jsonMediaType))
        return builder.build()
    }

    private fun endpoint(host: HostProfile, vararg segments: String): HttpUrl =
        segments.fold(host.validated().baseUrl.toHttpUrl().newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()

    private fun executeJson(host: HostProfile, request: Request): JSONObject {
        ordinaryClient.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            val raw = response.body?.string().orEmpty()
            if (raw.isBlank()) throw HermesApiException(response.code, "Hermes returned an empty response.")
            return runCatching { JSONObject(raw) }.getOrElse {
                throw HermesApiException(response.code, "Hermes returned an unreadable response.")
            }
        }
    }

    private fun executeIgnoringBody(request: Request) {
        ordinaryClient.newCall(request).execute().use(::ensureSuccessful)
    }

    private fun ensureSuccessful(response: Response) {
        if (response.isSuccessful) return
        val raw = response.body?.string().orEmpty()
        val message = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull().takeUnless { it.isNullOrBlank() }
            ?: when (response.code) {
                401 -> "API key was rejected by this Hermes host."
                404 -> "This host does not expose the required Hermes endpoint."
                else -> "Hermes request failed with HTTP ${response.code}."
            }
        throw HermesApiException(response.code, message)
    }

    private fun parseSession(json: JSONObject) = HermesSession(
        id = json.getString("id"),
        title = json.optNullableString("title"),
        preview = json.optNullableString("preview"),
        source = json.optNullableString("source"),
        model = json.optNullableString("model"),
        lastActive = json.optNullableString("last_active"),
        messageCount = json.optInt("message_count").takeIf { json.has("message_count") && !json.isNull("message_count") },
        isActive = json.optBoolean("is_active", false),
    )

    private fun parseMessage(json: JSONObject) = HermesMessage(
        id = json.optNullableString("id"),
        role = json.optString("role", "assistant"),
        content = json.optNullableString("content").orEmpty(),
        toolName = json.optNullableString("tool_name"),
        timestamp = json.optNullableString("timestamp"),
    )

    private fun parseTask(json: JSONObject): HermesTask? {
        val id = json.optNullableString("id")?.trim()?.take(120) ?: return null
        val content = json.optNullableString("content")?.trim()?.take(240) ?: return null
        val status = json.optString("status", "pending").trim().lowercase()
        if (id.isBlank() || content.isBlank() || status !in TASK_STATUSES) return null
        return HermesTask(id = id, content = content, status = status)
    }

    private fun parseSubagent(json: JSONObject): HermesSubagent? {
        val id = json.optNullableString("id")?.trim()?.take(120) ?: return null
        val status = json.optString("status", "working").trim().lowercase()
        if (id.isBlank() || status !in SUBAGENT_STATUSES) return null
        return HermesSubagent(
            id = id,
            status = status,
            taskIndex = json.optInt("task_index", 0).coerceAtLeast(0),
            taskCount = json.optInt("task_count", 0).coerceAtLeast(0),
            toolCount = json.optInt("tool_count", 0).coerceAtLeast(0),
            goal = json.optNullableString("goal")?.trim()?.take(240),
            activity = json.optNullableString("activity")?.trim()?.take(240),
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val ORDINARY_READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 20L
        const val ORDINARY_CALL_TIMEOUT_SECONDS = 45L
        val TASK_STATUSES = setOf("pending", "in_progress", "completed", "cancelled")
        val SUBAGENT_STATUSES = setOf(
            "running", "working", "thinking", "completed", "failed", "timeout", "interrupted", "error",
        )

        fun defaultOrdinaryClient(): OkHttpClient = secureClientBuilder()
            .readTimeout(ORDINARY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(ORDINARY_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        fun defaultEventStreamClient(baseClient: OkHttpClient): OkHttpClient = baseClient.newBuilder()
            // SSE is intentionally unbounded. Coroutine cancellation closes
            // the active Call, while the host may keep a healthy run open for
            // much longer than an ordinary request timeout.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        fun secureClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Never follow http<->https scheme changes: authenticated traffic
            // must not silently downgrade to cleartext.
            .followSslRedirects(false)
    }
}

private fun JSONObject.optRunUsage(): HermesRunUsage? {
    val usage = optJSONObject("usage") ?: return null
    val parsed = HermesRunUsage(
        inputTokens = usage.optNonNegativeLong("input_tokens"),
        outputTokens = usage.optNonNegativeLong("output_tokens"),
        totalTokens = usage.optNonNegativeLong("total_tokens"),
    )
    return parsed.takeUnless(HermesRunUsage::isEmpty)
}

private fun JSONObject.optNonNegativeLong(name: String): Long? =
    takeIf { has(name) && !isNull(name) }
        ?.optLong(name, -1L)
        ?.takeIf { it >= 0L }

private fun JSONObject.optNullableEpochSeconds(name: String): Long? =
    takeIf { has(name) && !isNull(name) }
        ?.optDouble(name, Double.NaN)
        ?.takeIf(Double::isFinite)
        ?.toLong()
        ?.takeIf { it >= 0L }

private inline fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val row = optJSONObject(index) ?: continue
            runCatching { transform(row) }.getOrNull()?.let(::add)
        }
    }
}

private fun JSONArray?.toStringList(): List<String> = buildList {
    this@toStringList ?: return@buildList
    for (index in 0 until length()) {
        optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return opt(name)?.toString()?.takeIf { it.isNotBlank() }
}
