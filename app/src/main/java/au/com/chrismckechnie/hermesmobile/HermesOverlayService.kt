package au.com.chrismckechnie.hermesmobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
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

class HermesOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var chip: TextView? = null
    private var panel: View? = null
    private var pollJob: Job? = null
    private var sessions = emptyList<OverlaySession>()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createForegroundChannel()
        startForeground(OVERLAY_NOTIFICATION_ID, foregroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pollJob == null) {
            pollJob = scope.launch {
                while (true) {
                    refreshSessions()
                    delay(15_000)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun refreshSessions() {
        val next = withContext(Dispatchers.IO) {
            val settings = PreferencesSettingsStore(applicationContext)
            val enabled = settings.loadNotificationHostIds()
            val hosts = SecureHostStore(applicationContext).load().snapshot.hosts.filter { it.id in enabled }
            val gateway = HermesHttpGateway()
            hosts.flatMap { host ->
                runCatching { gateway.listActiveSessions(host) }.getOrDefault(emptyList()).map { session ->
                    OverlaySession(host, session)
                }
            }
        }
        sessions = next.distinctBy { it.host.id to it.session.sessionId }
        if (sessions.isEmpty()) {
            removeWindows()
            stopSelf()
        } else {
            showOrUpdateChip()
            if (panel != null) showPanel()
        }
    }

    private fun showOrUpdateChip() {
        chip?.let {
            it.text = sessions.size.toString()
            it.contentDescription = "${sessions.size} active Hermes sessions"
            return
        }
        val view = TextView(this).apply {
            text = sessions.size.toString()
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "${sessions.size} active Hermes sessions"
            background = roundedBackground(0xEE3A3228.toInt(), 64f)
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
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (downX - event.rawX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    moved = moved || kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = startY + dy
                    windowManager.updateViewLayout(view, params); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); true }
                else -> false
            }
        }
        windowManager.addView(view, params)
        chip = view
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
    }

    override fun onDestroy() {
        pollJob?.cancel()
        removeWindows()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        setStroke(1.dp, 0x55FFFFFF)
    }

    private fun createForegroundChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    HermesNotificationCoordinator.OVERLAY_CHANNEL,
                    "Active session overlay",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun foregroundNotification() = NotificationCompat.Builder(this, HermesNotificationCoordinator.OVERLAY_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle("Hermes sessions active")
        .setContentText("Tap to open Hermes Mobile")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val OVERLAY_NOTIFICATION_ID = 9042

        fun onPush(context: Context, event: MobilePushEvent) {
            if (!Settings.canDrawOverlays(context)) return
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
            if (event.activeCount == 0 && event.isTerminal) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HermesOverlayService::class.java)) }
        }

        fun startIfAllowed(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HermesOverlayService::class.java)) }
        }
    }
}

private data class OverlaySession(val host: HostProfile, val session: HermesActiveSession)
