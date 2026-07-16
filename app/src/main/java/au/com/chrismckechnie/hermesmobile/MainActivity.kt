package au.com.chrismckechnie.hermesmobile

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val safeStartup by lazy {
        AppDiagnosticsRegistry.recorder.consumeSafeStartup()
    }
    private val viewModel: HermesViewModel by viewModels { HermesViewModel.factory(safeStartup) }
    private var permissionHealth by mutableStateOf(
        PermissionHealth(
            notifications = notificationPermissionStatus(
                sdkInt = Build.VERSION.SDK_INT,
                permissionGranted = false,
                notificationsEnabled = true,
            ),
            overlay = PermissionStatus.Denied,
            canRequestNotificationPermission = Build.VERSION.SDK_INT >= 33,
        )
    )
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionHealth()
        configureMobileBackground(viewModel.state.value)
    }
    private val permissionSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissionHealth()
        configureMobileBackground(viewModel.state.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(if (safeStartup) null else savedInstanceState)
        // Transparent bars; icon shade follows the system dark-mode setting
        // (matches the palette HermesMobileApp picks). No platform contrast
        // scrim over the three-button nav bar (API 29+).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        refreshPermissionHealth()
        setContent {
            var recoveryAccepted by remember { mutableStateOf(!safeStartup) }
            if (!recoveryAccepted) {
                LaunchedEffect(Unit) {
                    AppDiagnosticsRegistry.recorder.recordPhase(DiagnosticPhase.AppReady)
                }
                MaterialTheme {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Hermes Mobile recovery")
                        Text(
                            "The previous launch did not complete. Background state and overlays are paused.",
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                        Button(onClick = {
                            applicationContext.clearCrashProneRuntimeState()
                            AppDiagnosticsRegistry.recorder.recordPhase(DiagnosticPhase.AppStart)
                            recoveryAccepted = true
                        }) {
                            Text("Reset runtime and open")
                        }
                    }
                }
                return@setContent
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                AppDiagnosticsRegistry.recorder.recordPhase(DiagnosticPhase.AppReady)
            }
            LaunchedEffect(state.monitoredHostIds, state.overlayEnabled, state.activeRuns.keys) {
                configureMobileBackground(state)
            }
            LaunchedEffect(state.notificationHostIds, permissionHealth.notifications) {
                maybeAutomaticallyRequestNotificationPermission(
                    hasNotificationSubscriptions = state.notificationHostIds.isNotEmpty()
                )
            }
            LaunchedEffect(state.hosts.map(HostProfile::id), state.notificationHostIds, state.overlayEnabled) {
                MobileRegistration.enqueue(applicationContext, state.notificationHostIds)
            }
            HermesMobileApp(
                state = state,
                viewModel = viewModel,
                permissionHealth = permissionHealth,
                onRequestNotificationPermission = ::requestNotificationPermission,
                onOpenNotificationSettings = ::openNotificationSettings,
                onOpenOverlaySettings = ::openOverlaySettings,
            )
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionHealth()
        if (safeStartup) {
            HermesOverlayService.stop(applicationContext)
        } else {
            viewModel.refreshActivityHistory()
            HermesNotificationCoordinator(applicationContext).refreshSummary()
            configureMobileBackground(viewModel.state.value)
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.dataString?.takeIf { it.startsWith("hermes://pair?") }?.let { pairingUri ->
            viewModel.offerPairing(pairingUri)
            intent.data = null
            return
        }
        if (intent?.getStringExtra(HermesNotificationCoordinator.EXTRA_SCREEN) == "jobs") {
            viewModel.selectScreen(DeckScreen.Jobs)
            return
        }
        val hostId = intent?.getStringExtra(HermesNotificationCoordinator.EXTRA_HOST_ID) ?: return
        val sessionId = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_SESSION_ID) ?: return
        viewModel.openSessionFromNotification(hostId, sessionId)
    }

    private fun configureMobileBackground(state: HermesUiState) {
        if (!shouldStartOverlayService(state.overlayEnabled, Settings.canDrawOverlays(this))) {
            HermesOverlayService.stop(applicationContext)
            return
        }
        val monitoredRuns = state.activeRuns.values.filter { it.host.id in state.monitoredHostIds }
        if (monitoredRuns.isNotEmpty()) HermesOverlayService.startForRuns(applicationContext, monitoredRuns)
        else HermesOverlayService.startIfAllowed(applicationContext)
    }

    private fun refreshPermissionHealth() {
        val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        permissionHealth = PermissionHealth(
            notifications = notificationPermissionStatus(
                sdkInt = Build.VERSION.SDK_INT,
                permissionGranted = notificationPermissionGranted,
                notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            ),
            overlay = overlayPermissionStatus(Settings.canDrawOverlays(this)),
            canRequestNotificationPermission =
                Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted,
        )
    }

    private fun maybeAutomaticallyRequestNotificationPermission(hasNotificationSubscriptions: Boolean) {
        val preferences = getSharedPreferences(PERMISSION_HEALTH_PREFERENCES, MODE_PRIVATE)
        val shouldRequest = shouldAutomaticallyRequestNotificationPermission(
            hasNotificationSubscriptions = hasNotificationSubscriptions,
            notificationStatus = permissionHealth.notifications,
            canRequestPermission = permissionHealth.canRequestNotificationPermission,
            hasRequestedAutomatically = preferences.getBoolean(
                KEY_NOTIFICATION_AUTO_REQUESTED,
                false,
            ),
        )
        if (!shouldRequest) return

        preferences.edit().putBoolean(KEY_NOTIFICATION_AUTO_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestNotificationPermission() {
        if (permissionHealth.notifications != PermissionStatus.Denied) {
            refreshPermissionHealth()
            return
        }
        if (!permissionHealth.canRequestNotificationPermission) {
            openNotificationSettings()
            return
        }
        markNotificationPermissionHandled()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        markNotificationPermissionHandled()
        launchPermissionSettings(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        )
    }

    private fun openOverlaySettings() {
        launchPermissionSettings(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun launchPermissionSettings(intent: Intent) {
        val resolvableIntent = if (intent.resolveActivity(packageManager) != null) {
            intent
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            )
        }
        permissionSettingsLauncher.launch(resolvableIntent)
    }

    private fun markNotificationPermissionHandled() {
        getSharedPreferences(PERMISSION_HEALTH_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_AUTO_REQUESTED, true)
            .apply()
    }

    private companion object {
        const val PERMISSION_HEALTH_PREFERENCES = "permission_health"
        const val KEY_NOTIFICATION_AUTO_REQUESTED = "notification_auto_requested"
    }
}

internal fun shouldStartOverlayService(overlayEnabled: Boolean, canDrawOverlays: Boolean): Boolean =
    overlayEnabled && canDrawOverlays

internal fun Context.clearCrashProneRuntimeState() {
    HermesOverlayService.stop(this)
    listOf(
        "hermes_mobile_settings",
        "hermes_overlay_position",
        "hermes_overlay_visibility",
    ).forEach { name ->
        getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
