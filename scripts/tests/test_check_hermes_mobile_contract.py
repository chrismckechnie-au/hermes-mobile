from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import threading
import time
import unittest
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Iterator


SCRIPT = Path(__file__).parents[1] / "check_hermes_mobile_contract.py"
TEST_KEY = "test-contract-secret"


CORE_FEATURES = {
    "run_submission": True,
    "run_status": True,
    "run_events_sse": True,
    "run_stop": True,
    "run_approval_response": True,
    "tool_progress_events": True,
    "approval_events": True,
    "session_resources": True,
    "session_fork": True,
    "skills_api": True,
}

CORE_ENDPOINTS = {
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
}


def stock_capabilities() -> dict[str, Any]:
    return {
        "object": "hermes.api_server.capabilities",
        "platform": "hermes-agent",
        "features": dict(CORE_FEATURES),
        "endpoints": dict(CORE_ENDPOINTS),
    }


def json_response(body: Any, status: int = 200, delay: float = 0.0) -> dict[str, Any]:
    return {
        "status": status,
        "body": json.dumps(body).encode(),
        "content_type": "application/json",
        "delay": delay,
    }


@contextmanager
def fake_host(
    routes: dict[str, dict[str, Any]],
    *,
    require_key: bool = True,
) -> Iterator[str]:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if require_key and self.headers.get("Authorization") != f"Bearer {TEST_KEY}":
                self._send(json_response({"error": "unauthorized"}, 401))
                return
            self._send(routes.get(self.path, json_response({"error": "not found"}, 404)))

        def _send(self, spec: dict[str, Any]) -> None:
            if spec.get("delay"):
                time.sleep(spec["delay"])
            try:
                self.send_response(spec["status"])
                self.send_header("Content-Type", spec.get("content_type", "application/json"))
                self.end_headers()
                self.wfile.write(spec.get("body", b""))
            except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
                pass

        def log_message(self, format: str, *args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def core_routes(capabilities: dict[str, Any] | None = None) -> dict[str, dict[str, Any]]:
    return {
        "/v1/capabilities": json_response(capabilities or stock_capabilities()),
        "/health": json_response({"status": "ok", "platform": "hermes-agent"}),
        "/v1/models": json_response({"object": "list", "data": [{"id": "hermes-agent"}]}),
    }


def run_checker(url: str, *args: str, key: str = TEST_KEY, use_env: bool = False) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    command = [sys.executable, str(SCRIPT)]
    if use_env:
        env["HERMES_API_URL"] = url
        env["HERMES_API_KEY"] = key
    else:
        command.extend(["--url", url, "--api-key", key])
    command.extend(args)
    return subprocess.run(command, capture_output=True, text=True, timeout=8, env=env, check=False)


class ContractCheckerTest(unittest.TestCase):
    def test_stock_official_core_is_compatible_without_mobile_extensions(self) -> None:
        with fake_host(core_routes()) as url:
            result = run_checker(url, "--json")

        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual("core-compatible", report["classification"])
        self.assertTrue(report["core"]["compatible"])
        self.assertFalse(report["mobile_extension"]["features"]["active_run_list"]["advertised"])
        self.assertFalse(report["mobile_extension"]["features"]["complete_model_inventory"]["advertised"])

    def test_extended_host_probes_read_only_optional_endpoints(self) -> None:
        capabilities = stock_capabilities()
        capabilities["features"].update(
            {
                "active_session_registry": True,
                "mobile_notifications": True,
                "host_update_api": True,
                "run_reasoning_effort": True,
            }
        )
        capabilities["endpoints"].update(
            {
                "active_sessions": {"method": "GET", "path": "/v1/active-sessions"},
                "mobile_device": {"method": "PUT", "path": "/v1/mobile/devices/{installation_id}"},
                "host_update": {"method": "GET", "path": "/v1/host-update"},
            }
        )
        routes = core_routes(capabilities)
        routes["/v1/active-sessions"] = json_response({"data": []})
        routes["/v1/host-update"] = json_response({"supported": True, "update_available": False})

        with fake_host(routes) as url:
            result = run_checker(url, "--json", use_env=True)

        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        features = report["mobile_extension"]["features"]
        self.assertEqual("passed", features["active_run_list"]["probe"])
        self.assertEqual("passed", features["host_update"]["probe"])
        self.assertEqual("not-probed-mutating", features["push_wake_registration"]["probe"])

    def test_auth_failure_has_distinct_exit_code(self) -> None:
        with fake_host(core_routes()) as url:
            result = run_checker(url, "--json", key="wrong-key")

        self.assertEqual(3, result.returncode)
        self.assertEqual("auth-failure", json.loads(result.stdout)["classification"])

    def test_missing_required_flag_is_limited(self) -> None:
        capabilities = stock_capabilities()
        del capabilities["features"]["run_events_sse"]
        with fake_host(core_routes(capabilities)) as url:
            result = run_checker(url, "--json")

        self.assertEqual(2, result.returncode)
        report = json.loads(result.stdout)
        self.assertIn("run_events_sse", report["core"]["missing_features"])

    def test_timeout_is_unreachable(self) -> None:
        routes = core_routes()
        routes["/v1/capabilities"] = json_response(stock_capabilities(), delay=0.25)
        with fake_host(routes) as url:
            result = run_checker(url, "--json", "--timeout", "0.05")

        self.assertEqual(4, result.returncode)
        self.assertEqual("unreachable", json.loads(result.stdout)["classification"])

    def test_refused_connection_is_unreachable(self) -> None:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        result = run_checker(f"http://127.0.0.1:{port}", "--json", "--timeout", "0.1")

        self.assertEqual(4, result.returncode)
        self.assertEqual("unreachable", json.loads(result.stdout)["classification"])

    def test_html_error_and_diagnostics_never_reveal_key(self) -> None:
        routes = core_routes()
        routes["/v1/capabilities"] = {
            "status": 520,
            "body": f"<html><body>filename={TEST_KEY}</body></html>".encode(),
            "content_type": "text/html",
        }
        with fake_host(routes) as url:
            human = run_checker(url)
            machine = run_checker(url, "--json")

        self.assertEqual(2, human.returncode)
        self.assertEqual(2, machine.returncode)
        combined = human.stdout + human.stderr + machine.stdout + machine.stderr
        self.assertNotIn(TEST_KEY, combined)
        self.assertIn("HTTP 520", combined)
        self.assertIn("text/html", combined)


if __name__ == "__main__":
    unittest.main()
