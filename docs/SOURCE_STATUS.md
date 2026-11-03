# Source-only development status — 2.0.61-debug

Updated: 2026-11-03. Source version: **2.0.61-debug / 81**. Latest published production release referenced by this repository remains **2.0.51**.

**No APK was built for this source batch.** Gradle, Kotlin compilation, JUnit/Robolectric and device verification were not run. Source-check success is not compatibility certification for TikTok 47.6.3.

## Current user-directed ordering

The user supplied the full offline acceptance requirements on 2026-11-03. Offline-video work remains the final integrated feature group, but is **no longer awaiting clarification**. The online-comment batch is committed; the local-only execution-boundary preparation is now in source. Full offline page integration remains pending. Existing code is retained. No APK build is authorized.

## Offline acceptance contract (received 2026-11-03; NOT implemented/verified)

This contract supersedes any earlier cache-only interpretation. It is not satisfied by hiding network errors or by invoking the online host translation service.

| Area | Required offline behavior | Current gap |
| --- | --- | --- |
| Video menu | Long press while playing or paused opens locally executable actions; no connectivity-wide disable. | Offline page/menu binding not integrated. |
| Save/export | Export only complete, validated local video bytes to user-accessible storage. Never fetch missing bytes; report incomplete/missing/unsupported caches. Offline cache and user export remain distinct. | Host cache discovery/completeness and local-only export not integrated. |
| Comments | Open cached comments with author information/text and scroll cached pages; distinguish empty, not cached and partially cached pages. | Persistent comment snapshots and offline UI not integrated. |
| Comment translation | Single-comment original/translation/bilingual toggle in the comment area, using only cached output or a ready local engine. | Current manual viewer is online; no bundled local model. |
| Captions | Read original cues, synchronize to playback/seek/replay, show/hide and switch original/translation/bilingual. | Cached tracks alone do not provide offline page/player integration. |
| Caption translation | Read saved translations or run a ready local engine; optional local speech recognition only if actually available. | Module translation/caption cache misses are denied in local-only mode; no bundled local translation/ASR model, and offline page integration is missing. |
| Persistence | Reopening the app retains complete local data/translations and per-video identity without applying stale results to another video. | Caption archives exist; full offline video/comment association is missing. |
| Strict execution | Offline routes must have no remote translation or download fallback, even if Wi-Fi returns mid-operation. Missing data/model/permissions/capability get explicit states. | Module-owned shared translation/caption fetches have a local-only boundary in source. Host offline-entry classification, native remote operations and the remaining call-site/runtime audit are incomplete. |

Required later runtime checks: airplane-mode cold reopen, no available network, network returning during an offline operation, empty/partial caches, missing models, seek/replay/switching videos, interrupted export, comment pagination/toggles, and packet inspection proving no offline-path requests. No such runtime test has been run. Ordinary TikTok background traffic cannot be treated as proof of this module's behavior; the isolated call path must also be checked.

## Changes present in source

- Bounded versioned subtitle translation archives, copied track ownership on restoration, coalesced background writes, and cached source-timeline reads before network requests. Previously cached content can follow the offline path; there is no bundled offline machine-translation model.
- Country presets expanded to 163 countries/territories, with ISO Chinese display-name fallback and locale-independent code normalization. This is **not** an official TikTok availability list or a complete list of states/provinces.
- Cleanup-option search and settings-change subscriptions, plus the template toggle connected to an existing template-button binding. Specific 47.6.3 components remain unverified.
- Dependency preparation and late admission for 15 reversible feature installation units. Requested settings remain masked until successful installation. Startup-sensitive settings retain their restart contract; existing views may require re-entry or another binding callback.
- Shared subtitle preview/playback renderer: outline, shadow, line/letter spacing, corner radius, padding, alignment, line limits and original/translated/bilingual modes. Defaults preserve unrestricted lines and no outline/shadow.
- Per-key manager change clocks and durable suppression of superseded injected overrides. Unrelated changes preserve local choices; an explicit later edit of the same manager setting wins. Stored override values are retained for recovery. Host changes never write into framework read-only preferences. This is **not automatic two-way synchronization**.

## Added in 2.0.61

