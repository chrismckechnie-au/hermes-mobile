package au.com.chrismckechnie.hermesmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
    private var chipTitle: TextView? = null
    private var chipSubtitle: TextView? = null
    private var chipCount: TextView? = null
    private var panel: View? = null
    private var pollJob: Job? = null
    private var sessions = emptyList<OverlaySession>()
    private var localRunHint: LocalRunHint? = null
    private val serviceStartedAt = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createForegroundChannel()
        startForeground(HermesNotificationCoordinator.WORK_NOTIFICATION_ID, foregroundNotification(emptyList()))
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
        startForeground(HermesNotificationCoordinator.WORK_NOTIFICATION_ID, foregroundNotification(sessions))
        val settings = PreferencesSettingsStore(applicationContext)
        if (settings.loadOverlayEnabled() && Settings.canDrawOverlays(this)) {
            showOrUpdateChip()
            if (panel != null) showPanel()
        } else {
            removeWindows()
        }
    }

    private fun showOrUpdateChip() {
        val copy = activeWorkCopy(sessions.map { it.host.name to it.session })
        chip?.let {
            chipTitle?.text = copy.title
            chipSubtitle?.text = copy.text
            chipCount?.apply {
                text = copy.count.toString()
                visibility = if (copy.count > 1) View.VISIBLE else View.GONE
            }
            it.contentDescription = activeSessionDescription(sessions.size)
            return
        }

        val title = overlayText(copy.title, 13f, Color.WHITE, bold = true)
        val subtitle = overlayText(copy.text, 11f, 0xFFB7B7B7.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val count = overlayText(copy.count.toString(), 11f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(0xFF343434.toInt(), 14.dp.toFloat())
            visibility = if (copy.count > 1) View.VISIBLE else View.GONE
        }
        val copyColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(subtitle)
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 7.dp, 10.dp, 7.dp)
            background = roundedBackground(0xF51B1B1B.toInt(), 28.dp.toFloat())
            contentDescription = activeSessionDescription(sessions.size)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.hermes_official)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = roundedBackground(Color.WHITE, 12.dp.toFloat())
            }, LinearLayout.LayoutParams(42.dp, 42.dp).apply { marginEnd = 9.dp })
            addView(copyColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(count, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 8.dp })
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        val params = overlayParams(260.dp, 58.dp, Gravity.END or Gravity.CENTER_VERTICAL).apply { x = 14.dp }
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
        chipTitle = title
        chipSubtitle = subtitle
        chipCount = count
    }

    private fun showPanel() {
        hidePanel()
        val first = sessions.firstOrNull()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 14.dp, 14.dp, 12.dp)
            background = roundedBackground(0xFA181818.toInt(), 24.dp.toFloat())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.hermes_official)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 10.dp })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(overlayText("Hermes", 15f, Color.WHITE, bold = true))
                    addView(overlayText("Active work", 11f, 0xFFA4A4A4.toInt()))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(overlayText("×", 22f, 0xFFB7B7B7.toInt()).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Collapse active work"
                    setOnClickListener { hidePanel() }
                }, LinearLayout.LayoutParams(40.dp, 40.dp))
            })
            sessions.forEach { item ->
                val stateLabel = when (item.session.state.lowercase()) {
                    "waiting_for_approval", "approval_required" -> "Needs approval"
                    "queued" -> "Queued"
                    "stopping" -> "Stopping"
                    else -> "Working"
                }
                val stateColor = if (stateLabel == "Needs approval") 0xFFFFC66D.toInt() else 0xFF9FD3B4.toInt()
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(13.dp, 11.dp, 13.dp, 11.dp)
                    background = roundedBackground(0xFF262626.toInt(), 16.dp.toFloat())
                    addView(overlayText(item.session.title, 13f, Color.WHITE, bold = true).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(overlayText("●  $stateLabel · ${item.host.name}", 11f, stateColor).apply {
                        setPadding(0, 4.dp, 0, 0)
                    })
                    setOnClickListener {
                        startActivity(HermesNotificationCoordinator.sessionIntent(context, item.host.id, item.session.sessionId))
                        hidePanel()
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 10.dp
                })
            }
            addView(overlayText("Open Hermes Mobile", 12f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(10.dp, 14.dp, 10.dp, 12.dp)
                setOnClickListener {
                    startActivity(
                        first?.let { HermesNotificationCoordinator.sessionIntent(context, it.host.id, it.session.sessionId) }
                            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    hidePanel()
                }
            })
            addView(overlayText("Hide until next run", 11f, 0xFF8E8E8E.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(10.dp, 6.dp, 10.dp, 8.dp)
                setOnClickListener { stopSelf() }
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        val maxHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val panelHeight = (170 + sessions.size * 76).dp.coerceAtMost(maxHeight)
        windowManager.addView(scroll, overlayParams(320.dp, panelHeight, Gravity.END or Gravity.CENTER_VERTICAL).apply { x = 18.dp })
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
        chipTitle = null
        chipSubtitle = null
        chipCount = null
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
        val copy = activeWorkCopy(active.map { it.host.name to it.session })
        val openIntent = first?.let {
            HermesNotificationCoordinator.sessionIntent(this, it.host.id, it.session.sessionId)
        } ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, HermesNotificationCoordinator.WORK_CHANNEL)
            .setSmallIcon(R.drawable.ic_hermes_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setSubText(copy.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text).setSummaryText(copy.summary))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(0xFF1B1B1B.toInt())
            .setWhen(serviceStartedAt)
            .setUsesChronometer(active.isNotEmpty())
            .setContentInfo(copy.count.takeIf { it > 1 }?.toString())
            .setContentIntent(pending)
            .addAction(0, "Open", pending)
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
        setStroke(1.dp, 0x24FFFFFF)
    }

    private fun overlayText(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun activeSessionDescription(count: Int) = "$count active Hermes ${if (count == 1) "session" else "sessions"}"
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
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

internal data class ActiveWorkCopy(
    val title: String,
    val text: String,
    val summary: String?,
    val count: Int,
)

internal fun activeWorkCopy(active: List<Pair<String, HermesActiveSession>>): ActiveWorkCopy {
    val first = active.firstOrNull()
    val count = active.size
    val needsApproval = first?.second?.state?.lowercase() in setOf("waiting_for_approval", "approval_required")
    return ActiveWorkCopy(
        title = when {
            needsApproval -> "Hermes needs approval"
            count == 0 -> "Connecting to Hermes"
            count == 1 -> "Hermes is working"
            else -> "Hermes is working on $count tasks"
        },
        text = when {
            first == null -> "Checking active sessions"
            count == 1 -> first.second.title
            else -> "${first.second.title} · ${count - 1} more"
        },
        summary = first?.first,
        count = count,
    )
}
