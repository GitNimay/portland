# Security Policy

## Supported Versions

Security fixes are applied to the latest release built from the `main` branch (`v1.0.*`). Older release builds are not backported; keep Portland updated via the built-in self-updater or [GitHub Releases](https://github.com/GitNimay/portland/releases).

| Version | Supported |
| --- | --- |
| Latest (`main` / latest `v1.0.*` release) | Yes |
| Older `v1.0.*` releases | No |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues or pull requests.**

Report vulnerabilities confidentially through **GitHub Private Vulnerability Reporting**:

- Open the repository's **Security** tab → **Advisories** → **Report a vulnerability** (`https://github.com/GitNimay/portland/security/advisories/new`).

Please include:
- A clear description of the vulnerability and potential impact
- Steps to reproduce or a minimal proof of concept
- Affected files, components, or APK version (`versionCode` / `versionName`)

### Response Process

- **Acknowledgment:** Within 72 hours of submission
- **Triage & Fix:** Confirmed issues are investigated privately via a temporary security advisory fork, verified against CI/security checks, and released before public disclosure.

## Security Controls in This Repository

- **Static Analysis (SAST):** CodeQL (`java-kotlin`) and Android Lint (`lintDebug`) run on every PR and push to `main`.
- **Secret Detection:** GitHub Secret Scanning with Push Protection plus Gitleaks on every PR and push (commit range) and a weekly full-history scan.
- **Dependency & Supply Chain Scanning:** OSV-Scanner, Trivy, GitHub Dependency Graph, Dependabot vulnerability alerts, and automated Dependabot security/version updates.
- **Release Integrity:** Release APKs are signed with a single permanent release keystore, verified by SHA-256 checksum in `releases/latest.json` by `Updater.kt`, and published only after all required checks pass.
- **CI Supply Chain:** Every GitHub Action is pinned to a full commit SHA, workflows are linted by actionlint and zizmor, release APKs carry a signed build provenance attestation (`gh attestation verify app-release.apk -R GitNimay/portland`), and secret-bearing release builds never write the shared Actions cache.
