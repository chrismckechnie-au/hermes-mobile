package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CrashDiagnosticsTest {
    @Test
    fun `safe startup is used after any fatal exit`() {
        val crash = ProcessExitDiagnostic(1L, "crash", 0, 0, 0, 0, "app_start")

        assertTrue(shouldUseSafeStartup(crash))
        assertTrue(shouldUseSafeStartup(crash.copy(lastPhase = "app_ready")))
        assertFalse(shouldUseSafeStartup(crash.copy(reason = "user_requested")))
        assertFalse(shouldUseSafeStartup(null))
    }

    @Test
    fun `message lengths are reduced to bounded diagnostic buckets`() {
        assertEquals("empty", diagnosticMessageLengthBucket(0))
        assertEquals("1-128", diagnosticMessageLengthBucket(1))
        assertEquals("1-128", diagnosticMessageLengthBucket(128))
        assertEquals("129-512", diagnosticMessageLengthBucket(129))
        assertEquals("513-2048", diagnosticMessageLengthBucket(2_048))
        assertEquals("2049-8000", diagnosticMessageLengthBucket(8_000))
    }

    @Test
    fun `failure snapshots retain category and stack without exception message`() {
        val secret = "https://host.example/api?key=secret prompt contents"
        val error = IOException(secret).apply {
            stackTrace = arrayOf(StackTraceElement("Example", "send", "Example.kt", 42))
        }

        val snapshot = sanitizedDiagnosticFailure(DiagnosticPhase.RunSubmit, error)

        assertEquals("run_submit", snapshot.phase)
        assertEquals("network", snapshot.category)
        assertEquals(1, snapshot.stackTrace.size)
        assertFalse(snapshot.summary.contains(secret))
        assertFalse(snapshot.summary.contains("host.example"))
    }

    @Test
    fun `exit diagnostic copy contains only bounded process metadata`() {
        val diagnostic = ProcessExitDiagnostic(
            timestampMillis = 1_700_000_000_000,
            reason = "low_memory",
            status = 9,
            importance = 100,
            pssKb = 48_000,
            rssKb = 120_000,
            lastPhase = "run_stream",
        )

        val copy = formatProcessExitDiagnostic(
            diagnostic = diagnostic,
            appVersion = "0.4.13",
            sdkInt = 35,
            device = "Google Pixel",
        )

        assertTrue(copy.contains("Hermes Mobile 0.4.13"))
        assertTrue(copy.contains("Reason: low_memory"))
        assertTrue(copy.contains("Last safe phase: run_stream"))
        assertFalse(copy.contains("prompt"))
        assertFalse(copy.contains("api-key"))
    }
}
