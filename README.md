# Toki

Subtitle edition contributors: **154Er · Gi-Ai154M**. This credit covers improvements and maintenance of the local subtitle edition; original project attribution and license are retained.

## 2.0.51 release

[Download the production-signed unified APK](https://github.com/w0201a/Toki-Subtitle/releases/tag/v2.0.51) · [Incremental changelog](CHANGELOG.md)

- Repairs duplicate/misplaced captions, competing prefetch sessions and track-cache invalidation; the user confirmed the 2.0.49 subtitle fix.
- Adds Fill, automatic black-border detection/cropping, 100%–150% manual zoom and an optional live vertical-swipe lock.
- Improves the injected entry under TikTok Settings and opens directly to the feature list.
- Adds comment-sticker downloads. Enable the option, reopen TikTok and long-press a sticker comment to save its original animation/image to Pictures/TikTok/Stickers or the configured image directory.

Native subtitle rendering is the default. Select Custom and enable video translation to use Toki caption presentation and pretranslation. Prefetch requires readable tracks already loaded by TikTok; its cache lasts for the current process. Automatic cropping, live swipe lock and sticker downloads need broader device feedback. Some newer labels fall back to English.

The unified APK contains Root/non-Root entry points, retains the production certificate, disables debugging in both runtimes and enables R8/resource shrinking. **Release and Debug signatures differ and cannot update each other in place.** Publishing does not uninstall apps or clear data. All 2.0.45–2.0.50 incremental entries remain in the changelog.

## 2.0.44 update

[Download the signed unified APK](https://github.com/w0201a/Toki-Subtitle/releases/tag/v2.0.44) · [中文 / English release notes](CHANGELOG.md)

- Hidden top tabs are skipped during swipes; an optional TikTok Minis cleanup switch blocks the home sidebar.
- Choose Toki's interface language from its toolbar or embedded settings; the default follows the phone's system language.
- Subtitle pretranslation processes complete available tracks in batches of five, retaining up to 100 completed videos. Watched videos are released in older batches of five after another five have been passed, preserving the most recent watched batch and unwatched cached videos.
- Playback-time subtitle display covers missing native frames, and progress-bar fade handling is repaired.

The subtitle cache is held in memory for the current TikTok process. Ahead-of-playback translation requires TikTok to have loaded a readable subtitle track. Device checks used TikTok 47.4.16; the 100-video cache limit and refill sequence were checked locally. Some newer UI strings fall back to English, and the separate `SubtitleMenu` adaptation mismatch remains. See the [bilingual changelog](CHANGELOG.md) for validation details.

Version 2.0.18 Debug adds on-device business-reference analysis and independent translation, subtitle, title and poll paths. If features fail after a TikTok update or downgrade, choose **Detect compatibility again** in Toki’s feature status page, fully close and reopen TikTok, and wait for local detection. No computer or app-data clearing is required. Ambiguous contracts remain unavailable; major host rewrites can still require a module update. An optional capture compatibility switch addresses a normal phone display with black mirroring by allowing capture of TikTok’s main window.

## Toki Subtitle edition

An MIT-licensed derivative of [MeiYongAI/Toki](https://github.com/MeiYongAI/Toki). Upstream attribution and community links are retained below; download and issue links target this edition.

This edition adds subtitle drag controls and independent axis locks, subtitle display during clean-screen playback, translation scheduling and feed prefetch improvements, comment poll translation, long-description translation with final-layout rendering, and a home-screen sponsor selector with original WeChat Pay / Alipay images.

Build Debug by default: `./gradlew :app:assembleDebug` (Windows: `gradlew.bat`). Requires Android SDK 37, Build Tools 37.0.0 and JDK 21 or later. Enable the module for TikTok in LSPosed, or use a compatible non-Root injection setup. Internal host interfaces are version-dependent; current device checks include TikTok 47.4.16. Signed public releases use the unified build described in [RELEASING.md](RELEASING.md).

Starting with 2.0.16 Debug, subtitle extensions automatically invalidate their cache after a host code update and discover structural matches in the background. Discovery saves and applies verified hooks in the running process; switch videos to refresh existing content. No computer connection is needed, and later launches reuse the cache. Obfuscated names may change while code structure stays compatible. Missing or ambiguous extensions are disabled independently and reported in feature status. Relationship contracts cover poll bridge binding, subtitle frame-to-renderer calls and description video-field writes; individual members remain independently verified even when unrelated methods change. Major implementation changes can still require maintained rules; future-version compatibility is not guaranteed. CI builds Debug by default; unsigned Release requires an explicit manual selection.

[Recent changes](CHANGELOG.md) · [Issues and fixes](docs/ISSUES_AND_FIXES.md) · [Link destinations](docs/LINKS.md) · [Release guide](RELEASING.md)

Sponsorship is voluntary and does not gate features. The provided payment QR images are public application resources. Signing keys, device diagnostics, screenshots, host APKs and decompiled host files are excluded from this repository.

<img src="https://raw.githubusercontent.com/MeiYongAI/Toki/main/docs/assets/toki.svg" align="right" width="96" height="96" alt="Toki icon">

An LSPosed module that gives you more control over your TikTok experience.

[![Build](https://github.com/w0201a/Toki-Subtitle/actions/workflows/build.yml/badge.svg)](https://github.com/w0201a/Toki-Subtitle/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84.svg)](#dependencies)

**English** · [简体中文](README.zh-CN.md)

[Download](https://github.com/w0201a/Toki-Subtitle/releases/latest) ·
[Subtitle repository](https://github.com/w0201a/Toki-Subtitle) ·
[Original author / upstream](https://github.com/MeiYongAI/Toki) ·
[Changelog](CHANGELOG.md) ·
[Report a problem](https://github.com/w0201a/Toki-Subtitle/issues/new/choose) ·
[Subtitle edition Telegram](https://t.me/Toki_Subtitle)

Toki provides feed filters, playback controls, media tools and region settings
through a Material 3 interface with 57 language options.

> **AI-generated project.** This module is developed with AI-generated code and
> may contain errors. Review changes and test carefully before relying on it.
>
> **Hide My Applist (HMA):** Do not hide Toki from TikTok. Toki does not provide
> HMA compatibility support. See [Troubleshooting](#troubleshooting).

## Table of contents

- [Install](#install)
- [Usage](#usage)
- [Features](#features)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [Contributing](#contributing)
- [Support](#support)
- [Disclaimer](#disclaimer)
- [License](#license)

## Install

### Dependencies

| Requirement | Supported configuration |
| --- | --- |
| Android | Android 9 (API 28) or newer |
| Framework | A working LSPosed installation supporting **libxposed API 102** |
| TikTok | Official client; on-device adaptation uses code identity. TikTok **47.4.16** was exercised on a Huawei device; compatibility of every feature and future version is not guaranteed. |
| Module scope | `com.zhiliaoapp.musically` or `com.ss.android.ugc.trill` |

Toki supports **official TikTok clients only**. Third-party modified clients,
including TikTok Central, are not supported or adapted.

Toki requires the framework to apply changes to TikTok. Other TikTok versions
are outside the documented compatibility scope. Download
variants can contain different code, so a listed version alone does not
guarantee that every feature will work.

### Enable the module

1. Download and install the Toki APK from
   [Releases](https://github.com/w0201a/Toki-Subtitle/releases/latest).
2. Enable Toki in LSPosed and select your installed TikTok in the module scope.
3. Open Toki, check the activation status on Home and choose the features you
   want to enable.
4. Force-stop TikTok and open it again. If a method-discovery dialog appears,
   let it finish and dismiss the completion message. In 2.0.16 Debug, verified
   features apply automatically; switch videos to refresh existing content.

Verified builds use bundled adaptation results without first-run discovery. Other builds
run native method discovery. In 2.0.16 Debug, results apply automatically after verification.

## Usage

Open Toki to configure features, change the interface language, view feature
status or import/export settings from Home.

For example, to filter ads, enable ad filtering in the feed settings, restart
TikTok and check the feature status in Toki after opening TikTok.

- **Restart when prompted.** Newly enabled features, region spoofing, playback
  speed and immersive layout changes can require a new TikTok session. When
  Toki indicates that a restart is needed, force-stop TikTok and open it again.
- **Check feature status.** The status page distinguishes your requested
  settings from those applied in the current session and reports adaptation
  failures. Installed filtering and clean-mode features receive configuration
  updates during the session.
- **Keep configuration backups.** Export settings before reinstalling or
  making substantial changes, and import them from Home when needed.

## Features

| Area | Options |
| --- | --- |
| Feed filters | Filter ads across video feeds, including creator profiles. On For You, filter LIVE, photos, AI-labeled videos/photos, topic/creator cards, keywords, authors, duration, views and likes; block offline video insertion. |
| Layout cleanup | Separate top navigation, bottom navigation and video-page controls, including rewards, activity cards and the stop auto-scroll button, plus opacity settings. |
| Playback | Custom speeds, automatic clean mode, centered or fullscreen video, background audio unlock, progress-bar options and enhanced auto scroll across video pages. |
| Media and tools | Prefer watermark-free downloads, set custom save folders, handle audio restrictions, configure translation and copy original or translated comment text. |
| Region | Spoof SIM/region, language, time zone and location within TikTok; display creator regions. |
| Management | Feature status, settings import/export, automatic method discovery and 57 interface language options. |

## Troubleshooting

### How to apply method-discovery results

In 2.0.16 Debug, discovery applies verified features automatically. Dismiss the completion
message and switch videos to refresh existing content. If discovery fails, check Toki's
feature diagnostics and [report the problem](#contributing).

### TikTok crashes before the discovery dialog appears

If you use Hide My Applist (HMA), check whether it hides Toki
(`io.github.meiyongai.toki`) from TikTok. Logs from the previous implementation
confirmed a startup crash when the discovery dialog could not find the hidden
module package. The current source changes the resource-loading path, but the
affected devices and HMA configuration have not been verified with that change.

Remove Toki from the apps hidden from TikTok, or disable HMA's hiding rules for
TikTok, then force-stop TikTok and reopen it. **Toki does not provide HMA
compatibility support. Do not hide Toki from TikTok while using the module.**

If the crash persists, include your HMA version and the LSPosed and crash logs
from the same launch with your [bug report](#contributing).

### A setting has no effect

Check that Toki is enabled, the correct TikTok package is selected in LSPosed
and the relevant feature is enabled in Toki. Follow any restart instruction on
the feature status page. If adaptation fails, include the TikTok version and
download source in your report.

## Development

[w0201a/Toki-Subtitle](https://github.com/w0201a/Toki-Subtitle) maintains this edition, its issues and releases, with contributions by 154Er and Gi-Ai154M. The original author is [MeiYongAI](https://github.com/MeiYongAI/Toki). The [upstream module catalog](https://github.com/Xposed-Modules-Repo/io.github.meiyongai.toki) distributes upstream builds, not this edition. See [RELEASING.md](RELEASING.md) for this edition’s release process.

### Environment

| Component | Version or requirement |
| --- | --- |
| IDE | Android Studio, or Android SDK command-line tools |
| Gradle runtime | JDK 21; Java source/target level is 17 |
| Android SDK | Platform 37 (`platforms;android-37.0`), Build Tools 37.0.0 |
| Build plugins | Android Gradle Plugin 9.4.0; Kotlin Compose plugin 2.2.10 |
| Gradle | 9.7.1, provided by the wrapper |
| App SDK levels | `compileSdk = 37`, `targetSdk = 35`, `minSdk = 28` |

Dependencies are resolved from Google Maven and Maven Central; Gradle plugins
also use the Gradle Plugin Portal. Set `JAVA_HOME` or Android Studio's Gradle
JDK to JDK 21, and configure the SDK through `ANDROID_HOME` or a local
`local.properties` file. Machine-specific paths stay outside version control.

### Build and test

Clone the repository and install the SDK packages used by CI:

```bash
git clone https://github.com/w0201a/Toki-Subtitle.git
cd Toki-Subtitle
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```

Build a debug APK and run the same verification tasks as
[CI](.github/workflows/build.yml):

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

In Windows PowerShell, use:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

For release-specific lint checks, run `:app:lintRelease` with the wrapper.
Release builds enable R8 and resource shrinking.

| Build | APK output |
| --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release without signing configuration | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release with signing configuration | `app/build/outputs/apk/release/app-release.apk` |

### Sign a release

Copy [signing.properties.example](signing.properties.example) to
`keystore/keystore.properties`, then supply your own keystore path, alias and
credentials. Keystore paths are relative to the repository root. Without this
file, the release APK is unsigned and must be signed before installation.

Keep your keystore and `keystore/keystore.properties` private and backed up.
Updates to the same installed app require the same signing key. The project's
published release certificate fingerprint is:

```text
SHA-256 21:ED:E4:0E:2C:F1:8C:30:C1:9C:B4:1F:89:C0:F6:18:3B:78:05:3E:56:74:26:50:E7:14:CD:E2:A7:26:25:3C
```

### Source layout and adaptation

| Path | Purpose |
| --- | --- |
| `app/src/main/java/io/github/meiyongai/toki/` | Module entry point, hooks, configuration and Compose UI |
| `app/src/main/res/` | Android resources and translations |
| `app/src/main/resources/META-INF/xposed/` | Xposed module entry, API requirements and scope |
| `app/src/main/resources/toki-host-rules.tsv` | Host-code fingerprint rules |
| `app/src/test/java/io/github/meiyongai/toki/` | Unit and resource/layout tests |

<details>
<summary>Feature installation and method-discovery behavior</summary>

Toki selects features from the startup configuration. When all main features
are off, it keeps session diagnostics without business hooks or adaptation
scanning. SIM, language, time zone, GPS, download-path changes, status bar and duration
alerts do not require symbol scanning; watermark-free downloads require source-selection
adaptation. Saved progress-bar sub-options and
region presets do not enable their parent features.

Official host builds use a common set of code fingerprints and member contracts.
There are no adaptation branches selected by version number or download source.
Unmatched code is reported as an adaptation failure.

</details>

## Contributing

Use [GitHub Issues](https://github.com/w0201a/Toki-Subtitle/issues/new/choose) for bug
reports and feature requests, or join the [Telegram group](https://t.me/Toki_Subtitle)
for questions and discussion.

For a bug report, include:

- Toki and TikTok versions, plus the TikTok download source.
- Android version, device model and LSPosed version.
- Reproduction steps, relevant settings, and expected/actual behavior.
- Whether method discovery completed, feature status and relevant LSPosed logs.
  For crashes, include crash logs from the same launch.

Remove personal information before sharing logs or screenshots. Follow the
issue templates; do not upload TikTok APKs or decompiled host code.

Pull requests are welcome. Describe the problem, resulting behavior and how
you verified the change. Run unit tests and lint before submitting, update
both README translations when changing documented behavior, and keep generated
build files, local SDK paths and signing credentials out of commits.

## Support

If Toki is useful to you, you can support development through
[Ko-fi](https://ko-fi.com/meiyongai) or
[Alipay](https://github.com/MeiYongAI/Toki/blob/main/app/src/main/res/drawable-nodpi/alipay.jpg). These are
the original author MeiYongAI’s sponsorship channels, retained to support the upstream work.

For this subtitle edition by 154Er and Gi-Ai154M, use the home-screen sponsor selector: [WeChat Pay](app/src/main/res/drawable-nodpi/sponsor_wechat_154er.jpg) / [Alipay](app/src/main/res/drawable-nodpi/sponsor_alipay_154er.jpg). Sponsorship is optional and does not unlock features.

<details>
<summary>USDT · TRC20 / Tron</summary>

`TXoTeZLpbQdn4wZF51858bC3zCwS822HbB`

Use the TRC20 (Tron) network only. Verify the address and network before sending.

</details>

## Disclaimer

Toki is an independent project, not affiliated with or endorsed by TikTok or
ByteDance. It modifies app behavior and is provided **as is**, without warranties
of compatibility, reliability or account safety. Use it at your own risk and
follow applicable laws and platform terms. Respect creators' rights; download
or reuse content only with permission.

## License

Original author: Copyright © 2026 MeiYongAI. Subtitle edition contributions: © 2026 154Er and Gi-Ai154M. Licensed under the [MIT License](LICENSE).
Dependency credits and licenses are listed in
[Third-party notices](THIRD_PARTY_NOTICES.md).

### Unified APK (2.0.36)

Use the same signed APK for modern Root frameworks and LSPatch local or integrated mode. In integrated mode, updating the standalone module does not update the copy already embedded in TikTok. Long-press the TikTok Home navigation button for in-app settings; long-press subtitles for the subtitle editor.

Build a unified test package with `scripts/build-universal.ps1 -ApiAar <api-102.0.0.aar>`. For official signing add `-PtokiProduction=true` with private signing properties configured. This disables debugging in both the outer package and embedded runtime. The package validator checks both entry points, API namespace isolation and identical discovery profiles. Signing files and device diagnostics must not be committed.