- A default-off **Toki translations and subtitle files: local data only** control persists through the existing configuration channel. Cached caption files and translations remain readable; misses do not gain remote fallback. Unknown/unready configuration, missing connectivity permissions/capabilities and disconnected/unvalidated networks deny module-owned remote translation/caption requests. No remote connectivity probe is used.
- Operation tickets are captured before asynchronous caption work, manual-comment sessions and subtitle queues. A ticket begun local-only or subsequently observed denied cannot become remote when Wi-Fi returns. Foreground subtitle tasks and prefetch batches guard dispatch; caption HTTP guards opening, redirects and reads; the shared translation transport guards host-service invocation.
- Revoked subtitle results are not displayed, cached or retried. Mode changes cancel subtitle subscriptions and HTTP reads. Missing local translations/engines or subtitle files get foreground notices, with source text retained. Re-entering the video is still advised after changing mode.
- Scope is deliberately narrow: **not a TikTok firewall or complete offline feature**. TikTok-native translation/background traffic, stickers/native downloads, offline video classification while connected, long-press video menus, validated local export, persistent offline comments and a local translation/ASR engine are NOT implemented by this batch. Enable the explicit local-only control when using an offline entry while the device still has a validated network.
- Added policy and subtitle cache-only/revocation regression test sources; existing subtitle fixtures explicitly opt into fake online transports. Source-only checks are not runtime privacy/compatibility certification. No compilation, JUnit/Robolectric, APK build, device or packet inspection was run.

## Added in 2.0.60

- Default-off **Manual comment translation** offers a long-press fallback viewer with an explicit route to native comment actions. It reads Comment.getText() directly, not a rendered translation, and never changes the host comment model or list.
- Original/translation/bilingual display, selectable text, segmented progress and retry-only-failed controls are wired. Requests are explicit per comment, use the existing host translation channel, and have no new disk/offline cache. A 16000-character source cap, <=700-character Unicode-safe chunks, six-second request timeouts and generation/duplicate guards bound the work. Pure numeric/emoji text is not silently excluded; unchanged service results keep the original.
- Closing, pause or disabling cancels work; reentrant rendering callbacks cannot dispatch or requeue work after close/restart; successful segments are retained only within the dialog. Manual and sticker menus share native-return scope to avoid recursive choosers. Menu and translation dependency preparation is ordered behind extension readiness; this is the 19th reversible installation unit.
- Getter signatures were inspected offline and regression sources added, not executed. This is an opt-in fallback window, not a claim that every native comment cell, bulk-translation path, language or offline page is fixed.

## Added in 2.0.59

- New default-off **Hold-to-speed gesture → Slide down to lock / unlock** option uses the inspected 47.6.3 native edge-speed gesture/state machine. It preserves native eligibility, left/right geometry, lock hints and release decisions; it does not replace whole-page touch dispatch or directly overwrite the speed manager.
- Downward-mode and legacy-mode gate overrides are synchronous/thread-local, confined to owned native calls. New sessions begin only on a native single-pointer down event. Cancel/multi-touch, root recreation, data rebind, detach, pause, disable and disposal withdraw pending state and use native noncommitting finish, with delayed-runnable/listener cleanup. Already committed user-selected speed is not reset merely by disabling the extension.
- Disabled experiment groups may return a zero threshold. Valid native values are retained; a local 72dp-or-touch-slop fallback is used only in the owned gesture, without editing experiment storage. This is the 18th reversible installation unit. First activation may need video re-entry, and only locations where TikTok already supports edge hold-speed are eligible. Fixed-speed interaction, layouts and actual gestures remain untested.
- DEX method/field/service descriptors and native branch logic were inspected offline; scope/session/threshold/admission regression sources were added but not executed. No compilation or APK build was run.

## Added in 2.0.58

