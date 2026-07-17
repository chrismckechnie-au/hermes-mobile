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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class HermesHttpGateway(
    internal val ordinaryClient: OkHttpClient = defaultOrdinaryClient(),
    internal val eventStreamClient: OkHttpClient = defaultEventStreamClient(ordinaryClient),
) : HermesGateway {
    override suspend fun exchangeMobilePairing(
        request: MobilePairingRequest,
        installationId: String,
        deviceName: String,
    ): MobilePairingResult = withContext(Dispatchers.IO) {
        val url = request.baseUrl.toHttpUrlOrNull()
            ?: throw HermesApiException(400, "The pairing link contains an invalid host URL.")
        val body = JSONObject().apply {
            put("grant", request.grant)
            put("installation_id", installationId)
            put("device_name", deviceName.take(120))
        }.toString().toRequestBody(jsonMediaType)
        val response = ordinaryClient.newCall(
            Request.Builder()
                .url(url.newBuilder().addPathSegments("v1/mobile/pairing/exchange").build())
                .post(body)
                .build(),
        ).execute()
        response.use {
            ensureSuccessful(response)
            val json = JSONObject(readBoundedBody(response))
            val token = json.optString("token")
            if (token.isBlank()) throw HermesApiException(502, "Hermes did not return a device token.")
            MobilePairingResult(token, json.optString("device_id"))
        }
    }
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
            sessions = rows.toObjectList(::parseSession).distinctBy(HermesSession::id),
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
        val data = executeJson(
            host,
            request(host, endpoint(host, "api", "sessions", sessionId, "messages")),
            maxBytes = MAX_TRANSCRIPT_JSON_BYTES.toLong(),
        )
        HermesMessagesPage(
            // The envelope carries the resolved id — a rotated session resolves
            // to its continuation here.
            sessionId = data.optNullableString("session_id") ?: sessionId,
            messages = data.requireListArray("data", "message").toObjectList(::parseMessage),
        )
    }

    override suspend fun listJobs(host: HostProfile): List<HermesJob> = withContext(Dispatchers.IO) {
        val url = endpoint(host, "api", "jobs").newBuilder()
            .addQueryParameter("include_disabled", "true")
            .build()
        val data = executeJson(host, request(host, url))
        data.requireListArray("jobs", "job").toObjectList { json ->
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
        data.requireListArray("data", "skill").toObjectList { json ->
            HermesSkill(
                name = json.optString("name"),
                description = json.optNullableString("description"),
            )
        }.filter { it.name.isNotBlank() }
    }

    override suspend fun listToolsets(host: HostProfile): List<HermesToolset> = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, listOf("v1", "toolsets")))
        data.requireListArray("data", "toolset").toObjectList { json ->
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
        data.requireListArray("data", "model").toObjectList { json -> json.optString("id") }
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
        idempotencyKey: String?,
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
        val submitRequest = request(host, endpoint(host, "v1", "runs"), method = "POST", body = body)
            .newBuilder()
            .apply {
                idempotencyKey?.trim()?.takeIf(String::isNotBlank)?.let {
                    header("Idempotency-Key", it)
                }
            }
            .build()
        val data = executeJson(host, submitRequest)
        data.optNullableString("run_id") ?: throw HermesApiException(502, "Hermes did not return a run id.")
    }

    override suspend fun streamRunEvents(
        host: HostProfile,
        runId: String,
        afterEventId: Long?,
        onEvent: (HermesRunEvent) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val streamRequest = request(host, endpoint(host, "v1", "runs", runId, "events"))
            .newBuilder()
            .apply {
                afterEventId?.takeIf { it > 0L }?.let { header("Last-Event-ID", it.toString()) }
            }
            .build()
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
                            val line = source.readBoundedUtf8Line() ?: break
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

    private fun parseRunEvent(payload: JSONObject): HermesRunEvent? {
        val eventId = payload.optLong("event_id").takeIf { payload.has("event_id") && it > 0L }
        return when (payload.optString("event")) {
            "message.delta" -> HermesRunEvent.MessageDelta(payload.optString("delta"), eventId)
            "reasoning.available" -> HermesRunEvent.ReasoningAvailable(payload.optString("text"), eventId)
            "tool.started" -> HermesRunEvent.ToolStarted(
                payload.optNullableString("tool") ?: "tool",
                payload.optNullableString("preview"),
                eventId,
            )
            "tool.completed" -> HermesRunEvent.ToolCompleted(
                payload.optNullableString("tool") ?: "tool",
                failed = payload.optBoolean("error", false),
                eventId = eventId,
            )
            "tasks.updated" -> HermesRunEvent.TasksUpdated(
                payload.optJSONArray("tasks").toObjectList(::parseTask).filterNotNull(),
                eventId,
            )
            "subagent.updated" -> payload.optJSONObject("subagent")
                ?.let(::parseSubagent)
                ?.let { HermesRunEvent.SubagentUpdated(it, eventId) }
            "workspace.updated" -> HermesRunEvent.WorkspaceUpdated(
                parseWorkspaceUpdate(payload),
                eventId,
            )
            "approval.request" -> HermesRunEvent.ApprovalRequested(payload.optNullableString("command"), eventId)
            "approval.responded" -> HermesRunEvent.ApprovalResponded(payload.optNullableString("choice"), eventId)
            "run.completed" -> HermesRunEvent.Completed(
                output = payload.optString("output"),
                usage = payload.optRunUsage(),
                eventId = eventId,
            )
            "run.failed" -> HermesRunEvent.Failed(payload.optString("error", "Hermes run failed."), eventId)
            "run.cancelled" -> HermesRunEvent.Cancelled
            else -> null // Future events remain forward-compatible.
        }
    }

    private fun parseWorkspaceUpdate(payload: JSONObject): HermesWorkspaceUpdate {
        val files = payload.optJSONArray("files").toObjectList { item ->
            HermesWorkspaceChange(
                path = item.optString("path").trim().take(MAX_WORKSPACE_PATH_LENGTH),
                status = item.optString("status", "modified").trim().take(MAX_WORKSPACE_STATUS_LENGTH),
                additions = item.optInt("additions").takeIf { item.has("additions") },
                deletions = item.optInt("deletions").takeIf { item.has("deletions") },
                diff = item.optNullableString("diff")?.take(MAX_WORKSPACE_DIFF_LENGTH),
            )
        }.filter { it.path.isNotBlank() }.distinctBy(HermesWorkspaceChange::path).take(MAX_WORKSPACE_FILES)
        val hostTruncated = payload.optBoolean("truncated", false)
        val clientTruncated = (payload.optJSONArray("files")?.length() ?: 0) > files.size
        return HermesWorkspaceUpdate(files, hostTruncated || clientTruncated)
    }

    private fun parseSessionActivityEvent(json: JSONObject): HermesSessionActivityEvent? {
        val eventId = json.optNullableLong("event_id") ?: return null
        val sessionId = json.optNullableString("session_id") ?: return null
        val turnId = json.optNullableString("turn_id") ?: return null
        val type = json.optNullableString("type") ?: return null
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val subagentJson = payload.optJSONObject("subagent") ?: payload.takeIf {
            it.has("id") || it.has("subagent_id")
        }
        return HermesSessionActivityEvent(
            eventId = eventId,
            sessionId = sessionId,
            turnId = turnId,
            type = type,
            timestampSeconds = json.optNullableEpochSeconds("timestamp"),
            surface = json.optNullableString("surface"),
            userText = payload.optNullableString("user_text"),
            text = payload.optNullableString("text") ?: payload.optNullableString("delta")
                ?: payload.optNullableString("message"),
            toolId = payload.optNullableString("tool_id"),
            toolName = payload.optNullableString("name"),
            toolContext = payload.optNullableString("context"),
            toolDurationSeconds = payload.optNullableDouble("duration_s"),
            toolFailed = payload.optBoolean("failed", false),
            tasks = payload.optJSONArray("tasks").toObjectList(::parseTask).filterNotNull(),
            subagent = subagentJson?.let(::parseActivitySubagent),
            workspaceUpdate = payload.optJSONArray("workspace_changes")?.let { files ->
                parseWorkspaceUpdate(JSONObject().put("files", files))
            },
            status = payload.optNullableString("status") ?: payload.optNullableString("kind"),
        )
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
                statusHistory = json.optJSONArray("status_history").toStringList().takeLast(12),
                updatedAt = json.optNullableEpochSeconds("updated_at"),
                leaseId = json.optNullableString("lease_id"),
                activityCursor = json.optNullableLong("activity_cursor"),
                activityTurnId = json.optNullableString("activity_turn_id"),
            )
        }.filter { it.sessionId.isNotBlank() }
    }

    override suspend fun loadSessionActivity(
        host: HostProfile,
        sessionId: String,
        beforeEventId: Long?,
        limit: Int,
    ): HermesSessionActivityPage = withContext(Dispatchers.IO) {
        val url = endpoint(host, "v1", "sessions", sessionId, "activity").newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 512).toString())
            .apply { beforeEventId?.takeIf { it > 0L }?.let { addQueryParameter("before", it.toString()) } }
            .build()
        val data = executeJson(host, request(host, url))
        HermesSessionActivityPage(
            sessionId = data.optNullableString("session_id") ?: sessionId,
            events = data.optJSONArray("data").toObjectList(::parseSessionActivityEvent).filterNotNull(),
            nextBefore = data.optNullableLong("next_before"),
        )
    }

    override suspend fun streamSessionActivity(
        host: HostProfile,
        sessionId: String,
        afterEventId: Long?,
        onEvent: (HermesSessionActivityEvent) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val streamRequest = request(host, endpoint(host, "v1", "sessions", sessionId, "activity", "events"))
            .newBuilder()
            .apply { afterEventId?.takeIf { it > 0L }?.let { header("Last-Event-ID", it.toString()) } }
            .build()
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
                            ?: throw HermesApiException(response.code, "Hermes returned an empty activity stream.")
                        val dataLines = mutableListOf<String>()
                        fun dispatch() {
                            if (dataLines.isEmpty() || !continuation.isActive) return
                            val payload = runCatching { JSONObject(dataLines.joinToString("\n")) }.getOrNull()
                            dataLines.clear()
                            payload?.let(::parseSessionActivityEvent)?.let(onEvent)
                        }
                        while (continuation.isActive && !source.exhausted()) {
                            val line = source.readBoundedUtf8Line() ?: break
                            when {
                                line.isBlank() -> dispatch()
                                line.startsWith(":") -> Unit
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

    override suspend fun registerMobileDevice(
        host: HostProfile,
        installationId: String,
        token: String,
        appVersion: String,
        overlayEnabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("token", token)
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

    override suspend fun clearStaleActiveSession(host: HostProfile, leaseId: String) = withContext(Dispatchers.IO) {
        executeIgnoringBody(
            request(host, endpoint(host, "v1", "active-sessions", leaseId), method = "DELETE"),
        )
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

    private fun executeJson(host: HostProfile, request: Request, maxBytes: Long = MAX_JSON_BYTES.toLong()): JSONObject {
        ordinaryClient.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            val raw = readBoundedBody(response, maxBytes)
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
        val raw = runCatching { readBoundedBody(response) }.getOrDefault("")
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

    override suspend fun listJobRuns(host: HostProfile, jobId: String): List<HermesJobRun> = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, endpoint(host, "api", "jobs", jobId, "runs")))
        data.requireListArray("data", "job run").toObjectList { json ->
            HermesJobRun(
                sessionId = json.optString("session_id"),
                status = json.optString("status", "unknown"),
                startedAt = json.optNullableString("started_at"),
                endedAt = json.optNullableString("ended_at"),
                messageCount = json.optInt("message_count").coerceAtLeast(0),
                toolCallCount = json.optInt("tool_call_count").coerceAtLeast(0),
            )
        }
    }

    override suspend fun sendMobileNotificationTest(host: HostProfile, installationId: String) = withContext(Dispatchers.IO) {
        executeIgnoringBody(
            request(host, endpoint(host, "v1", "mobile", "devices", installationId, "test"), method = "POST"),
        )
    }

    private fun readBoundedBody(response: Response, maxBytes: Long = MAX_JSON_BYTES.toLong()): String {
        val source = response.body?.source() ?: return ""
        val sink = Buffer()
        var total = 0L
        while (total <= maxBytes) {
            val read = source.read(sink, minOf(8_192L, maxBytes + 1L - total))
            if (read == -1L) break
            total += read
        }
        if (total > maxBytes) {
            throw HermesApiException(response.code, "Hermes returned a response that was too large.")
        }
        return sink.readUtf8()
    }

    private fun BufferedSource.readBoundedUtf8Line(): String? {
        if (exhausted()) return null
        return try {
            readUtf8LineStrict(MAX_STREAM_LINE_BYTES.toLong())
        } catch (error: EOFException) {
            if (buffer.size > MAX_STREAM_LINE_BYTES) {
                throw HermesApiException(502, "Hermes returned a stream event that was too large.")
            }
            readUtf8()
        }
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
            parentId = json.optNullableString("parent_id")?.trim()?.take(120),
            childSessionId = json.optNullableString("child_session_id")?.trim()?.take(120),
            depth = json.optInt("depth", 0).coerceIn(0, 16),
            model = json.optNullableString("model")?.trim()?.take(160),
        )
    }

    private fun parseActivitySubagent(json: JSONObject): HermesSubagent? {
        val id = json.optNullableString("id") ?: json.optNullableString("subagent_id") ?: return null
        return parseSubagent(JSONObject(json.toString()).put("id", id))
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val ORDINARY_READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 20L
        const val ORDINARY_CALL_TIMEOUT_SECONDS = 45L
        const val MAX_WORKSPACE_FILES = 100
        const val MAX_WORKSPACE_PATH_LENGTH = 320
        const val MAX_WORKSPACE_STATUS_LENGTH = 32
        const val MAX_WORKSPACE_DIFF_LENGTH = 20_000
        const val MAX_JSON_BYTES = 4 * 1024 * 1024
        const val MAX_TRANSCRIPT_JSON_BYTES = 16 * 1024 * 1024
        const val MAX_STREAM_LINE_BYTES = 256 * 1024
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

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return opt(name)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
}

private fun JSONObject.optNullableDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return opt(name)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)
}

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

private fun JSONObject.requireListArray(name: String, resource: String): JSONArray =
    optJSONArray(name)
        ?: throw HermesApiException(200, "Hermes returned an unexpected $resource list shape.")
