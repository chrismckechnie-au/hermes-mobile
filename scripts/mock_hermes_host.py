#!/usr/bin/env python3
"""Small contract-faithful Hermes API host for Android UI verification."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
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

    def do_GET(self):
        if not self._auth():
            return
        path = self.path.split("?", 1)[0]
        if path == "/v1/capabilities":
            self._json(200, {
                "object": "hermes.api_server.capabilities",
                "platform": "hermes-agent",
                "model": "hermes-agent",
                "features": {
                    "session_list": True,
                    "session_create": True,
                    "session_chat_stream": True,
                    "run_approval": True,
                    "jobs": True,
                },
            })
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
        else:
            self._json(404, {"error": {"message": "Not found"}})

    def do_POST(self):
        if not self._auth():
            return
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        path = self.path.split("?", 1)[0]
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
        elif path.startswith("/api/sessions/") and path.endswith("/chat/stream"):
            session_id = path.split("/")[3]
            events = [
                ("run.started", {"run_id": "run-demo", "session_id": session_id}),
                ("assistant.delta", {"delta": "Connected to ", "message_id": "msg-demo"}),
                ("tool.started", {"tool_name": "host_probe", "preview": "Checking Hermes capabilities"}),
                ("tool.completed", {"tool_name": "host_probe", "preview": "Capabilities confirmed"}),
                ("assistant.delta", {"delta": "Hermes. Host selection and streaming are working.", "message_id": "msg-demo"}),
                ("assistant.completed", {"content": "Connected to Hermes. Host selection and streaming are working.", "session_id": session_id}),
                ("run.completed", {"completed": True, "session_id": session_id}),
                ("done", {}),
            ]
            payload = "".join(f"event: {name}\ndata: {json.dumps(data)}\n\n" for name, data in events).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            self._json(404, {"error": {"message": "Not found"}})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Mock Hermes host listening on http://0.0.0.0:{PORT}", flush=True)
    server.serve_forever()
