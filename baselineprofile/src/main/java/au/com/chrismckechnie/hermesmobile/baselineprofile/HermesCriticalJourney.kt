package au.com.chrismckechnie.hermesmobile.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "au.com.chrismckechnie.hermesmobile"
private const val UI_TIMEOUT_MILLIS = 15_000L

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun sendMessage() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
    ) {
        pressHome()
        startActivityAndWait()
        configureMockHostIfNeeded()
        sendBenchmarkMessage()
    }
}

@LargeTest
@RunWith(AndroidJUnit4::class)
class SendMessageBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun sendAndRenderFirstProgress() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            configureMockHostIfNeeded()
        },
    ) {
        sendBenchmarkMessage()
    }
}

private fun MacrobenchmarkScope.configureMockHostIfNeeded() {
    if (!device.wait(Until.hasObject(By.text("Connect Hermes")), 2_000L)) return
    val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), UI_TIMEOUT_MILLIS)
    check(fields.size >= 3) { "Hermes host form did not expose its three text fields" }
    fields[0].text = "Benchmark host"
    fields[1].text = "http://10.0.2.2:8766"
    fields[2].text = "test-key"
    val httpLabel = device.wait(Until.findObject(By.text("Allow private-network HTTP")), UI_TIMEOUT_MILLIS)
    device.click(device.displayWidth - 56, httpLabel.visibleCenter.y)
    device.wait(Until.findObject(By.text("Save and connect")), UI_TIMEOUT_MILLIS).click()
    check(device.wait(Until.hasObject(By.text("Ready when you are")), UI_TIMEOUT_MILLIS)) {
        "Hermes mock host did not connect"
    }
}

private fun MacrobenchmarkScope.sendBenchmarkMessage() {
    device.findObject(By.desc("New session"))?.click()
    device.waitForIdle()
    val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), UI_TIMEOUT_MILLIS)
    check(fields.isNotEmpty()) { "Hermes composer was not visible" }
    fields.last().text = "Benchmark the Android send path"
    device.wait(Until.findObject(By.desc("Send")), UI_TIMEOUT_MILLIS).click()
    check(device.wait(Until.hasObject(By.textContains("Connected to Hermes")), UI_TIMEOUT_MILLIS)) {
        "Hermes did not render the benchmark response"
    }
}
