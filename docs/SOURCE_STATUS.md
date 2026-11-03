# Source-only development status — 2.0.65-debug

Updated: 2026-11-03. Source version: **2.0.65-debug / 85**. Latest published production release referenced by this repository remains **2.0.51**.

**No APK was built for this source batch.** Gradle, Kotlin compilation, JUnit/Robolectric and device verification were not run. Source-check success is not compatibility certification for TikTok 47.6.3.

## Current user-directed ordering

The user supplied the full offline acceptance requirements on 2026-11-03. Offline-video work remains the final integrated feature group, but is **no longer awaiting clarification**. The online-comment batch and local-only preparation are committed. The user has since confirmed hybrid networking by default: online translation/data are permitted when connected; saved caches are used when disconnected; explicit local-only remains optional. Selected-cell offline classification, a local menu and complete-MP4 export have now been wired in source; a saved-comment viewer with optional explicit online translation and persistent translated results is now wired as well; missing-comment online loading/native-comment-button/engine/player integration remains pending. Existing code is retained. No APK build is authorized.

## Offline acceptance contract (received and networking policy amended 2026-11-03; full behavior NOT verified)

**Latest user confirmation supersedes the earlier blanket prohibition on remote requests:** when connected, online translation or additional data are allowed; when disconnected, use saved local data and report missing capabilities. Explicit local-data-only mode remains available. Native offline playback/menus/comments and true disconnected operation are still required; hiding errors is not a substitute. The incomplete-video-cache export rule remains separate and unchanged in this batch.

| Area | Required offline behavior | Current gap |
| --- | --- | --- |
| Video menu | Long press while playing or paused opens locally executable actions; no connectivity-wide disable. | Experimental selected VideoViewCell local menu is wired; alternative layouts, paused gestures and conflicts remain unverified. |
| Save/export | Export only complete, validated local video bytes to user-accessible storage. Never fetch missing bytes; report incomplete/missing/unsupported caches. Offline cache and user export remain distinct. | Read-only indexed MP4 cache discovery, completeness checks and Android 10+ local export are wired. Split/encrypted/special MDL caches and API 28 still unsupported. |
| Comments | Open cached comments with author information/text and scroll cached pages; distinguish empty, not cached and partially cached pages. | A dedicated menu opens current-account native persisted snapshots with cached author/text/replies and local pagination. Native button integration and online missing-page loading are still absent. |
| Comment translation | Single-comment original/translation/bilingual toggle in the comment area. Cached/local results work disconnected; online translation may be used when connected unless explicit local-only is enabled. | Per-row original/translation/bilingual, optional explicit online translation and account/source/target-bound private persistence are wired. No bundled local model; native-cell coverage and device validation remain incomplete. |
| Captions | Read original cues, synchronize to playback/seek/replay, show/hide and switch original/translation/bilingual. | Cached tracks alone do not provide offline page/player integration. |
| Caption translation | Read saved translations offline; online translation/caption data may be requested when connected unless explicit local-only is enabled. Local translation/ASR applies only if actually available. | Hybrid eligibility and visible-frame reconnect handling are in source. No bundled local engine, and full offline player integration is missing. |
| Persistence | Reopening the app retains complete local data/translations and per-video identity without applying stale results to another video. | Caption archives exist; full offline video/comment association is missing. |
| Networking policy | Default hybrid mode allows online work when connected. The optional explicit local-only switch prohibits module translation/caption fallback; disconnected paths must report missing data or engines. | Offline content classification no longer forces local-only. Identity-bound operations, cached reads and visible-frame reconnect checks are wired; all offline-page/comment paths and device behavior still need integration/verification. |

Required later runtime checks: airplane-mode cold reopen, no available network, network loss/restoration in hybrid mode and strict explicit-local-only mode, empty/partial caches, missing models, seek/replay/switching videos, interrupted export, comment pagination/toggles, and packet inspection distinguishing allowed hybrid requests from explicit-local-only behavior. No such runtime test has been run. Ordinary TikTok background traffic cannot be treated as proof of this module's behavior; the isolated call path must also be checked.

## Changes present in source

- Bounded versioned subtitle translation archives, copied track ownership on restoration, coalesced background writes, and cached source-timeline reads before network requests. Previously cached content can follow the offline path; there is no bundled offline machine-translation model.
- Country presets expanded to 163 countries/territories, with ISO Chinese display-name fallback and locale-independent code normalization. This is **not** an official TikTok availability list or a complete list of states/provinces.
- Cleanup-option search and settings-change subscriptions, plus the template toggle connected to an existing template-button binding. Specific 47.6.3 components remain unverified.
- Dependency preparation and late admission for 15 reversible feature installation units. Requested settings remain masked until successful installation. Startup-sensitive settings retain their restart contract; existing views may require re-entry or another binding callback.
- Shared subtitle preview/playback renderer: outline, shadow, line/letter spacing, corner radius, padding, alignment, line limits and original/translated/bilingual modes. Defaults preserve unrestricted lines and no outline/shadow.
- Per-key manager change clocks and durable suppression of superseded injected overrides. Unrelated changes preserve local choices; an explicit later edit of the same manager setting wins. Stored override values are retained for recovery. Host changes never write into framework read-only preferences. This is **not automatic two-way synchronization**.

