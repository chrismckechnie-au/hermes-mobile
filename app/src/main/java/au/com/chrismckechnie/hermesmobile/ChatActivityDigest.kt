package au.com.chrismckechnie.hermesmobile

/** User-selectable organization for tool activity in Chat. */
enum class ChatActivityLayout { Grouped, Chronological }

enum class ActivityOutcome { Completed, Failed, Cancelled }

/**
 * A bounded local summary of a terminal Run. It intentionally excludes prompts,
 * tool arguments, outputs, diffs, credentials, and private reasoning.
 */
data class CompletedActivityDigest(
    val hostId: String,
    val sessionId: String,
    val runId: String,
    val milestones: List<String>,
    val tools: List<CompletedToolDigest>,
    val outcome: ActivityOutcome,
    val completedAtMillis: Long,
)

data class CompletedToolDigest(
    val name: String,
    val preview: String?,
    val failed: Boolean,
    val durationSeconds: Double? = null,
)

internal fun safeActivityPreview(value: String?): String? {
    val normalized = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
    val assignedSecret = Regex("(?i)\\b(api[_-]?key|token|password|secret|authorization)\\b\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+")
    val flaggedSecret = Regex("(?i)(--?(?:api[-_]?key|token|password|secret)\\s+)[^\\s,;]+")
    val redacted = normalized
        .replace(assignedSecret) { match -> "${match.groupValues[1]}=[redacted]" }
        .replace(flaggedSecret) { match -> "${match.groupValues[1]}[redacted]" }
    return redacted.take(40).takeIf(String::isNotBlank)
}

internal fun boundedActivityDigests(
    digests: List<CompletedActivityDigest>,
    nowMillis: Long = System.currentTimeMillis(),
): List<CompletedActivityDigest> {
    val minTimestamp = nowMillis - ACTIVITY_DIGEST_MAX_AGE_MILLIS
    return digests
        .asSequence()
        .filter { it.completedAtMillis >= minTimestamp }
        .sortedByDescending(CompletedActivityDigest::completedAtMillis)
        .distinctBy { Triple(it.hostId, it.sessionId, it.runId) }
        .groupBy { it.hostId to it.sessionId }
        .values
        .flatMap { it.take(ACTIVITY_DIGESTS_PER_SESSION) }
        .sortedByDescending(CompletedActivityDigest::completedAtMillis)
        .take(ACTIVITY_DIGESTS_TOTAL)
        .toList()
}

internal const val ACTIVITY_DIGESTS_PER_SESSION = 50
internal const val ACTIVITY_DIGESTS_TOTAL = 200
internal const val ACTIVITY_DIGEST_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1_000
