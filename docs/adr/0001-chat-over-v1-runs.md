# Chat rides the /v1/runs transport, not the session chat stream

The Hermes API exposes two ways to run an agent turn: `POST /api/sessions/{id}/chat/stream` (server loads Session history, but wires no approval callback and never registers the agent for stop) and `POST /v1/runs` + `GET /v1/runs/{run_id}/events` (approval events, stop, and status — but the client must supply conversation history itself). We moved all chat to `/v1/runs` because Approvals and stop are impossible on the session stream: an approval-gated tool hangs the app with no signal. The cost — the client sends `session_id` plus a client-built history projection each turn — was accepted over maintaining two transports.

## Consequences

- The client, not the Host, builds the history sent with each Run: a projection filtered to `user`/`assistant` roles with non-blank content (tool messages don't survive the Host's `{role, content}` reduction). Long Sessions mean heavier request payloads.
- Run-events SSE uses `data:`-only framing (event name inside the JSON `event` field) and cannot be resumed after disconnect; the client reconciles by polling run status until terminal and reloading Session messages, adopting the resolved `session_id` the messages envelope returns (compression-triggered session rotation).
- Stop is not terminal: `POST /stop` returns `stopping`; the client keeps consuming events/status until `run.completed`/`run.failed`/`run.cancelled`.
- Agent Runs are enabled only when the Host advertises the full control bundle — `run_submission`, `run_events_sse`, `run_stop`, `approval_events`, `run_approval_response` — because a partial bundle recreates the silent-approval hang this decision exists to eliminate. Hosts without the bundle are unsupported (clear error, no fallback path).
