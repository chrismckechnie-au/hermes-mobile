# Plan: Slash commands, Run stop, Approvals, Session management

_Locked via grill-with-docs — by Claude + Chris. Terms per CONTEXT.md. Revised after Codex review rounds 1–4._

## Goal

Give Hermes Mobile a slash-command composer and full Run control. Typing `/` opens a unified menu of Local Commands and Host Skills; every agent turn becomes a Run that can be stopped and that surfaces Approvals inline instead of hanging silently. Sessions gain rename, fork, and delete from both touch and slash surfaces.

## Run event contract (verified against host source, `api_server.py`)

`GET /v1/runs/{run_id}/events` is `data:`-only SSE (event name inside JSON `event` field; `: keepalive` comments every 30s; stream closes with a `: stream closed` comment; queue is single-consumer and destroyed on disconnect — no resume). Events:

| event | fields |
|---|---|
| `message.delta` | `delta` |
| `tool.started` | `tool`, `preview` |
| `tool.completed` | `tool`, `duration`, `error` (bool) |
| `reasoning.available` | `text` |
| `approval.request` | `command` (redacted), `choices: [once, session, always, deny]`, run status → `waiting_for_approval` |
| `approval.responded` | `choice`, `resolved` (another client may resolve the Approval — reconcile the card from this event) |
| `run.completed` | `output`, `usage.input_tokens`, `usage.output_tokens`, `usage.total_tokens` (terminal) |
| `run.failed` | `error` (terminal) |
| `run.cancelled` | — (terminal) |

All events carry `run_id`/`timestamp`. Unknown events are ignored. Contract fixtures for every row above go in `HermesHttpGatewayTest.kt` and the mock host. Note `tool` (not `tool_name`) and that `tool.completed` has no preview — different from the old session-stream contract.

## Approach

