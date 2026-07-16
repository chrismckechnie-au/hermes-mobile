# Hermes Mobile

[![Android CI](https://github.com/chrismckechnie-au/hermes-mobile/actions/workflows/android-ci.yml/badge.svg)](https://github.com/chrismckechnie-au/hermes-mobile/actions/workflows/android-ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Native Android client for remotely operating a user-owned [Hermes Agent](https://github.com/NousResearch/hermes-agent) host.

Hermes Mobile uses Kotlin and Jetpack Compose. It is a client only: the agent, model credentials, tools, memory, sessions, and scheduled jobs remain on the selected Hermes host.

> [!IMPORTANT]
> Hermes Mobile is an independent community project. It is not affiliated with or endorsed by Nous Research. Review the [privacy notice](PRIVACY.md), [security policy](SECURITY.md), and powerful Android/Hermes permissions before connecting a production host.

## Implemented

- Native Command Deck interface for Android, styled in an Apple-inspired dark/light system palette (switchable in Settings) with the official Hermes app icon, Lucide icons, and JetBrains Mono
- Multiple saved Hermes hosts with quick switching, editing, and confirmed deletion
- HTTPS by default, with explicit opt-in for private-network HTTP; scheme-downgrade redirects are refused
- API keys encrypted at rest with Android Keystore (AES-GCM); unlock failures surface a notice instead of silently wiping hosts
- Capability discovery, host-version display, and authenticated connection status
- Capability-gated Hermes host update checks and confirmed remote updates when the host explicitly exposes the update API
- Session listing with pagination (`has_more`), pull-to-refresh, request-derived titles for new chats, direct chat-header rename, delete, history loading, and selection
- On-device session search plus All, Running, Approval, Mobile, and Desktop filter pills across loaded sessions, with host-default model aliases resolved to the configured model and explicit Android sharing of user/assistant transcripts without tool previews or progress details
- Host model discovery with compact model, reasoning-effort, and permission controls directly above the Chat composer
- Capability-gated live task, subagent, and workspace-change pills, including a bounded per-file diff sheet when the host exposes `run_workspace_updates`
- Independent streaming runs per host/session, with stop/cancel, follow-up messages that interrupt and replace the current run, durable event-cursor replay after network/process loss, and idempotent submit retry protection
- Sessions are sorted strictly by their latest host update, with compact timestamps and origin-aware labels for untitled mobile, desktop, Discord, scheduled, and delegated sessions; visible-list liveness refresh, missed-terminal reconciliation, distinct Running and Stalled filters, and safe manual cleanup remain available
- Host-backed `/goal` and installed-skill `/plan` commands appear in composer autocomplete with a command indicator when the host advertises slash-command Run support
- Browse host skills and toolsets from the Host tab; start a skill in Chat or inspect the concrete host tools it exposes, including plugin-contributed tools when available
- Optional Android system dictation from the composer, appending the recognizer result to the current draft
- Markdown rendering of assistant replies (code blocks with copy, headings, bullets, bold/italic/inline code, links)
- Live assistant deltas plus collapsible, grouped tool activity and compact left-aligned Hermes progress bubbles
- Collapsible, compact Hermes activity bubbles for host-provided reasoning progress, including a rolling status history for desktop-originated work opened on mobile
- Compact input, output, and total token usage beneath a completed reply when the Hermes host reports terminal Run usage
- Tool-run approval cards (`approval.request` → approve/deny via `POST /v1/runs/{id}/approval`)
- Compact ongoing work notification and a draggable edge icon that opens an attached active-session panel with session names, latest bounded activity, and relative update times; it hides while Hermes Mobile is open, restores when the app backgrounds, and can be dropped onto a close target to hide it until the next run
- Optional host-provided task-plan and delegated-subagent pills above an active chat, each opening a compact live status drawer; desktop-active session rows remain visibly running even without a mobile-run registry entry
- Scheduled job listing with pause/resume and run-now
- Connected, connecting, empty, authentication-error, network-error, and retry states

Not implemented: file upload (the Hermes API server currently rejects file content with `400 unsupported_content_type`).

## Hermes API endpoints

The client uses the following Hermes HTTP surface:

- `GET /v1/capabilities`
- `GET /health`
- `GET` / `POST /v1/host-update` (only on hosts that advertise `host_update_api`)
- `GET /v1/models`
- `GET /v1/skills`
- `GET /v1/toolsets`
- `GET /v1/active-sessions`
- `GET /v1/sessions/{id}/activity` / `GET /v1/sessions/{id}/activity/events` (when advertised by `hermes.mobile` 1.1)
- `DELETE /v1/active-sessions/{lease_id}` (only for host-confirmed stale activity)
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

Host-update controls are deliberately hidden unless the connected host advertises
`host_update_api`. This prevents a phone from attempting an update through an
older or managed Hermes installation that cannot safely apply one in place.

Stock Hermes does not currently advertise every mobile extension listed above.
See the [host compatibility contract](docs/mobile-api-contract.md) for the exact
stock/optional boundary and proposed upstream requirements. Diagnose a host
without making any changes to it:

```bash
python scripts/check_hermes_mobile_contract.py --url https://HOST --api-key "$HERMES_API_KEY"
```

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
coordinate and its latest SSE event cursor, reconnects to each
`/v1/runs/{run_id}/events`, and reconciles long-running work after a live SSE
connection is lost. Retry-safe hosts also receive one stable `Idempotency-Key`
per logical submission. A run in one session never prevents
drafting or starting work in another session or host.

Push delivery is opt-in per saved host under **Settings → Notifications**. The
same section enables the optional Android draw-over-other-apps session overlay.
Notifications contain status and the session title only; prompts, responses,
tool output, commands, and credentials are never included in FCM payloads.
Hermes Mobile posts notifications when work needs attention or reaches an
outcome instead of keeping a separate ongoing work notification. The overlay
is seeded from the local run and
then reconciled against `/v1/active-sessions`, avoiding first-poll races. The
overlay contains active work plus unread approval, completion, and failure
flags. Opening the related session clears its unread state.

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

With a Hermes host that advertises `hermes.mobile` 1.2, run `hermes mobile
pair` after enabling the block. Scan the printed QR code with the phone's
camera and open the Hermes link. The app exchanges the five-minute, single-use
grant for a revocable device credential; the host's master API key is never
placed in the QR code. Use `hermes mobile pair --url https://host.example` when
automatic LAN address discovery is not appropriate. Settings can send a
targeted test notification after registration succeeds.

Without `google-services.json`, remote device registration and push delivery
remain inactive. Local working status still functions. Notification, Bubble,
and overlay permissions remain user-controlled. The overlay only runs as a
visible foreground service while opted-in hosts report active sessions.
Android requires a quiet foreground-service notification while the optional
floating overlay is active; disabling the overlay removes that system entry.

## Permissions and transport security

Hermes Mobile requests internet access and, when the corresponding features are enabled, Android notification, foreground-service, and display-over-other-apps permissions. The overlay permission is optional and can be revoked from Android settings. Dictation discovers the installed speech-recognition service without requesting broad installed-app visibility.

HTTPS is required by default. Android's network policy permits cleartext so that a user can explicitly opt a saved host into private-network HTTP; the app rejects HTTP profiles without that opt-in and refuses scheme-changing redirects. Cleartext to GitHub and Google service domains is always denied. Because HTTP bearer credentials are still visible to the selected network, use the opt-in only behind a trusted LAN or VPN.

## Build

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

Install it on an attached emulator/device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are intended for development. Distributed builds use R8 code and
resource shrinking plus a generated Baseline Profile for the launch and
send-message journeys.

### Crash diagnostics

Crash reporting is off by default. When Firebase is configured, users can opt
in under **Settings > Diagnostics**. Hermes Mobile reports only bounded process
exit metadata, lifecycle phases, message-length buckets, and sanitized failure
types through its custom diagnostics. Firebase Crashlytics also processes
automatic crash and ANR reports, which can include uncaught exception, device,
OS, and app-state metadata supplied by Android. Hermes Mobile does not
intentionally add host URLs, API keys, session IDs, prompts, or transcript
content to custom diagnostic keys or logs. The same settings section shows a
copyable summary of the previous Android process exit to help investigate
device-only failures.

### Send-path performance checks

With an emulator running and the local contract host listening on port `8766`,
generate the Baseline Profile or run the frame-timing benchmark:

```bash
python3 scripts/mock_hermes_host.py
./gradlew :app:generateBaselineProfile
./gradlew :baselineprofile:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
python3 scripts/verify_benchmark_results.py \
  baselineprofile/build/outputs/connected_android_test_additional_output \
  --max-frame-ms 700
```

The benchmark configures `http://10.0.2.2:8766` with the test-only key when the
app has no saved host, creates an isolated session for each iteration, sends a
message, and measures frame timing through the first rendered host response.
The verifier fails if any sampled CPU frame reaches 700 ms. The scheduled and
manually runnable Android performance workflow executes the same gate on a
hardware-accelerated emulator and retains its JSON results and Perfetto traces.

### Signed release builds

Release packaging never falls back to the Android debug key. Provide all four values through environment variables:

| Environment variable | Value |
| --- | --- |
| `HERMES_RELEASE_STORE_FILE` | Absolute path to the upload keystore |
| `HERMES_RELEASE_STORE_PASSWORD` | Keystore password |
| `HERMES_RELEASE_KEY_ALIAS` | Signing key alias |
| `HERMES_RELEASE_KEY_PASSWORD` | Signing key password |

The equivalent `hermesReleaseStoreFile`, `hermesReleaseStorePassword`, `hermesReleaseKeyAlias`, and `hermesReleaseKeyPassword` properties may be stored in the user's untracked `~/.gradle/gradle.properties`. Never put signing credentials in this repository.

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

The signed APK and Play-ready AAB are written to:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

The Android release workflow requires these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON_BASE64`

Store them in the workflow's `release` environment and protect that environment with required reviewers before publishing production builds.

A `v*` tag runs tests and release lint, builds signed APK/AAB artifacts, generates SHA-256 checksums and GitHub build-provenance attestations, and uploads them to the corresponding GitHub Release. A manual workflow run produces the same signed workflow artifacts without creating a release.

## Contract verification host

`scripts/mock_hermes_host.py` is a local, contract-faithful fixture for emulator QA. It listens on port `8766` by default and accepts the test-only bearer key `test-key`. Send a chat message containing the word "approve" to exercise the tool-approval card.

```bash
python3 scripts/mock_hermes_host.py
```

From an Android emulator, add `http://10.0.2.2:8766` and enable private-network HTTP. The fixture is only for local development; never expose it as a real Hermes host.

## Contributing and licence

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and pull-request guidance. Hermes Mobile source code is available under the [MIT License](LICENSE); bundled third-party materials retain the licences documented in [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md).
