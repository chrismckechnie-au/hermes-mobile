# Hermes Mobile host compatibility contract

Status: **client compatibility guide and upstream proposal**. This document does
not claim that the proposed mobile extension is implemented by stock Hermes.

The stock reference below was checked against Nous Research's official
`hermes-agent` `main` at commit
[`f5c2ea49`](https://github.com/NousResearch/hermes-agent/blob/f5c2ea49a4716ccd377c99511ee1bb9be1b56275/gateway/platforms/api_server.py#L1624-L1702)
on 2026-07-15. Hermes evolves quickly; clients must negotiate
`GET /v1/capabilities` rather than infer support from a version string.

## Compatibility levels

### Stock core

A core-compatible host can connect, list models, manage sessions, submit and
observe independent runs, stop work, answer approvals, and list skills and
toolsets. The checker requires these stock feature flags to be exactly `true`:

- `run_submission`, `run_status`, `run_events_sse`, and `run_stop`
- `run_approval_response`, `approval_events`, and `tool_progress_events`
- `session_resources` and `session_fork`
- `skills_api`

It also requires the endpoint declarations currently advertised by stock
Hermes for health, models, runs, approvals, skills, toolsets, and session CRUD,
messages, and fork. It performs read-only requests to `/health` and
`/v1/models`; it does not submit a run or alter a session.

When a Run completes, the event may include `usage.input_tokens`,
`usage.output_tokens`, and `usage.total_tokens`. Hermes Mobile displays these
counts only when the reconciled final assistant message exactly matches the
terminal event output; it never guesses a usage-to-message association.

The following stock flags are deliberately `false` and are not required for
core compatibility:

- `admin_config_rw`
- `jobs_admin`
- `memory_write_api`
- `audio_api`
- `realtime_voice`

Stock `/v1/models` advertises the API server's default model plus configured
`model_routes` aliases. It is **not a complete inventory of every model or
profile enabled elsewhere on the user's Hermes installation**.

### Optional mobile extensions

Hermes Mobile may discover the following experimental host additions. They are
not part of the stock official contract at the reference commit and must never
be assumed solely because a host is reachable.

| Capability | Current experimental name | Endpoint | Stock support |
| --- | --- | --- | --- |
| Active work recovery | `active_session_registry` | `GET /v1/active-sessions` | Not implemented |
| Push wake registration | `mobile_notifications` | `PUT`/`DELETE /v1/mobile/devices/{installation_id}` | Not implemented |
| Per-run reasoning | `run_reasoning_effort` | Run request field | Not implemented |
| Host self-update | `host_update_api` | `GET`/`POST /v1/host-update` | Not implemented |
| Scheduled-job administration | No stable stock flag | `/api/jobs...` | Not implemented in the stable API server |
| Complete model inventory | No stock flag | Extended `/v1/models` contract | Not implemented |

An absent optional flag means “unsupported”, not “empty”. If a host advertises
an optional read-only endpoint, the checker probes it. Device registration,
updates, approvals, stops, pairing, and all other writes are intentionally
never sent by the checker.

## Proposed `hermes.mobile` 1.0 extension

Everything in this section is a **draft upstream requirement and is not yet a
stock Hermes API**. A future host should advertise it under a namespaced entry
so existing flat capability consumers continue to work:

```json
{
  "extensions": {
    "hermes.mobile": {
      "version": "1.0",
      "features": {
        "active_run_list": true,
        "event_replay": true,
        "idempotent_run_submission": true,
        "pairing": true,
        "scoped_device_tokens": true,
        "push_wake_registration": true,
        "complete_model_inventory": true
      },
      "endpoints": {}
    }
  }
}
```

The version is `MAJOR.MINOR`. Clients may use an equal major version and must
feature-negotiate minor additions. Unknown fields and unknown optional features
must be ignored.

The 1.0 behavior target is:

1. **Active run list.** A read-only authenticated endpoint returns every
   nonterminal run visible to the device, with stable run and session IDs,
   state, timestamps, pending-approval state, and an optional safe status
   summary. It must work for runs started by other clients.
2. **Replayable events.** Every run event has a stable monotonic sequence/cursor.
   Reconnect accepts an `after` cursor, replays retained events in order, and
   then follows live events without gaps. Duplicate delivery is allowed; event
   identity must let clients de-duplicate it. Terminal state remains queryable
   for a documented retention period.
3. **Idempotent submission.** `POST /v1/runs` accepts `Idempotency-Key`. Repeating
   an identical request returns the same run; reusing a key for a different
   body returns a conflict and never starts a second run.
4. **Pairing and scoped credentials.** A short-lived, single-use pairing grant
   can be encoded in a QR/deep link and exchanged for a revocable device token.
   The token contains explicit least-privilege scopes, expiry/rotation metadata,
   and a stable device ID. The long-lived server bearer key is never put in the
   QR code or returned to the phone.
5. **Push wake registration.** Upsert and delete are idempotent and bind an
   opaque installation ID and push token to the scoped device credential. Push
   payloads contain only host/run/session identifiers, coarse state, and a
   wake/reconcile signal—never prompts, replies, tool arguments/output,
   terminal commands, credentials, file paths, or private reasoning.
6. **Complete model inventory.** The model resource lists every model/profile
   the authenticated device may actually select, using a stable public ID and
   display label. It declares supported reasoning-effort values and relevant
   input capabilities without exposing provider keys or secret configuration.

Endpoints and schemas beyond these minimum behaviors should be finalized in an
official Hermes proposal before the mobile client treats `hermes.mobile` 1.x as
stable.

## Client and host security requirements

- Use TLS or a trusted private VPN. Plain HTTP exposes bearer credentials and
  agent traffic to that network.
- Never forward credentials across redirects. The checker refuses all
  redirects; the Android client must at least refuse cross-origin and HTTPS to
  HTTP redirects.
- Keep API keys and device tokens out of URLs, logs, crash reports, JSON
  diagnostics, notifications, and analytics.
- Treat capability declarations as untrusted input: accept only same-origin
  relative paths and bound connection, read, response-size, and retry limits.
- Administrative actions, updates, and credential changes require a distinct
  capability, explicit confirmation, and stronger/recent authentication.
- Only show host-provided safe status summaries. Do not request or expose hidden
  chain-of-thought.
- An HTML response is not a Hermes event or filename. In particular, an HTML
  `520` response normally comes from a proxy/CDN failure; clients should show
  the status and content type, preserve the draft, and offer retry rather than
  rendering the page as chat content.

## Run the read-only checker

```bash
python scripts/check_hermes_mobile_contract.py \
  --url https://hermes.example.com \
  --api-key "$HERMES_API_KEY"
```

PowerShell:

```powershell
$env:HERMES_API_URL = "https://hermes.example.com"
$env:HERMES_API_KEY = "your-api-server-key"
python scripts/check_hermes_mobile_contract.py --json
```

`API_SERVER_KEY` is also accepted for local host administration workflows. The
key is never included in human or JSON output. TLS verification is enabled,
redirects are refused, responses are capped at 1 MiB, and the per-request
timeout defaults to five seconds (`--timeout`, maximum 30 seconds).

Exit codes:

| Code | Meaning |
| ---: | --- |
| `0` | Stock core compatible; absent optional mobile extensions are allowed |
| `2` | Reachable but limited, malformed, or inconsistently advertised |
| `3` | Bearer authentication failed (`401`/`403`) |
| `4` | Unreachable, timed out, or TLS connection failed |
| `64` | Invalid command-line input |

Run the checker tests with only the Python standard library:

```bash
python -m unittest discover -s scripts/tests -v
```
