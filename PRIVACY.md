# Hermes Mobile Privacy Notice

Effective: 15 July 2026

Hermes Mobile is an independent Android client for connecting to a Hermes Agent host chosen and controlled by the user. The project maintainer does not operate a Hermes host, transcript relay, analytics service, or advertising service for the app.

## Data handled by the app

- **Host connection data:** friendly names, server URLs, bearer API keys, connection preferences, and host capability/version information.
- **Hermes content:** prompts, responses, session titles, tool activity, approval requests, job information, and run status returned by the selected host.
- **App state:** settings, notification and overlay choices, installation identifier, active-run recovery coordinates, and UI state needed to restore the app.
- **Push data, when enabled:** a Firebase Cloud Messaging registration token is registered directly with opted-in Hermes hosts. Notification payloads are limited to session identity/title and status metadata; prompts, responses, tool output, commands, and API credentials are not included.

API keys are encrypted on the device with Android Keystore-backed AES-GCM. Other app settings are stored in Android app-private storage. Android backup is disabled. Hermes content remains on the configured host unless it is temporarily held in memory or app-private state for display and recovery.

## Where data goes

Hermes Mobile sends authenticated requests only to hosts the user configures. The operator of each host controls its storage, logging, model providers, tools, plugins, and retention policies.

When a build includes Firebase and remote notifications are enabled, Google Firebase processes the app installation and messaging token and may receive standard service metadata such as device, app, and delivery information. The configured Hermes host receives the token so it can address notifications. See [Google's Firebase privacy and security information](https://firebase.google.com/support/privacy).

Dictation is performed by the speech recognition provider installed or selected on the device. That provider may process audio remotely under its own privacy terms. Hermes Mobile receives only the recognition result.

Choosing **Share transcript** sends the selected session's user and assistant messages to the Android share target chosen by the user. Tool previews and Hermes progress details are excluded from the exported text. The selected share target controls how it stores or transmits that content.

## Android permissions

- **Internet:** connect to user-configured Hermes hosts and, when configured, Firebase Cloud Messaging.
- **Notifications:** show run, job, approval, and connection updates. Notification content can be visible on the lock screen according to Android settings.
- **Display over other apps:** optional floating active-session control. This powerful permission is requested only when the overlay is enabled and can be revoked in Android settings.
- **Foreground service:** keep the explicitly enabled active-session overlay and its visible ongoing notification available while Hermes work is active.
- **Speech recognizer visibility:** discover the device's installed dictation provider. The app does not request broad installed-app visibility.

## Security and transport choices

HTTPS is the default. A user may explicitly allow HTTP for a host intended to be reachable only through a trusted private network, LAN, or VPN. HTTP traffic is not encrypted and can be read or modified by anyone able to observe that network. Hermes API credentials can authorize powerful tools; use scoped network access, long random credentials, and TLS whenever possible.

## Retention and deletion

Removing a host deletes its local profile and makes a best-effort request to unregister the device from that host. It does not delete sessions, logs, or model-provider data stored by the host. Clear the app's storage or uninstall it to remove its remaining local data. Revoke the corresponding API key and notification registration on the host if a device is lost or no longer trusted.

## Children

Hermes Mobile is a technical administration client and is not directed to children under 13.

## Changes and contact

Material changes will be recorded in this file and release notes. For privacy questions, open an issue in the [Hermes Mobile repository](https://github.com/chrismckechnie-au/hermes-mobile/issues/new). Do not include API keys, prompts, tool output, or other sensitive information in public posts.
