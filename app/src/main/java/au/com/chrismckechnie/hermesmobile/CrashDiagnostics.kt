package au.com.chrismckechnie.hermesmobile

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

enum class DiagnosticPhase(val value: String) {
    AppStart("app_start"),
    AppReady("app_ready"),
    SendValidate("send_validate"),
    SessionCreate("session_create"),
    HistoryLoad("history_load"),
    RunSubmit("run_submit"),
    RunAccepted("run_accepted"),
    RunStream("run_stream"),
    RunTerminal("run_terminal"),
    NotificationShortcut("notification_shortcut"),
    OverlayPromote("overlay_promote"),
    OverlayRefresh("overlay_refresh"),
}

enum class DiagnosticSendRoute(val value: String) {
    NewSession("new_session"),
    ExistingSession("existing_session"),
    FollowUp("follow_up"),
}

data class DiagnosticContext(
    val sendRoute: DiagnosticSendRoute? = null,
    val messageLength: Int? = null,
    val activeRunCount: Int? = null,
    val overlayEnabled: Boolean? = null,
)

internal data class DiagnosticFailure(
    val phase: String,
    val category: String,
    val httpStatus: Int?,
    val stackTrace: Array<StackTraceElement>,
) {
    val summary: String
        get() = listOfNotNull(phase, category, httpStatus?.toString()).joinToString(":")

    fun asThrowable(): Throwable = SanitizedDiagnosticException(summary).also {
        it.stackTrace = stackTrace
    }
}

private class SanitizedDiagnosticException(message: String) : RuntimeException(message)

internal fun diagnosticMessageLengthBucket(length: Int): String = when (length.coerceAtLeast(0)) {
    0 -> "empty"
    in 1..128 -> "1-128"
    in 129..512 -> "129-512"
    in 513..2_048 -> "513-2048"
    else -> "2049-8000"
}

internal fun sanitizedDiagnosticFailure(phase: DiagnosticPhase, error: Throwable): DiagnosticFailure {
    val category = when (error) {
        is HermesApiException -> "api"
        is IOException -> "network"
        is SecurityException -> "permission"
        is OutOfMemoryError -> "memory"
        else -> "runtime"
    }
    return DiagnosticFailure(
        phase = phase.value,
        category = category,
        httpStatus = (error as? HermesApiException)?.statusCode,
        stackTrace = error.stackTrace.copyOf(),
    )
}

data class ProcessExitDiagnostic(
    val timestampMillis: Long,
    val reason: String,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val lastPhase: String?,
)

internal fun shouldUseSafeStartup(exit: ProcessExitDiagnostic?): Boolean =
    exit?.reason in setOf("anr", "crash", "native_crash", "initialization_failure")

internal fun formatProcessExitDiagnostic(
    diagnostic: ProcessExitDiagnostic,
    appVersion: String,
    sdkInt: Int,
    device: String,
): String = buildString {
    appendLine("Hermes Mobile $appVersion")
    appendLine("Android API $sdkInt")
    appendLine("Device: $device")
    appendLine("Previous process exit: ${Instant.ofEpochMilli(diagnostic.timestampMillis)}")
    appendLine("Reason: ${diagnostic.reason}")
    appendLine("Status: ${diagnostic.status}")
    appendLine("Importance: ${diagnostic.importance}")
    appendLine("PSS: ${diagnostic.pssKb} KB")
    appendLine("RSS: ${diagnostic.rssKb} KB")
    diagnostic.lastPhase?.let { appendLine("Last safe phase: $it") }
}.trimEnd()

interface AppDiagnostics {
    fun initialize()
    fun setCollectionEnabled(enabled: Boolean)
    fun recordPhase(phase: DiagnosticPhase, context: DiagnosticContext = DiagnosticContext())
    fun recordFailure(phase: DiagnosticPhase, error: Throwable)
    fun latestExit(): ProcessExitDiagnostic?
    fun consumeSafeStartup(): Boolean = false
}

internal object NoOpAppDiagnostics : AppDiagnostics {
    override fun initialize() = Unit
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun recordPhase(phase: DiagnosticPhase, context: DiagnosticContext) = Unit
    override fun recordFailure(phase: DiagnosticPhase, error: Throwable) = Unit
    override fun latestExit(): ProcessExitDiagnostic? = null
    override fun consumeSafeStartup(): Boolean = false
}

internal object AppDiagnosticsRegistry {
    @Volatile
    var recorder: AppDiagnostics = NoOpAppDiagnostics
        private set

    fun initialize(context: Context): AppDiagnostics = AndroidAppDiagnostics(context.applicationContext).also {
        recorder = it
        it.initialize()
    }
}

private class AndroidAppDiagnostics(context: Context) : AppDiagnostics {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val settingsStore = PreferencesSettingsStore(appContext)

