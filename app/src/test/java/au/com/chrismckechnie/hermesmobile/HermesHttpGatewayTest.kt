package au.com.chrismckechnie.hermesmobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

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
    fun `lists sessions from Hermes list envelope with pagination`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[{"id":"session-1","title":"Mobile work","source":"api_server","model":"hermes-agent","last_active":1720000000}],"has_more":true}
        """.trimIndent()))

        val page = gateway.listSessions(profile, offset = 25)
        val request = server.takeRequest()

        assertTrue(request.path.orEmpty().contains("offset=25"))
        assertEquals(1, page.sessions.size)
        assertEquals("session-1", page.sessions.single().id)
        assertEquals("Mobile work", page.sessions.single().title)
        assertTrue(page.hasMore)
    }

    @Test
    fun `malformed session row is skipped instead of failing the list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[{"title":"missing id"},{"id":"session-2","title":"Good"}],"has_more":false}
        """.trimIndent()))

        val page = gateway.listSessions(profile)

        assertEquals(listOf("session-2"), page.sessions.map { it.id })
    }

    @Test
    fun `unexpected session envelope raises an error instead of silent empty list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"list","items":[]}"""))

        val error = assertThrows(HermesApiException::class.java) {
            runBlocking { gateway.listSessions(profile) }
        }
        assertTrue(error.message.contains("unexpected"))
    }

    @Test
    fun `error envelope message is surfaced with status code`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))

        val error = assertThrows(HermesApiException::class.java) {
            runBlocking { gateway.listSessions(profile) }
        }
        assertEquals(401, error.statusCode)
        assertEquals("Invalid API key", error.message)
    }

    @Test
    fun `plain http failure maps to friendly message`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = assertThrows(HermesApiException::class.java) {
            runBlocking { gateway.listSessions(profile) }
        }
        assertEquals("Hermes request failed with HTTP 500.", error.message)
    }

    @Test
    fun `unreadable json body raises api exception`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        val error = assertThrows(HermesApiException::class.java) {
            runBlocking { gateway.probe(profile) }
        }
        assertTrue(error.message.contains("unreadable"))
    }

    @Test
    fun `streams assistant tool and approval events from session chat SSE`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
            event: run.started
            data: {"run_id":"run-1","session_id":"session-1"}

            event: assistant.delta
            data: {"delta":"Hello","message_id":"msg-1"}

            event: tool.started
            data: {"tool_name":"terminal","preview":"Running tests"}

            event: approval.request
            data: {"approval_id":"appr-1","tool_name":"terminal","message":"Run rm?","run_id":"run-1"}

            event: assistant.completed
            data: {"content":"Hello from Hermes","message_id":"msg-1"}

            event: mystery.event
            data: {}

            event: done
            data: {}

        """.trimIndent()))
        val events = mutableListOf<HermesStreamEvent>()

        gateway.streamSessionChat(profile, "session-1", "Hi") { events += it }
        val request = server.takeRequest()

        assertEquals("/api/sessions/session-1/chat/stream", request.path)
        assertTrue(events.any { it is HermesStreamEvent.AssistantDelta && it.text == "Hello" })
        assertTrue(events.any { it is HermesStreamEvent.ToolStarted && it.toolName == "terminal" })
        assertTrue(events.any { it is HermesStreamEvent.ApprovalRequested && it.approvalId == "appr-1" && it.runId == "run-1" })
        assertTrue(events.any { it is HermesStreamEvent.Completed && it.content == "Hello from Hermes" })
        assertTrue(events.any { it is HermesStreamEvent.Unknown && it.name == "mystery.event" })
    }

    @Test
    fun `sse error event maps to Failed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
            event: error
            data: {"message":"agent crashed"}

        """.trimIndent()))
        val events = mutableListOf<HermesStreamEvent>()

        gateway.streamSessionChat(profile, "session-1", "Hi") { events += it }

        assertTrue(events.any { it is HermesStreamEvent.Failed && it.message == "agent crashed" })
    }

    @Test
    fun `mid-stream disconnect surfaces as IOException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: run.started\ndata: {}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        assertThrows(IOException::class.java) {
            runBlocking { gateway.streamSessionChat(profile, "session-1", "Hi") { } }
        }
        Unit
    }

    @Test
    fun `stopRun posts to the runs stop endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"stopping"}"""))

        gateway.stopRun(profile, "run-7")
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/v1/runs/run-7/stop", request.path)
    }

    @Test
    fun `resolveApproval posts decision payload`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        gateway.resolveApproval(profile, "run-7", "appr-1", true)
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/v1/runs/run-7/approval", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"approval_id\":\"appr-1\""))
        assertTrue(body.contains("\"decision\":true"))
    }

    @Test
    fun `renameSession patches and deleteSession deletes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        gateway.renameSession(profile, "session-1", "New title")
        val patch = server.takeRequest()
        gateway.deleteSession(profile, "session-1")
        val delete = server.takeRequest()

        assertEquals("PATCH", patch.method)
        assertEquals("/api/sessions/session-1", patch.path)
        assertTrue(patch.body.readUtf8().contains("New title"))
        assertEquals("DELETE", delete.method)
        assertEquals("/api/sessions/session-1", delete.path)
    }

    @Test
    fun `setJobEnabled maps to pause and resume endpoints`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        gateway.setJobEnabled(profile, "job-1", false)
        val pause = server.takeRequest()
        gateway.setJobEnabled(profile, "job-1", true)
        val resume = server.takeRequest()

        assertEquals("/api/jobs/job-1/pause", pause.path)
        assertEquals("/api/jobs/job-1/resume", resume.path)
    }
}
