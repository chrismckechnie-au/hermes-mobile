package au.com.chrismckechnie.hermesmobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HermesHttpGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
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
        )
    }

    override suspend fun listSessions(host: HostProfile, limit: Int): List<HermesSession> = withContext(Dispatchers.IO) {
        val url = endpoint(host, "api", "sessions").newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 200).toString())
            .addQueryParameter("offset", "0")
            .build()
        val data = executeJson(host, request(host, url))
        data.optJSONArray("data").toObjectList(::parseSession)
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

    override suspend fun loadMessages(host: HostProfile, sessionId: String): List<HermesMessage> = withContext(Dispatchers.IO) {
        val data = executeJson(host, request(host, endpoint(host, "api", "sessions", sessionId, "messages")))
        data.optJSONArray("data").toObjectList(::parseMessage)
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

    override suspend fun streamSessionChat(
        host: HostProfile,
        sessionId: String,
        input: String,
        onEvent: (HermesStreamEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("input", input)
        val request = request(
            host,
            endpoint(host, "api", "sessions", sessionId, "chat", "stream"),
            method = "POST",
            body = body,
        )
        client.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            val source = response.body?.source() ?: throw HermesApiException(response.code, "Hermes returned an empty stream.")
            var eventName: String? = null
            val dataLines = mutableListOf<String>()

            fun dispatch() {
                val name = eventName ?: return
                val payloadText = dataLines.joinToString("\n").ifBlank { "{}" }
                val payload = runCatching { JSONObject(payloadText) }.getOrElse { JSONObject() }
                onEvent(parseStreamEvent(name, payload))
                eventName = null
                dataLines.clear()
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isBlank() -> dispatch()
                    line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                    line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
                }
            }
            dispatch()
        }
    }

    private fun parseStreamEvent(name: String, payload: JSONObject): HermesStreamEvent = when (name) {
        "run.started" -> HermesStreamEvent.RunStarted(payload.optNullableString("run_id"))
        "assistant.delta" -> HermesStreamEvent.AssistantDelta(payload.optString("delta"))
        "tool.started" -> HermesStreamEvent.ToolStarted(
            payload.optNullableString("tool_name") ?: "tool",
            payload.optNullableString("preview"),
        )
        "tool.completed" -> HermesStreamEvent.ToolCompleted(
            payload.optNullableString("tool_name") ?: "tool",
            payload.optNullableString("preview"),
        )
        "tool.failed" -> HermesStreamEvent.ToolCompleted(
            payload.optNullableString("tool_name") ?: "tool",
            payload.optNullableString("preview"),
            failed = true,
        )
        "assistant.completed" -> HermesStreamEvent.Completed(
            payload.optString("content"),
            payload.optNullableString("session_id"),
        )
        "error" -> HermesStreamEvent.Failed(payload.optString("message", "Hermes stream failed."))
        "done" -> HermesStreamEvent.Done
        else -> HermesStreamEvent.Done
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
            .header("Accept", if (method == "POST" && url.encodedPath.endsWith("/stream")) "text/event-stream" else "application/json")
            .header("X-Hermes-Session-Key", "hermes-mobile:${validated.id}")
        if (method == "GET") builder.get()
        else builder.method(method, (body ?: JSONObject()).toString().toRequestBody(jsonMediaType))
        return builder.build()
    }

    private fun endpoint(host: HostProfile, vararg segments: String): HttpUrl =
        segments.fold(host.validated().baseUrl.toHttpUrl().newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()

    private fun executeJson(host: HostProfile, request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            val raw = response.body?.string().orEmpty()
            if (raw.isBlank()) throw HermesApiException(response.code, "Hermes returned an empty response.")
            return runCatching { JSONObject(raw) }.getOrElse {
                throw HermesApiException(response.code, "Hermes returned an unreadable response.")
            }
        }
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
    )

    private fun parseMessage(json: JSONObject) = HermesMessage(
        id = json.optNullableString("id"),
        role = json.optString("role", "assistant"),
        content = json.optNullableString("content").orEmpty(),
        toolName = json.optNullableString("tool_name"),
        timestamp = json.optNullableString("timestamp"),
    )
}

private inline fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(transform(it)) }
        }
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return opt(name)?.toString()?.takeIf { it.isNotBlank() }
}
