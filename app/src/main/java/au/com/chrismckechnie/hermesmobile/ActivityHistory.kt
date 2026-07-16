package au.com.chrismckechnie.hermesmobile

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ActivityEntry(
    val hostId: String,
    val sessionId: String,
    val title: String,
    val state: String,
    val runId: String? = null,
    val event: String = "session.updated",
    val eventId: String? = null,
    val latestStatus: String? = null,
    val updatedAtMillis: Long = 0L,
    val tasksCompleted: Int = 0,
    val tasksTotal: Int = 0,
    val activeSubagents: Int = 0,
    val errorCategory: MobileErrorCategory = MobileErrorCategory.Unknown,
    val read: Boolean = false,
) {
    val isTerminal: Boolean get() = event in TERMINAL_EVENTS || state.lowercase() in TERMINAL_STATES
    val requiresAttention: Boolean get() = event in ATTENTION_EVENTS || state.lowercase() in ATTENTION_STATES
    val isActive: Boolean get() = !isTerminal && !requiresAttention

    internal fun identity(): String = listOf(
        hostId,
        sessionId,
        eventId?.takeIf(String::isNotBlank) ?: runId.orEmpty(),
        if (eventId.isNullOrBlank()) event else "event-id",
    ).joinToString("\u0000")

    private companion object {
        val TERMINAL_EVENTS = setOf(
            "session.completed", "session.failed", "session.cancelled", "job.completed", "job.failed",
        )
        val TERMINAL_STATES = setOf("completed", "failed", "error", "cancelled", "stopped")
        val ATTENTION_EVENTS = setOf(
            "approval.required", "session.completed", "session.failed", "session.cancelled", "job.completed", "job.failed",
        )
        val ATTENTION_STATES = setOf("waiting_for_approval", "approval_required", "failed", "error")
    }
}

typealias AttentionItem = ActivityEntry

internal fun recordActivityEntry(
    current: List<ActivityEntry>,
    incoming: ActivityEntry,
    nowMillis: Long = System.currentTimeMillis(),
): List<ActivityEntry> {
    val normalized = incoming.copy(
        title = incoming.title.trim().ifBlank { "Hermes session" }.take(120),
        state = incoming.state.trim().ifBlank { "updated" }.take(80),
        latestStatus = incoming.latestStatus?.replace(Regex("\\s+"), " ")?.trim()?.take(180)?.takeIf(String::isNotBlank),
        updatedAtMillis = incoming.updatedAtMillis.takeIf { it > 0L } ?: nowMillis,
        tasksCompleted = incoming.tasksCompleted.coerceIn(0, 999),
        tasksTotal = incoming.tasksTotal.coerceIn(0, 999),
        activeSubagents = incoming.activeSubagents.coerceIn(0, 999),
    )
    val cutoff = nowMillis - TimeUnit.DAYS.toMillis(ACTIVITY_RETENTION_DAYS)
    return (current.filterNot { it.identity() == normalized.identity() } + normalized)
        .filter { it.updatedAtMillis >= cutoff }
        .sortedByDescending(ActivityEntry::updatedAtMillis)
        .take(ACTIVITY_MAX_ENTRIES)
}

internal fun markActivityRead(
    current: List<ActivityEntry>,
    hostId: String,
    sessionId: String? = null,
    entryId: String? = null,
): List<ActivityEntry> = current.map { item ->
    val matches = item.hostId == hostId &&
        (sessionId == null || item.sessionId == sessionId) &&
        (entryId == null || item.identity() == entryId)
    if (matches) item.copy(read = true) else item
}

internal fun activityUnreadCount(entries: List<ActivityEntry>): Int = entries.count { it.requiresAttention && !it.read }

object ActivityEntryCodec {
    fun encode(items: List<ActivityEntry>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("hostId", item.hostId)
                put("sessionId", item.sessionId)
                put("title", item.title)
                put("state", item.state)
                item.runId?.let { put("runId", it) }
                put("event", item.event)
                item.eventId?.let { put("eventId", it) }
                item.latestStatus?.let { put("latestStatus", it) }
                put("updatedAtMillis", item.updatedAtMillis)
                put("tasksCompleted", item.tasksCompleted)
                put("tasksTotal", item.tasksTotal)
                put("activeSubagents", item.activeSubagents)
                put("errorCategory", item.errorCategory.wireValue)
                put("read", item.read)
            })
        }
    }.toString()

    fun decode(raw: String, nowMillis: Long = System.currentTimeMillis()): List<ActivityEntry> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val hostId = item.optString("hostId")
                val sessionId = item.optString("sessionId")
                if (hostId.isBlank() || sessionId.isBlank()) continue
                add(
                    ActivityEntry(
                        hostId = hostId,
                        sessionId = sessionId,
                        title = item.optString("title").ifBlank { "Hermes session" }.take(120),
                        state = item.optString("state").ifBlank { "updated" }.take(80),
                        runId = item.optString("runId").takeIf(String::isNotBlank),
                        event = item.optString("event").ifBlank { "session.updated" },
                        eventId = item.optString("eventId").takeIf(String::isNotBlank),
                        latestStatus = item.optString("latestStatus").takeIf(String::isNotBlank)?.take(180),
                        updatedAtMillis = item.optLong("updatedAtMillis").takeIf { it > 0L } ?: nowMillis,
                        tasksCompleted = item.optInt("tasksCompleted").coerceIn(0, 999),
                        tasksTotal = item.optInt("tasksTotal").coerceIn(0, 999),
                        activeSubagents = item.optInt("activeSubagents").coerceIn(0, 999),
                        errorCategory = MobileErrorCategory.from(item.optString("errorCategory")),
                        read = item.optBoolean("read", false),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

internal const val ACTIVITY_MAX_ENTRIES = 100
internal const val ACTIVITY_RETENTION_DAYS = 7L