    override fun initialize() {
        capturePreviousExit()
        setCollectionEnabled(settingsStore.loadCrashReportingEnabled())
        recordPhase(DiagnosticPhase.AppStart)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        crashlytics()?.let { reporter ->
            reporter.setCrashlyticsCollectionEnabled(enabled)
            if (enabled) reporter.sendUnsentReports() else reporter.deleteUnsentReports()
        }
    }

    override fun recordPhase(phase: DiagnosticPhase, context: DiagnosticContext) {
        preferences.edit().putString(KEY_LAST_PHASE, phase.value).apply()
        val reporter = crashlytics().takeIf { settingsStore.loadCrashReportingEnabled() } ?: return
        reporter.setCustomKey("phase", phase.value)
        context.sendRoute?.let { reporter.setCustomKey("send_route", it.value) }
        context.messageLength?.let { reporter.setCustomKey("message_length", diagnosticMessageLengthBucket(it)) }
        context.activeRunCount?.let { reporter.setCustomKey("active_run_count", it) }
        context.overlayEnabled?.let { reporter.setCustomKey("overlay_enabled", it) }
        reporter.log("phase=${phase.value}")
    }

    override fun recordFailure(phase: DiagnosticPhase, error: Throwable) {
        val failure = sanitizedDiagnosticFailure(phase, error)
        preferences.edit()
            .putString(KEY_LAST_FAILURE_PHASE, failure.phase)
            .putString(KEY_LAST_FAILURE_CATEGORY, failure.category)
            .apply()
        crashlytics()
            ?.takeIf { settingsStore.loadCrashReportingEnabled() }
            ?.recordException(failure.asThrowable())
    }

    override fun latestExit(): ProcessExitDiagnostic? = preferences.getString(KEY_LAST_EXIT, null)
        ?.let(::decodeExitDiagnostic)

    override fun consumeSafeStartup(): Boolean {
        val exit = latestExit()?.takeIf(::shouldUseSafeStartup) ?: return false
        if (exit.timestampMillis <= preferences.getLong(KEY_SAFE_STARTUP_CONSUMED_TIMESTAMP, 0L)) return false
        preferences.edit().putLong(KEY_SAFE_STARTUP_CONSUMED_TIMESTAMP, exit.timestampMillis).commit()
        return true
    }

    private fun capturePreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return
        val exit = activityManager.getHistoricalProcessExitReasons(appContext.packageName, 0, 1).firstOrNull() ?: return
        val lastCaptured = preferences.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        if (exit.timestamp <= lastCaptured) return
        val diagnostic = ProcessExitDiagnostic(
            timestampMillis = exit.timestamp,
            reason = processExitReason(exit.reason),
            status = exit.status,
            importance = exit.importance,
            pssKb = exit.pss,
            rssKb = exit.rss,
            lastPhase = preferences.getString(KEY_LAST_PHASE, null),
        )
        preferences.edit()
            .putLong(KEY_LAST_EXIT_TIMESTAMP, exit.timestamp)
            .putString(KEY_LAST_EXIT, encodeExitDiagnostic(diagnostic))
            .apply()
    }

    private fun crashlytics(): FirebaseCrashlytics? = runCatching {
        FirebaseApp.getApps(appContext).takeIf(List<FirebaseApp>::isNotEmpty)
            ?.let { FirebaseCrashlytics.getInstance() }
    }.getOrNull()

    private companion object {
        const val PREFERENCES = "hermes_diagnostics"
        const val KEY_LAST_PHASE = "last_phase"
        const val KEY_LAST_FAILURE_PHASE = "last_failure_phase"
        const val KEY_LAST_FAILURE_CATEGORY = "last_failure_category"
        const val KEY_LAST_EXIT = "last_exit"
        const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
        const val KEY_SAFE_STARTUP_CONSUMED_TIMESTAMP = "safe_startup_consumed_timestamp"
    }
}

private fun encodeExitDiagnostic(value: ProcessExitDiagnostic): String = JSONObject().apply {
    put("timestamp_millis", value.timestampMillis)
    put("reason", value.reason)
    put("status", value.status)
    put("importance", value.importance)
    put("pss_kb", value.pssKb)
    put("rss_kb", value.rssKb)
    value.lastPhase?.let { put("last_phase", it) }
}.toString()

private fun decodeExitDiagnostic(encoded: String): ProcessExitDiagnostic? = runCatching {
    val value = JSONObject(encoded)
    ProcessExitDiagnostic(
        timestampMillis = value.getLong("timestamp_millis"),
        reason = value.getString("reason"),
        status = value.getInt("status"),
        importance = value.getInt("importance"),
        pssKb = value.getLong("pss_kb"),
        rssKb = value.getLong("rss_kb"),
        lastPhase = value.optString("last_phase").takeIf(String::isNotBlank),
    )
}.getOrNull()

private fun processExitReason(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
    else -> "unknown"
}
