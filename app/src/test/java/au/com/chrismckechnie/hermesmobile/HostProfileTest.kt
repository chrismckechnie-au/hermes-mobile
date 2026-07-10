package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostProfileTest {
    @Test
    fun `normalizes root and v1 urls to the Hermes server root`() {
        assertEquals("https://hermes.example.com", normalizeHermesBaseUrl(" https://hermes.example.com/v1/ "))
        assertEquals("http://192.168.50.105:8642", normalizeHermesBaseUrl("http://192.168.50.105:8642/"))
    }

    @Test
    fun `requires explicit opt in for cleartext http`() {
        val profile = HostProfile(
            id = "host-1",
            name = "Ubuntu Hermes",
            baseUrl = "http://192.168.50.105:8642",
            apiKey = "secret",
            allowInsecureHttp = false,
        )

        assertThrows(IllegalArgumentException::class.java) { profile.validated() }
    }

    @Test
    fun `accepts an authenticated private http host after explicit opt in`() {
        val profile = HostProfile(
            id = "host-1",
            name = "Ubuntu Hermes",
            baseUrl = "http://192.168.50.105:8642/v1",
            apiKey = "secret",
            allowInsecureHttp = true,
        ).validated()

        assertEquals("http://192.168.50.105:8642", profile.baseUrl)
    }
}
