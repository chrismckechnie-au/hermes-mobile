package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfileTest {

    @Test
    fun `host draft validation returns field scoped errors`() {
        val errors = validateHostDraft(
            existing = null,
            name = " ",
            baseUrl = "not a url",
            apiKey = "",
            allowInsecureHttp = false,
        )

        assertEquals("Give this host a name.", errors.name)
        assertNotNull(errors.baseUrl)
        assertEquals("Hermes API key is required.", errors.apiKey)
        assertFalse(errors.isValid)
    }

    @Test
    fun `existing host may retain its stored key`() {
        val existing = HostProfile("h1", "Desktop", "https://hermes.example.com", "secret")

        val errors = validateHostDraft(
            existing = existing,
            name = "Desktop",
            baseUrl = "https://hermes.example.com",
            apiKey = "",
            allowInsecureHttp = false,
        )

        assertTrue(errors.isValid)
    }

    @Test
    fun `private network http requires explicit opt in`() {
        val blocked = validateHostDraft(null, "LAN", "http://192.168.1.2:8642", "key", false)
        val allowed = validateHostDraft(null, "LAN", "http://192.168.1.2:8642", "key", true)

        assertNotNull(blocked.baseUrl)
        assertTrue(allowed.isValid)
    }

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
