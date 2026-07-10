package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostSnapshotCodecTest {
    private val host = HostProfile(
        id = "h1",
        name = "Desk",
        baseUrl = "https://hermes.example.com",
        apiKey = "secret-key",
        allowInsecureHttp = false,
    )

    @Test
    fun `round trips hosts and selection`() {
        val snapshot = HostSnapshot(listOf(host, host.copy(id = "h2", allowInsecureHttp = true)), "h2")

        val decoded = HostSnapshotCodec.decode(HostSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trips null selection`() {
        val decoded = HostSnapshotCodec.decode(HostSnapshotCodec.encode(HostSnapshot(listOf(host), null)))

        assertNull(decoded.selectedHostId)
        assertEquals(listOf(host), decoded.hosts)
    }

    @Test
    fun `corrupt host rows are skipped not fatal`() {
        val raw = """{"selectedHostId":"h1","hosts":[{"id":"h1"},{"id":"h2","name":"Ok","baseUrl":"https://x","apiKey":"k"}]}"""

        val decoded = HostSnapshotCodec.decode(raw)

        assertEquals(listOf("h2"), decoded.hosts.map { it.id })
    }

    @Test
    fun `missing allowInsecureHttp defaults to false for legacy snapshots`() {
        val raw = """{"selectedHostId":null,"hosts":[{"id":"h1","name":"Desk","baseUrl":"https://x","apiKey":"k"}]}"""

        val decoded = HostSnapshotCodec.decode(raw)

        assertEquals(false, decoded.hosts.single().allowInsecureHttp)
    }
}
