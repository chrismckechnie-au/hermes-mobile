package au.com.chrismckechnie.hermesmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashRecoveryTest {
    @Test
    fun recoveryClearsRuntimeStateWithoutClearingHosts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val durableStores = listOf("hermes_mobile_secure_hosts")
        val runtimeStores = listOf(
            "hermes_mobile_settings",
            "hermes_overlay_position",
            "hermes_overlay_visibility",
        )

        (durableStores + runtimeStores).forEach { name ->
            context.getSharedPreferences(name, 0).edit().putString("sentinel", name).commit()
        }

        context.clearCrashProneRuntimeState()

        durableStores.forEach { name ->
            assertEquals(name, context.getSharedPreferences(name, 0).getString("sentinel", null))
        }
        runtimeStores.forEach { name ->
            assertFalse(context.getSharedPreferences(name, 0).contains("sentinel"))
        }
    }
}
