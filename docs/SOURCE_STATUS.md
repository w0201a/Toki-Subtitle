# Source-only development status — 2.0.57-debug

Updated: 2026-11-03. Source version: **2.0.57-debug / 77**. Latest published production release referenced by this repository remains **2.0.51**.

**No APK was built for this source batch.** Gradle, Kotlin compilation, JUnit/Robolectric and device verification were not run. Source-check success is not compatibility certification for TikTok 47.6.3.

## Changes present in source

- Bounded versioned subtitle translation archives, copied track ownership on restoration, coalesced background writes, and cached source-timeline reads before network requests. Previously cached content can follow the offline path; there is no bundled offline machine-translation model.
- Country presets expanded to 163 countries/territories, with ISO Chinese display-name fallback and locale-independent code normalization. This is **not** an official TikTok availability list or a complete list of states/provinces.
- Cleanup-option search and settings-change subscriptions, plus the template toggle connected to an existing template-button binding. Specific 47.6.3 components remain unverified.
- Dependency preparation and late admission for 15 reversible feature installation units. Requested settings remain masked until successful installation. Startup-sensitive settings retain their restart contract; existing views may require re-entry or another binding callback.
- Shared subtitle preview/playback renderer: outline, shadow, line/letter spacing, corner radius, padding, alignment, line limits and original/translated/bilingual modes. Defaults preserve unrestricted lines and no outline/shadow.
- Per-key manager change clocks and durable suppression of superseded injected overrides. Unrelated changes preserve local choices; an explicit later edit of the same manager setting wins. Stored override values are retained for recovery. Host changes never write into framework read-only preferences. This is **not automatic two-way synchronization**.

## Added in 2.0.57

- Read-only inventory of the original TikTok 47.6.3 APK found the real sharing and requested feed implementations absent from the partial exported source tree. Targeted CLI decompilation identified separate share action/channel adapters and their item key methods. The raw APK/DEX/decompiled output remains private and is not included in the repository.
- New **Share panel cleanup** settings provide 39 independently selectable named options, including the recommended-recipient row, repost/copy, social channels, report/dislike/save/promote/group, captions/speed/clear-screen and others. The 42 catalogue business keys were observed in constant-return key methods in the local APK. Advanced extra keys are explicit, bounded exact matches, not button text or wildcards.
- Concrete setters are hooked only within two inspected share adapter hierarchies. Filtering does not modify host lists in place or replace callbacks; tracked adapters can hot-hide and restore items from the latest native list. Panel view destruction releases tracked adapter references; the recipient row uses shared visibility ownership. First activation requires reopening the share panel once. This adds a 16th reversible installation unit; actual Hook, UI, adapter variants and target-version behavior remain untested.

## Added in 2.0.56

- A dedicated **Layout cleanup → Bottom → Bottom-of-video search suggestions** option now owns the three existing bottom search-bar targets. It does not hide top search. The legacy Search suggestion option still hides those bars; the settings hint explains how to restore them. Actual TikTok 47.6.3 implementation availability and detail/profile variants remain unverified.
- Sticker export now separates animated and static source categories, retains original bytes, and offers actual static PNG/JPEG/WebP conversion. Explicit source choices do not silently cross into the other category. Converted animation is labelled as a single static frame; original export retains the file bytes.
- Software bitmap decoding is bounded to a 2048-pixel longest edge; source dimensions and encoded byte growth are limited. JPEG conversion explicitly places white behind transparent pixels. Native-image regression sources were added but not executed. API signatures were inspected read-only in the installed Android SDK, not compiled or device-tested.

## Requirement tracking

| Requested area | Current evidence / remaining work |
|---|---|
| All supported countries, territories, states | 163 preset records and Chinese ISO fallback in code; official host coverage and subdivision model still incomplete. |
| Quick-comment hiding on profile/detail and other pages | Existing component mappings remain; 47.6.3 APK class presence is now confirmed, but full binding/variant behavior and device coverage remain missing. |
| Author country in videos opened from a profile | Existing author formatter only; alternate detail binding still incomplete. |
| Templates/effects | Existing template-button mapping wired; template implementation presence confirmed in the APK; effect anchors, variants and runtime behavior still need verification. |
| Individually selectable share actions | 39 named controls, exact custom keys and reversible action/channel adapter filtering now wired to locally inspected 47.6.3 contracts. Runtime, alternative panels and some unnamed actions still need verification; not a whole-panel hide substitute. |
| Settings synchronization and no-restart use | Late registration and per-key conflict handling added; bidirectional manager/host synchronization and all current-page rebinding remain incomplete. |
| Faster/reliable subtitle translation and replay | Cache, track-ownership and write-path changes in source; scheduling, replay/seek and latency need runtime verification. |
| Offline subtitles and translation persistence | Cached source/translation path added; uncached content needs network, and host playback must still identify the track. |
| Offline long-press, export/download and comments | Not implemented end-to-end. Comments/replies/translations need persistent models and offline UI integration; downloaded bytes must be proven local. |
| Sticker format/source selection | Source-category selection and real static PNG/JPEG/WebP conversion now wired in source; original bytes retained. Runtime, pixels and API 28–29 coverage unverified. Animated re-encoding is not implemented. |
| Hold-to-speed, downward-slide lock/unlock | Existing speed support only; requested gesture extension incomplete. |
| More video display modes | Existing center/smart/fill retained; additional modes incomplete. |
| Search/inbox/live internal cleanup | Dedicated bottom-of-video search suggestions toggle added to existing targets. This is not search-page/inbox/live internal cleanup; more bindings and runtime checks remain. |
| All comments translatable and translation switching | Existing native gates/poll path retained; ordinary-comment fallback/cache/toggling incomplete. |
| More subtitle styles | New renderer/settings/test sources present; compilation, pixel checks, device behavior and full localization unverified. |

## Verification performed

Run the source-only checker with:

    python scripts/verify-caption-source.py

The checker parses default/Simplified-Chinese XML, checks string references and preset uniqueness, inspects selected wiring, and checks lexical delimiter balance in selected Kotlin files. It **does not** compile or execute Kotlin, prove API signatures, run tests, measure translation speed, validate rendered pixels or certify Hook contracts.

Whitespace was checked with `git diff --check`. Regression test sources cover cache archives, restored ownership, feature admission, scan ordering, style bounds/render state, and configuration conflicts; they remain unexecuted in this batch.

## Publication boundaries

Only source, regression sources and curated documentation are published. Local APKs, signing material, credentials, screenshots, decompiled host code, raw device logs, analysis tools and private handoff notes are excluded. No tag, release or APK upload is created for this batch. The source commit uses `[skip ci]` to respect the request not to build yet.
