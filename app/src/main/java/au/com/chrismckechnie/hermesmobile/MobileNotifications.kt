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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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

object MobileRegistration {
    suspend fun sync(context: Context, registeredFid: String? = null) = withContext(Dispatchers.IO) {
        if (FirebaseApp.getApps(context).isEmpty()) return@withContext
        if (registeredFid == null) {
            awaitRegistration()
            return@withContext
        }
        val installationFid = registeredFid
        val installationId = PreferencesSettingsStore(context).getOrCreateInstallationId()
        val hosts = SecureHostStore(context).load().snapshot.hosts
        val settings = PreferencesSettingsStore(context)
        val enabled = settings.loadNotificationHostIds()
        val gateway = HermesHttpGateway()
        hosts.forEach { host ->
            runCatching {
                if (host.id in enabled) {
                    gateway.registerMobileDevice(
                        host = host,
                        installationId = installationId,
                        token = installationFid,
                        appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
                        overlayEnabled = settings.loadOverlayEnabled(),
                    )
                } else {
                    gateway.unregisterMobileDevice(host, installationId)
                }
            }
        }
    }

    private suspend fun awaitRegistration(): Boolean = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().register().addOnCompleteListener { registration ->
            if (continuation.isActive) continuation.resume(registration.isSuccessful)
        }
    }

}

class HermesMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        MobileBackgroundScope.launch { MobileRegistration.sync(applicationContext, installationId) }
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
        val shortcut = ShortcutInfo.Builder(context, shortcutId(event))
            .setShortLabel(event.title.take(40))
            .setLongLived(true)
            .setPerson(Person.Builder().setName("Hermes").setKey("hermes-agent").build())
            .setIcon(Icon.createWithResource(context, R.drawable.hermes_official))
            .setIntent(sessionIntent(context, event.hostProfileId, event.sessionId))
            .build()
        context.getSystemService(ShortcutManager::class.java).addDynamicShortcuts(listOf(shortcut))
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

private object MobileBackgroundScope {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    fun launch(block: suspend () -> Unit) = scope.launch { block() }
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
