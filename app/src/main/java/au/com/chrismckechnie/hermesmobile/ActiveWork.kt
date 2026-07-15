package au.com.chrismckechnie.hermesmobile

data class ActiveWorkItem(
    val key: SessionKey,
    val ref: RunRef?,
    val title: String,
    val hostName: String,
    val state: String,
    val latestUpdate: String?,
    val needsAttention: Boolean,
    val isCurrentSession: Boolean,
    internal val priority: Int,
)

/** A deterministic, host-safe view of every local and host-reported active session. */
internal fun HermesUiState.activeWorkItems(): List<ActiveWorkItem> {
    val localKeys = activeRuns.keys
    val hostReported = activeHostSessions.toMutableMap()
    val items = activeRuns.map { (key, run) ->
        val reported = hostReported.remove(key)
        val workState = when {
            run.awaitingApproval -> "waiting_for_approval"
            run.terminalUnsynced -> "sync_required"
            run.reconcilingTranscript -> "syncing"
            run.stopping -> "stopping"
            else -> reported?.state ?: "working"
        }
        activeWorkItem(
            key = key,
            ref = run.ref,
            title = sessions.takeIf { key.hostId == activeHostId }
                ?.firstOrNull { it.id == key.sessionId }
                ?.title
                ?.takeIf(String::isNotBlank)
                ?: run.sessionTitle?.takeIf(String::isNotBlank)
                ?: reported?.title?.takeIf(String::isNotBlank)
                ?: "Untitled session",
            state = workState,
            latestUpdate = reported?.latestStatus,
        )
    }.toMutableList()

    hostReported.forEach { (key, work) ->
        items += activeWorkItem(
            key = key,
            ref = null,
            title = work.title.takeIf(String::isNotBlank) ?: "Hermes session",
            state = work.state,
            latestUpdate = work.latestStatus,
        )
    }

    val selectedHostId = activeHostId
    if (selectedHostId != null) {
        sessions.asSequence()
            .filter(HermesSession::isActive)
            .map { SessionKey(selectedHostId, it.id) to it }
            .filter { (key, _) -> key !in localKeys && key !in activeHostSessions }
            .forEach { (key, session) ->
                items += activeWorkItem(
                    key = key,
                    ref = null,
                    title = session.title?.takeIf(String::isNotBlank) ?: "Hermes session",
                    state = "working",
                    latestUpdate = null,
                )
            }
    }

    return items.sortedWith(
        compareBy<ActiveWorkItem>(ActiveWorkItem::priority)
            .thenByDescending { it.key.hostId == activeHostId }
            .thenBy { it.hostName.lowercase() }
            .thenBy { it.title.lowercase() }
            .thenBy { it.ref?.runId.orEmpty() },
    )
}

private fun HermesUiState.activeWorkItem(
    key: SessionKey,
    ref: RunRef?,
    title: String,
    state: String,
    latestUpdate: String?,
): ActiveWorkItem {
    val normalizedState = state.trim().lowercase()
    return ActiveWorkItem(
        key = key,
        ref = ref,
        title = title,
        hostName = hosts.firstOrNull { it.id == key.hostId }?.name ?: "Unknown host",
        state = normalizedState,
        latestUpdate = latestUpdate?.trim()?.takeIf(String::isNotBlank),
        needsAttention = normalizedState in ATTENTION_STATES,
        isCurrentSession = key == activeSessionKey,
        priority = activeWorkPriority(normalizedState),
    )
}

private val ATTENTION_STATES = setOf(
    "waiting_for_approval",
    "approval_required",
    "sync_required",
    "stalled",
    "unresponsive",
    "failed",
    "error",
)

internal fun activeWorkPriority(state: String): Int = when (state.trim().lowercase()) {
    "waiting_for_approval", "approval_required" -> 0
    "sync_required" -> 1
    "stalled", "unresponsive", "failed", "error" -> 2
    "syncing" -> 3
    "stopping" -> 4
    "queued" -> 5
    else -> 6
}

internal fun activeWorkSummary(items: List<ActiveWorkItem>): String {
    val attention = items.count(ActiveWorkItem::needsAttention)
    val active = items.size
    return when {
        attention > 0 -> "$attention ${if (attention == 1) "needs" else "need"} attention · $active active"
        active == 1 -> "1 active run · ${items.single().title}"
        else -> "$active active runs"
    }
}