## Added in 2.0.65 — explicit online cached-comment translation and persistence

- Independent default-off **Online translation for cached comments** option prepares the existing host translation extension, with live admission, core-only deferral, status reporting and service rollback. Cached reading remains independent. No user text is submitted just by enabling or opening the viewer; a row action is required, validated connectivity is checked and explicit local-only prevents submission.
- Per-window single-comment segmented translation reuses timeout, duplicate/stale guards and retry-failed-segments behavior. Progress/partial results appear in that row; original/translation/bilingual stays selectable. Unchanged service output is not saved as a new translation. Actual host target language is read and checked, not assumed Chinese. No bundled local model is added.
- Complete successful results are written on a bounded background worker to private Toki preferences and restored on a read worker when reopening comments, including disconnected use. Cache identity hashes length-prefixed account/video/comment/exact source/normalized target. Existing host snapshots/models remain read-only; missing-comment downloading is still not implemented.
- At most 2000 saved results and about 2M characters; full/failed/busy persistence is reported without deleting or evicting earlier user caches. Only a confirmed commit matching the same displayed source/target/output marks that result saved. Partial or stale results never claim complete durable success.
- Close/reload/pause/account-page changes, feature disable and explicit local-only cancel active translation. The original/translated text is not written to public diagnostics. The host translation service, initial adaptation, real latency/language behavior and cold-reopen restoration still need actual device verification.
- 252 source-only and whitespace checks passed. Session/retry/cancellation, source-account-target keying, private restore/capacity and recycled-row precise-save regression sources are added but NOT executed. No compilation, JUnit/Robolectric, device verification or APK build was performed.

## Added in 2.0.64 — saved-comment viewer

- Offline local menu action **Browse cached comments** opens an account-isolated read-only native snapshot viewer. The inspected store DEX is fingerprinted; native current-account identity selects offline_comment_<uid> and snap_<videoId>. The same host Gson/snapshot type parses persisted data, so obfuscated JSON keys are not guessed. Native predownload/network jobs are never called by this viewer.
- Snapshot video identity, native status/error codes and account stability are checked. Missing, empty, partial, corrupt, account-changed and unsupported states are distinct. Cached author names/handles, source text and saved replies are shown in a scrollable recycled list with local pages. At most 500 rows/1000 visited nodes/3 reply levels; raw JSON size/depth and Unicode-safe text lengths are bounded. End of cached rows never claims all online comments were loaded.
- Saved translations support per-row original/translation/bilingual modes with selectable body text. Missing translations retain original text and disable unavailable modes, explicitly stating no bundled local engine. No host models, snapshots, accounts or files are written/deleted. Native snapshot persistence supplies cold-reopen data only when TikTok has actually saved it.
- A bounded read worker and generation guards reject obsolete reload/closed/left-page results; account rechecks clear old window data after account switches. Existing offline feature ownership closes the comment window on page/menu-owner disposal. No avatars/images/profile links are fetched by this viewer.
- Remaining: missing-comment online fill/reply network pagination, online per-row translation with persistent results, native comment-button integration, bundled local translation and all real-device compatibility/airplane-mode tests. This batch implements a dedicated local viewer, not full native-comment replacement. Hybrid networking remains the current policy; the viewer simply has no new online loader yet.
- 233 source-only checks plus whitespace checks passed. Added data/pagination/Unicode/bounds/account and recycled-cell regression sources were NOT executed. No compilation, JUnit/Robolectric, device verification or APK build was run.

## Added in 2.0.63 — current networking policy

- The user explicitly withdrew the blanket no-network restriction and confirmed **online translation/additional data when connected, local caches when disconnected**. Offline-item classification now only chooses context/menu identity, not remote eligibility. An explicit, default-off local-data-only switch remains available. Historical .61/.62 forced-local descriptions below are superseded on this point.
- Existing translation/caption caches remain first. The module network decision checks configuration readiness, the explicit switch and validated connectivity, but not the offline-item flag. Per-operation denial remains latched; a reconnect starts a new eligible operation instead of reviving a denied old subscription. Playback identity still rejects results from another selected video.
- Visible subtitle rendering and current-timeline updates sample access at most once per second. An observed denied-to-allowed transition can retry missing translations or the missing current subtitle file; existing translated text and loaded cue tracks are retained. This is not an unconditional per-frame retry loop. Paused/no-redraw recovery still needs device validation.
- This batch does **not** add an online comment-data loader or bundled local engine. It restores eligibility for existing module translation/caption transports. Complete-local-video export still never auto-downloads missing bytes; that separate rule was not silently changed.
- 212 source-only checks and whitespace checks passed; policy/reconnect regression sources are added but NOT executed. No compilation, JUnit/Robolectric, device, reconnect/packet validation or APK build was run.

