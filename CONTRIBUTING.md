# Contributing to Hermes Mobile

Hermes Mobile is an independent companion to [Hermes Agent](https://github.com/NousResearch/hermes-agent). Contributions should preserve compatibility with unmodified official Hermes hosts wherever possible and capability-gate optional or experimental host APIs.

## Development setup

Install JDK 17 and Android SDK 35, clone the repository, and run:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The mock host in `scripts/mock_hermes_host.py` supports local emulator testing described in the README.

Firebase is optional for local development. To test remote push, create your own Firebase Android app for `au.com.chrismckechnie.hermesmobile` and put its configuration in `app/google-services.json`. This file is ignored by Git and must never be committed.

## Pull requests

- Keep changes focused and follow the existing Kotlin and Jetpack Compose patterns.
- Add or update tests for changed behavior, especially host compatibility, reconnects, approvals, and concurrent sessions.
- Run the smallest relevant test first, then the full debug verification command above.
- Update public documentation and `THIRD-PARTY-LICENSES.md` when behavior, permissions, dependencies, fonts, or bundled artwork change.
- Never add API keys, host URLs containing credentials, Firebase service-account keys, keystores, signing passwords, transcripts, screenshots with private data, or generated build artifacts.
- Explain any new Android permission and its user-facing value in the pull request and privacy notice.

Open an issue before a large protocol or architecture change so it can be aligned with official Hermes capabilities. Security reports must follow [SECURITY.md](SECURITY.md), not the public issue tracker.

## Licence and project identity

Contributions are accepted under the repository's [MIT License](LICENSE). This is an independent project and is not affiliated with or endorsed by Nous Research. Hermes names and artwork remain subject to their respective owners' rights.
