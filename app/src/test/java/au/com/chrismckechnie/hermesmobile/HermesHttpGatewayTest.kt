package au.com.chrismckechnie.hermesmobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesHttpGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var profile: HostProfile
    private lateinit var gateway: HermesHttpGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        profile = HostProfile(
            id = "host-1",
            name = "Test Hermes",
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            allowInsecureHttp = true,
        )
        gateway = HermesHttpGateway()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `probe uses bearer auth and reads capabilities`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent","features":{"session_list":true,"session_chat_stream":true,"file_upload":false}}
        """.trimIndent()))

        val info = gateway.probe(profile)
        val request = server.takeRequest()

        assertEquals("/v1/capabilities", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("hermes-agent", info.model)
        assertTrue(info.features.contains("session_chat_stream"))
        assertTrue(!info.features.contains("file_upload"))
    }

    @Test
    fun `lists sessions from Hermes list envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[{"id":"session-1","title":"Mobile work","source":"api_server","model":"hermes-agent","last_active":1720000000}]}
        """.trimIndent()))

        val sessions = gateway.listSessions(profile)

        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions.single().id)
        assertEquals("Mobile work", sessions.single().title)
    }

    @Test
    fun `streams assistant and tool events from session chat SSE`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
            event: run.started
            data: {"run_id":"run-1","session_id":"session-1"}

            event: assistant.delta
            data: {"delta":"Hello","message_id":"msg-1"}

            event: tool.started
            data: {"tool_name":"terminal","preview":"Running tests"}

            event: assistant.completed
            data: {"content":"Hello from Hermes","message_id":"msg-1"}

            event: run.completed
            data: {"completed":true,"session_id":"session-1"}

            event: done
            data: {}

        """.trimIndent()))
        val events = mutableListOf<HermesStreamEvent>()

        gateway.streamSessionChat(profile, "session-1", "Hi") { events += it }
        val request = server.takeRequest()

        assertEquals("/api/sessions/session-1/chat/stream", request.path)
        assertTrue(events.any { it is HermesStreamEvent.AssistantDelta && it.text == "Hello" })
        assertTrue(events.any { it is HermesStreamEvent.ToolStarted && it.toolName == "terminal" })
        assertTrue(events.any { it is HermesStreamEvent.Completed && it.content == "Hello from Hermes" })
    }
}
