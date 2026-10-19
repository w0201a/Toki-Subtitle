# Toki Subtitle releases

Original Toki author: MeiYongAI. Subtitle edition contributors: 154Er and Gi-Ai154M.

The default development build is Debug. An explicit release uses `./gradlew :app:assembleRelease`, with R8 code optimization and resource shrinking enabled.

Published release APKs are signed locally with the dedicated subtitle edition release certificate. The private keystore and `keystore/keystore.properties` are excluded from Git and never uploaded. Keep a private backup of both files to sign future updates with the same certificate.

GitHub Actions runs unit tests and lint and builds both variants. Its Release artifact is unsigned because no signing credentials are provided to Actions. Install the signed APK from GitHub Releases instead. Releases include a SHA-256 checksum file.

The dedicated release certificate differs from the development Debug certificate. Android cannot install one over the other with the same package ID. Back up settings before any user-managed migration; publishing a release does not uninstall or clear the existing app.

Before publishing, verify the APK signature using Android SDK `apksigner verify --verbose --print-certs` and scan the complete Git history using `gitleaks git --redact --config .gitleaks.toml`.
