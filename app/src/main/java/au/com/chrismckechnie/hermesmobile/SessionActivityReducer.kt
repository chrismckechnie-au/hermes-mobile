package au.com.chrismckechnie.hermesmobile

/** A compact, replayable representation of one host turn. */
data class SessionActivityTurn(
    val turnId: String,
    val userText: String? = null,
    val assistantText: String? = null,
    val reasoning: List<String> = emptyList(),
    val tools: List<ChatUiItem.Tool> = emptyList(),
    val tasks: List<HermesTask> = emptyList(),
    val subagents: Map<String, HermesSubagent> = emptyMap(),
    val workspaceUpdate: HermesWorkspaceUpdate? = null,
    val latestStatus: String? = null,
    val terminal: Boolean = false,
    val updatedAtSeconds: Long? = null,
)

data class SessionActivityState(
    val turns: List<SessionActivityTurn> = emptyList(),
    val lastEventId: Long? = null,
    val seenEventIds: List<Long> = emptyList(),
) {
    val latestTurn: SessionActivityTurn? get() = turns.lastOrNull()
    val activeTurn: SessionActivityTurn? get() = turns.lastOrNull { !it.terminal }
}

/**
 * Pure reducer for both paged history and the live session SSE feed.
 *
 * Host event ids are stable journal cursors, so reconnects can safely replay
 * events without duplicating tools or assistant deltas in the mobile UI.
 */
internal fun reduceSessionActivity(
    state: SessionActivityState,
    event: HermesSessionActivityEvent,
): SessionActivityState {
    if (event.eventId in state.seenEventIds) return state
    val existing = state.turns.firstOrNull { it.turnId == event.turnId }
        ?: SessionActivityTurn(turnId = event.turnId)
    val updated = reduceTurn(existing, event)
    val turns = (state.turns.filterNot { it.turnId == event.turnId } + updated)
        .takeLast(MAX_ACTIVITY_TURNS)
    return state.copy(
        turns = turns,
        lastEventId = maxOf(state.lastEventId ?: 0L, event.eventId),
        seenEventIds = (state.seenEventIds + event.eventId).takeLast(MAX_ACTIVITY_EVENT_IDS),
    )
}

private fun reduceTurn(
    turn: SessionActivityTurn,
    event: HermesSessionActivityEvent,
): SessionActivityTurn {
    val status = event.status?.trim()?.takeIf(::isUsefulProgressUpdate)
    val timestamp = event.timestampSeconds ?: turn.updatedAtSeconds
    return when (event.type) {
        "message.start" -> turn.copy(
            userText = event.userText?.trim()?.takeIf(String::isNotBlank) ?: turn.userText,
            latestStatus = status ?: "Reviewing the request…",
            updatedAtSeconds = timestamp,
        )
        "message.delta" -> turn.copy(
            assistantText = (turn.assistantText.orEmpty() + event.text.orEmpty()).take(MAX_ACTIVITY_TEXT),
            latestStatus = status ?: "Writing the response…",
            updatedAtSeconds = timestamp,
        )
        "message.complete" -> turn.copy(
            assistantText = event.text?.takeIf(String::isNotBlank) ?: turn.assistantText,
            latestStatus = status ?: "Completed",
            terminal = true,
            updatedAtSeconds = timestamp,
        )
        "reasoning.delta", "reasoning.available" -> turn.copy(
            reasoning = appendActivityText(turn.reasoning, event.text),
            latestStatus = status ?: event.text?.compactActivityText() ?: turn.latestStatus,
            updatedAtSeconds = timestamp,
        )
        "tool.start" -> turn.copy(
            tools = upsertActivityTool(
                turn.tools,
                ChatUiItem.Tool(
                    id = event.toolId?.takeIf(String::isNotBlank) ?: "activity-tool-${event.eventId}",
                    name = event.toolName?.takeIf(String::isNotBlank) ?: "tool",
                    preview = event.toolContext,
                    running = true,
                ),
            ),
            latestStatus = status ?: turn.latestStatus ?: "Working…",
            updatedAtSeconds = timestamp,
        )
        "tool.complete" -> turn.copy(
            tools = completeActivityTool(turn.tools, event),
            tasks = event.tasks.takeIf { it.isNotEmpty() } ?: turn.tasks,
            workspaceUpdate = event.workspaceUpdate?.mergeWith(turn.workspaceUpdate) ?: turn.workspaceUpdate,
            latestStatus = status ?: turn.latestStatus ?: "Working…",
            updatedAtSeconds = timestamp,
        )
        "tasks.updated" -> turn.copy(
            tasks = event.tasks,
            latestStatus = status ?: "Updated the task plan…",
            updatedAtSeconds = timestamp,
        )
        else -> when {
            event.type.startsWith("subagent.") && event.subagent != null -> {
                val previous = turn.subagents[event.subagent.id]
                val merged = event.subagent.copy(
                    goal = event.subagent.goal ?: previous?.goal,
                    activity = event.subagent.activity ?: previous?.activity,
                )
                turn.copy(
                    subagents = turn.subagents + (merged.id to merged),
                    latestStatus = status ?: merged.activity ?: "A subagent is ${merged.status.replace('_', ' ')}…",
                    updatedAtSeconds = timestamp,
                )
            }
            event.type == "approval.request" -> turn.copy(
                latestStatus = status ?: "Waiting for your approval",
                updatedAtSeconds = timestamp,
            )
            event.type == "status.error" || event.type == "status.cancelled" -> turn.copy(
                reasoning = appendActivityText(turn.reasoning, event.text ?: event.status),
                latestStatus = status ?: if (event.type == "status.cancelled") "The task was stopped" else "The task hit an issue",
                terminal = true,
                updatedAtSeconds = timestamp,
            )
            else -> turn.copy(latestStatus = status ?: turn.latestStatus, updatedAtSeconds = timestamp)
        }
    }
}

