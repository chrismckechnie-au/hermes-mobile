# Context

Glossary of the ubiquitous language for Hermes Mobile. Terms only — no implementation details.

## Terms

**Hermes host** — a user-owned machine (desktop or server) running the Hermes Agent API server. The phone is only a client; the agent, credentials, tools, memory, sessions, and jobs live on the host.

**Command Deck** — the mobile app's four-screen surface (Chat, Sessions, Jobs, Host) for operating a connected Hermes host.

**Hermes Teal** — the canonical Hermes visual identity, defined by the desktop dashboard's default theme: deep teal ground, warm cream accent, warm amber glow, film-grain texture.

**LENS_0** — the desktop design system's internal name for the Hermes Teal palette (background `#041c1c`, midground `#ffe6cb`, white foreground).

**Midground** — desktop design-system term for the theme's accent color, used for interactive and branded elements. In Hermes Teal the midground is warm cream, not a green.

**Session** — a conversation thread that exists on the Hermes host, listed and resumed from the mobile client.

**Job** — scheduled work configured on the Hermes host and surfaced read-only in the mobile client.

**Capability discovery** — the client's initial `/v1/capabilities` probe that authenticates and learns what the host supports before loading sessions.