- Added a primary-author-label binding path inspected directly in the 47.6.3 DEX. It decorates only the component's designated author TextView using current video/author identity. Original nickname spans are retained; owned prefix spans support disable, country changes and repeated binding without stripping similar native names. Text replaced by TikTok is not overwritten during refresh. Detached labels restore and reapply on attachment using current settings; tracking is weak and bounded to 256 labels.
- Author labels and quick-comment cleanup now prepare directly without unrelated navigation/formatter scans. Quick cleanup has one configuration owner and targets the three existing feed variants plus the inspected video-detail bottom-input component and its attach callback. It retains the old quick-comment setting key and UI location, and does not hide the comment button/sheet. Visibility ownership restores/reapplies across detach/attach, with 384 weakly tracked views.
- Exact method/field descriptors were inspected offline. Regression test sources were added, not executed. Actual profile/detail route coverage and Hook behavior remain unverified; first activation can still require re-entering the video page. Missing region data is not invented. The reversible-feature catalogue now has 17 installation units.

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
| Quick-comment hiding on profile/detail and other pages | Independently installed feed variants plus real video-detail bottom-input creation/attachment targets, with reversible view ownership. Runtime, layouts and alternate variants remain unverified. |
| Author country in videos opened from a profile | New primary-author TextView binding with span preservation, current identity checks and restoration is connected from actual 47.6.3 contracts. Profile/detail variants and runtime behavior remain unverified. |
| Templates/effects | Existing template-button mapping wired; template implementation presence confirmed in the APK; effect anchors, variants and runtime behavior still need verification. |
| Individually selectable share actions | 39 named controls, exact custom keys and reversible action/channel adapter filtering now wired to locally inspected 47.6.3 contracts. Runtime, alternative panels and some unnamed actions still need verification; not a whole-panel hide substitute. |
| Settings synchronization and no-restart use | Late registration and per-key conflict handling added; bidirectional manager/host synchronization and all current-page rebinding remain incomplete. |
| Faster/reliable subtitle translation and replay | Cache, track-ownership and write-path changes in source; scheduling, replay/seek and latency need runtime verification. |
| Offline subtitles and translation persistence | Cached source/translation paths and a local-only remote-denial control are in source. No local engine; host offline player/track identity and full local UI still need integration. |
| Offline long-press, export/download and comments | Detailed acceptance requirements received on 2026-11-03; scheduled as the final integrated group. Still not implemented/verified end-to-end; source models and local-byte export need integration. |
| Sticker format/source selection | Source-category selection and real static PNG/JPEG/WebP conversion now wired in source; original bytes retained. Runtime, pixels and API 28–29 coverage unverified. Animated re-encoding is not implemented. |
| Hold-to-speed, downward-slide lock/unlock | Owned native downward-mode path, default-off setting and cancellation lifecycle wired from 47.6.3 contracts. Real gestures, fixed-speed interaction, layout coverage and native-eligibility limitations remain unverified. |
| More video display modes | Existing center/smart/fill retained; additional modes incomplete. |
| Search/inbox/live internal cleanup | Dedicated bottom-of-video search suggestions toggle added to existing targets. This is not search-page/inbox/live internal cleanup; more bindings and runtime checks remain. |
| All comments translatable and translation switching | Default-off ordinary-comment manual fallback viewer with source/translation/bilingual and per-segment retry now connected. Native bulk/cell coverage, special comments and service language quality remain incomplete/unverified. Offline comment snapshots and local-engine integration are pending under the received acceptance contract. |
| More subtitle styles | New renderer/settings/test sources present; compilation, pixel checks, device behavior and full localization unverified. |

## Verification performed

Run the source-only checker with:

    python scripts/verify-caption-source.py

The checker parses default/Simplified-Chinese XML, checks string references and preset uniqueness, inspects selected wiring, and checks lexical delimiter balance in selected Kotlin files. It **does not** compile or execute Kotlin, prove API signatures, run tests, measure translation speed, validate rendered pixels or certify Hook contracts.

Whitespace was checked with `git diff --check`. Regression test sources cover cache archives, restored ownership, feature admission, scan ordering, style bounds/render state, and configuration conflicts; they remain unexecuted in this batch.

## Publication boundaries

Only source, regression sources and curated documentation are published. Local APKs, signing material, credentials, screenshots, decompiled host code, raw device logs, analysis tools and private handoff notes are excluded. No tag, release or APK upload is created for this batch. The source commit uses `[skip ci]` to respect the request not to build yet.
