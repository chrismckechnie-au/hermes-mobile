# ADR 0004: One owner for active-work state and bounded activity history

## Status

Accepted for Hermes Mobile 0.5.

## Decision

The foreground ViewModel owns active-work polling while the app is visible.
It polls every five seconds with active runs and backs off to 30 seconds while
idle. The overlay service suspends network polling in the foreground and owns
reconciliation only while the app is backgrounded. Run SSE remains the source
of detailed live events.

Push events are stored in one bounded activity history (100 entries, seven
days), deduplicated by host/session/run/event identity, and separated into
active, action-needed, and result notification channels. Notification and
PendingIntent identifiers include the host, session, and run to prevent
cross-host collisions. Payload text is bounded and never contains prompts,
tool arguments/output, credentials, file paths, or private reasoning.

## Consequences

The app avoids duplicate five-second loops and notification ID races. Activity
Center and notification summaries share one durable read state. Remote push is
still best-effort; reconnecting to host activity remains authoritative.