1. **Docs first (done)**: CONTEXT.md glossary, ADR 0001 (transport), this plan.
2. **Transport** (`HermesModels.kt`, `HermesHttpGateway.kt`): replace `streamSessionChat` with:
   - `submitRun(host, sessionId, input, history)` → `POST /v1/runs` (`session_id`, `conversation_history`, `input`). History is filtered to `user`/`assistant` roles with non-blank content (tool messages don't round-trip the `{role, content}` reduction). **Never auto-retry a submission** — if the response is lost the turn may already be executing; reconcile by reloading Session messages.
   - `streamRunEvents(host, runId, onEvent)` — parser per the contract table.
   - `getRunStatus(host, runId)`, `respondApproval(host, runId, choice)`, `stopRun(host, runId)`, `listSkills`, `renameSession`, `deleteSession`, `forkSession` (fork response's child is selected directly; session list refresh uses `include_children=true` so forked children appear — host defaults it to false).
3. **ViewModel** (`HermesViewModel.kt`):
   - **Test seams**: convert `HermesViewModel` from `AndroidViewModel` to a plain `ViewModel(gateway, hostStore, savedStateHandle, dispatcher)`; a production `ViewModelProvider.Factory` in `MainActivity` constructs `SecureHostStore(applicationContext)` and `HermesHttpGateway()`, and obtains the `SavedStateHandle` via `CreationExtras.createSavedStateHandle()` (a plain factory does not receive saved state automatically — recreation is tested through this same factory path). JVM unit tests inject fakes directly — no `Application`, no Robolectric dependency needed.
   - **Run lifecycle**: immutable `RunContext(hostSnapshot: HostProfile, sessionId, runId)` — the full authenticated Host profile is snapshotted so Stop/approval/reconciliation keep working even if the profile is edited or deleted mid-run; as a second guard, editing/deleting a Host with an active Run is blocked. Run transcript state is keyed by the RunContext's session id, not "whatever is displayed": events keep applying while the user browses other Sessions, and a persistent run banner exposes the Approval card and Stop from anywhere — the banner always names the originating Session (title/id) with a "Return to Session" action, so an approval is never granted under the impression it belongs to the visible Session. Switching Hosts during an active Run is blocked (an uncontrollable orphan Run otherwise). Snapshot the history payload **before** the optimistic user item is appended; the new text goes only in `input`. Refresh history from `GET /api/sessions/{id}/messages` immediately before submit (narrows, doesn't eliminate, the multi-writer staleness window — accepted residual risk; a Host-side revision check is out of scope); the **resolved session id from that pre-submit load is what goes into `RunContext` and `submitRun`** — never the possibly-rotated original. **Process death**: `SavedStateHandle` persists the non-secret Run coordinates (hostId, sessionId, runId); on recreation the Host profile is re-resolved from the store by id, run status is polled, and Stop is offered (approval details are unrecoverable after event loss — same "pending on host" surface). **Secrets**: `RunContext` holds the authenticated profile privately and is never stringified; logs carry only host/session/run ids.
   - **Reconciliation**: after a terminal **event** (`run.completed`/`failed`/`cancelled`), reload Session messages from the Host — final output alone can't reconstruct tool interleaving. **Stop is not terminal**: `POST /stop` returns `stopping`; keep consuming the stream (or polling) until a terminal event/status arrives, then reload. `loadMessages` returns the envelope's resolved `session_id` (`api_server.py:1821-1826`) along with the messages; the resolved id is adopted as `activeSessionId` **only when the user is still displaying the Session that was loaded** — a background Run completing must re-key its transcript state without hijacking navigation. On stream disconnect without a terminal event: poll `getRunStatus` with bounded backoff (2s doubling, cap 30s, give up after ~2 min) until terminal, then reload; if status is `waiting_for_approval` after event loss, the approval payload is unrecoverable — surface "approval pending on host; approve there or stop" with a Stop action only. **Lost submission response** (`submitRun` fails after the request may have been accepted): enter an outcome-unknown state — send disabled, baseline message ids/count captured pre-submit, poll Session messages with bounded backoff for **new messages beyond the baseline** (an older identical text is not evidence; the run is treated as concluded only when a new user+assistant pair past the baseline appears). Transcript shape alone never auto-unlocks sending (the Host persists messages during intermediate tool steps, so a new pair doesn't prove the Run is terminal): detected evidence is displayed, but the lock is released only by a known terminal Run status or an explicit user acknowledgement ("Resume" on evidence / "Send anyway" on timeout) — never a silent re-enable, never an auto-resubmit.
   - **Approvals**: one actionable Approval card per Run (the endpoint has no approval id and resolves a FIFO queue entry — multiple live cards could authorize the wrong command). Responding posts `once`/`session`/`deny`; the card is marked resolved only on a successful response **or** a matching `approval.responded` event (another client — Discord, CLI — may resolve it first). UI label for `session` is **"Allow for this run"** (host keys the grant by run id — CONTEXT.md updated).
   - **Slash dispatch** in `sendMessage()`: reserved Local Command registry (exact first-token match, parsed before any transcript mutation): `/new`, `/rename <title>`, `/fork`, `/delete`, `/stop`. `/delete` triggers the same confirm dialog as the touch path. During an active Run the composer stays enabled but only `/stop` executes; other input gets a "run in progress" notice. Skill selection sends `Use the <name> skill: <args>` as chat. Suggestions are **typed actions** (`LocalCommand` vs `Skill`): tapping a menu row dispatches that action directly, never re-parsing slash text — so a Skill named `new` stays reachable from its menu row even though typed `/new` resolves to the reserved Local Command.
   - **Session actions**: rename/fork/delete disabled while that Session has an active Run. Delete of the current Session returns to deck. Skills fetched on connect, cached per Host.
   - **Capability gating**: agent Runs require the complete control bundle — `run_submission` + `run_events_sse` + `run_stop` + `approval_events` + `run_approval_response` — otherwise a Host could still start a Run that waits invisibly on an approval with no escape hatch (the exact failure this plan exists to fix). Per-feature flags for the rest: slash skills section `skills_api`; rename/delete `session_resources`; fork `session_fork`. Missing flag → action hidden or clear error.
   - **Observability**: single-tag structured Logcat lines keyed by run id: submission, status transitions, disconnect/reconcile outcome, approval decision, stop result. Redact command payloads.
4. **UI** (`HermesApp.kt` Composer, `DeckScreen.kt`): suggestion panel above composer on `/` (Local Commands pinned, Skills filtered); inline Approval card (Deny / Allow once / Allow for this run); stop affordance while a Run is active; long-press session row → bottom sheet Rename / Fork / Delete-with-confirm.
5. **Mock host** (`scripts/mock_hermes_host.py`): `/v1/skills`, `/v1/runs` submit/events/approval/stop emitting the exact contract table above (approval fixture on input containing "approve-me"), `PATCH/DELETE /api/sessions/{id}`, `POST /api/sessions/{id}/fork` (child + branched source, honoring `include_children`), updated capability flags.
6. **Tests**: gateway contract fixtures per event row; request shapes; ViewModel (via injected fakes): slash dispatch, duplicate-user-message guard, run-events-apply-while-browsing-other-session, host-switch-blocked-during-run, approval single-card flow incl. external `approval.responded` resolution, stop-waits-for-terminal, disconnect→poll→reconcile, lost-submission outcome-unknown flow (baseline evidence + send-anyway gate), resolved-session-id adoption (incl. no-navigation-hijack case), process-death recovery via SavedStateHandle, delete-navigation, capability-bundle gating, tool-heavy history filtering.

## Scope addition (user, post-review): model switching UI

`GET /v1/models` lists the Host default plus every selectable model from the user's authenticated provider inventory. Hermes flattens that inventory into per-request routes, preferring the current provider when multiple configured providers expose the same model id; explicit `model_routes` aliases still take precedence. `POST /v1/runs` accepts the selected `model`, while a session `/model` override always wins. Client: fetch models on connect, show a model chip in the chat header that opens a Model settings sheet (model + reasoning dropdowns), pass the selection as `model` on each run submission. Selection is in-memory per Host (no persistence — the Host default is always a safe fallback).

**Reasoning selection (second addition)** required a small Host patch — the API had no per-request reasoning hook (effort was global `agent.reasoning_effort` config; `model_routes` can't carry it). Patch: `POST /v1/runs` accepts optional `reasoning_effort` (validated via `hermes_constants.parse_reasoning_effort`, 400 on invalid), `_create_agent` takes a per-run `reasoning_override`, and capabilities advertise `run_reasoning_effort`. The client gates the selector on that flag, so a hermes-agent update that reverts the patch just hides the control instead of breaking sends.

## Key decisions & tradeoffs

- **All chat over `/v1/runs`** — see docs/adr/0001-chat-over-v1-runs.md. Approvals/stop are impossible on the session chat stream (no approval callback, agent never registered for stop — verified in host source). Cost: client supplies filtered history each turn.
- **Slash commands are client-side**: the API server does no slash parsing; menu maps to Local Commands (direct API calls) and Skills (chat-text invocation).
- **No "always" approval grant on mobile**; the `session` grant is labeled "Allow for this run" to match its real scope.
- **No fallback transport**: mock host learns `/v1/runs`; hosts missing capability flags get per-action gating/errors.
- **Rejected (host-contract changes, out of scope)**: idempotency keys for run submission, session-revision checks for stale history, approval ids, effective-session-id on completion. Mitigations above are client-side only.

## Risks / open questions

- Multi-writer history staleness: narrowed by pre-submit refresh, not eliminated. Accepted.
- Full-history payloads grow with Session length; accepted now, trim later if needed.
- `/v1/runs` turns persist to the Session store and seeded history is not duplicated — verified in host source (`run_agent.py:1890`, `:1848-1855`); smoke-checked in V2.

## Out of scope

Jobs admin, memory write API, voice/audio, host-side command parsing or contract changes, "always" approval grants.

## Verification

- V1: `./gradlew :app:testDebugUnitTest` green.
- V2 (contract, real host): trivial `/v1/runs` turn with `session_id` against the real Host; confirm events match the contract table and the turn appears in `GET /api/sessions/{id}/messages`.
- V3 (emulator, mock host): plain message streams; `/` menu appears and filters; `/new` local; approval fixture (card renders, Allow once resumes, Deny refuses, second approval only after first resolves); stop mid-run; disconnect reconciliation; long-press rename/fork/delete; fork lands on child session.
- V4: `:app:assembleDebug`, device install, end-to-end turn against real Host including a Skill invocation from the slash menu.