## Added in 2.0.62 (historical; forced-local default superseded above)

- Independent default-off **Offline video: local controls (experimental)** unit, live-installable without unrelated scan dependencies. Re-enter the video after enabling. Inspected named VideoViewCell/Aweme/OfflineVideoInfo contracts classify the selected holder before native selection callbacks; exact offline_mode_page is also recognized. Background/adjacent progress cannot replace the owner. Known offline denial survives unbinding until a known next selection/page exit; translation tickets bind the playback revision as well as network eligibility.
- Playing or paused offline video surfaces can open a dedicated local hold menu with complete-cache save and subtitle-settings actions. Ordinary taps/scrolling remain native; movement, multitouch, cancellation, stale holders, page exit and settings disable cancel pending holds. Interactive overlays/inputs are excluded. Native touch cancellation precedes showing the local dialog. No native share/download menu is called.
- Save reads only exact media-key MDL cache-index metadata, with a 100ms lock bound and known DEX SHA-256 gates for the two obfuscated cache-reader files. It does not invoke preload, URL resolution, download, native online export or cache deletion. Native index byte counts, actual file length, canonical storage roots, non-fragmented MP4 box structure and descriptor-based local video-track inspection are checked before copying.
- Android 10+ exports use MediaStore pending rows; publication follows successful local copy. Source length/mtime and operation eligibility are checked while copying. Source caches remain untouched; failed pending outputs are retained rather than silently deleted. Default video export directory or an eligible configured public media directory is used.
- Partial scope: unsupported code variants, API 28 export, model-based/DASH split media, encryption, fragmented/special MDL formats and alternative offline pages are NOT solved by this batch. Missing/incomplete/unsupported caches report their state without network fallback. Offline comments, persistent comment snapshots, in-cell translation toggles, local translation/ASR models and full offline subtitle/player synchronization remain incomplete.
- New policy/admission/hold/file-integrity/hit-target regression sources are unexecuted. Static checks do NOT prove native cache format compatibility, complete playback of a saved file, gesture correctness or zero remote traffic on a device. No compilation, test execution, build, device or packet inspection was performed.

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
| Offline subtitles and translation persistence | Persistent caches, optional local-only and default hybrid online eligibility/reconnect handling are in source. No local engine; host offline player/time identity and full local UI still need integration. |
| Offline long-press, export/download and comments | Selected-cell local menu, indexed complete-MP4 export and current-account cached-comment viewer are wired in source. Real cache/gesture/device validation, split-format/API-28 export, native comment-button integration and online fill remain incomplete. |
| Sticker format/source selection | Source-category selection and real static PNG/JPEG/WebP conversion now wired in source; original bytes retained. Runtime, pixels and API 28–29 coverage unverified. Animated re-encoding is not implemented. |
| Hold-to-speed, downward-slide lock/unlock | Owned native downward-mode path, default-off setting and cancellation lifecycle wired from 47.6.3 contracts. Real gestures, fixed-speed interaction, layout coverage and native-eligibility limitations remain unverified. |
| More video display modes | Existing center/smart/fill retained; additional modes incomplete. |
| Search/inbox/live internal cleanup | Dedicated bottom-of-video search suggestions toggle added to existing targets. This is not search-page/inbox/live internal cleanup; more bindings and runtime checks remain. |
| All comments translatable and translation switching | Ordinary-comment fallback plus cached-comment per-row modes, optional explicit online translation and private persistent results are in source. Native bulk/cell coverage, special comments, missing-comment loading, local engines and all device/service behavior remain incomplete/unverified. |
| More subtitle styles | New renderer/settings/test sources present; compilation, pixel checks, device behavior and full localization unverified. |

## Verification performed

Run the source-only checker with:

    python scripts/verify-caption-source.py

The checker parses default/Simplified-Chinese XML, checks string references and preset uniqueness, inspects selected wiring, and checks lexical delimiter balance in selected Kotlin files. It **does not** compile or execute Kotlin, prove API signatures, run tests, measure translation speed, validate rendered pixels or certify Hook contracts.

Whitespace was checked with `git diff --check`. Regression test sources cover cache archives, restored ownership, feature admission, scan ordering, style bounds/render state, and configuration conflicts; they remain unexecuted in this batch.

## Publication boundaries

Only source, regression sources and curated documentation are published. Local APKs, signing material, credentials, screenshots, decompiled host code, raw device logs, analysis tools and private handoff notes are excluded. No tag, release or APK upload is created for this batch. The source commit uses `[skip ci]` to respect the request not to build yet.
