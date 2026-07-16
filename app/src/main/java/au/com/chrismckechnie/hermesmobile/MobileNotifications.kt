package au.com.chrismckechnie.hermesmobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class MobileErrorCategory(val wireValue: String) {
    HostOffline("host_offline"),
    ApprovalTimeout("approval_timeout"),
    ToolFailure("tool_failure"),
    ModelApi("model_api"),
    Cancelled("cancelled"),
    Unknown("unknown");

    companion object {
        fun from(value: String?): MobileErrorCategory = entries.firstOrNull {
            it.wireValue == value?.trim()?.lowercase()
        } ?: Unknown
    }
}

data class MobilePushEvent(
    val event: String,
    val hostProfileId: String,
    val sessionId: String,
    val runId: String?,
    val title: String,
    val state: String,
    val activeCount: Int,
    val eventId: String? = null,
    val latestStatus: String? = null,
    val updatedAtMillis: Long? = null,
    val tasksCompleted: Int = 0,
    val tasksTotal: Int = 0,
    val activeSubagents: Int = 0,
    val errorCategory: MobileErrorCategory = MobileErrorCategory.Unknown,
) {
    val isTerminal: Boolean get() = event in setOf(
        "session.completed", "session.failed", "session.cancelled", "job.completed", "job.failed",
    )
    val requiresAttention: Boolean get() = event in setOf(
        "approval.required", "session.completed", "session.failed", "session.cancelled", "job.completed", "job.failed",
    )
    val isActive: Boolean get() = !isTerminal && event != "approval.required"

    companion object {
        fun from(data: Map<String, String>): MobilePushEvent? {
            val hostId = data["host_profile_id"].orEmpty()
            val sessionId = data["session_id"].orEmpty()
            if (hostId.isBlank() || sessionId.isBlank()) return null
            return MobilePushEvent(
                event = data["event"].orEmpty().ifBlank { "session.updated" },
                hostProfileId = hostId,
                sessionId = sessionId,
                runId = data["run_id"]?.takeIf(String::isNotBlank),
                title = data["title"].orEmpty().ifBlank { "Hermes session" }.take(120),
                state = data["state"].orEmpty().ifBlank { "active" },
                activeCount = data["active_count"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                eventId = data["event_id"]?.trim()?.take(128)?.takeIf(String::isNotBlank),
                latestStatus = data["latest_status"]
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.take(180)
                    ?.takeIf(String::isNotBlank),
                updatedAtMillis = parsePushTimestamp(data["updated_at"]),
                tasksCompleted = data["tasks_completed"].boundedCount(),
                tasksTotal = data["tasks_total"].boundedCount(),
                activeSubagents = data["active_subagents"].boundedCount(),
                errorCategory = MobileErrorCategory.from(data["error_category"]),
            )
        }

        private fun String?.boundedCount(): Int = this?.toIntOrNull()?.coerceIn(0, 999) ?: 0

        private fun parsePushTimestamp(value: String?): Long? {
            val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
            raw.toLongOrNull()?.let { epoch ->
                return if (epoch in 1..9_999_999_999L) epoch * 1_000L else epoch.takeIf { it > 0L }
            }
            return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        }
    }
}

internal fun mobileNotificationId(hostId: String, sessionId: String, runId: String?): Int {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$hostId\u0000$sessionId\u0000${runId.orEmpty()}".toByteArray(Charsets.UTF_8))
    return (ByteBuffer.wrap(digest).int and Int.MAX_VALUE).coerceAtLeast(1)
}

data class MobileRegistrationStatus(
    val hostId: String,
    val desired: Boolean,
    val registered: Boolean,
    val pending: Boolean,
    val errorMessage: String? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastSuccessMessage: String? = null,
    val lastFailureAtMillis: Long? = null,
    val lastFailureMessage: String? = null,
)

internal enum class MobileRegistrationAction { Register, Unregister }

internal data class MobileRegistrationFailure(
    val hostId: String,
    val action: MobileRegistrationAction,
    val cause: Exception,
    val message: String,
    val retryable: Boolean,
)

internal data class MobileRegistrationReport(
    val succeededHostIds: Set<String>,
    val failures: List<MobileRegistrationFailure>,
) {
    val isSuccess: Boolean get() = failures.isEmpty()
}

internal enum class MobileRegistrationWorkDecision { Success, Retry, Failure }

internal const val MOBILE_REGISTRATION_MAX_ATTEMPTS = 6

internal suspend fun syncMobileRegistrationHosts(
    hosts: List<HostProfile>,
    enabledHostIds: Set<String>,
    synchronize: suspend (HostProfile, MobileRegistrationAction) -> Unit,
): MobileRegistrationReport {
    val succeededHostIds = mutableSetOf<String>()
    val failures = mutableListOf<MobileRegistrationFailure>()
    hosts.forEach { host ->
        val action = if (host.id in enabledHostIds) {
            MobileRegistrationAction.Register
        } else {
            MobileRegistrationAction.Unregister
        }
        try {
            synchronize(host, action)
            succeededHostIds += host.id
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            failures += mobileRegistrationFailure(host.id, action, cause)
        }
    }
    return MobileRegistrationReport(succeededHostIds, failures)
}

internal fun markMobileRegistrationsPending(
    current: List<MobileRegistrationStatus>,
    hostIds: Set<String>,
    desiredHostIds: Set<String>,
): List<MobileRegistrationStatus> {
    val byHostId = current.associateBy(MobileRegistrationStatus::hostId)
    return hostIds.sorted().map { hostId ->
        val previous = byHostId[hostId] ?: MobileRegistrationStatus(
            hostId = hostId,
            desired = hostId in desiredHostIds,
            registered = false,
            pending = false,
        )
        previous.copy(
            desired = hostId in desiredHostIds,
            pending = true,
            errorMessage = null,
        )
    }
}

internal fun applyMobileRegistrationReportToStatuses(
    current: List<MobileRegistrationStatus>,
    hostIds: Set<String>,
    desiredHostIds: Set<String>,
    report: MobileRegistrationReport,
    nowMillis: Long,
    willRetry: Boolean,
): List<MobileRegistrationStatus> {
    val byHostId = current.associateBy(MobileRegistrationStatus::hostId)
    val failures = report.failures.associateBy(MobileRegistrationFailure::hostId)
    return hostIds.sorted().map { hostId ->
        val desired = hostId in desiredHostIds
        val previous = byHostId[hostId] ?: MobileRegistrationStatus(
            hostId = hostId,
            desired = desired,
            registered = false,
            pending = true,
        )
        when {
            hostId in report.succeededHostIds -> previous.copy(
                desired = desired,
                registered = desired,
                pending = false,
                errorMessage = null,
                lastSuccessAtMillis = nowMillis,
                lastSuccessMessage = if (desired) {
                    "Notifications registered with this host."
                } else {
                    "Notifications disabled on this host."
                },
            )

            hostId in failures -> {
                val failure = failures.getValue(hostId)
                previous.copy(
                    desired = desired,
                    pending = willRetry && failure.retryable,
                    errorMessage = failure.message,
                    lastFailureAtMillis = nowMillis,
                    lastFailureMessage = failure.message,
                )
            }

            else -> previous.copy(desired = desired)
        }
    }
}

internal fun decideMobileRegistrationWork(
    report: MobileRegistrationReport,
    runAttemptCount: Int,
): MobileRegistrationWorkDecision = when {
    report.failures.isEmpty() -> MobileRegistrationWorkDecision.Success
    report.failures.any(MobileRegistrationFailure::retryable) &&
        runAttemptCount < MOBILE_REGISTRATION_MAX_ATTEMPTS - 1 -> MobileRegistrationWorkDecision.Retry
    else -> MobileRegistrationWorkDecision.Failure
}

private class PermanentMobileRegistrationException(message: String) : Exception(message)

private fun mobileRegistrationFailure(
    hostId: String,
    action: MobileRegistrationAction,
    cause: Exception,
): MobileRegistrationFailure {
    val retryable = when (cause) {
        is PermanentMobileRegistrationException,
        is IllegalArgumentException,
        is SecurityException -> false
        is HermesApiException -> cause.statusCode in setOf(408, 425, 429) || cause.statusCode >= 500
        else -> true
    }
    val message = when (cause) {
        is PermanentMobileRegistrationException -> cause.message.orEmpty()
        is HermesApiException -> when (cause.statusCode) {
            401, 403 -> "Hermes rejected the mobile notification credentials."
            404 -> "This Hermes host does not support mobile notification registration."
            in 400..499 -> "Hermes rejected the mobile notification registration request."
            else -> "Hermes could not update mobile notifications."
        }
        is SocketTimeoutException -> "The Hermes host timed out while updating mobile notifications."
        is IOException -> "Could not reach this Hermes host."
        else -> "Mobile notification registration failed."
    }.take(240)
    return MobileRegistrationFailure(hostId, action, cause, message, retryable)
}

object MobileRegistration {
    fun statuses(context: Context): List<MobileRegistrationStatus> =
        PreferencesSettingsStore(context.applicationContext).loadMobileRegistrationStatuses()

    fun enqueue(
        context: Context,
        desiredHostIds: Set<String>? = null,
    ) = enqueueInternal(
        context = context,
        registeredToken = null,
        desiredHostIds = desiredHostIds,
        policy = ExistingWorkPolicy.REPLACE,
    )

    fun enqueueRetry(context: Context) = enqueueInternal(
        context = context,
        registeredToken = null,
        desiredHostIds = null,
        policy = ExistingWorkPolicy.REPLACE,
    )

    internal fun enqueueRegisteredToken(context: Context, registeredToken: String) = enqueueInternal(
        context = context,
        registeredToken = registeredToken,
        desiredHostIds = null,
        // A rotated FID must supersede work carrying the previous identity.
        policy = ExistingWorkPolicy.REPLACE,
    )

    private fun enqueueInternal(
        context: Context,
        registeredToken: String?,
        desiredHostIds: Set<String>?,
        policy: ExistingWorkPolicy,
    ) {
        val appContext = context.applicationContext
        val settings = PreferencesSettingsStore(appContext)
        val hostIds = SecureHostStore(appContext).load().snapshot.hosts.map(HostProfile::id).toSet()
        val desired = (desiredHostIds ?: settings.loadNotificationHostIds()).intersect(hostIds)
        settings.markMobileRegistrationPending(hostIds, desired)

        val request = OneTimeWorkRequestBuilder<MobileRegistrationWorker>()
            .setInputData(workDataOf(TOKEN_INPUT_KEY to registeredToken.orEmpty()))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    internal suspend fun performSync(context: Context, registeredToken: String?): MobileRegistrationReport = withContext(Dispatchers.IO) {
        val installationId = PreferencesSettingsStore(context).getOrCreateInstallationId()
        val hosts = SecureHostStore(context).load().snapshot.hosts
        val settings = PreferencesSettingsStore(context)
        val enabled = settings.loadNotificationHostIds()
        val gateway = HermesHttpGateway()
        var messagingToken = registeredToken?.trim()?.takeIf(String::isNotEmpty)
        var tokenFailure: Exception? = null
        if (hosts.any { it.id in enabled } && messagingToken == null) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    throw PermanentMobileRegistrationException(
                        "Firebase is not configured in this Hermes Mobile build.",
                    )
                }
                messagingToken = awaitMessagingToken()
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                tokenFailure = cause
            }
        }
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        val report = syncMobileRegistrationHosts(hosts, enabled) { host, action ->
            when (action) {
                MobileRegistrationAction.Register -> {
                    tokenFailure?.let { throw it }
                    val token = messagingToken ?: throw PermanentMobileRegistrationException(
                        "Firebase did not provide a messaging token.",
                    )
                    gateway.registerMobileDevice(
                        host = host,
                        installationId = installationId,
                        token = token,
                        appVersion = appVersion,
                        overlayEnabled = settings.loadOverlayEnabled(),
                    )
                }

                MobileRegistrationAction.Unregister -> gateway.unregisterMobileDevice(host, installationId)
            }
        }
        report.failures.forEach { failure ->
            Log.w(
                TAG,
                "Could not ${failure.action.name.lowercase()} mobile notifications for host ${failure.hostId}.",
                failure.cause,
            )
        }
        report
    }

    private suspend fun awaitMessagingToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { installation ->
            if (!continuation.isActive) return@addOnCompleteListener
            val token = installation.result?.trim().orEmpty()
            if (installation.isSuccessful && token.isNotEmpty()) {
                continuation.resume(token)
            } else {
                continuation.resumeWith(
                    Result.failure(
                        installation.exception ?: IllegalStateException("Firebase did not return a messaging token."),
                    ),
                )
            }
        }
    }

    internal const val TOKEN_INPUT_KEY = "registered_token"
    private const val WORK_NAME = "hermes-mobile-registration"
    private const val WORK_TAG = "mobile-registration"
    private const val TAG = "MobileRegistration"
}

class MobileRegistrationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val settings = PreferencesSettingsStore(applicationContext)
        val hosts = SecureHostStore(applicationContext).load().snapshot.hosts
        val hostIds = hosts.map(HostProfile::id).toSet()
        val desiredHostIds = settings.loadNotificationHostIds().intersect(hostIds)
        val report = try {
            MobileRegistration.performSync(
                context = applicationContext,
                registeredToken = inputData.getString(MobileRegistration.TOKEN_INPUT_KEY),
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            MobileRegistrationReport(
                succeededHostIds = emptySet(),
                failures = hosts.map { host ->
                    mobileRegistrationFailure(
                        hostId = host.id,
                        action = if (host.id in desiredHostIds) {
                            MobileRegistrationAction.Register
                        } else {
                            MobileRegistrationAction.Unregister
                        },
                        cause = cause,
                    )
                },
            )
        }
        val decision = decideMobileRegistrationWork(report, runAttemptCount)
        settings.applyMobileRegistrationReport(
            hostIds = hostIds,
            desiredHostIds = desiredHostIds,
            report = report,
            nowMillis = System.currentTimeMillis(),
            willRetry = decision == MobileRegistrationWorkDecision.Retry,
        )
        return when (decision) {
            MobileRegistrationWorkDecision.Success -> Result.success()
            MobileRegistrationWorkDecision.Retry -> Result.retry()
            MobileRegistrationWorkDecision.Failure -> Result.failure()
        }
    }
}

class HermesMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        MobileRegistration.enqueueRetry(applicationContext)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MobileRegistration.enqueueRegisteredToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val event = MobilePushEvent.from(message.data) ?: return
        val settings = PreferencesSettingsStore(applicationContext)
        event.runId?.let { runId ->
            when {
                event.isTerminal -> settings.clearRunStatus(runId)
                event.event == "approval.required" -> settings.saveRunStatus(runId, "Waiting for your approval")
                settings.loadRunStatus(runId).isNullOrBlank() -> settings.saveRunStatus(runId, "Working on the task…")
            }
        }
        HermesNotificationCoordinator(applicationContext).post(event)
        if (message.priority == RemoteMessage.PRIORITY_HIGH && event.isActive) {
            HermesOverlayService.onPush(applicationContext, event)
        }
    }
}

class HermesNotificationCoordinator(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val settings = PreferencesSettingsStore(context.applicationContext)

    fun post(event: MobilePushEvent) {
        createChannels()
        val activity = record(event)
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        postGroupSummary()
        if (event.isTerminal) {
            notificationManager.cancel(
                mobileNotificationTag(event, NotificationLane.Active),
                mobileNotificationId(event.hostProfileId, event.sessionId, event.runId),
            )
        }
        if (notificationLane(event) == NotificationLane.Active) {
            return
        }

        val isJob = event.event.startsWith("job.")
        val openIntent = if (isJob) jobsIntent(context) else sessionIntent(context, event.hostProfileId, event.sessionId)
        openIntent.data = notificationUri("open", event.hostProfileId, event.sessionId, event.runId)
        val contentPending = PendingIntent.getActivity(
            context, mobileNotificationId(event.hostProfileId, event.sessionId, event.runId), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copy = mobileNotificationCopy(event)
        val lane = notificationLane(event)
        val builder = NotificationCompat.Builder(context, channelFor(lane))
            .setSmallIcon(iconFor(lane))
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text).setSummaryText("Hermes · ${event.title}"))
            .setCategory(copy.category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(event.isTerminal)
            .setOngoing(copy.ongoing)
            .setOnlyAlertOnce(copy.ongoing)
            .setSilent(copy.silent)
            .setColor(0xFF1B1B1B.toInt())
            .setGroup(NOTIFICATION_GROUP)
            .setContentIntent(contentPending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification(copy.title))
            .setNumber(activityUnreadCount(settings.loadAttentionItems()))
            .addAction(0, if (event.event == "approval.required") "Review" else "Open", contentPending)
        if (event.event != "approval.required") {
            builder.setDeleteIntent(activityActionPendingIntent(ACTION_DISMISS, event, activity.identity()))
        }
        if (!isJob) {
            builder.setShortcutId(shortcutId(event))
        }

        if (!isJob && Build.VERSION.SDK_INT >= 29) {
            publishShortcut(event)
            val bubblePending = PendingIntent.getActivity(
                context,
                mobileNotificationId(event.hostProfileId, event.sessionId, event.runId) xor 0xBABB1E,
                Intent(context, BubbleActivity::class.java).apply {
                    data = notificationUri("bubble", event.hostProfileId, event.sessionId, event.runId)
                    putExtra(EXTRA_HOST_ID, event.hostProfileId)
                    putExtra(EXTRA_SESSION_ID, event.sessionId)
                    putExtra(EXTRA_SESSION_TITLE, event.title)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            builder.setBubbleMetadata(
                NotificationCompat.BubbleMetadata.Builder(
                    bubblePending,
                    IconCompat.createWithResource(context, R.drawable.hermes_official),
                ).setDesiredHeight(420).build()
            )
        }
        notificationManager.notify(
            mobileNotificationTag(event),
            mobileNotificationId(event.hostProfileId, event.sessionId, event.runId),
            builder.build(),
        )
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(ACTIVE_CHANNEL, "Active Work", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Silent ongoing Hermes work status"
                    setShowBadge(false)
                },
                NotificationChannel(ACTION_CHANNEL, "Action Needed", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Approvals and failures that need attention"
                    setShowBadge(true)
                    if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(true)
                },
                NotificationChannel(RESULTS_CHANNEL, "Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Completed Hermes work"
                    setShowBadge(true)
                },
                NotificationChannel(OVERLAY_SERVICE_CHANNEL, "Active session overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Android-required status while the floating overlay is active"
                    setShowBadge(false)
                },
            ),
        )
    }

    fun refreshSummary() {
        createChannels()
        if (Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            postGroupSummary()
        }
    }

    private fun record(event: MobilePushEvent): ActivityEntry {
        if (event.isTerminal) {
            settings.saveAttentionItems(
                settings.loadAttentionItems().map { item ->
                    val sameRun = item.hostId == event.hostProfileId &&
                        item.sessionId == event.sessionId &&
                        (event.runId == null || item.runId == event.runId)
                    if (sameRun && item.event == "approval.required") item.copy(read = true) else item
                },
            )
        }
        val activity = ActivityEntry(
                hostId = event.hostProfileId,
                sessionId = event.sessionId,
                title = event.title,
                state = event.state,
                runId = event.runId,
                event = event.event,
                eventId = event.eventId,
                latestStatus = event.latestStatus,
                updatedAtMillis = event.updatedAtMillis ?: System.currentTimeMillis(),
                tasksCompleted = event.tasksCompleted,
                tasksTotal = event.tasksTotal,
                activeSubagents = event.activeSubagents,
                errorCategory = event.errorCategory,
            )
        settings.recordActivity(activity)
        return activity
    }

    private fun postGroupSummary() {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val all = settings.loadAttentionItems()
        val unread = all.filter { it.requiresAttention && !it.read }
        if (unread.isEmpty()) {
            notificationManager.cancel(GROUP_SUMMARY_ID)
            return
        }
        val lines = buildList {
            unread.take(5).forEach { add("${it.title} · ${attentionLabel(it.state)}") }
        }
        val title = "${unread.size} Hermes update${if (unread.size == 1) "" else "s"} to review"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.forEach(style::addLine)
        val target = unread.first()
        val openIntent = sessionIntent(context, target.hostId, target.sessionId).apply {
            data = notificationUri("summary", target.hostId, target.sessionId, target.runId)
        }
        val pending = PendingIntent.getActivity(
            context,
            GROUP_SUMMARY_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, RESULTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_hermes_working)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "Open Hermes Mobile")
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification("Hermes has an update"))
        notificationManager.notify(GROUP_SUMMARY_ID, builder.build())
    }

    private fun publicNotification(title: String) = NotificationCompat.Builder(context, ACTIVE_CHANNEL)
        .setSmallIcon(R.drawable.ic_hermes_notification)
        .setContentTitle(title)
        .setContentText("Open Hermes Mobile for details")
        .build()

    private fun activityActionPendingIntent(action: String, event: MobilePushEvent, entryId: String? = null): PendingIntent {
        val intent = Intent(context, MobileNotificationActionReceiver::class.java).apply {
            this.action = action
            data = notificationUri(action, event.hostProfileId, event.sessionId, event.runId, entryId)
            putExtra(EXTRA_HOST_ID, event.hostProfileId)
            putExtra(EXTRA_SESSION_ID, event.sessionId)
            putExtra(EXTRA_RUN_ID, event.runId)
            putExtra(EXTRA_ACTIVITY_ID, entryId)
        }
        return PendingIntent.getBroadcast(
            context,
            mobileNotificationId(event.hostProfileId, event.sessionId, event.runId) xor action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun channelFor(lane: NotificationLane): String = when (lane) {
        NotificationLane.Active -> ACTIVE_CHANNEL
        NotificationLane.Action -> ACTION_CHANNEL
        NotificationLane.Result -> RESULTS_CHANNEL
    }

    private fun iconFor(lane: NotificationLane): Int = when (lane) {
        NotificationLane.Active -> R.drawable.ic_hermes_working
        NotificationLane.Action -> R.drawable.ic_hermes_attention
        NotificationLane.Result -> R.drawable.ic_hermes_done
    }

    private fun mobileNotificationTag(
        event: MobilePushEvent,
        lane: NotificationLane = notificationLane(event),
    ): String = "${lane.name.lowercase()}:${event.hostProfileId}:${event.sessionId}:${event.runId.orEmpty()}".take(180)

    private fun notificationUri(
        action: String,
        hostId: String,
        sessionId: String,
        runId: String?,
        entryId: String? = null,
    ): Uri = Uri.Builder()
        .scheme("hermes-mobile")
        .authority("notification")
        .appendPath(action)
        .appendPath(hostId)
        .appendPath(sessionId)
        .appendPath(runId.orEmpty())
        .appendPath(entryId.orEmpty())
        .build()

    private fun publishShortcut(event: MobilePushEvent) {
        if (Build.VERSION.SDK_INT < 29) return
        publishShortcutSafely(
            update = {
                val shortcut = ShortcutInfo.Builder(context, shortcutId(event))
                    .setShortLabel(event.title.take(40))
                    .setLongLived(true)
                    .setPerson(Person.Builder().setName("Hermes").setKey("hermes-agent").build())
                    .setIcon(Icon.createWithResource(context, R.drawable.hermes_official))
                    .setIntent(sessionIntent(context, event.hostProfileId, event.sessionId))
                    .build()
                val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                publishDynamicShortcut(
                    sdkInt = Build.VERSION.SDK_INT,
                    incomingId = shortcut.id,
                    maxCount = shortcutManager.maxShortcutCountPerActivity,
                    existing = shortcutManager.dynamicShortcuts.map {
                        DynamicShortcutSlot(it.id, it.lastChangedTimestamp)
                    },
                    rateLimitingActive = shortcutManager.isRateLimitingActive,
                    push = if (Build.VERSION.SDK_INT >= 30) {
                        { shortcutManager.pushDynamicShortcut(shortcut) }
                    } else {
                        {}
                    },
                    remove = shortcutManager::removeDynamicShortcuts,
                    add = { shortcutManager.addDynamicShortcuts(listOf(shortcut)) },
                )
            },
            onFailure = { error ->
                runCatching {
                    AppDiagnosticsRegistry.recorder.recordFailure(DiagnosticPhase.NotificationShortcut, error)
                }
            },
        )
    }

    private fun shortcutId(event: MobilePushEvent) = "hermes-${event.hostProfileId}-${event.sessionId}".take(120)

    companion object {
        const val ACTIVE_CHANNEL = "hermes_active_work_v2"
        const val ACTION_CHANNEL = "hermes_action_needed_v2"
        const val RESULTS_CHANNEL = "hermes_results_v2"
        const val OVERLAY_SERVICE_CHANNEL = "hermes_overlay_service_v3"
        const val GROUP_SUMMARY_ID = 9043
        const val OVERLAY_SERVICE_NOTIFICATION_ID = 9044
        const val NOTIFICATION_GROUP = "hermes_session_updates"
        const val EXTRA_HOST_ID = "host_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_SESSION_TITLE = "session_title"
        const val EXTRA_SCREEN = "screen"
        const val ACTION_STOP = "au.com.chrismckechnie.hermesmobile.NOTIFICATION_STOP"
        const val ACTION_DISMISS = "au.com.chrismckechnie.hermesmobile.NOTIFICATION_DISMISS"

        @Deprecated("Use ACTIVE_CHANNEL") const val WORK_CHANNEL = ACTIVE_CHANNEL
        @Deprecated("Use OVERLAY_SERVICE_CHANNEL") const val OVERLAY_CHANNEL = OVERLAY_SERVICE_CHANNEL
        @Deprecated("Use ACTION_CHANNEL or RESULTS_CHANNEL") const val RUN_CHANNEL = ACTION_CHANNEL
        @Deprecated("Use GROUP_SUMMARY_ID") const val WORK_NOTIFICATION_ID = GROUP_SUMMARY_ID

        fun sessionIntent(context: Context, hostId: String, sessionId: String) =
            Intent(context, MainActivity::class.java).apply {
                action = "au.com.chrismckechnie.hermesmobile.OPEN_SESSION"
                putExtra(EXTRA_HOST_ID, hostId)
                putExtra(EXTRA_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        fun jobsIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
            action = "au.com.chrismckechnie.hermesmobile.OPEN_JOBS"
            putExtra(EXTRA_SCREEN, "jobs")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}

class MobileNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hostId = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_HOST_ID).orEmpty()
        val sessionId = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_SESSION_ID).orEmpty()
        when (intent.action) {
            HermesNotificationCoordinator.ACTION_DISMISS -> if (hostId.isNotBlank()) {
                PreferencesSettingsStore(context).markActivityRead(
                    hostId,
                    sessionId.takeIf(String::isNotBlank),
                    intent.getStringExtra(HermesNotificationCoordinator.EXTRA_ACTIVITY_ID),
                )
                HermesNotificationCoordinator(context.applicationContext).refreshSummary()
            }

            HermesNotificationCoordinator.ACTION_STOP -> {
                val runId = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_RUN_ID).orEmpty()
                if (hostId.isBlank() || runId.isBlank()) return
                val request = OneTimeWorkRequestBuilder<NotificationStopWorker>()
                    .setInputData(workDataOf("host_id" to hostId, "run_id" to runId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    "notification-stop-${mobileNotificationId(hostId, sessionId, runId)}",
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}

class NotificationStopWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val hostId = inputData.getString("host_id").orEmpty()
        val runId = inputData.getString("run_id").orEmpty()
        val host = SecureHostStore(applicationContext).load().snapshot.hosts.firstOrNull { it.id == hostId }
            ?: return Result.failure()
        val settings = PreferencesSettingsStore(applicationContext)
        settings.saveRunStatus(runId, "Stopping…")
        return runCatching { HermesHttpGateway().stopRun(host, runId) }.fold(
            onSuccess = {
                settings.saveRunStatus(runId, "Stop requested")
                Result.success()
            },
            onFailure = { error ->
                val permanent = (error as? HermesApiException)?.statusCode in 400..499
                if (permanent || runAttemptCount >= 2) {
                    settings.saveRunStatus(runId, "Could not stop the run")
                    Result.failure()
                } else {
                    Result.retry()
                }
            },
        )
    }
}

internal data class DynamicShortcutSlot(
    val id: String,
    val lastChangedAt: Long,
)

internal fun shortcutIdsToRemoveForCapacity(
    existing: List<DynamicShortcutSlot>,
    incomingId: String,
    maxCount: Int,
): List<String> {
    if (maxCount <= 0 || existing.any { it.id == incomingId }) return emptyList()
    val required = (existing.size - maxCount + 1).coerceAtLeast(0)
    return existing.sortedBy(DynamicShortcutSlot::lastChangedAt).take(required).map(DynamicShortcutSlot::id)
}

internal fun publishDynamicShortcut(
    sdkInt: Int,
    incomingId: String,
    maxCount: Int,
    existing: List<DynamicShortcutSlot>,
    rateLimitingActive: Boolean,
    push: () -> Unit,
    remove: (List<String>) -> Unit,
    add: () -> Boolean,
) {
    if (sdkInt >= 30) {
        push()
        return
    }
    if (maxCount <= 0 || rateLimitingActive) return
    val removals = shortcutIdsToRemoveForCapacity(existing, incomingId, maxCount)
    if (removals.isNotEmpty()) remove(removals)
    add()
}

internal fun publishShortcutSafely(
    update: () -> Unit,
    onFailure: (RuntimeException) -> Unit,
) {
    try {
        update()
    } catch (error: RuntimeException) {
        onFailure(error)
    }
}

internal data class MobileNotificationCopy(
    val title: String,
    val text: String,
    val category: String,
    val ongoing: Boolean,
    val silent: Boolean,
)

internal enum class NotificationLane { Active, Action, Result }

internal fun notificationLane(event: MobilePushEvent): NotificationLane = when (event.event) {
    "approval.required", "session.failed", "job.failed" -> NotificationLane.Action
    "session.completed", "session.cancelled", "job.completed" -> NotificationLane.Result
    "notification.test" -> NotificationLane.Result
    else -> NotificationLane.Active
}

internal fun mobileNotificationCopy(event: MobilePushEvent): MobileNotificationCopy = MobileNotificationCopy(
    title = when (event.event) {
        "approval.required" -> "Hermes needs your approval"
        "session.failed", "job.failed" -> "Hermes hit an issue"
        "session.cancelled" -> "Hermes stopped"
        "session.completed", "job.completed" -> "Hermes finished"
        "notification.test" -> "Hermes notifications are working"
        else -> "Hermes is working"
    },
    text = buildList {
        add(event.title)
        event.latestStatus?.takeIf { !it.equals(event.title, ignoreCase = true) }?.let(::add)
        if (event.tasksTotal > 0) add("${event.tasksCompleted.coerceAtMost(event.tasksTotal)}/${event.tasksTotal} tasks")
        if (event.activeSubagents > 0) add("${event.activeSubagents} active subagent${if (event.activeSubagents == 1) "" else "s"}")
    }.joinToString(" · "),
    category = when {
        event.event == "approval.required" -> NotificationCompat.CATEGORY_MESSAGE
        event.isTerminal || event.event == "notification.test" -> NotificationCompat.CATEGORY_STATUS
        else -> NotificationCompat.CATEGORY_PROGRESS
    },
    ongoing = !event.isTerminal && event.event != "notification.test",
    silent = notificationLane(event) == NotificationLane.Active,
)
