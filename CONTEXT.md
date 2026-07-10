# Hermes Mobile

Native Android client for remotely operating a user-owned Hermes Agent host. The client renders and controls; the agent, tools, sessions, and credentials live on the Host.

## Language

**Host**:
A user-owned Hermes Agent server the client connects to, identified by a base URL and bearer key.
_Avoid_: Server, backend, endpoint (as a noun for the machine)

**Session**:
A persisted conversation transcript stored on the Host, listed and managed via `/api/sessions`.
_Avoid_: Chat, thread, conversation (as a stored object)

**Run**:
One agent turn executing on the Host, with its own lifecycle (submitted → running → waiting for approval → completed/stopped), addressable by run id.
_Avoid_: Request, job, turn

**Skill**:
A named capability installed on the Host, discoverable via the skills listing and invoked by chat text. Skills execute host-side.
_Avoid_: Command (skills are not commands), plugin

**Local Command**:
A composer action the client executes itself by calling Host endpoints directly (e.g. new, rename, fork, delete, stop). Never sent to the agent as chat text.
_Avoid_: Slash command (that is the menu affordance, not the action type)

**Slash Command**:
The composer affordance: typing `/` opens a unified menu of Local Commands and Skills. A UI concept only — the Host API has no slash parsing.

**Approval**:
A host-side gate that pauses a Run until the user grants or denies a dangerous tool action. Grant scopes: once, this Run (the API's `session` choice is keyed by run id, not the persisted Session), always (not offered on mobile).
_Avoid_: Permission, confirmation, "allow for session" (misleading — the grant dies with the Run)
