# Hermes Mobile

Native Android client for remotely operating a user-owned [Hermes Agent](https://github.com/NousResearch/hermes-agent) host.

Hermes Mobile uses Kotlin and Jetpack Compose. It is a client only: the agent, model credentials, tools, memory, sessions, and scheduled jobs remain on the selected Hermes host.

## Implemented

- Native Command Deck interface for Android
- Multiple saved Hermes hosts with quick switching, editing, and confirmed deletion
- HTTPS by default, with explicit opt-in for private-network HTTP; scheme-downgrade redirects are refused
- API keys encrypted at rest with Android Keystore (AES-GCM); unlock failures surface a notice instead of silently wiping hosts
- Capability discovery and authenticated connection status
- Session listing with pagination (`has_more`), pull-to-refresh, creation, rename, delete, history loading, and selection
- Streaming chat over Server-Sent Events with stop/cancel for in-flight runs
- Markdown rendering of assistant replies (code blocks with copy, headings, bullets, bold/italic/inline code, links)
- Live assistant deltas and structured tool start/completion cards
- Tool-run approval cards (`approval.request` → approve/deny via `POST /v1/runs/{id}/approval`)
- Scheduled job listing with pause/resume and run-now
- Connected, connecting, empty, authentication-error, network-error, and retry states

Not implemented (host support required): push notifications for job results, file upload (the Hermes API server currently rejects file content with `400 unsupported_content_type`). Note: current hermes-agent releases emit `approval.request` on `/v1/runs/{id}/events` streams; the session chat stream handler processes the event whenever the host sends it there too.

## Hermes API endpoints

The client uses Hermes' supported HTTP surface:

- `GET /v1/capabilities`
- `GET /api/sessions` (with `limit`/`offset` pagination)
- `POST /api/sessions`
- `PATCH /api/sessions/{id}` (rename)
- `DELETE /api/sessions/{id}`
- `GET /api/sessions/{id}/messages`
- `POST /api/sessions/{id}/chat/stream`
- `GET /api/jobs`
- `POST /api/jobs/{id}/pause` / `POST /api/jobs/{id}/resume` / `POST /api/jobs/{id}/run`
- `POST /v1/runs/{id}/stop`
- `POST /v1/runs/{id}/approval`

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
