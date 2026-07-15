package au.com.chrismckechnie.hermesmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground owner for the ongoing work notification and optional draw-over-apps UI.
 * The Run remains host-owned; this service only polls privacy-safe status metadata.
 */
class HermesOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var chip: View? = null
    private var chipBadge: TextView? = null
    private var panel: View? = null
    private var pollJob: Job? = null
    private var sessions = emptyList<OverlaySession>()
    private var localRunHint: LocalRunHint? = null
    private val officialIcon by lazy { BitmapFactory.decodeResource(resources, R.drawable.hermes_official) }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createForegroundChannel()
        startForeground(STATUS_NOTIFICATION_ID, foregroundNotification(emptyList()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.localRunHint()?.let { localRunHint = it }
        if (pollJob == null) {
            pollJob = scope.launch {
                while (true) {
                    refreshSessions()
                    delay(POLL_INTERVAL_MS)
                }
            }
        } else {
            scope.launch { refreshSessions() }
        }
        return START_STICKY
    }

    private suspend fun refreshSessions() {
        val next = withContext(Dispatchers.IO) {
            val settings = PreferencesSettingsStore(applicationContext)
            val enabled = settings.loadNotificationHostIds()
            val hosts = SecureHostStore(applicationContext).load().snapshot.hosts.filter { it.id in enabled }
            val gateway = HermesHttpGateway()
            val remote = hosts.flatMap { host ->
                runCatching { gateway.listActiveSessions(host) }.getOrDefault(emptyList()).map { session ->
                    OverlaySession(host, session)
                }
            }
            val hint = localRunHint ?: settings.loadRunCheckpoint()?.let { checkpoint ->
                LocalRunHint(checkpoint.hostId, checkpoint.sessionId, checkpoint.runId, "Hermes task")
            }
            val local = hint?.let { value ->
                val host = hosts.firstOrNull { it.id == value.hostId } ?: return@let null
                val alreadyReported = remote.any {
                    it.host.id == value.hostId && it.session.sessionId == value.sessionId
                }
                if (alreadyReported) return@let null
                val status = runCatching { gateway.getRunStatus(host, value.runId) }.getOrNull()
                if (status?.isTerminal == true) null else OverlaySession(
                    host,
                    HermesActiveSession(
                        sessionId = value.sessionId,
                        runId = value.runId,
                        title = value.title,
                        state = status?.status ?: "working",
                        surface = "api_server",
                    ),
                )
            }
            (remote + listOfNotNull(local)).distinctBy { it.host.id to it.session.sessionId }
        }

        sessions = next
        if (sessions.isEmpty()) {
            removeWindows()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Re-promoting updates the required foreground notification and still
        // permits the overlay service when Android notification permission is denied.
        startForeground(STATUS_NOTIFICATION_ID, foregroundNotification(sessions))
        val settings = PreferencesSettingsStore(applicationContext)
        if (settings.loadOverlayEnabled() && Settings.canDrawOverlays(this)) {
            showOrUpdateChip()
            if (panel != null) showPanel()
        } else {
            removeWindows()
        }
    }

    private fun showOrUpdateChip() {
        chip?.let {
            chipBadge?.text = sessions.size.toString()
            it.contentDescription = activeSessionDescription(sessions.size)
            return
        }

        val badge = TextView(this).apply {
            text = sessions.size.toString()
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(0xEE1F1B17.toInt(), 18f)
        }
        val view = FrameLayout(this).apply {
            contentDescription = activeSessionDescription(sessions.size)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.hermes_official)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(64.dp, 64.dp))
            addView(
                badge,
                FrameLayout.LayoutParams(22.dp, 22.dp, Gravity.END or Gravity.BOTTOM),
            )
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        val params = overlayParams(64.dp, 64.dp, Gravity.END or Gravity.CENTER_VERTICAL).apply { x = 18.dp }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (downX - event.rawX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    moved = moved || kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = startY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }
                else -> false
            }
        }
        windowManager.addView(view, params)
        chip = view
        chipBadge = badge
    }

    private fun showPanel() {
        hidePanel()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            background = roundedBackground(0xF51F1B17.toInt(), 24f)
            addView(TextView(context).apply {
                text = "Active Hermes sessions"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(8.dp, 0, 8.dp, 8.dp)
            })
            sessions.forEach { item ->
                addView(Button(context).apply {
                    text = "${item.session.title}\n${item.host.name} · ${item.session.state}"
                    isAllCaps = false
                    setOnClickListener {
                        startActivity(HermesNotificationCoordinator.sessionIntent(context, item.host.id, item.session.sessionId))
                        hidePanel()
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            addView(Button(context).apply {
                text = "Dismiss overlay"
                isAllCaps = false
                setOnClickListener { stopSelf() }
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        val maxHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        windowManager.addView(scroll, overlayParams(320.dp, maxHeight, Gravity.END or Gravity.CENTER_VERTICAL).apply { x = 18.dp })
        panel = scroll
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    private fun removeWindows() {
        hidePanel()
        chip?.let { runCatching { windowManager.removeView(it) } }
        chip = null
        chipBadge = null
    }

    override fun onDestroy() {
        pollJob?.cancel()
        removeWindows()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foregroundNotification(active: List<OverlaySession>): android.app.Notification {
        val first = active.firstOrNull()
        val title = when (active.size) {
            0 -> "Connecting to Hermes"
            1 -> "Hermes is working"
            else -> "Hermes is working on ${active.size} tasks"
        }
        val text = first?.session?.title ?: "Checking active sessions"
        val openIntent = first?.let {
            HermesNotificationCoordinator.sessionIntent(this, it.host.id, it.session.sessionId)
        } ?: Intent(this, MainActivity::class.java)
        return NotificationCompat.Builder(this, HermesNotificationCoordinator.WORK_CHANNEL)
            .setSmallIcon(R.drawable.ic_hermes_notification)
            .setLargeIcon(officialIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(first?.host?.name)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, active.isNotEmpty())
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
    }

    private fun createForegroundChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                HermesNotificationCoordinator.WORK_CHANNEL,
                "Hermes active work",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Ongoing status while Hermes is actively working" }
        )
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(1.dp, 0x55FFFFFF)
    }

    private fun activeSessionDescription(count: Int) = "$count active Hermes ${if (count == 1) "session" else "sessions"}"
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATUS_NOTIFICATION_ID = 9042
        private const val POLL_INTERVAL_MS = 5_000L
        private const val EXTRA_RUN_ID = "active_run_id"
        private const val EXTRA_RUN_TITLE = "active_run_title"

        fun startForRun(context: Context, run: ActiveRun) {
            val intent = Intent(context, HermesOverlayService::class.java).apply {
                putExtra(HermesNotificationCoordinator.EXTRA_HOST_ID, run.host.id)
                putExtra(HermesNotificationCoordinator.EXTRA_SESSION_ID, run.sessionId)
                putExtra(EXTRA_RUN_ID, run.runId)
                putExtra(EXTRA_RUN_TITLE, run.sessionTitle ?: "Hermes task")
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun onPush(context: Context, event: MobilePushEvent) {
            if (event.activeCount == 0 && event.isTerminal) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HermesOverlayService::class.java)) }
        }

        fun startIfAllowed(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HermesOverlayService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesOverlayService::class.java))
        }

        private fun Intent.localRunHint(): LocalRunHint? {
            val hostId = getStringExtra(HermesNotificationCoordinator.EXTRA_HOST_ID).orEmpty()
            val sessionId = getStringExtra(HermesNotificationCoordinator.EXTRA_SESSION_ID).orEmpty()
            val runId = getStringExtra(EXTRA_RUN_ID).orEmpty()
            if (hostId.isBlank() || sessionId.isBlank() || runId.isBlank()) return null
            return LocalRunHint(hostId, sessionId, runId, getStringExtra(EXTRA_RUN_TITLE).orEmpty().ifBlank { "Hermes task" })
        }
    }
}

private data class OverlaySession(val host: HostProfile, val session: HermesActiveSession)
private data class LocalRunHint(val hostId: String, val sessionId: String, val runId: String, val title: String)
