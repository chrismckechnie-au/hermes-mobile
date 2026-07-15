# Security Policy

Hermes Mobile controls remote agents that may have access to terminals, files, credentials, and third-party services. Treat every saved host and API key as privileged access.

## Supported versions

Security fixes are provided for the latest published release. Users should update promptly and verify release checksums. Development builds from `main` are unsupported and may contain incomplete migrations or security controls.

## Report a vulnerability

Do not disclose suspected vulnerabilities in a public issue, discussion, screenshot, or chat transcript. Email `chris.mckechnie@gmail.com` with the subject `Hermes Mobile security report` and include:

- affected app and Hermes host versions;
- reproduction steps and expected impact;
- whether credentials, prompts, responses, or tool output may have been exposed;
- any proposed fix or mitigations; and
- a safe way to contact you.

Do not include live credentials or personal data. Use a disposable test host where possible. Reports will be acknowledged as availability permits; no specific response or remediation deadline is guaranteed.

## Deployment guidance

- Prefer HTTPS with a certificate trusted by Android. Use private-network HTTP only behind a trusted LAN or VPN and only after the in-app warning is accepted.
- Never expose the Hermes API directly to the public internet without strong authentication, TLS, firewall restrictions, rate limiting, and monitoring.
- Use a unique, long random API key per environment and rotate it after device loss, accidental disclosure, or team changes.
- Keep Android, Hermes Mobile, the Hermes host, plugins, and dependencies updated.
- Review approval requests before allowing tools to modify files, execute commands, access secrets, or contact external systems.
- Enable the overlay and lock-screen notification content only on devices where that information may safely be displayed.
- Do not commit `google-services.json`, keystores, signing passwords, host keys, logs, screenshots, or captured API responses.

## Release integrity

Published release workflows build signed APK and AAB artifacts from version tags and attach SHA-256 checksums and GitHub build-provenance attestations. Signing material is supplied only through protected GitHub Actions secrets. The Gradle build never substitutes the debug key for a release key.
