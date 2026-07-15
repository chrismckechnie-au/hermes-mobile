#!/usr/bin/env python3
"""Read-only compatibility check for a Hermes host used by Hermes Mobile."""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import ssl
import sys
from dataclasses import asdict, dataclass
from typing import Any
from urllib import error, parse, request


EXIT_CORE_COMPATIBLE = 0
EXIT_LIMITED = 2
EXIT_AUTH_FAILURE = 3
EXIT_UNREACHABLE = 4
EXIT_USAGE = 64

DEFAULT_TIMEOUT_SECONDS = 5.0
MAX_RESPONSE_BYTES = 1024 * 1024

CORE_FEATURES = (
    "run_submission",
    "run_status",
    "run_events_sse",
    "run_stop",
    "run_approval_response",
    "tool_progress_events",
    "approval_events",
    "session_resources",
    "session_fork",
    "skills_api",
)

CORE_ENDPOINTS = {
    "health": ("GET", "/health"),
    "models": ("GET", "/v1/models"),
    "runs": ("POST", "/v1/runs"),
    "run_status": ("GET", "/v1/runs/{run_id}"),
    "run_events": ("GET", "/v1/runs/{run_id}/events"),
    "run_approval": ("POST", "/v1/runs/{run_id}/approval"),
    "run_stop": ("POST", "/v1/runs/{run_id}/stop"),
    "skills": ("GET", "/v1/skills"),
    "toolsets": ("GET", "/v1/toolsets"),
    "sessions": ("GET", "/api/sessions"),
    "session_create": ("POST", "/api/sessions"),
    "session": ("GET", "/api/sessions/{session_id}"),
    "session_update": ("PATCH", "/api/sessions/{session_id}"),
    "session_delete": ("DELETE", "/api/sessions/{session_id}"),
    "session_messages": ("GET", "/api/sessions/{session_id}/messages"),
    "session_fork": ("POST", "/api/sessions/{session_id}/fork"),
}


@dataclass
class Probe:
    name: str
    path: str
    ok: bool
    status: int | None = None
    detail: str = ""
    failure: str | None = None
    payload: Any = None

    def public(self) -> dict[str, Any]:
        value = asdict(self)
        value.pop("payload", None)
        return value


