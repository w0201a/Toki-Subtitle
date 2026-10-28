# Toki Subtitle releases

Original Toki author: MeiYongAI. Subtitle edition contributors: 154Er and Gi-Ai154M.

The default development build is Debug. Public releases use one signed unified APK containing the modern Root entry and an isolated compatibility runtime for supported non-Root injection environments. Both layers enable R8 code optimization and resource shrinking, use the release certificate and disable debugging.

On Windows, with the local signing configuration available, run:

```powershell
./scripts/build-universal.ps1 -PtokiProduction=true
```

The script builds and checks the isolated runtime, embeds it, and checks the unified APK at `app/build/outputs/apk/universal/app-universal.apk`. `:app:assembleRelease` remains available for a modern-entry-only build; it is not the unified public artifact.

Published release APKs are signed locally with the dedicated subtitle edition release certificate. The private keystore and `keystore/keystore.properties` are excluded from Git and never uploaded. Keep a private backup of both files to sign future updates with the same certificate.

GitHub Actions runs unit tests and lint, builds Debug and a unified test APK, and can optionally build unsigned Release. No production signing credentials are provided to Actions. Install the signed unified APK from GitHub Releases instead. Releases include a SHA-256 checksum file and Chinese / English notes describing the final behavior and actual validation scope.

The dedicated release certificate differs from the development Debug certificate. Android cannot install one over the other with the same package ID. Back up settings before any user-managed migration; publishing a release does not uninstall or clear the existing app.

Before publishing, verify the APK signature using Android SDK `apksigner verify --verbose --print-certs` and scan the complete Git history using `gitleaks git --redact --config .gitleaks.toml`.

Update `app/build.gradle.kts`, `CHANGELOG.md`, both READMEs and the curated issue record before committing. Stage only application sources, their tests, public documentation and necessary build files. Local device captures, raw diagnostics, host APKs, decompiled files, temporary scripts and private signing material remain local. Create the version tag on the pushed commit, attach the unified APK and checksums to a draft release, then publish after checking the remote assets.

## Incremental release records

Every small change receives its own dated version entry in CHANGELOG.md, including Debug-only iterations. A production release adds a new summary entry; it never replaces prior entries. Update both READMEs and docs/ISSUES_AND_FIXES.md with the release status, verification scope and signature guidance. The current subtitle-edition source and release destination is w0201a/Toki-Subtitle; upstream links are attribution, not upload destinations.
