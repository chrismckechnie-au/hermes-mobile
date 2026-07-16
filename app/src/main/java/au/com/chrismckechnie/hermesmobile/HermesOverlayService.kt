package au.com.chrismckechnie.hermesmobile

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
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
    private var chipParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var dismissTarget: TextView? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var dismissArmed = false
    private var pollJob: Job? = null
    private var sessions = emptyList<OverlaySession>()
    private var localRunHints = emptyMap<String, LocalRunHint>()
    private var appInForeground = false
    private val positionPreferences by lazy { getSharedPreferences("hermes_overlay_position", MODE_PRIVATE) }
    private val visibilityPreferences by lazy { getSharedPreferences(VISIBILITY_PREFERENCES, MODE_PRIVATE) }
    private val diagnostics: AppDiagnostics get() = AppDiagnosticsRegistry.recorder
    private var foregroundReady = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        appInForeground = visibilityPreferences.getBoolean(APP_FOREGROUND_KEY, false)
        createForegroundChannel()
        foregroundReady = promoteToForeground(emptyList())
        serviceRunning = foregroundReady
        if (!foregroundReady) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        if (intent?.hasExtra(EXTRA_APP_FOREGROUND) == true) {
            appInForeground = intent.getBooleanExtra(EXTRA_APP_FOREGROUND, false)
            visibilityPreferences.edit().putBoolean(APP_FOREGROUND_KEY, appInForeground).apply()
        }
        intent?.localRunHint()?.let { hint -> localRunHints = localRunHints + (hint.runId to hint) }
        if (pollJob == null) {
            pollJob = scope.launch {
                var consecutiveFailures = 0
                while (true) {
                    if (appInForeground) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    when (refreshSessionsSafely()) {
                        RefreshOutcome.Success -> {
                            consecutiveFailures = 0
                            delay(POLL_INTERVAL_MS)
                        }
                        RefreshOutcome.Failed -> {
                            consecutiveFailures += 1
                            delay(overlayPollRetryDelayMillis(consecutiveFailures))
                        }
                        RefreshOutcome.Stopped -> break
                    }
                }
            }
        } else {
            scope.launch { refreshSessionsSafely() }
        }
        return START_STICKY
    }

    private suspend fun refreshSessionsSafely(): RefreshOutcome = try {
        refreshSessions()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        diagnostics.recordFailure(DiagnosticPhase.OverlayRefresh, error)
        RefreshOutcome.Failed
    }

    private suspend fun refreshSessions(): RefreshOutcome {
        diagnostics.recordPhase(DiagnosticPhase.OverlayRefresh)
        val refresh = withContext(Dispatchers.IO) {
            val settings = PreferencesSettingsStore(applicationContext)
            val enabled = settings.loadMonitoredHostIds()
            val hosts = SecureHostStore(applicationContext).load().snapshot.hosts.filter { it.id in enabled }
            val attention = settings.loadAttentionItems().filter {
                !it.read && it.hostId in hosts.map(HostProfile::id)
            }
            val gateway = HermesHttpGateway()
            val hints = (localRunHints.values + settings.loadRunCheckpoints().map { checkpoint ->
                LocalRunHint(checkpoint.hostId, checkpoint.sessionId, checkpoint.runId, "Hermes task")
            }).distinctBy(LocalRunHint::runId)
            var hadFailures = false
            val remote = hosts.flatMap { host ->
                val fetched = overlayFetchOrPrevious(
                    fetch = runCatching {
                        gateway.listActiveSessions(host)
                            .filter { session -> isOverlayActiveSession(session.state) }
                            .map { session ->
                                val hintedTitle = hints.firstOrNull {
                                    it.hostId == host.id && it.sessionId == session.sessionId
                                }?.title
                                OverlaySession(host, session.copy(title = overlaySessionTitle(session.title, hintedTitle)))
                            }
                    },
                    previous = sessions.filter { it.host.id == host.id },
                )
                if (fetched.failed) {
                    hadFailures = true
                }
                fetched.items
            }
            val staleRunIds = mutableSetOf<String>()
            val local = hints.mapNotNull { value ->
                val host = hosts.firstOrNull { it.id == value.hostId } ?: return@mapNotNull null
                val alreadyReported = remote.any {
                    it.host.id == value.hostId && it.session.sessionId == value.sessionId
                }
                if (alreadyReported) return@mapNotNull null
                val statusResult = runCatching { gateway.getRunStatus(host, value.runId) }
                val statusError = statusResult.exceptionOrNull() as? HermesApiException
                if (statusError?.statusCode in setOf(404, 410)) {
                    staleRunIds += value.runId
                    return@mapNotNull null
                }
                val status = statusResult.getOrNull()
                if (status?.isTerminal == true) {
                    staleRunIds += value.runId
                    null
                } else OverlaySession(
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
            if (staleRunIds.isNotEmpty()) {
                staleRunIds.forEach {
                    localRunHints = localRunHints - it
                    settings.clearRunStatus(it)
                }
                settings.updateRunCheckpoints { checkpoints ->
                    checkpoints.filterNot { it.runId in staleRunIds }
                }
            }
            val active = (remote + local)
                .distinctBy { it.host.id to it.session.sessionId }
                .map { item ->
                    item.copy(
                        latestStatus = overlayLatestUpdate(
                            localStatus = item.session.runId?.let(settings::loadRunStatus),
                            remoteStatus = item.session.latestStatus,
                            updatedAtSeconds = item.session.updatedAt,
                        ),
                        attention = attention.firstOrNull {
                            it.hostId == item.host.id && it.sessionId == item.session.sessionId
                        },
                    )
                }
            val outcomes = attention.mapNotNull { item ->
                if (active.any { it.host.id == item.hostId && it.session.sessionId == item.sessionId }) return@mapNotNull null
                val host = hosts.firstOrNull { it.id == item.hostId } ?: return@mapNotNull null
                OverlaySession(
                    host = host,
                    session = HermesActiveSession(
                        sessionId = item.sessionId,
                        runId = item.runId,
                        title = item.title,
                        state = item.state,
                        surface = "notification",
                    ),
                    latestStatus = item.latestStatus ?: attentionLabel(item.state),
                    attention = item,
                )
            }
            OverlayRefreshSnapshot(
                (active + outcomes).distinctBy { it.host.id to it.session.sessionId },
                hadFailures,
                hosts.isNotEmpty(),
            )
        }

        val sessionsChanged = sessions != refresh.sessions
        sessions = refresh.sessions
        if (sessions.isEmpty()) {
            if (refresh.hadFailures && refresh.hasMonitoredHosts) return RefreshOutcome.Failed
            removeWindows()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            foregroundReady = false
            return RefreshOutcome.Stopped
        }

        // Re-promoting updates the required foreground notification and still
        // permits the overlay service when Android notification permission is denied.
        if (!promoteToForeground(sessions)) return RefreshOutcome.Stopped
        val settings = PreferencesSettingsStore(applicationContext)
        val sessionSignature = activeSessionSignature()
        val dismissedForCurrentSessions = positionPreferences.getString("dismissed_sessions", null) == sessionSignature
        if (!appInForeground && settings.loadOverlayEnabled() && Settings.canDrawOverlays(this) && !dismissedForCurrentSessions) {
            if (positionPreferences.contains("dismissed_sessions")) {
                positionPreferences.edit().remove("dismissed_sessions").apply()
            }
            showOrUpdateChip()
            if (panel != null && sessionsChanged) showPanel() else updatePanelPosition()
        } else {
            removeWindows()
        }
        return if (refresh.hadFailures) RefreshOutcome.Failed else RefreshOutcome.Success
    }

    private fun promoteToForeground(active: List<OverlaySession>): Boolean = try {
        diagnostics.recordPhase(DiagnosticPhase.OverlayPromote)
        ServiceCompat.startForeground(
            this,
            HermesNotificationCoordinator.OVERLAY_SERVICE_NOTIFICATION_ID,
            foregroundNotification(active),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        true
    } catch (error: RuntimeException) {
        diagnostics.recordFailure(DiagnosticPhase.OverlayPromote, error)
        foregroundReady = false
        serviceRunning = false
        stopSelf()
        false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrUpdateChip() {
        chip?.let {
            updateChipBadge()
            return
        }

        val view = object : FrameLayout(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            clipToOutline = true
            elevation = 8.dp.toFloat()
            background = roundedBackground(Color.WHITE, 13.dp.toFloat())
            contentDescription = activeSessionDescription(sessions.size)
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.hermes_official)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val badge = overlayText("", 9f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                minWidth = 16.dp
                minHeight = 16.dp
                setPadding(3.dp, 0, 3.dp, 0)
                background = roundedBackground(0xFFC94747.toInt(), 8.dp.toFloat())
                elevation = 10.dp.toFloat()
            }
            addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 16.dp, Gravity.TOP or Gravity.END).apply {
                topMargin = (-2).dp
                marginEnd = (-2).dp
            })
            chipBadge = badge
        }
        val params = initialChipParams()
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
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    moved = moved || kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8
                    if (moved && dismissTarget == null) showDismissTarget()
                    params.x = (startX + dx).coerceIn(OVERLAY_MARGIN_DP.dp, screenWidth - CHIP_SIZE_DP.dp - OVERLAY_MARGIN_DP.dp)
                    params.y = (startY + dy).coerceIn(OVERLAY_MARGIN_DP.dp, screenHeight - CHIP_SIZE_DP.dp - OVERLAY_MARGIN_DP.dp)
                    windowOperation {
                        windowManager.updateViewLayout(view, params)
                        updateDismissTarget(params)
                        updatePanelPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dismiss = dismissArmed
                    hideDismissTarget()
                    if (!moved) {
                        view.performClick()
                    } else if (dismiss) {
                        dismissOverlayForCurrentSessions()
                    } else {
                        settleChipPosition(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideDismissTarget()
                    if (moved) settleChipPosition(view, params)
                    true
                }
                else -> false
            }
        }
        if (!windowOperation { windowManager.addView(view, params) }) return
        chip = view
        chipParams = params
        updateChipBadge()
    }

    private fun updateChipBadge() {
        val attention = sessions.mapNotNull(OverlaySession::attention)
        val attentionCount = attention.size
        chip?.contentDescription = activeSessionDescription(sessions.size, attentionCount)
        chipBadge?.apply {
            visibility = if (attentionCount == 0) View.GONE else View.VISIBLE
            val presentation = overlayAttentionBadge(attention)
            text = presentation.text
            background = roundedBackground(
                when (presentation.kind) {
                    OverlayBadgeKind.Approval -> 0xFFE39A3B.toInt()
                    OverlayBadgeKind.Error -> 0xFFC94747.toInt()
                    OverlayBadgeKind.Done -> 0xFF3A9B63.toInt()
                },
                8.dp.toFloat(),
            )
        }
    }

    private fun settleChipPosition(view: View, params: WindowManager.LayoutParams) {
        params.x = snapOverlayX(
            currentX = params.x,
            chipWidth = params.width,
            screenWidth = screenWidth,
            margin = OVERLAY_MARGIN_DP.dp,
        )
        params.y = params.y.coerceIn(OVERLAY_MARGIN_DP.dp, screenHeight - params.height - OVERLAY_MARGIN_DP.dp)
        windowOperation {
            windowManager.updateViewLayout(view, params)
            persistChipPosition(params)
            updatePanelPosition()
        }
    }

    private fun showPanel() {
        if (!windowOperation(::showPanelUnsafe)) hidePanel()
    }

    private fun showPanelUnsafe() {
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
                val stateLabel = when {
                    item.attention != null -> attentionLabel(item.attention.state)
                    else -> when (item.session.state.lowercase()) {
                    "waiting_for_approval", "approval_required" -> "Needs approval"
                    "unresponsive", "stalled" -> "No recent activity"
                    "queued" -> "Queued"
                    "stopping" -> "Stopping"
                    else -> "Working"
                    }
                }
                val stateColor = when (stateLabel) {
                    "Needs approval" -> 0xFFFFC66D.toInt()
                    "Failed" -> 0xFFC94747.toInt()
                    else -> 0xFF9FD3B4.toInt()
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(13.dp, 11.dp, 13.dp, 11.dp)
                    background = roundedBackground(0xFF262626.toInt(), 16.dp.toFloat())
                    addView(overlayText(item.session.title, 13f, Color.WHITE, bold = true).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    (item.latestStatus ?: item.attention?.state)?.takeIf { it.isNotBlank() }?.let { status ->
                        addView(overlayText(status, 12f, 0xFFD1D1D1.toInt()).apply {
                            maxLines = 3
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, 7.dp, 0, 0)
                        })
                    }
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
                setOnClickListener { dismissOverlayForCurrentSessions() }
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        val maxHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val sessionContentHeight = sessions.fold(0) { height, item ->
            height + if ((item.latestStatus ?: item.attention?.state).isNullOrBlank()) 76 else 112
        }
        val panelHeight = (170 + sessionContentHeight).dp.coerceAtMost(maxHeight)
        val panelWidth = 320.dp.coerceAtMost(screenWidth - OVERLAY_MARGIN_DP.dp * 2)
        val params = overlayParams(panelWidth, panelHeight).also(::positionPanelParams)
        windowManager.addView(scroll, params)
        panel = scroll
        panelParams = params
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
    }

    private fun activeSessionSignature(): String = sessions
        .map { item -> "${item.host.id}:${item.session.runId ?: item.session.sessionId}" }
        .sorted()
        .joinToString("|")

    private fun dismissOverlayForCurrentSessions() {
        positionPreferences.edit()
            .putString("dismissed_sessions", activeSessionSignature())
            .apply()
        removeWindows()
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val size = DISMISS_TARGET_SIZE_DP.dp
        val view = overlayText("×", 28f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Drop here to hide overlay until the next run"
            background = roundedBackground(0xE6222222.toInt(), size / 2f)
            elevation = 10.dp.toFloat()
        }
        val params = overlayParams(size, size).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            x = (screenWidth - size) / 2
            y = screenHeight - size - DISMISS_TARGET_BOTTOM_MARGIN_DP.dp
        }
        if (!windowOperation { windowManager.addView(view, params) }) return
        dismissTarget = view
        dismissParams = params
        dismissArmed = false
    }

    private fun updateDismissTarget(chip: WindowManager.LayoutParams) {
        val target = dismissTarget ?: return
        val targetParams = dismissParams ?: return
        val armed = isOverlayDismissDrop(
            chipX = chip.x,
            chipY = chip.y,
            chipSize = chip.width,
            targetX = targetParams.x,
            targetY = targetParams.y,
            targetSize = targetParams.width,
        )
        if (armed == dismissArmed) return
        dismissArmed = armed
        target.background = roundedBackground(
            if (armed) 0xF5C94747.toInt() else 0xE6222222.toInt(),
            targetParams.width / 2f,
        )
        target.scaleX = if (armed) 1.12f else 1f
        target.scaleY = if (armed) 1.12f else 1f
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { runCatching { windowManager.removeView(it) } }
        dismissTarget = null
        dismissParams = null
        dismissArmed = false
    }

    private fun removeWindows() {
        hideDismissTarget()
        hidePanel()
        chip?.let { runCatching { windowManager.removeView(it) } }
        chip = null
        chipBadge = null
        chipParams = null
    }

    override fun onDestroy() {
        pollJob?.cancel()
        removeWindows()
        scope.cancel()
        serviceRunning = false
        foregroundReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initialChipParams(): WindowManager.LayoutParams {
        val margin = OVERLAY_MARGIN_DP.dp
        val size = CHIP_SIZE_DP.dp
        val availableY = (screenHeight - size - margin * 2).coerceAtLeast(0)
        val savedY = positionPreferences.getFloat("y_fraction", 0.5f).coerceIn(0f, 1f)
        val right = positionPreferences.getBoolean("right_side", true)
        return overlayParams(size, size).apply {
            x = if (right) screenWidth - size - margin else margin
            y = margin + (availableY * savedY).toInt()
        }
    }

    private fun persistChipPosition(params: WindowManager.LayoutParams) {
        val margin = OVERLAY_MARGIN_DP.dp
        val availableY = (screenHeight - params.height - margin * 2).coerceAtLeast(1)
        positionPreferences.edit()
            .putBoolean("right_side", params.x + params.width / 2 >= screenWidth / 2)
            .putFloat("y_fraction", ((params.y - margin).toFloat() / availableY).coerceIn(0f, 1f))
            .apply()
    }

    private fun positionPanelParams(params: WindowManager.LayoutParams) {
        val anchor = chipParams ?: return
        val point = anchoredPanelPosition(
            chipX = anchor.x,
            chipY = anchor.y,
            chipSize = anchor.width,
            panelWidth = params.width,
            panelHeight = params.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            margin = OVERLAY_MARGIN_DP.dp,
            gap = PANEL_GAP_DP.dp,
        )
        params.x = point.x
        params.y = point.y
    }

    private fun updatePanelPosition() {
        val view = panel ?: return
        val params = panelParams ?: return
        windowOperation {
            positionPanelParams(params)
            windowManager.updateViewLayout(view, params)
        }
    }

    private inline fun windowOperation(operation: () -> Unit): Boolean = try {
        operation()
        true
    } catch (error: RuntimeException) {
        diagnostics.recordFailure(DiagnosticPhase.OverlayRefresh, error)
        false
    }

    private fun foregroundNotification(active: List<OverlaySession>): android.app.Notification {
        val first = active.firstOrNull()
        val openIntent = first?.let {
            HermesNotificationCoordinator.sessionIntent(this, it.host.id, it.session.sessionId)
        } ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, HermesNotificationCoordinator.OVERLAY_SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_hermes_notification)
            .setContentTitle("Hermes overlay active")
            .setContentText("Tap to open Hermes Mobile")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(0xFF1B1B1B.toInt())
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()
    }

    private fun createForegroundChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                HermesNotificationCoordinator.OVERLAY_SERVICE_CHANNEL,
                "Hermes active work",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Android-required status while the optional floating overlay is active"
                setShowBadge(false)
            }
        )
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

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

    private fun activeSessionDescription(count: Int, attentionCount: Int = 0): String = buildString {
        append("$count Hermes ${if (count == 1) "session" else "sessions"}")
        if (attentionCount > 0) append(", $attentionCount update${if (attentionCount == 1) "" else "s"} to review")
    }
    private val screenWidth: Int get() = resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = resources.displayMetrics.heightPixels
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHIP_SIZE_DP = 44
        private const val OVERLAY_MARGIN_DP = 12
        private const val PANEL_GAP_DP = 8
        private const val DISMISS_TARGET_SIZE_DP = 60
        private const val DISMISS_TARGET_BOTTOM_MARGIN_DP = 28
        private const val POLL_INTERVAL_MS = 5_000L
        private const val EXTRA_RUN_ID = "active_run_id"
        private const val EXTRA_RUN_TITLE = "active_run_title"
        private const val EXTRA_APP_FOREGROUND = "app_foreground"
        private const val VISIBILITY_PREFERENCES = "hermes_overlay_visibility"
        private const val APP_FOREGROUND_KEY = "app_foreground"
        @Volatile private var serviceRunning = false

        fun startForRun(context: Context, run: ActiveRun) {
            val intent = Intent(context, HermesOverlayService::class.java).apply {
                putExtra(HermesNotificationCoordinator.EXTRA_HOST_ID, run.host.id)
                putExtra(HermesNotificationCoordinator.EXTRA_SESSION_ID, run.sessionId)
                putExtra(EXTRA_RUN_ID, run.runId)
                putExtra(EXTRA_RUN_TITLE, run.sessionTitle ?: "Hermes task")
            }
            startForegroundServiceSafely(context, intent)
        }

        fun startForRuns(context: Context, runs: Collection<ActiveRun>) {
            runs.forEach { run -> startForRun(context, run) }
        }

        fun setAppForeground(context: Context, foreground: Boolean) {
            context.getSharedPreferences(VISIBILITY_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(APP_FOREGROUND_KEY, foreground)
                .apply()
            if (serviceRunning) try {
                context.startService(Intent(context, HermesOverlayService::class.java).apply {
                    putExtra(EXTRA_APP_FOREGROUND, foreground)
                })
            } catch (error: RuntimeException) {
                AppDiagnosticsRegistry.recorder.recordFailure(DiagnosticPhase.OverlayPromote, error)
            }
        }

        fun onPush(context: Context, event: MobilePushEvent) {
            if (event.activeCount == 0 && event.isTerminal && !event.requiresAttention) return
            startForegroundServiceSafely(context, Intent(context, HermesOverlayService::class.java))
        }

        fun startIfAllowed(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            startForegroundServiceSafely(context, Intent(context, HermesOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesOverlayService::class.java))
        }

        private fun startForegroundServiceSafely(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                AppDiagnosticsRegistry.recorder.recordFailure(DiagnosticPhase.OverlayPromote, error)
            }
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

private enum class RefreshOutcome { Success, Failed, Stopped }

private data class OverlayRefreshSnapshot(
    val sessions: List<OverlaySession>,
    val hadFailures: Boolean,
    val hasMonitoredHosts: Boolean,
)

private data class OverlaySession(
    val host: HostProfile,
    val session: HermesActiveSession,
    val latestStatus: String? = null,
    val attention: AttentionItem? = null,
)
private data class LocalRunHint(val hostId: String, val sessionId: String, val runId: String, val title: String)

internal data class OverlayPoint(val x: Int, val y: Int)

internal data class OverlayFetchResult<T>(val items: List<T>, val failed: Boolean)

internal enum class OverlayBadgeKind { Approval, Error, Done }

internal data class OverlayBadgePresentation(val text: String, val kind: OverlayBadgeKind)

internal fun overlayAttentionBadge(attention: List<ActivityEntry>): OverlayBadgePresentation {
    val latest = attention.maxByOrNull(ActivityEntry::updatedAtMillis)
    val approval = latest?.event == "approval.required" ||
        latest?.state?.lowercase() in setOf("waiting_for_approval", "approval_required")
    val failed = latest?.event in setOf("session.failed", "job.failed") ||
        latest?.state?.lowercase() in setOf("failed", "error")
    return OverlayBadgePresentation(
        text = if (attention.size > 1) attention.size.coerceAtMost(9).toString() else if (approval || failed) "!" else "✓",
        kind = when {
            approval -> OverlayBadgeKind.Approval
            failed -> OverlayBadgeKind.Error
            else -> OverlayBadgeKind.Done
        },
    )
}

internal fun <T> overlayFetchOrPrevious(fetch: Result<List<T>>, previous: List<T>): OverlayFetchResult<T> =
    fetch.fold(
        onSuccess = { OverlayFetchResult(it, failed = false) },
        onFailure = { OverlayFetchResult(previous, failed = true) },
    )

internal fun overlayPollRetryDelayMillis(consecutiveFailures: Int): Long {
    val shift = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(3)
    return (5_000L shl shift).coerceAtMost(30_000L)
}

internal fun snapOverlayX(currentX: Int, chipWidth: Int, screenWidth: Int, margin: Int): Int =
    if (currentX + chipWidth / 2 < screenWidth / 2) margin else screenWidth - chipWidth - margin

internal fun isOverlayDismissDrop(
    chipX: Int,
    chipY: Int,
    chipSize: Int,
    targetX: Int,
    targetY: Int,
    targetSize: Int,
): Boolean {
    val deltaX = chipX + chipSize / 2 - (targetX + targetSize / 2)
    val deltaY = chipY + chipSize / 2 - (targetY + targetSize / 2)
    val activationRadius = (targetSize * 0.62f).toInt()
    return deltaX.toLong() * deltaX + deltaY.toLong() * deltaY <=
        activationRadius.toLong() * activationRadius
}

internal fun anchoredPanelPosition(
    chipX: Int,
    chipY: Int,
    chipSize: Int,
    panelWidth: Int,
    panelHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    margin: Int,
    gap: Int,
): OverlayPoint {
    val maxX = (screenWidth - panelWidth - margin).coerceAtLeast(margin)
    val x = (chipX + chipSize / 2 - panelWidth / 2).coerceIn(margin, maxX)
    val below = chipY + chipSize + gap
    val above = chipY - panelHeight - gap
    val maxY = (screenHeight - panelHeight - margin).coerceAtLeast(margin)
    val y = when {
        below <= maxY -> below
        above >= margin -> above
        chipY < screenHeight / 2 -> maxY
        else -> margin
    }.coerceIn(margin, maxY)
    return OverlayPoint(x, y)
}

internal fun overlayLatestUpdate(
    localStatus: String?,
    remoteStatus: String?,
    updatedAtSeconds: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    val status = (localStatus ?: remoteStatus)
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(180)
        ?.takeIf(String::isNotEmpty)
    val age = updatedAtSeconds?.let { updatedAt ->
        val elapsedSeconds = ((nowMillis / 1_000L) - updatedAt).coerceAtLeast(0L)
        when {
            elapsedSeconds < 60L -> "just now"
            elapsedSeconds < 3_600L -> "${elapsedSeconds / 60L}m ago"
            elapsedSeconds < 86_400L -> "${elapsedSeconds / 3_600L}h ago"
            else -> "${elapsedSeconds / 86_400L}d ago"
        }
    }
    return when {
        status != null && age != null -> "$status · $age"
        status != null -> status
        age != null -> "Last update $age"
        else -> null
    }
}

internal fun overlaySessionTitle(remoteTitle: String?, localTitle: String?): String {
    val remote = remoteTitle?.trim().orEmpty()
    val local = localTitle?.trim().orEmpty()
    val remoteIsGeneric = remote.isBlank() || remote.lowercase() in setOf("hermes session", "hermes task")
    return when {
        remoteIsGeneric && local.isNotBlank() && local.lowercase() !in setOf("hermes session", "hermes task") -> local
        remote.isNotBlank() -> remote
        local.isNotBlank() -> local
        else -> "Hermes session"
    }
}

internal fun isOverlayActiveSession(state: String?): Boolean =
    state?.trim()?.lowercase() !in setOf(
        "completed",
        "failed",
        "cancelled",
        "stopped",
        "terminal",
        "unresponsive",
        "stalled",
    )

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

internal fun attentionLabel(state: String): String = when (state.lowercase()) {
    "waiting_for_approval", "approval_required" -> "Needs approval"
    "failed", "error" -> "Failed"
    "cancelled" -> "Stopped"
    "completed", "complete" -> "Finished"
    else -> "New update"
}
