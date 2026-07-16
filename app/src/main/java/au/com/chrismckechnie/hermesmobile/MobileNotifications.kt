package au.com.chrismckechnie.hermesmobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
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
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class MobilePushEvent(
    val event: String,
    val hostProfileId: String,
    val sessionId: String,
    val runId: String?,
    val title: String,
    val state: String,
    val activeCount: Int,
) {
    val isTerminal: Boolean get() = event in setOf(
        "session.completed", "session.failed", "session.cancelled", "job.completed", "job.failed",
    )
    val requiresAttention: Boolean get() = event in setOf(
        "approval.required", "session.completed", "session.failed", "session.cancelled",
    )

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
            )
        }
    }
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
        registeredFid = null,
        desiredHostIds = desiredHostIds,
        policy = ExistingWorkPolicy.REPLACE,
    )

    fun enqueueRetry(context: Context) = enqueueInternal(
        context = context,
        registeredFid = null,
        desiredHostIds = null,
        policy = ExistingWorkPolicy.REPLACE,
    )

    internal fun enqueueRegisteredFid(context: Context, registeredFid: String) = enqueueInternal(
        context = context,
        registeredFid = registeredFid,
        desiredHostIds = null,
        // A rotated FID must supersede work carrying the previous identity.
        policy = ExistingWorkPolicy.REPLACE,
    )

    private fun enqueueInternal(
        context: Context,
        registeredFid: String?,
        desiredHostIds: Set<String>?,
        policy: ExistingWorkPolicy,
    ) {
        val appContext = context.applicationContext
        val settings = PreferencesSettingsStore(appContext)
        val hostIds = SecureHostStore(appContext).load().snapshot.hosts.map(HostProfile::id).toSet()
        val desired = (desiredHostIds ?: settings.loadNotificationHostIds()).intersect(hostIds)
        settings.markMobileRegistrationPending(hostIds, desired)

        val request = OneTimeWorkRequestBuilder<MobileRegistrationWorker>()
            .setInputData(workDataOf(FID_INPUT_KEY to registeredFid.orEmpty()))
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

    internal suspend fun performSync(context: Context, registeredFid: String?): MobileRegistrationReport = withContext(Dispatchers.IO) {
        val installationId = PreferencesSettingsStore(context).getOrCreateInstallationId()
        val hosts = SecureHostStore(context).load().snapshot.hosts
        val settings = PreferencesSettingsStore(context)
        val enabled = settings.loadNotificationHostIds()
        val gateway = HermesHttpGateway()
        var installationFid = registeredFid?.trim()?.takeIf(String::isNotEmpty)
        var fidFailure: Exception? = null
        if (hosts.any { it.id in enabled } && installationFid == null) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    throw PermanentMobileRegistrationException(
                        "Firebase is not configured in this Hermes Mobile build.",
                    )
                }
                awaitRegistration()
                installationFid = awaitInstallationFid()
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                fidFailure = cause
            }
        }
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        val report = syncMobileRegistrationHosts(hosts, enabled) { host, action ->
            when (action) {
                MobileRegistrationAction.Register -> {
                    fidFailure?.let { throw it }
                    val fid = installationFid ?: throw PermanentMobileRegistrationException(
                        "Firebase did not provide an installation id.",
                    )
                    gateway.registerMobileDevice(
                        host = host,
                        installationId = installationId,
                        token = fid,
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

    private suspend fun awaitRegistration(): Unit = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().register().addOnCompleteListener { registration ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (registration.isSuccessful) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWith(
                    Result.failure(
                        registration.exception ?: IllegalStateException("Firebase did not explain why registration failed."),
                    ),
                )
            }
        }
    }

    private suspend fun awaitInstallationFid(): String = suspendCancellableCoroutine { continuation ->
        FirebaseInstallations.getInstance().id.addOnCompleteListener { installation ->
            if (!continuation.isActive) return@addOnCompleteListener
            val fid = installation.result?.trim().orEmpty()
            if (installation.isSuccessful && fid.isNotEmpty()) {
                continuation.resume(fid)
            } else {
                continuation.resumeWith(
                    Result.failure(
                        installation.exception ?: IllegalStateException("Firebase did not return an installation id."),
                    ),
                )
            }
        }
    }

    internal const val FID_INPUT_KEY = "registered_fid"
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
                registeredFid = inputData.getString(MobileRegistration.FID_INPUT_KEY),
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
        MobileRegistration.enqueueRegisteredFid(applicationContext, installationId)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // The mobile endpoint targets Firebase Installation IDs. A legacy
        // token refresh therefore requests a fresh FID callback instead of
        // accidentally uploading the token in the `fid` field.
        MobileRegistration.enqueueRetry(applicationContext)
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
        if (event.requiresAttention) {
            settings.markAttention(
                AttentionItem(
                    hostId = event.hostProfileId,
                    sessionId = event.sessionId,
                    title = event.title,
                    state = event.state,
                ),
            )
        }
        HermesNotificationCoordinator(applicationContext).post(event)
        HermesOverlayService.onPush(applicationContext, event)
    }
}