private fun appendActivityText(existing: List<String>, raw: String?): List<String> {
    val text = raw?.compactActivityText() ?: return existing
    if (!isUsefulProgressUpdate(text) || existing.lastOrNull() == text) return existing
    return (existing + text).takeLast(MAX_ACTIVITY_REASONING)
}

private fun String.compactActivityText(): String = trim().replace(Regex("\\s+"), " ").take(2_000)

private fun upsertActivityTool(
    tools: List<ChatUiItem.Tool>,
    incoming: ChatUiItem.Tool,
): List<ChatUiItem.Tool> {
    val index = tools.indexOfFirst { it.id == incoming.id }
    return if (index < 0) tools + incoming else tools.mapIndexed { position, tool ->
        if (position == index) incoming else tool
    }
}

private fun completeActivityTool(
    tools: List<ChatUiItem.Tool>,
    event: HermesSessionActivityEvent,
): List<ChatUiItem.Tool> {
    val id = event.toolId?.takeIf(String::isNotBlank)
    val index = id?.let { candidate -> tools.indexOfFirst { it.id == candidate } }
        ?.takeIf { it >= 0 }
        ?: tools.indexOfLast { it.running && it.name == event.toolName }
    val completed = ChatUiItem.Tool(
        id = id ?: tools.getOrNull(index)?.id ?: "activity-tool-${event.eventId}",
        name = event.toolName?.takeIf(String::isNotBlank) ?: tools.getOrNull(index)?.name ?: "tool",
        preview = event.toolContext ?: tools.getOrNull(index)?.preview,
        running = false,
        failed = event.toolFailed,
        durationSeconds = event.toolDurationSeconds,
        workspaceChanges = event.workspaceUpdate?.files.orEmpty(),
    )
    return if (index < 0) tools + completed else tools.mapIndexed { position, tool ->
        if (position == index) completed else tool
    }
}

private fun HermesWorkspaceUpdate.mergeWith(previous: HermesWorkspaceUpdate?): HermesWorkspaceUpdate {
    previous ?: return this
    val files = (previous.files + files).associateBy(HermesWorkspaceChange::path).values.take(MAX_ACTIVITY_WORKSPACE_FILES)
    return HermesWorkspaceUpdate(files = files, truncated = previous.truncated || truncated)
}

private const val MAX_ACTIVITY_TURNS = 50
private const val MAX_ACTIVITY_EVENT_IDS = 512
private const val MAX_ACTIVITY_REASONING = 24
private const val MAX_ACTIVITY_TEXT = 20_000
private const val MAX_ACTIVITY_WORKSPACE_FILES = 100
