#!/usr/bin/env python3
"""Small contract-faithful Hermes API host for Android UI verification.

Speaks the /v1/runs transport the mobile client uses: run submission,
data-only SSE run events, approvals, stop, plus session rename/delete/fork.

Fixtures:
- input containing "approve-me": the run emits approval.request and blocks
  until POST /v1/runs/{id}/approval arrives (deny -> refusal output).
- input containing "slow": the run streams deltas for ~15s so Stop can be
  exercised mid-run.
- input containing "tasks" or "subagent": emits live task-plan and delegated-work updates.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import threading
import time
import uuid

API_KEY = "test-key"
PORT = int(os.environ.get("HERMES_MOCK_PORT", "8766"))
SESSIONS = [
    {
        "id": "session-mobile",
        "title": "Hermes Mobile build",
        "preview": "Polish the native Command Deck and connect it to Hermes.",
        "source": "api_server",
        "model": "hermes-agent",
        "message_count": 4,
        "last_active": time.time(),
        "is_active": True,
    },
    {
        "id": "session-priorities",
        "title": "Daily priorities",
        "preview": "Review the autonomous queue and surface blockers.",
        "source": "discord",
        "model": "hermes-agent",
        "message_count": 12,
        "last_active": time.time() - 3600,
    },
]
MESSAGES = {
    "session-mobile": [
        {"id": "m1", "role": "user", "content": "Can the phone choose between Hermes hosts?"},
        {"id": "m2", "role": "assistant", "content": "Yes. Host profiles are stored on-device, the selected API is probed for capabilities, and its bearer key stays encrypted in Android Keystore."},
    ]
}
SKILLS = [
    {"name": "grill-me", "description": "A relentless interview to sharpen a plan or design.", "category": None},
    {"name": "code-review", "description": "Review changes since a fixed point along Standards and Spec axes.", "category": None},
    {"name": "diagnosing-bugs", "description": "Diagnosis loop for hard bugs and performance regressions.", "category": None},
]
TOOLSETS = [
    {
        "name": "terminal",
        "label": "Terminal",
        "description": "Run commands on the Hermes host.",
        "enabled": True,
        "configured": True,
        "tools": ["shell_command", "read_thread_terminal"],
    },
    {
        "name": "web",
        "label": "Web research",
        "description": "Search and inspect web pages.",
        "enabled": True,
        "configured": True,
        "tools": ["web_search", "web_open"],
    },
]

RUNS = {}  # run_id -> {status, session_id, input, approval_event, approval_choice, stopped}
RUNS_LOCK = threading.Lock()


def _find_session(session_id):
    return next((s for s in SESSIONS if s["id"] == session_id), None)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        print(format % args, flush=True)

    def _auth(self):
        if self.headers.get("Authorization") != f"Bearer {API_KEY}":
            self._json(401, {"error": {"message": "Invalid API key"}})
            return False
        return True

    def _json(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(length) or b"{}")

    # ------------------------------------------------------------------
    # GET
    # ------------------------------------------------------------------

    def do_GET(self):
        if not self._auth():
            return
        path = self.path.split("?", 1)[0]
        if path == "/v1/capabilities":
            self._json(200, {
                "object": "hermes.api_server.capabilities",
                "platform": "hermes-agent",
                "model": "hermes-agent",
                "version": "2026.7.15",
                "features": {
                    "session_list": True,
                    "session_create": True,
                    "session_chat_stream": True,
                    "session_resources": True,
                    "session_fork": True,
                    "run_submission": True,
                    "run_status": True,
                    "run_events_sse": True,
                    "run_stop": True,
                    "tool_progress_events": True,
                    "approval_events": True,
                    "run_approval_response": True,
                    "run_reasoning_effort": True,
                    "run_task_updates": True,
                    "run_subagent_updates": True,
                    "skills_api": True,
                    "toolsets_api": True,
                    "host_update_api": True,
                    "jobs": True,
                },
                "endpoints": {
                    "health": {"method": "GET", "path": "/health"},
                    "models": {"method": "GET", "path": "/v1/models"},
                    "runs": {"method": "POST", "path": "/v1/runs"},
                    "run_status": {"method": "GET", "path": "/v1/runs/{run_id}"},
                    "run_events": {"method": "GET", "path": "/v1/runs/{run_id}/events"},
                    "run_approval": {"method": "POST", "path": "/v1/runs/{run_id}/approval"},
                    "run_stop": {"method": "POST", "path": "/v1/runs/{run_id}/stop"},
                    "skills": {"method": "GET", "path": "/v1/skills"},
                    "toolsets": {"method": "GET", "path": "/v1/toolsets"},
                    "sessions": {"method": "GET", "path": "/api/sessions"},
                    "session_create": {"method": "POST", "path": "/api/sessions"},
                    "session": {"method": "GET", "path": "/api/sessions/{session_id}"},
                    "session_update": {"method": "PATCH", "path": "/api/sessions/{session_id}"},
                    "session_delete": {"method": "DELETE", "path": "/api/sessions/{session_id}"},
                    "session_messages": {"method": "GET", "path": "/api/sessions/{session_id}/messages"},
                    "session_fork": {"method": "POST", "path": "/api/sessions/{session_id}/fork"},
                    "host_update": {"method": "GET", "path": "/v1/host-update"},
                },
            })
        elif path == "/health":
            self._json(200, {"status": "ok", "platform": "hermes-agent", "version": "2026.7.15"})
        elif path == "/v1/host-update":
            self._json(200, {
                "current_version": "2026.7.15",
                "update_available": True,
                "can_apply": True,
                "message": "A newer Hermes Agent release is available.",
                "update_command": "hermes update",
            })
        elif path == "/v1/skills":
            self._json(200, {"object": "list", "data": SKILLS})
        elif path == "/v1/toolsets":
            self._json(200, {"object": "list", "platform": "api_server", "data": TOOLSETS})
        elif path == "/v1/models":
            now = int(time.time())
            self._json(200, {"object": "list", "data": [
                {"id": "hermes-agent", "object": "model", "created": now, "owned_by": "hermes", "root": "hermes-agent", "parent": None},
                {"id": "gpt-5.6-sol", "object": "model", "created": now, "owned_by": "hermes", "provider": "openai-codex", "root": "gpt-5.6-sol", "parent": "hermes-agent"},
                {"id": "gpt-5.6-terra", "object": "model", "created": now, "owned_by": "hermes", "provider": "openai-codex", "root": "gpt-5.6-terra", "parent": "hermes-agent"},
                {"id": "gpt-5.6-luna", "object": "model", "created": now, "owned_by": "hermes", "provider": "openai-codex", "root": "gpt-5.6-luna", "parent": "hermes-agent"},
                {"id": "claude-fable-5", "object": "model", "created": now, "owned_by": "hermes", "provider": "opencode-zen", "root": "claude-fable-5", "parent": "hermes-agent"},
            ]})
        elif path == "/v1/active-sessions":
            active = [
                {
                    "session_id": run["session_id"],
                    "run_id": run_id,
                    "title": (_find_session(run["session_id"]) or {}).get("title", "Hermes session"),
                    "state": run["status"],
                    "surface": "api_server",
                }
                for run_id, run in RUNS.items()
                if run["status"] not in {"completed", "failed", "cancelled"}
            ]
            self._json(200, {"object": "list", "active_count": len(active), "data": active})
        elif path == "/api/sessions":
            self._json(200, {"object": "list", "data": SESSIONS, "limit": 50, "offset": 0, "has_more": False})
        elif path == "/api/jobs":
            self._json(200, {"jobs": [
                {"id": "job-daily", "name": "Daily Agent Updates", "schedule": "0 4 * * *", "enabled": True, "deliver": "discord"},
                {"id": "job-research", "name": "Daily Content Research", "schedule": "0 5 * * *", "enabled": True, "deliver": "discord"},
            ]})
        elif path.startswith("/api/sessions/") and path.endswith("/messages"):
            session_id = path.split("/")[3]
            self._json(200, {"object": "list", "session_id": session_id, "data": MESSAGES.get(session_id, [])})
        elif path.startswith("/api/sessions/"):
            session = _find_session(path.split("/")[3])
            if session is None:
                self._json(404, {"error": {"message": "Not found"}})
            else:
                self._json(200, {"object": "hermes.session", "session": session})
        elif path.startswith("/v1/runs/") and path.endswith("/events"):
            self._stream_run_events(path.split("/")[3])
        elif path.startswith("/v1/runs/"):
            run_id = path.split("/")[3]
            run = RUNS.get(run_id)
            if run is None:
                self._json(404, {"error": {"message": f"Run not found: {run_id}"}})
            else:
                self._json(200, {"object": "hermes.run", "run_id": run_id, "status": run["status"]})
        else:
            self._json(404, {"error": {"message": "Not found"}})

    # ------------------------------------------------------------------
    # POST / PATCH / DELETE
    # ------------------------------------------------------------------

    def do_POST(self):
        if not self._auth():
            return
        path = self.path.split("?", 1)[0]
        body = self._body()
        if path == "/api/sessions":
            session = {
                "id": f"api_{uuid.uuid4().hex[:8]}",
                "title": body.get("title", "Hermes Mobile"),
                "source": "api_server",
                "model": "hermes-agent",
                "message_count": 0,
            }
            SESSIONS.insert(0, session)
            self._json(201, {"object": "hermes.session", "session": session})
        elif path == "/v1/host-update":
            self._json(202, {"accepted": True, "message": "Host update started. Hermes will restart when ready."})
        elif path == "/v1/runs":
            effort = body.get("reasoning_effort")
            if effort is not None and effort not in {"none", "minimal", "low", "medium", "high", "xhigh", "max"}:
                self._json(400, {"error": {"message": f"Invalid reasoning_effort: {effort!r}", "code": "invalid_reasoning_effort"}})
                return
            run_id = f"run_{uuid.uuid4().hex[:8]}"
            with RUNS_LOCK:
                RUNS[run_id] = {
                    "status": "running",
                    "session_id": body.get("session_id") or run_id,
                    "input": body.get("input", ""),
                    "approval_event": threading.Event(),
                    "approval_choice": None,
                    "stopped": False,
                }
            self._json(202, {"run_id": run_id, "status": "started"})
        elif path.startswith("/v1/runs/") and path.endswith("/approval"):
            run_id = path.split("/")[3]
            run = RUNS.get(run_id)
            if run is None:
                self._json(404, {"error": {"message": f"Run not found: {run_id}"}})
                return
            choice = str(body.get("choice", "")).lower()
            if choice not in {"once", "session", "always", "deny"}:
                self._json(400, {"error": {"message": "Invalid approval choice"}})
                return
            run["approval_choice"] = choice
            run["approval_event"].set()
            self._json(200, {"object": "hermes.run.approval_response", "run_id": run_id, "choice": choice, "resolved": 1})
        elif path.startswith("/v1/runs/") and path.endswith("/stop"):
            run_id = path.split("/")[3]
            run = RUNS.get(run_id)
            if run is None:
                self._json(404, {"error": {"message": f"Run not found: {run_id}"}})
                return
            run["stopped"] = True
            run["status"] = "stopping"
            run["approval_event"].set()
            self._json(200, {"object": "hermes.run", "run_id": run_id, "status": "stopping"})
        elif path.startswith("/api/sessions/") and path.endswith("/fork"):
            source_id = path.split("/")[3]
            source = _find_session(source_id)
            if source is None:
                self._json(404, {"error": {"message": "Not found"}})
                return
            child = {
                "id": f"api_{uuid.uuid4().hex[:8]}",
                "title": f"{source.get('title') or 'fork'} fork",
                "source": "api_server",
                "model": source.get("model"),
                "message_count": source.get("message_count", 0),
                "parent_session_id": source_id,
            }
            SESSIONS.insert(0, child)
            MESSAGES[child["id"]] = list(MESSAGES.get(source_id, []))
            self._json(201, {"object": "hermes.session", "session": child})
        elif path.startswith("/api/sessions/") and path.endswith("/chat/stream"):
            # Legacy transport kept for older clients of the fixture.
            self._legacy_chat_stream(path.split("/")[3])
        else:
            self._json(404, {"error": {"message": "Not found"}})

    def do_PATCH(self):
        if not self._auth():
            return
        path = self.path.split("?", 1)[0]
        body = self._body()
        if path.startswith("/api/sessions/"):
            session = _find_session(path.split("/")[3])
            if session is None:
                self._json(404, {"error": {"message": "Not found"}})
                return
            if "title" in body:
                session["title"] = str(body["title"])
            self._json(200, {"object": "hermes.session", "session": session})
        else:
            self._json(404, {"error": {"message": "Not found"}})

    def do_DELETE(self):
        if not self._auth():
            return
        path = self.path.split("?", 1)[0]
        if path.startswith("/api/sessions/"):
            session_id = path.split("/")[3]
            global SESSIONS
            before = len(SESSIONS)
            SESSIONS = [s for s in SESSIONS if s["id"] != session_id]
            MESSAGES.pop(session_id, None)
            self._json(200, {"object": "hermes.session.deleted", "id": session_id, "deleted": len(SESSIONS) < before})
        else:
            self._json(404, {"error": {"message": "Not found"}})

    # ------------------------------------------------------------------
    # Run event streaming (data-only SSE, event name inside the JSON)
    # ------------------------------------------------------------------

    def _sse_start(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

    def _sse_data(self, run_id, event, **fields):
        payload = {"event": event, "run_id": run_id, "timestamp": time.time(), **fields}
        self.wfile.write(f"data: {json.dumps(payload)}\n\n".encode())
        self.wfile.flush()

    def _stream_run_events(self, run_id):
        run = RUNS.get(run_id)
        if run is None:
            self._json(404, {"error": {"message": f"Run not found: {run_id}"}})
            return
        self._sse_start()
        session_id = run["session_id"]
        text = run["input"]
        output = "Connected to Hermes. Run transport, stop, and approvals are working."
        try:
            self.wfile.write(b": keepalive\n\n")
            if "tasks" in text.lower() or "subagent" in text.lower():
                self._sse_data(run_id, "tasks.updated", tasks=[
                    {"id": "plan", "content": "Plan the requested work", "status": "completed"},
                    {"id": "implement", "content": "Implement and verify the change", "status": "in_progress"},
                ])
                self._sse_data(run_id, "subagent.updated", subagent={
                    "id": "mock-subagent-1",
                    "status": "working",
                    "task_index": 0,
                    "task_count": 1,
                    "tool_count": 1,
                    "goal": "Inspect the requested change",
                    "activity": "Reviewing the current implementation",
                })
            if "approve-me" in text:
                self._sse_data(run_id, "tool.started", tool="terminal", preview="rm -rf /tmp/hermes-demo")
                run["status"] = "waiting_for_approval"
                self._sse_data(run_id, "approval.request", command="rm -rf /tmp/hermes-demo", choices=["once", "session", "always", "deny"])
                run["approval_event"].wait(timeout=60)
                run["status"] = "running"
                choice = run["approval_choice"]
                self._sse_data(run_id, "approval.responded", choice=choice, resolved=1)
                if run["stopped"]:
                    run["status"] = "cancelled"
                    self._sse_data(run_id, "run.cancelled")
                    return
                failed = choice == "deny"
                self._sse_data(run_id, "tool.completed", tool="terminal", duration=0.4, error=failed)
                output = "Approval denied, so the command was not executed." if failed else "Approved command executed."
            elif "slow" in text:
                for index in range(30):
                    if run["stopped"]:
                        run["status"] = "cancelled"
                        self._sse_data(run_id, "run.cancelled")
                        return
                    self._sse_data(run_id, "message.delta", delta=f"chunk-{index} ", message_id="msg-demo")
                    time.sleep(0.5)
            else:
                self._sse_data(
                    run_id,
                    "reasoning.available",
                    text="Checking the host connection and available capabilities.",
                )
                self._sse_data(run_id, "message.delta", delta="Connected to ", message_id="msg-demo")
                self._sse_data(run_id, "tool.started", tool="host_probe", preview="Checking Hermes capabilities")
                self._sse_data(run_id, "tool.completed", tool="host_probe", duration=0.2, error=False)
                self._sse_data(run_id, "message.delta", delta="Hermes.", message_id="msg-demo")
            run["status"] = "completed"
            self._sse_data(run_id, "run.completed", output=output, usage={"input_tokens": 10, "output_tokens": 20, "total_tokens": 30})
            messages = MESSAGES.setdefault(session_id, [])
            messages.append({"id": f"m{uuid.uuid4().hex[:6]}", "role": "user", "content": text})
            messages.append({"id": f"m{uuid.uuid4().hex[:6]}", "role": "assistant", "content": output})
            session = _find_session(session_id)
            if session is not None:
                session["message_count"] = len(messages)
                session["last_active"] = time.time()
            self.wfile.write(b": stream closed\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _legacy_chat_stream(self, session_id):
        events = [
            ("run.started", {"run_id": "run-demo", "session_id": session_id}),
            ("assistant.delta", {"delta": "Connected to Hermes.", "message_id": "msg-demo"}),
            ("assistant.completed", {"content": "Connected to Hermes.", "session_id": session_id}),
            ("done", {}),
        ]
        payload = "".join(f"event: {name}\ndata: {json.dumps(data)}\n\n" for name, data in events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Mock Hermes host listening on http://0.0.0.0:{PORT}", flush=True)
    server.serve_forever()
