# Plan Review Log: Hermes Mobile — slash commands, run stop, approvals, session management

Act 1 (grill-with-docs) complete — plan locked, CONTEXT.md and docs/adr/0001 created. MAX_ROUNDS=5.
Reviewer model: gpt-5.6-sol (user-pinned via -m). Thread 019f4c64-11b8-7a42-b92f-2de6da6a44f8.

## Round 1 — Codex

VERDICT: REVISE. 16 findings (abridged; full text in scratchpad codex-verdict.txt round 1):

1. Run event contract underspecified vs old session-stream names/fields.
2. Client-supplied history stale under multiple writers.
3. Tool-rich history can't round-trip the {role, content} reduction.
4. Optimistic user item can duplicate into conversation_history + input.
5. Single status poll can't recover a disconnected Run; approval details lost with the queue.
6. Successful Runs also need transcript reconciliation (tool interleaving, session rotation).
7. "Allow for session" is false domain language — host keys approvals by run_id.
8. Multiple pending Approval cards can authorize the wrong command (no approval id).
9. Global activeRunId races host/session switches.
10. Delete/Fork can race an active Run.
11. Forked Sessions invisible — GET /api/sessions defaults include_children=false.
12. Capability gating on run_submission alone is too little.
13. Slash grammar/safety undefined (confirm on /delete, collisions, /stop unusable if composer disabled).
14. Run submission not idempotent.
15. ViewModel lacks test seams (gateway/store hard-constructed).
16. No observability plan for lifecycle failures.

### Claude's response

Accepted 1, 3–13, 15, 16 in full: event contract table enumerated from host source (incl. `tool` vs `tool_name`, terminal events run.completed/failed/cancelled); history filtered to user/assistant; pre-append history snapshot; bounded-backoff polling + reload after every terminal event; "Allow for this run" relabel (CONTEXT.md updated); single actionable approval card per Run; immutable RunContext; delete/fork disabled during active Run; include_children=true + select fork child; per-action capability gating; reserved command registry with confirm-on-/delete and /stop-during-run; constructor-injected seams; runId-keyed structured logging.

Accepted partially: 2 (pre-submit history refresh narrows the window; host-side revision check rejected — host contract out of scope), 14 (never auto-retry submission + reconcile via reload; idempotency key rejected — host contract out of scope).

Rejected host-contract changes across findings (approval ids, effective-session-id on completion) — out of scope for a client-only change; logged in PLAN.md "Rejected" bullet.

## Round 2 — Codex

VERDICT: REVISE. Prior findings resolved except 8 new/sharpened ones:

1. Host/Session switch during a Run drops approval.request — Run becomes uncontrollable.
2. Stop treated as terminal; /stop returns "stopping", terminal arrives later as run.cancelled.
3. Session rotation already solvable client-side — messages envelope returns resolved session_id (api_server.py:1821-1826).
4. Capability gating still permits silent-approval hang (send gated without approval_events/run_stop).
5. Kotlin default-arg constructor breaks `by viewModels()` (MainActivity.kt:12 needs exact (Application) ctor).
6. Lost submission responses unreconciled — user can resubmit while original Run executes.
7. approval.responded carries choice + resolved (api_server.py:4615-4621) — needed for cross-client resolution.
8. Host profile delete during its Run removes credentials needed to Stop/reconcile.

### Claude's response

All 8 verified against source and accepted:
(1+8) RunContext now snapshots the full authenticated HostProfile; run transcript state keyed by RunContext session id and updated while browsing elsewhere; persistent run banner exposes Approval/Stop globally; Host switch and Host edit/delete blocked during an active Run.
(2) Stop no longer terminal — keep streaming/polling until run.completed/failed/cancelled, then reload.
(3) loadMessages returns resolved session_id; activeSessionId adopts it after every load — rotation risk bullet removed.
(4) Send gated on full bundle: run_submission + run_events_sse + run_stop + approval_events + run_approval_response.
(5) @JvmOverloads on injected constructor to preserve the (Application) overload.
(6) Outcome-unknown state: send disabled, bounded poll for the submitted user text, release on evidence or timeout; never auto-resubmit.
(7) Event table row updated with choice/resolved; card resolves on successful POST or matching approval.responded.
Tests list extended to cover all of the above.

## Round 3 — Codex

VERDICT: REVISE. 7 findings:

1. Outcome-unknown detection unsafe (text match can hit older identical message; timeout auto re-enable can duplicate a Run).
2. Adopting resolved session id after every load hijacks navigation when a background Run completes.
3. Resolved session id must also be adopted before submission (RunContext could target a rotated Session).
4. @JvmOverloads fixes reflection but JVM tests still need an Application (no Robolectric in deps).
5. Process death loses the run id while the Host keeps executing.
6. Authenticated HostProfile inside RunContext is a logging/secrets hazard.
7. ADR 0001 stale (still says full history, run_submission-only gating).

### Claude's response

All 7 accepted:
(1) Baseline message ids/count captured pre-submit; evidence = new user+assistant pair beyond baseline; timeout keeps send locked behind explicit "Send anyway".
(2) Resolved id adopted as activeSessionId only when the loaded Session is still displayed; background transcripts re-keyed without navigation change.
(3) RunContext and submitRun use the resolved id from the pre-submit history load.
(4) Converted plan to plain ViewModel(gateway, hostStore, savedStateHandle, dispatcher) + production Factory constructing SecureHostStore — no Application in unit tests (verified: no Robolectric dep, no existing ViewModel test file in this worktree).
(5) SavedStateHandle persists non-secret run coordinates; recreation re-resolves host from store, polls status, offers Stop.
(6) RunContext private, never stringified; logs carry ids only.
(7) ADR 0001 rewritten: history projection, stop-not-terminal, rotation recovery, five-capability bundle.

## Round 4 — Codex

VERDICT: REVISE. 4 findings (rounds 1–3 otherwise addressed, ADR now consistent):

1. Outcome-unknown evidence can unlock mid-Run (host persists messages during intermediate tool steps).
2. SavedStateHandle needs a saved-state-aware factory (CreationExtras.createSavedStateHandle()).
3. Global Approval banner lacks origin context — could authorize under wrong-Session impression.
4. Same-named Skill reachability contradicts text reparsing.

### Claude's response

All 4 accepted:
(1) No auto-unlock from transcript shape — evidence is displayed; release requires terminal Run status or explicit "Resume"/"Send anyway" acknowledgement.
(2) Factory obtains SavedStateHandle via CreationExtras.createSavedStateHandle(); recreation tested through the same factory path.
(3) Run banner always names the originating Session with a "Return to Session" action.
(4) Suggestions are typed actions (LocalCommand vs Skill); menu taps dispatch directly, no reparse.

## Round 5 — Codex

VERDICT: APPROVED. "All prior findings are now addressed. No new blocking issues found. Plan is implementation-ready with appropriate contract tests, lifecycle recovery, approval safety, and verification coverage." Residual risks explicitly accepted: multi-writer staleness, unbounded history growth, no Host-side idempotency.

## Resolution

Converged in 5 rounds (REVISE ×4 → APPROVED). Implementation proceeds per the user-approved plan (Claude builds).
