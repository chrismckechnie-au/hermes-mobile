# Context

Glossary of the ubiquitous language for Hermes Mobile. Terms only — no implementation details.

## Terms

**Hermes host** — a user-owned machine (desktop or server) running the Hermes Agent API server. The phone is only a client; the agent, credentials, tools, memory, sessions, and jobs live on the host.

**Command Deck** — the mobile app's five-screen surface (Chat, Sessions, Jobs, Host, Settings) for operating a connected Hermes host.

**Hermes palette** — the app's Apple-inspired light and dark system palettes, with platform-style grouped surfaces and blue interactive accents.

**System theme** — the default appearance setting. It follows Android's current dark-mode setting; users can override it with Light or Dark in Settings.

**Session** — a conversation thread that exists on the Hermes host, listed and resumed from the mobile client.

**Job** — scheduled work configured on the Hermes host and surfaced read-only in the mobile client.

**Capability discovery** — the client's initial `/v1/capabilities` probe that authenticates and learns what the host supports before loading sessions.
