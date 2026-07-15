package au.com.chrismckechnie.hermesmobile

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `probe uses bearer auth and reads capability bundle`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent","features":{"run_submission":true,"run_events_sse":true,"run_stop":true,"approval_events":true,"run_approval_response":true,"skills_api":true,"file_upload":false}}
        """.trimIndent()))

        val info = gateway.probe(profile)
        val request = server.takeRequest()

        assertEquals("/v1/capabilities", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(info.supportsRuns)
        assertTrue(info.supportsSkills)
        assertFalse(info.supportsSessionFork)
    }

    @Test
    fun `probe without approval events fails the run bundle`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"features":{"run_submission":true,"run_events_sse":true,"run_stop":true,"run_approval_response":true}}
        """.trimIndent()))

        assertFalse(gateway.probe(profile).supportsRuns)
    }

    @Test
    fun `lists sessions including children`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[{"id":"session-1","title":"Mobile work","source":"api_server","model":"hermes-agent","last_active":1720000000}]}
        """.trimIndent()))

        val sessions = gateway.listSessions(profile)
        val request = server.takeRequest()

        assertTrue(request.path!!.contains("include_children=true"))
        assertEquals(1, sessions.sessions.size)
        assertEquals("session-1", sessions.sessions.single().id)
    }

    @Test
    fun `loadMessages returns the resolved session id from the envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","session_id":"session-2","data":[{"id":"m1","role":"user","content":"hi"}]}
        """.trimIndent()))

        val page = gateway.loadMessages(profile, "session-1")

        assertEquals("session-2", page.sessionId)
        assertEquals(1, page.messages.size)
    }

    @Test
    fun `listSkills parses the skills listing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[{"name":"grill-me","description":"A relentless interview.","category":null}]}
        """.trimIndent()))

        val skills = gateway.listSkills(profile)
        val request = server.takeRequest()

        assertEquals("/v1/skills", request.path)
        assertEquals("grill-me", skills.single().name)
    }

    @Test
    fun `listToolsets parses host tool metadata`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[{"name":"terminal","label":"Terminal","description":"Run commands","enabled":true,"configured":true,"tools":["shell_command","read_thread_terminal"]}]}
        """.trimIndent()))

        val toolsets = gateway.listToolsets(profile)

        assertEquals("/v1/toolsets", server.takeRequest().path)
        assertEquals("Terminal", toolsets.single().label)
        assertEquals(listOf("shell_command", "read_thread_terminal"), toolsets.single().tools)
    }

    @Test
    fun `listModels returns every unique model id advertised by the host`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[
              {"id":"hermes-agent","root":"hermes-agent","parent":null},
              {"id":"gpt-5.6-sol","provider":"openai-codex","parent":"hermes-agent"},
              {"id":"gpt-5.6-terra","provider":"openai-codex","parent":"hermes-agent"},
              {"id":"gpt-5.6-luna","provider":"openai-codex","parent":"hermes-agent"},
              {"id":"gpt-5.6-terra","provider":"opencode-zen","parent":"hermes-agent"}
            ]}
        """.trimIndent()))

        val models = gateway.listModels(profile)

        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals(
            listOf("hermes-agent", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
            models,
        )
    }

    @Test
    fun `submitRun carries the selected model and omits it by default`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run-9","status":"started"}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run-10","status":"started"}"""))

        gateway.submitRun(profile, "session-1", "hello", emptyList(), model = "hermes-fast", reasoningEffort = "high")
        val withOverrides = JSONObject(server.takeRequest().body.readUtf8())
        gateway.submitRun(profile, "session-1", "hello", emptyList())
        val withoutOverrides = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("hermes-fast", withOverrides.getString("model"))
        assertEquals("high", withOverrides.getString("reasoning_effort"))
        assertFalse(withoutOverrides.has("model"))
        assertFalse(withoutOverrides.has("reasoning_effort"))
    }

    @Test
    fun `submitRun sends input session and filtered history and returns run id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run-9","status":"started"}"""))

        val history = listOf(
            HermesMessage("m1", "user", "hello"),
            HermesMessage("m2", "tool", "tool output", toolName = "terminal"),
            HermesMessage("m3", "assistant", "hi"),
            HermesMessage("m4", "assistant", ""),
        )
        val runId = gateway.submitRun(profile, "session-1", "next question", history)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("run-9", runId)
        assertEquals("/v1/runs", request.path)
        assertEquals("next question", body.getString("input"))
        assertEquals("session-1", body.getString("session_id"))
        val sent = body.getJSONArray("conversation_history")
        assertEquals(2, sent.length())
        assertEquals("user", sent.getJSONObject(0).getString("role"))
        assertEquals("assistant", sent.getJSONObject(1).getString("role"))
    }

    @Test
    fun `streamRunEvents parses data-only SSE with keepalives and unknown events`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
            : keepalive

            data: {"event":"message.delta","run_id":"run-1","timestamp":1.0,"delta":"Hel"}

            data: {"event":"message.delta","run_id":"run-1","timestamp":1.1,"delta":"lo"}

            data: {"event":"tool.started","run_id":"run-1","timestamp":1.2,"tool":"terminal","preview":"ls"}

            data: {"event":"reasoning.available","run_id":"run-1","timestamp":1.3,"text":"thinking"}

            data: {"event":"tool.completed","run_id":"run-1","timestamp":1.4,"tool":"terminal","duration":0.2,"error":true}

            data: {"event":"approval.request","run_id":"run-1","timestamp":1.5,"command":"rm -rf x","choices":["once","session","always","deny"]}

            data: {"event":"approval.responded","run_id":"run-1","timestamp":1.6,"choice":"once","resolved":1}

            data: {"event":"run.completed","run_id":"run-1","timestamp":1.7,"output":"Hello","usage":{"total_tokens":3}}

            : stream closed

        """.trimIndent()))
        val events = mutableListOf<HermesRunEvent>()

        gateway.streamRunEvents(profile, "run-1", events::add)
        val request = server.takeRequest()

        assertEquals("/v1/runs/run-1/events", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals(
            listOf("Hel", "lo"),
            events.filterIsInstance<HermesRunEvent.MessageDelta>().map { it.delta },
        )
        assertTrue(events.any { it is HermesRunEvent.ToolStarted && it.tool == "terminal" && it.preview == "ls" })
        assertTrue(events.any { it is HermesRunEvent.ReasoningAvailable && it.text == "thinking" })
        assertTrue(events.any { it is HermesRunEvent.ToolCompleted && it.failed })
        assertTrue(events.any { it is HermesRunEvent.ApprovalRequested && it.command == "rm -rf x" })
        assertTrue(events.any { it is HermesRunEvent.ApprovalResponded && it.choice == "once" })
        assertTrue(events.any { it is HermesRunEvent.Completed && it.output == "Hello" })
        assertEquals(8, events.size)
    }

    @Test
    fun `streamRunEvents surfaces failure and cancellation`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"event":"run.failed","run_id":"run-1","timestamp":1.0,"error":"boom"}

            data: {"event":"run.cancelled","run_id":"run-1","timestamp":1.1}

        """.trimIndent()))
        val events = mutableListOf<HermesRunEvent>()

        gateway.streamRunEvents(profile, "run-1", events::add)

        assertTrue(events.any { it is HermesRunEvent.Failed && it.error == "boom" })
        assertTrue(events.any { it is HermesRunEvent.Cancelled })
    }

    @Test
    fun `getRunStatus maps terminal states`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.run","run_id":"run-1","status":"waiting_for_approval"}"""))

        val status = gateway.getRunStatus(profile, "run-1")

        assertEquals("waiting_for_approval", status.status)
        assertTrue(status.isWaitingForApproval)
        assertFalse(status.isTerminal)
    }

    @Test
    fun `lists active sessions for overlays`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","active_count":1,"data":[{"session_id":"session-1","run_id":"run-1","title":"Background work","state":"running","surface":"api_server"}]}
        """.trimIndent()))

        val sessions = gateway.listActiveSessions(profile)

        assertEquals("/v1/active-sessions", server.takeRequest().path)
        assertEquals("Background work", sessions.single().title)
    }

    @Test
    fun `registers device with host local profile routing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        gateway.registerMobileDevice(profile, "install-1", "token-1", "0.2.0", overlayEnabled = true)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("PUT", request.method)
        assertEquals("/v1/mobile/devices/install-1", request.path)
        assertEquals("host-1", body.getString("host_profile_id"))
        assertEquals("token-1", body.getString("fid"))
        assertTrue(body.getJSONObject("capabilities").getBoolean("overlay"))
    }

    @Test
    fun `respondApproval posts the choice`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.run.approval_response","run_id":"run-1","choice":"once","resolved":1}"""))

        gateway.respondApproval(profile, "run-1", "once")
        val request = server.takeRequest()

        assertEquals("/v1/runs/run-1/approval", request.path)
        assertEquals("once", JSONObject(request.body.readUtf8()).getString("choice"))
    }

    @Test
    fun `stopRun posts to the stop endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.run","run_id":"run-1","status":"stopping"}"""))

        gateway.stopRun(profile, "run-1")

        assertEquals("/v1/runs/run-1/stop", server.takeRequest().path)
    }

    @Test
    fun `renameSession patches the title`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.session","session":{"id":"session-1","title":"Renamed"}}"""))

        val session = gateway.renameSession(profile, "session-1", "Renamed")
        val request = server.takeRequest()

        assertEquals("PATCH", request.method)
        assertEquals("/api/sessions/session-1", request.path)
        assertEquals("Renamed", JSONObject(request.body.readUtf8()).getString("title"))
        assertEquals("Renamed", session.title)
    }

    @Test
    fun `deleteSession issues DELETE`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.session.deleted","id":"session-1","deleted":true}"""))

        gateway.deleteSession(profile, "session-1")
        val request = server.takeRequest()

        assertEquals("DELETE", request.method)
        assertEquals("/api/sessions/session-1", request.path)
    }

    @Test
    fun `forkSession returns the child session`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"object":"hermes.session","session":{"id":"session-2","title":"work fork","parent_session_id":"session-1"}}"""))

        val child = gateway.forkSession(profile, "session-1")
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/api/sessions/session-1/fork", request.path)
        assertEquals("session-2", child.id)
    }
}
