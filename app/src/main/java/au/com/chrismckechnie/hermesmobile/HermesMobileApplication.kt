package au.com.chrismckechnie.hermesmobile

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Keeps the draw-over-apps surface out of Hermes Mobile itself. */
class HermesMobileApplication : Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        super<Application>.onCreate()
        AppDiagnosticsRegistry.initialize(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        AppDiagnosticsRegistry.recorder.markProcessActive()
        HermesOverlayService.setAppForeground(this, true)
    }

    override fun onStop(owner: LifecycleOwner) {
        AppDiagnosticsRegistry.recorder.markProcessInactive()
        HermesOverlayService.setAppForeground(this, false)
    }
}