class NoRedirectHandler(request.HTTPRedirectHandler):
    """Never forward a bearer token through an HTTP redirect."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        raise error.HTTPError(req.full_url, code, "redirect refused", headers, fp)


def _redact(value: str, api_key: str) -> str:
    if api_key:
        value = value.replace(api_key, "[REDACTED]")
    return re.sub(r"(?i)bearer\s+[^\s,;]+", "Bearer [REDACTED]", value)


def _normalize_base_url(raw: str) -> str:
    raw = raw.strip()
    parsed = parse.urlsplit(raw)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("host URL must use http:// or https:// and include a hostname")
    if parsed.username or parsed.password:
        raise ValueError("credentials must not be embedded in the host URL")
    if parsed.query or parsed.fragment:
        raise ValueError("host URL must not contain a query string or fragment")
    path = parsed.path.rstrip("/")
    if path == "/v1":
        path = ""
    elif path:
        raise ValueError("host URL path must be empty or /v1")
    netloc = parsed.hostname
    if ":" in netloc and not netloc.startswith("["):
        netloc = f"[{netloc}]"
    if parsed.port is not None:
        netloc = f"{netloc}:{parsed.port}"
    return parse.urlunsplit((parsed.scheme, netloc, path, "", ""))


def _safe_relative_path(value: Any, fallback: str) -> str:
    path = value if isinstance(value, str) else fallback
    parsed = parse.urlsplit(path)
    if (
        not path.startswith("/")
        or path.startswith("//")
        or parsed.scheme
        or parsed.netloc
        or parsed.fragment
        or "{" in path
        or "}" in path
    ):
        return fallback
    return path


def _probe_json(
    opener: request.OpenerDirector,
    base_url: str,
    api_key: str,
    timeout: float,
    name: str,
    path: str,
) -> Probe:
    headers = {"Accept": "application/json", "User-Agent": "hermes-mobile-contract-check/1"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    req = request.Request(f"{base_url}{path}", headers=headers, method="GET")
    try:
        with opener.open(req, timeout=timeout) as response:
            status = int(response.status)
            content_type = response.headers.get_content_type()
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                return Probe(name, path, False, status, "JSON response exceeded 1 MiB", "invalid")
            try:
                payload = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                return Probe(
                    name,
                    path,
                    False,
                    status,
                    f"expected JSON; received {content_type or 'unknown content type'}",
                    "invalid",
                )
            return Probe(name, path, True, status, "JSON response received", payload=payload)
    except error.HTTPError as exc:
        content_type = exc.headers.get_content_type() if exc.headers else "unknown content type"
        failure = "auth" if exc.code in {401, 403} else "http"
        return Probe(name, path, False, exc.code, f"HTTP {exc.code}; received {content_type}", failure)
    except (socket.timeout, TimeoutError):
        return Probe(name, path, False, detail=f"request timed out after {timeout:g}s", failure="network")
    except error.URLError as exc:
        reason = exc.reason
        if isinstance(reason, (socket.timeout, TimeoutError)):
            detail = f"request timed out after {timeout:g}s"
        elif isinstance(reason, ssl.SSLError):
            detail = "TLS verification or negotiation failed"
        else:
            detail = f"connection failed ({type(reason).__name__})"
        return Probe(name, path, False, detail=detail, failure="network")
    except OSError as exc:
        return Probe(name, path, False, detail=f"connection failed ({type(exc).__name__})", failure="network")


def _endpoint_matches(endpoints: dict[str, Any], name: str, method: str, path: str) -> bool:
    advertised = endpoints.get(name)
    return bool(
        isinstance(advertised, dict)
        and str(advertised.get("method", "")).upper() == method
        and advertised.get("path") == path
    )


def _mobile_extension(capabilities: dict[str, Any]) -> dict[str, Any]:
    extensions = capabilities.get("extensions")
    if not isinstance(extensions, dict):
        return {}
    value = extensions.get("hermes.mobile")
    return value if isinstance(value, dict) else {}


def check_host(base_url: str, api_key: str, timeout: float) -> tuple[int, dict[str, Any]]:
    opener = request.build_opener(NoRedirectHandler())
    probes: list[Probe] = []
    cap_probe = _probe_json(opener, base_url, api_key, timeout, "capabilities", "/v1/capabilities")
    probes.append(cap_probe)

    report: dict[str, Any] = {
        "schema": "hermes-mobile-contract-check/1",
        "host": base_url,
        "classification": "limited",
        "core": {"compatible": False, "missing_features": [], "invalid_endpoints": []},
        "mobile_extension": {"version": None, "features": {}},
        "probes": [],
    }

    if not cap_probe.ok:
        if cap_probe.failure == "auth":
            code, classification = EXIT_AUTH_FAILURE, "auth-failure"
        elif cap_probe.failure == "network":
            code, classification = EXIT_UNREACHABLE, "unreachable"
        else:
            code, classification = EXIT_LIMITED, "limited"
        report["classification"] = classification
        report["probes"] = [probe.public() for probe in probes]
        return code, report

    capabilities = cap_probe.payload
    if not isinstance(capabilities, dict):
        cap_probe.ok = False
        cap_probe.detail = "capabilities payload must be a JSON object"
        cap_probe.failure = "invalid"
        report["probes"] = [probe.public() for probe in probes]
        return EXIT_LIMITED, report

    features = capabilities.get("features")
    features = features if isinstance(features, dict) else {}
    endpoints = capabilities.get("endpoints")
    endpoints = endpoints if isinstance(endpoints, dict) else {}

    missing_features = [name for name in CORE_FEATURES if features.get(name) is not True]
    invalid_endpoints = [
        name
        for name, (method, path) in CORE_ENDPOINTS.items()
        if not _endpoint_matches(endpoints, name, method, path)
    ]
    if capabilities.get("platform") != "hermes-agent":
        missing_features.append("platform=hermes-agent")

    health_path = _safe_relative_path(
        endpoints.get("health", {}).get("path") if isinstance(endpoints.get("health"), dict) else None,
        "/health",
    )
    model_path = _safe_relative_path(
        endpoints.get("models", {}).get("path") if isinstance(endpoints.get("models"), dict) else None,
        "/v1/models",
    )
    health_probe = _probe_json(opener, base_url, api_key, timeout, "health", health_path)
    probes.append(health_probe)
    model_probe = _probe_json(opener, base_url, api_key, timeout, "models", model_path)
    probes.append(model_probe)

    if health_probe.ok and not isinstance(health_probe.payload, dict):
        health_probe.ok = False
        health_probe.detail = "health payload must be a JSON object"
        health_probe.failure = "invalid"
    if model_probe.ok:
        payload = model_probe.payload
        rows = payload.get("data") if isinstance(payload, dict) else None
        if not (
            isinstance(rows, list)
            and rows
            and all(isinstance(row, dict) and isinstance(row.get("id"), str) and row["id"] for row in rows)
        ):
            model_probe.ok = False
            model_probe.detail = "models payload must contain a non-empty data list with model ids"
            model_probe.failure = "invalid"

    core_probe_auth = any(probe.failure == "auth" for probe in (health_probe, model_probe))
    core_probe_network = any(probe.failure == "network" for probe in (health_probe, model_probe))

    mobile = _mobile_extension(capabilities)
    mobile_features = mobile.get("features") if isinstance(mobile.get("features"), dict) else {}
    mobile_endpoints = mobile.get("endpoints") if isinstance(mobile.get("endpoints"), dict) else {}
    mobile_version = mobile.get("version") if isinstance(mobile.get("version"), str) else None
    if mobile_version and not re.fullmatch(r"\d+\.\d+", mobile_version):
        mobile_version = f"invalid ({mobile_version})"

    optional_specs = (
        ("active_run_list", ("active_run_list", "active_session_registry"), "active_sessions", "GET", "/v1/active-sessions", True),
        ("push_wake_registration", ("push_wake_registration", "mobile_notifications"), "mobile_device", "PUT", "/v1/mobile/devices/{installation_id}", False),
        ("host_update", ("host_update", "host_update_api"), "host_update", "GET", "/v1/host-update", True),
        ("event_replay", ("event_replay",), None, None, None, False),
        ("idempotent_run_submission", ("idempotent_run_submission",), None, None, None, False),
        ("pairing", ("pairing", "mobile_pairing"), "mobile_pairing", None, None, False),
        ("scoped_device_tokens", ("scoped_device_tokens",), None, None, None, False),
        ("complete_model_inventory", ("complete_model_inventory",), "models", "GET", "/v1/models", False),
        ("run_reasoning_effort", ("run_reasoning_effort",), None, None, None, False),
    )
    optional_failures: list[str] = []
    optional_report: dict[str, Any] = {}

    for label, flags, endpoint_name, expected_method, default_path, safe_get in optional_specs:
        target_enabled = any(mobile_features.get(flag) is True for flag in flags)
        legacy_enabled = any(features.get(flag) is True for flag in flags)
        endpoint_value = None
        if endpoint_name:
            endpoint_value = mobile_endpoints.get(endpoint_name, endpoints.get(endpoint_name))
        # The stock models endpoint is not evidence of the stronger complete
        # inventory contract; that extension must opt in explicitly.
        endpoint_advertised = isinstance(endpoint_value, dict) and label != "complete_model_inventory"
        advertised = target_enabled or legacy_enabled or endpoint_advertised
        item: dict[str, Any] = {"advertised": advertised, "probe": "not-advertised"}
        if advertised:
            item["probe"] = "not-probed"
            if endpoint_name and expected_method and default_path:
                method = endpoint_value.get("method") if isinstance(endpoint_value, dict) else expected_method
                path = endpoint_value.get("path") if isinstance(endpoint_value, dict) else default_path
                endpoint_valid = str(method).upper() == expected_method and path == default_path
                item["endpoint_valid"] = endpoint_valid
                if not endpoint_valid:
                    item["probe"] = "invalid-advertisement"
                    optional_failures.append(label)
                elif safe_get:
                    safe_path = _safe_relative_path(path, default_path)
                    probe = _probe_json(opener, base_url, api_key, timeout, label, safe_path)
                    probes.append(probe)
                    item["probe"] = "passed" if probe.ok else "failed"
                    if not probe.ok:
                        optional_failures.append(label)
                else:
                    item["probe"] = "not-probed-mutating"
        optional_report[label] = item

    core_ok = not missing_features and not invalid_endpoints and health_probe.ok and model_probe.ok
    report["core"] = {
        "compatible": core_ok,
        "missing_features": missing_features,
        "invalid_endpoints": invalid_endpoints,
    }
    report["mobile_extension"] = {
        "version": mobile_version,
        "features": optional_report,
    }

    if core_probe_auth:
        code, classification = EXIT_AUTH_FAILURE, "auth-failure"
    elif core_probe_network:
        code, classification = EXIT_UNREACHABLE, "unreachable"
    elif core_ok and not optional_failures:
        code, classification = EXIT_CORE_COMPATIBLE, "core-compatible"
    else:
        code, classification = EXIT_LIMITED, "limited"
    report["classification"] = classification
    report["probes"] = [probe.public() for probe in probes]
    return code, report


def _human_report(report: dict[str, Any]) -> str:
    labels = {
        "core-compatible": "CORE COMPATIBLE",
        "limited": "LIMITED OR MISCONFIGURED",
        "auth-failure": "AUTHENTICATION FAILED",
        "unreachable": "UNREACHABLE",
    }
    lines = [
        "Hermes Mobile contract check",
        f"Host: {report['host']}",
        f"Result: {labels.get(report['classification'], report['classification'])}",
    ]
    core = report["core"]
    if core["missing_features"]:
        lines.append("Missing core features: " + ", ".join(core["missing_features"]))
    if core["invalid_endpoints"]:
        lines.append("Missing/invalid core endpoints: " + ", ".join(core["invalid_endpoints"]))
    lines.append("Probes:")
    for probe in report["probes"]:
        status = f"HTTP {probe['status']}" if probe["status"] is not None else "no response"
        marker = "ok" if probe["ok"] else "failed"
        lines.append(f"  - {probe['name']}: {marker} ({status}) - {probe['detail']}")
    optional = report["mobile_extension"]["features"]
    if optional:
        lines.append("Optional mobile extensions:")
        for name, item in optional.items():
            lines.append(f"  - {name}: {item['probe']}")
    lines.append("No write, stop, approval, update, pairing, or registration request was sent.")
    return "\n".join(lines)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.getenv("HERMES_API_URL"), help="Hermes root URL (or HERMES_API_URL)")
    parser.add_argument(
        "--api-key",
        default=os.getenv("HERMES_API_KEY", os.getenv("API_SERVER_KEY", "")),
        help="Bearer key (or HERMES_API_KEY/API_SERVER_KEY); never printed",
    )
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS, help="Per-request timeout in seconds")
    parser.add_argument("--json", action="store_true", help="Emit redacted JSON diagnostics")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    api_key = args.api_key or ""
    if not args.url:
        print("error: --url or HERMES_API_URL is required", file=sys.stderr)
        return EXIT_USAGE
    if not (0.05 <= args.timeout <= 30.0):
        print("error: --timeout must be between 0.05 and 30 seconds", file=sys.stderr)
        return EXIT_USAGE
    try:
        base_url = _normalize_base_url(args.url)
        code, report = check_host(base_url, api_key, args.timeout)
        output = json.dumps(report, indent=2, sort_keys=True) if args.json else _human_report(report)
        print(_redact(output, api_key))
        return code
    except ValueError as exc:
        print(_redact(f"error: {exc}", api_key), file=sys.stderr)
        return EXIT_USAGE
    except Exception as exc:  # defensive CLI boundary; never expose credentials
        print(_redact(f"error: compatibility check failed ({type(exc).__name__})", api_key), file=sys.stderr)
        return EXIT_UNREACHABLE


if __name__ == "__main__":
    raise SystemExit(main())
