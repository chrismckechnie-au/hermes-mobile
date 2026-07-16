package au.com.chrismckechnie.hermesmobile

import androidx.compose.runtime.Immutable

@Immutable
internal data class ComposerActiveRunState(val stopping: Boolean)

@Immutable
internal data class ComposerInputState(
    val connectionPhase: HostConnectionPhase,
    val isSending: Boolean,
    val composerText: String,
    val hasActiveHost: Boolean,
    val activeRun: ComposerActiveRunState?,
    val blockedByUnknownOutcome: Boolean,
    val blockedByRecoveredFollowUp: Boolean,
) {
    val enabled: Boolean
        get() = connectionPhase == HostConnectionPhase.Connected &&
            !isSending && !blockedByUnknownOutcome && !blockedByRecoveredFollowUp
}

internal fun composerInputState(state: HermesUiState): ComposerInputState = ComposerInputState(
    connectionPhase = state.connectionPhase,
    isSending = state.isSending,
    composerText = state.composerText,
    hasActiveHost = state.activeHost != null,
    activeRun = state.activeRun?.let { ComposerActiveRunState(stopping = it.stopping) },
    blockedByUnknownOutcome = state.unknownOutcome != null,
    blockedByRecoveredFollowUp = state.queuedInterrupt?.requiresAcknowledgement == true,
)
