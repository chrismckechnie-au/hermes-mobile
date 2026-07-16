package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobilePairingTest {
    @Test fun `parses terminal qr payload`() {
        assertEquals(
            MobilePairingRequest("http://192.168.1.4:8642", "one-time secret"),
            parseMobilePairingUri("hermes://pair?url=http%3A%2F%2F192.168.1.4%3A8642&grant=one-time+secret"),
        )
    }

    @Test fun `rejects unsafe or incomplete payloads`() {
        assertNull(parseMobilePairingUri("hermes://pair?url=file%3A%2F%2Fhost&grant=secret"))
        assertNull(parseMobilePairingUri("hermes://pair?url=https%3A%2F%2Fhost"))
        assertNull(parseMobilePairingUri("https://host/pair?grant=secret"))
    }
}
