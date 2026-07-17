package au.com.chrismckechnie.hermesmobile

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

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
            {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent","default_model":"gpt-5.6-luna","features":{"run_submission":true,"run_events_sse":true,"run_stop":true,"approval_events":true,"run_approval_response":true,"skills_api":true,"run_permission_mode":true,"file_upload":false}}
        """.trimIndent()))

        val info = gateway.probe(profile)
        val request = server.takeRequest()

        assertEquals("/v1/capabilities", request.path)
        assertTrue(info.supportsPermissionMode)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(info.supportsRuns)
        assertTrue(info.supportsSkills)
        assertFalse(info.supportsSessionFork)
        assertEquals("gpt-5.6-luna", info.defaultModel)
    }

    @Test
    fun `probe without approval events fails the run bundle`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"features":{"run_submission":true,"run_events_sse":true,"run_stop":true,"run_approval_response":true}}
        """.trimIndent()))

        assertFalse(gateway.probe(profile).supportsRuns)
    }

    @Test
    fun `default clients bound ordinary requests but leave event streams open`() {
        val configured = HermesHttpGateway()

        assertEquals(8_000, configured.ordinaryClient.connectTimeoutMillis)
        assertEquals(30_000, configured.ordinaryClient.readTimeoutMillis)
        assertEquals(20_000, configured.ordinaryClient.writeTimeoutMillis)
        assertEquals(45_000, configured.ordinaryClient.callTimeoutMillis)
        assertEquals(8_000, configured.eventStreamClient.connectTimeoutMillis)
        assertEquals(0, configured.eventStreamClient.readTimeoutMillis)
        assertEquals(20_000, configured.eventStreamClient.writeTimeoutMillis)
        assertEquals(0, configured.eventStreamClient.callTimeoutMillis)
    }

    @Test(expected = InterruptedIOException::class)
    fun `ordinary requests use the bounded client`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(1, TimeUnit.SECONDS)
                .setResponseCode(200)
                .setBody("""{"features":{}}"""),
        )
        gateway = HermesHttpGateway(
            ordinaryClient = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build(),
            eventStreamClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
        )

        gateway.probe(profile)
    }

    @Test
    fun `reads host version and capability-gated update status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","version":"2026.7.15"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"current_version":"2026.7.15","update_available":true,"can_apply":true,"message":"Update available","update_command":"hermes update"}
        """.trimIndent()))

        assertEquals("2026.7.15", gateway.getHostVersion(profile))
        val update = gateway.getHostUpdate(profile, force = true)

        assertEquals("/health", server.takeRequest().path)
        assertEquals("/v1/host-update?force=true", server.takeRequest().path)
        assertTrue(update.updateAvailable)
        assertTrue(update.canApply)
    }

    @Test
    fun `starts a host update with bearer auth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"accepted":true,"message":"Update started"}"""))

        val result = gateway.updateHost(profile)
        val request = server.takeRequest()

        assertEquals("/v1/host-update", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(result.accepted)
    }

    @Test
    fun `lists sessions including children`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"list","data":[{"id":"session-1","title":"Mobile work","source":"api_server","model":"hermes-agent","last_active":1720000000,"is_active":true}]}
        """.trimIndent()))

        val sessions = gateway.listSessions(profile)
        val request = server.takeRequest()

        assertTrue(request.path!!.contains("include_children=true"))
        assertEquals(1, sessions.sessions.size)
        assertEquals("session-1", sessions.sessions.single().id)
        assertTrue(sessions.sessions.single().isActive)
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
    fun `loadMessages accepts transcripts above the ordinary json limit`() = runBlocking {
        val content = "x".repeat(4 * 1024 * 1024)
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"object":"list","session_id":"session-1","data":[{"id":"m1","role":"assistant","content":"$content"}]}""",
        ))

        val page = gateway.loadMessages(profile, "session-1")

        assertEquals(content.length, page.messages.single().content.length)
    }

    @Test
    fun `loadMessages remains bounded at the transcript json limit`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(16 * 1024 * 1024 + 1)))

        val error = runCatching { gateway.loadMessages(profile, "session-1") }.exceptionOrNull()

        assertTrue(error is HermesApiException)
        assertTrue(error?.message.orEmpty().contains("too large"))
    }

    @Test
    fun `listJobs parses the jobs listing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"jobs":[{"id":"job-1","name":"Daily report","schedule":"0 9 * * *","enabled":false,"deliver":"mobile"}]}
        """.trimIndent()))

        val jobs = gateway.listJobs(profile)
        val request = server.takeRequest()

        assertEquals("/api/jobs?include_disabled=true", request.path)
        assertEquals(HermesJob("job-1", "Daily report", "0 9 * * *", false, "mobile"), jobs.single())
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
    fun `collection endpoints reject a successful response with a missing list envelope`() = runBlocking {
        val calls = listOf<Pair<String, suspend () -> Any>>(
            "messages" to { gateway.loadMessages(profile, "session-1") },
            "jobs" to { gateway.listJobs(profile) },
            "skills" to { gateway.listSkills(profile) },
            "toolsets" to { gateway.listToolsets(profile) },
            "models" to { gateway.listModels(profile) },
        )

        calls.forEach { (name, call) ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val error = runCatching { call() }.exceptionOrNull()

            assertTrue("$name should reject a malformed list envelope", error is HermesApiException)
            assertNotNull(error?.message)
        }
    }

    @Test
    fun `collection endpoints preserve genuine empty lists`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session_id":"session-1","data":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jobs":[]}"""))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}""")) }

        assertTrue(gateway.loadMessages(profile, "session-1").messages.isEmpty())
        assertTrue(gateway.listJobs(profile).isEmpty())
        assertTrue(gateway.listSkills(profile).isEmpty())
        assertTrue(gateway.listToolsets(profile).isEmpty())
        assertTrue(gateway.listModels(profile).isEmpty())
    }

    @Test
    fun `submitRun carries the selected model and omits it by default`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run-9","status":"started"}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run-10","status":"started"}"""))

        gateway.submitRun(
            profile,
            "session-1",
            "hello",
            emptyList(),
            model = "hermes-fast",
            reasoningEffort = "high",
            permissionMode = "full-access",
            idempotencyKey = "submit-123",
        )
        val overrideRequest = server.takeRequest()
        val withOverrides = JSONObject(overrideRequest.body.readUtf8())
        gateway.submitRun(profile, "session-1", "hello", emptyList())
        val withoutOverrides = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("hermes-fast", withOverrides.getString("model"))
        assertEquals("high", withOverrides.getString("reasoning_effort"))
        assertEquals("full-access", withOverrides.getString("permission_mode"))
        assertEquals("submit-123", overrideRequest.getHeader("Idempotency-Key"))
        assertFalse(withoutOverrides.has("model"))
        assertFalse(withoutOverrides.has("reasoning_effort"))
        assertFalse(withoutOverrides.has("permission_mode"))
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
    fun `session activity history and stream parse durable desktop work`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"object":"hermes.session.activity.list","session_id":"session-1","next_before":"7","data":[
              {"event_id":"8","session_id":"session-1","turn_id":"turn-1","type":"tool.complete","timestamp":2.0,"surface":"tui_gateway","payload":{"tool_id":"call-1","name":"write_file","duration_s":0.4,"workspace_changes":[{"path":"app/Main.kt","additions":3,"deletions":1,"diff":"+activity"}]}},
              {"event_id":"9","session_id":"session-1","turn_id":"turn-1","type":"subagent.progress","timestamp":3.0,"surface":"tui_gateway","payload":{"subagent_id":"sub-1","status":"working","goal":"Inspect tests","text":"Running checks"}}
            ]}
        """.trimIndent()))
        val page = gateway.loadSessionActivity(profile, "session-1", beforeEventId = 10L, limit = 25)
        val historyRequest = server.takeRequest()

        assertEquals("/v1/sessions/session-1/activity?limit=25&before=10", historyRequest.path)
        assertEquals(7L, page.nextBefore)
        assertEquals("call-1", page.events.first().toolId)
        assertEquals("app/Main.kt", page.events.first().workspaceUpdate!!.files.single().path)
        assertEquals("sub-1", page.events.last().subagent!!.id)

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
            id: 10
            event: activity
            data: {"event_id":"10","session_id":"session-1","turn_id":"turn-1","type":"reasoning.available","timestamp":4.0,"surface":"tui_gateway","payload":{"text":"Checking output"}}

        """.trimIndent()))
        val events = mutableListOf<HermesSessionActivityEvent>()
        gateway.streamSessionActivity(profile, "session-1", 9L, events::add)
        val streamRequest = server.takeRequest()

        assertEquals("/v1/sessions/session-1/activity/events", streamRequest.path)
        assertEquals("9", streamRequest.getHeader("Last-Event-ID"))
        assertEquals("Checking output", events.single().text)
    }

    @Test
    fun `streamRunEvents parses data-only SSE with keepalives and unknown events`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("""
            : keepalive

            id: 5
            data: {"event":"message.delta","event_id":5,"run_id":"run-1","timestamp":1.0,"delta":"Hel"}

            data: {"event":"message.delta","run_id":"run-1","timestamp":1.1,"delta":"lo"}

            data: {"event":"tool.started","run_id":"run-1","timestamp":1.2,"tool":"terminal","preview":"ls"}

            data: {"event":"reasoning.available","run_id":"run-1","timestamp":1.3,"text":"thinking"}

            data: {"event":"tool.completed","run_id":"run-1","timestamp":1.4,"tool":"terminal","duration":0.2,"error":true}

            data: {"event":"tasks.updated","run_id":"run-1","timestamp":1.45,"tasks":[{"id":"plan","content":"Plan the release","status":"completed"},{"id":"ship","content":"Ship the release","status":"in_progress"}]}

            data: {"event":"subagent.updated","run_id":"run-1","timestamp":1.46,"subagent":{"id":"subagent-1","status":"working","task_index":0,"task_count":2,"tool_count":3,"goal":"Inspect the API","activity":"Reading the run events"}}

            data: {"event":"workspace.updated","run_id":"run-1","timestamp":1.47,"files":[{"path":"app/Main.kt","status":"modified","additions":3,"deletions":1,"diff":"@@ -1 +1 @@"}],"truncated":false}

            data: {"event":"approval.request","run_id":"run-1","timestamp":1.5,"command":"rm -rf x","choices":["once","session","always","deny"]}

            data: {"event":"approval.responded","run_id":"run-1","timestamp":1.6,"choice":"once","resolved":1}

            data: {"event":"run.completed","run_id":"run-1","timestamp":1.7,"output":"Hello","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}

            : stream closed

        """.trimIndent()))
        val events = mutableListOf<HermesRunEvent>()

        gateway.streamRunEvents(profile, "run-1", 4L, events::add)
        val request = server.takeRequest()

        assertEquals("/v1/runs/run-1/events", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("4", request.getHeader("Last-Event-ID"))
        assertEquals(5L, events.filterIsInstance<HermesRunEvent.MessageDelta>().first().eventId)
        assertEquals(
            listOf("Hel", "lo"),
            events.filterIsInstance<HermesRunEvent.MessageDelta>().map { it.delta },
        )
        assertTrue(events.any { it is HermesRunEvent.ToolStarted && it.tool == "terminal" && it.preview == "ls" })
        assertTrue(events.any { it is HermesRunEvent.ReasoningAvailable && it.text == "thinking" })
        assertTrue(events.any { it is HermesRunEvent.ToolCompleted && it.failed })
        assertEquals(
            listOf(HermesTask("plan", "Plan the release", "completed"), HermesTask("ship", "Ship the release", "in_progress")),
            events.filterIsInstance<HermesRunEvent.TasksUpdated>().single().tasks,
        )
        assertEquals(
            HermesSubagent("subagent-1", "working", 0, 2, 3, "Inspect the API", "Reading the run events"),
            events.filterIsInstance<HermesRunEvent.SubagentUpdated>().single().subagent,
        )
        assertEquals(
            HermesWorkspaceUpdate(
                files = listOf(HermesWorkspaceChange("app/Main.kt", "modified", 3, 1, "@@ -1 +1 @@")),
            ),
            events.filterIsInstance<HermesRunEvent.WorkspaceUpdated>().single().update,
        )
        assertTrue(events.any { it is HermesRunEvent.ApprovalRequested && it.command == "rm -rf x" })
        assertTrue(events.any { it is HermesRunEvent.ApprovalResponded && it.choice == "once" })
        val completed = events.filterIsInstance<HermesRunEvent.Completed>().single()
        assertEquals("Hello", completed.output)
        assertEquals(HermesRunUsage(inputTokens = 1, outputTokens = 2, totalTokens = 3), completed.usage)
        assertEquals(11, events.size)
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
    fun `event streams do not inherit the ordinary call timeout`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(250, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"event\":\"run.completed\",\"output\":\"done\"}\n\n"),
        )
        gateway = HermesHttpGateway(
            ordinaryClient = OkHttpClient.Builder().callTimeout(50, TimeUnit.MILLISECONDS).build(),
            eventStreamClient = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build(),
        )
        val events = mutableListOf<HermesRunEvent>()

        gateway.streamRunEvents(profile, "run-1", events::add)

        assertTrue(events.single() is HermesRunEvent.Completed)
    }

    @Test
    fun `cancelling a suspended event stream cancels its call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val startedAt = System.nanoTime()
        val result = runCatching {
            withTimeout(250) {
                gateway.streamRunEvents(profile, "run-never-responds", onEvent = { })
            }
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertTrue("Cancellation took ${elapsedMillis}ms", elapsedMillis < 2_000)
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
            {"object":"list","active_count":1,"data":[{"lease_id":"lease-1","session_id":"session-1","run_id":"run-1","title":"Background work","state":"running","surface":"api_server","latest_status":"Using terminal…","status_history":["Reviewing the request…","Using terminal…"],"updated_at":1720000000.75}]}
        """.trimIndent()))

        val sessions = gateway.listActiveSessions(profile)

        assertEquals("/v1/active-sessions", server.takeRequest().path)
        assertEquals("Background work", sessions.single().title)
        assertEquals("Using terminal…", sessions.single().latestStatus)
        assertEquals(listOf("Reviewing the request…", "Using terminal…"), sessions.single().statusHistory)
        assertEquals(1_720_000_000L, sessions.single().updatedAt)
        assertEquals("lease-1", sessions.single().leaseId)
    }

    @Test
    fun `clears stale active session by lease id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        gateway.clearStaleActiveSession(profile, "lease-1")
        val request = server.takeRequest()

        assertEquals("DELETE", request.method)
        assertEquals("/v1/active-sessions/lease-1", request.path)
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
        assertEquals("token-1", body.getString("token"))
        assertFalse(body.has("fid"))
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

    @Test
    fun `pairing exchange is unauthenticated and returns scoped token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"object":"hermes.mobile_pairing","device_id":"device-1","token":"hmob_secret"}""",
        ))

        val result = gateway.exchangeMobilePairing(
            MobilePairingRequest(server.url("/").toString().removeSuffix("/"), "grant-1"),
            installationId = "phone-1",
            deviceName = "Hermes Mobile",
        )
        val request = server.takeRequest()

        assertEquals("/v1/mobile/pairing/exchange", request.path)
        assertEquals(null, request.getHeader("Authorization"))
        assertEquals("grant-1", JSONObject(request.body.readUtf8()).getString("grant"))
        assertEquals("hmob_secret", result.token)
    }

    @Test
    fun `ordinary json response is bounded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(4 * 1024 * 1024 + 1)))

        val error = runCatching { gateway.listModels(profile) }.exceptionOrNull()

        assertTrue(error is HermesApiException)
        assertTrue(error?.message.orEmpty().contains("too large"))
    }
}
