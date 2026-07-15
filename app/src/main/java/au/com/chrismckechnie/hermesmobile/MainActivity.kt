package au.com.chrismckechnie.hermesmobile

import android.graphics.Color
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: HermesViewModel by viewModels { HermesViewModel.Factory }
    private var overlayPromptLaunched = false
    private var notificationPromptLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.notificationHostIds, state.overlayEnabled, state.activeRuns.keys) {
                configureMobileBackground(state)
            }
            LaunchedEffect(state.hosts.map(HostProfile::id), state.notificationHostIds, state.overlayEnabled) {
                MobileRegistration.enqueue(applicationContext, state.notificationHostIds)
            }
            HermesMobileApp(state = state, viewModel = viewModel)
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
        configureMobileBackground(viewModel.state.value)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(HermesNotificationCoordinator.EXTRA_SCREEN) == "jobs") {
            viewModel.selectScreen(DeckScreen.Jobs)
            return
        }
        val hostId = intent?.getStringExtra(HermesNotificationCoordinator.EXTRA_HOST_ID) ?: return
        val sessionId = intent.getStringExtra(HermesNotificationCoordinator.EXTRA_SESSION_ID) ?: return
        viewModel.openSessionFromNotification(hostId, sessionId)
    }

    private fun configureMobileBackground(state: HermesUiState) {
        if (state.notificationHostIds.isNotEmpty() && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPromptLaunched
        ) {
            notificationPromptLaunched = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4201)
        }
        if (state.overlayEnabled && !Settings.canDrawOverlays(this) && !overlayPromptLaunched) {
            overlayPromptLaunched = true
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        val monitoredRuns = state.activeRuns.values.filter { it.host.id in state.notificationHostIds }
        when {
            monitoredRuns.isNotEmpty() -> HermesOverlayService.startForRuns(applicationContext, monitoredRuns)
            state.overlayEnabled -> HermesOverlayService.startIfAllowed(applicationContext)
            else -> HermesOverlayService.stop(applicationContext)
        }
    }
}
