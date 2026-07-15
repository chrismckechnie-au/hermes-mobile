# Hermes Mobile

Native Android client for remotely operating a user-owned [Hermes Agent](https://github.com/NousResearch/hermes-agent) host.

Hermes Mobile uses Kotlin and Jetpack Compose. It is a client only: the agent, model credentials, tools, memory, sessions, and scheduled jobs remain on the selected Hermes host.

## Implemented

- Native Command Deck interface for Android, styled in an Apple-inspired dark/light system palette (switchable in Settings) with the official Hermes app icon, Lucide icons, and JetBrains Mono
- Multiple saved Hermes hosts with quick switching, editing, and confirmed deletion
- HTTPS by default, with explicit opt-in for private-network HTTP; scheme-downgrade redirects are refused
- API keys encrypted at rest with Android Keystore (AES-GCM); unlock failures surface a notice instead of silently wiping hosts
- Capability discovery and authenticated connection status
- Session listing with pagination (`has_more`), pull-to-refresh, creation, direct chat-header rename, delete, history loading, and selection
- Host model discovery with per-run model and reasoning-effort selection from the Chat model sheet
- Independent streaming runs per host/session, with stop/cancel, follow-up messages that interrupt and replace the current run, multi-run process-death recovery, and unknown-submit protection
- Sessions are sorted by latest activity, with active work pinned first and clear running, queued, approval, and stopping indicators
- Browse host skills and toolsets from the Host tab; start a skill in Chat or inspect the concrete host tools it exposes, including plugin-contributed tools when available
- Optional Android system dictation from the composer, appending the recognizer result to the current draft
- Markdown rendering of assistant replies (code blocks with copy, headings, bullets, bold/italic/inline code, links)
- Live assistant deltas plus collapsible, grouped tool activity and compact left-aligned Hermes progress bubbles
- Collapsible, compact Hermes activity bubbles for host-provided reasoning progress
- Tool-run approval cards (`approval.request` → approve/deny via `POST /v1/runs/{id}/approval`)
- Compact ongoing work notification and a draggable edge icon that opens an attached session panel with latest safe activity; it hides while Hermes Mobile is open, restores when the app backgrounds, shows a count for updates to review, and can be dropped onto a close target to hide it until the next run
- Scheduled job listing with pause/resume and run-now
- Connected, connecting, empty, authentication-error, network-error, and retry states

Not implemented: file upload (the Hermes API server currently rejects file content with `400 unsupported_content_type`).

## Hermes API endpoints

The client uses Hermes' supported HTTP surface:

- `GET /v1/capabilities`
- `GET /v1/models`
- `GET /v1/skills`
- `GET /v1/toolsets`
- `GET /v1/active-sessions`
- `PUT` / `DELETE /v1/mobile/devices/{installation_id}`
- `POST /v1/runs`
- `GET /v1/runs/{id}` / `GET /v1/runs/{id}/events`
- `POST /v1/runs/{id}/stop` / `POST /v1/runs/{id}/approval`
- `GET /api/sessions` (with `limit`/`offset` pagination)
- `POST /api/sessions`
- `PATCH /api/sessions/{id}` (rename)
- `DELETE /api/sessions/{id}`
- `GET /api/sessions/{id}/messages`
- `POST /api/sessions/{id}/fork`
- `GET /api/jobs`
- `POST /api/jobs/{id}/pause` / `POST /api/jobs/{id}/resume` / `POST /api/jobs/{id}/run`

Every request uses `Authorization: Bearer <API_SERVER_KEY>`.

## Configure a Hermes host

Add these values to the host's `~/.hermes/.env`:

```dotenv
API_SERVER_ENABLED=true
API_SERVER_KEY=replace-with-a-long-random-secret
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
```

Then restart the Hermes gateway and confirm the endpoint from another device:

```bash
curl http://HOST_IP:8642/v1/capabilities \
  -H 'Authorization: Bearer replace-with-a-long-random-secret'
```

`API_SERVER_HOST=0.0.0.0` exposes the agent to the attached network. Only use it behind a trusted LAN, VPN/Tailscale, firewall, or TLS reverse proxy. The Hermes API can exercise powerful tools, including terminal access.

In Hermes Mobile, choose **Add a host** and enter:

- a friendly name
- the server root, such as `https://hermes.example.com` or `http://100.x.y.z:8642`
- the matching `API_SERVER_KEY`
- private-network HTTP opt-in only when the connection is protected by a trusted LAN/VPN

Entering a URL ending in `/v1` is also supported; the client normalizes it to the server root. When editing a saved host, leave the API key blank to keep the stored key.

## Background runs, notifications, Bubbles, and overlay

Runs execute on the Hermes host and continue when the Android activity or app
process closes. Hermes Mobile durably stores every active host/session/run
coordinate, reconnects to each `/v1/runs/{run_id}`, and reconciles long-running
work after a live SSE connection is lost. A run in one session never prevents
drafting or starting work in another session or host.

Push delivery is opt-in per saved host under **Settings → Notifications**. The
same section enables the optional Android draw-over-other-apps session overlay.
Notifications contain status and the session title only; prompts, responses,
tool output, commands, and credentials are never included in FCM payloads.
Runs started in Hermes Mobile also show an ongoing local work notification;
this does not require Firebase. The overlay is seeded from the local run and
then reconciled against `/v1/active-sessions`, avoiding first-poll races.
Terminal outcomes and approval requests remain counted until their session is
opened, so an update is not lost when the phone is locked or the app is away.

Dictation uses the Android device's installed speech recognizer. Its privacy
and network behavior are controlled by that provider; some recognizers send
audio to a remote service.

Firebase is deliberately user-owned:

1. Create an Android app with package `au.com.chrismckechnie.hermesmobile` in
   your Firebase project and place its config at `app/google-services.json`
   (the path is gitignored).
2. Enable the Firebase Cloud Messaging API. On each opted-in Hermes host,
   provide Application Default Credentials for a service account allowed to
   send FCM messages, commonly with `GOOGLE_APPLICATION_CREDENTIALS`.
3. Add this profile-scoped `config.yaml` block and restart the gateway:

   ```yaml
   mobile_notifications:
     enabled: true
     project_id: your-firebase-project-id
   ```

Without `google-services.json`, remote device registration and push delivery
remain inactive. Local working status still functions. Notification, Bubble,
and overlay permissions remain user-controlled. The overlay only runs as a
visible foreground service while opted-in hosts report active sessions.

## Build

Requirements: JDK 17 and Android SDK 35.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

Install it on an attached emulator/device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Contract verification host

`scripts/mock_hermes_host.py` is a local, contract-faithful fixture for emulator QA. It listens on port `8766` by default and accepts the test-only bearer key `test-key`. Send a chat message containing the word "approve" to exercise the tool-approval card.

```bash
python3 scripts/mock_hermes_host.py
```

From an Android emulator, add `http://10.0.2.2:8766` and enable private-network HTTP. The fixture is only for local development; never expose it as a real Hermes host.