class HermesNotificationCoordinator(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun post(event: MobilePushEvent) {
        createChannels()
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val isJob = event.event.startsWith("job.")
        val openIntent = if (isJob) jobsIntent(context) else sessionIntent(context, event.hostProfileId, event.sessionId)
        val contentPending = PendingIntent.getActivity(
            context, event.sessionId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copy = mobileNotificationCopy(event)
        val builder = NotificationCompat.Builder(context, RUN_CHANNEL)
            .setSmallIcon(R.drawable.ic_hermes_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text).setSummaryText("Hermes Remote"))
            .setCategory(copy.category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(event.isTerminal)
            .setOngoing(copy.ongoing)
            .setOnlyAlertOnce(copy.ongoing)
            .setSilent(copy.silent)
            .setColor(0xFF1B1B1B.toInt())
            .setGroup(NOTIFICATION_GROUP)
            .setContentIntent(contentPending)
            .addAction(0, if (event.event == "approval.required") "Review" else "Open", contentPending)
        if (!isJob) {
            builder.setShortcutId(shortcutId(event))
        }

        if (!isJob && Build.VERSION.SDK_INT >= 29) {
            publishShortcut(event)
            val bubblePending = PendingIntent.getActivity(
                context,
                event.sessionId.hashCode() xor 0xBABB1E,
                Intent(context, BubbleActivity::class.java).apply {
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
        val notificationManager = NotificationManagerCompat.from(context)
        if (event.isTerminal && event.activeCount == 0) {
            notificationManager.cancel(WORK_NOTIFICATION_ID)
        }
        val notificationId = if (!event.isTerminal && event.event != "approval.required" && !isJob) {
            WORK_NOTIFICATION_ID
        } else {
            event.sessionId.hashCode()
        }
        notificationManager.notify(notificationId, builder.build())
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(
            NotificationChannel(RUN_CHANNEL, "Hermes runs", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Active session, approval, failure, and completion status"
                if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(OVERLAY_CHANNEL, "Active session overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

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
        const val RUN_CHANNEL = "hermes_runs"
        const val OVERLAY_CHANNEL = "hermes_overlay"
        const val WORK_CHANNEL = "hermes_active_work"
        const val WORK_NOTIFICATION_ID = 9042
        const val NOTIFICATION_GROUP = "hermes_session_updates"
        const val EXTRA_HOST_ID = "host_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_TITLE = "session_title"
        const val EXTRA_SCREEN = "screen"

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

internal fun mobileNotificationCopy(event: MobilePushEvent): MobileNotificationCopy = MobileNotificationCopy(
    title = when (event.event) {
        "approval.required" -> "Hermes needs your approval"
        "session.failed", "job.failed" -> "Hermes hit an issue"
        "session.cancelled" -> "Hermes stopped"
        "session.completed", "job.completed" -> "Hermes finished"
        else -> "Hermes is working"
    },
    text = event.title,
    category = when {
        event.event == "approval.required" -> NotificationCompat.CATEGORY_MESSAGE
        event.isTerminal -> NotificationCompat.CATEGORY_STATUS
        else -> NotificationCompat.CATEGORY_PROGRESS
    },
    ongoing = !event.isTerminal,
    silent = event.event !in setOf("approval.required", "session.failed", "job.failed"),
)
