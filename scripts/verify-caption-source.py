#!/usr/bin/env python3
"""Source-only checks. Does not invoke Gradle, compile Kotlin, run JUnit, or package an APK."""
from pathlib import Path
from collections import Counter
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/io/github/meiyongai/toki"

def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print("PASS " + message)

def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")

base_names = set()
for locale in ("values", "values-b+zh+Hans"):
    names = []
    files = list((ROOT / "app/src/main/res" / locale).glob("*.xml"))
    for xml in files:
        tree = ET.parse(xml)
        strings = [node.attrib["name"] for node in tree.getroot() if node.tag == "string"]
        names.extend(strings)
    check(not [name for name, n in Counter(names).items() if n > 1], locale + " XML parses with unique string names")
    if locale == "values":
        base_names.update(names)
    check({"layout_filter_hint", "layout_filter_empty"} <= set(names), locale + " contains cleanup search labels")

for file in (JAVA / "ui/screen/LayoutCleanupSection.kt", JAVA / "model/LayoutCleanup.kt"):
    references = set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", file.read_text(encoding="utf-8")))
    check(references <= base_names, file.name + " string references exist in default resources")

regions = (JAVA / "model/RegionPresets.kt").read_text(encoding="utf-8")
codes = re.findall(r'RegionPreset\("[^"\n]*", "[^"\n]*", "([A-Z]{2})"', regions)
check(len(codes) > 100, "expanded preset list contains more than 100 country/territory records")
check(len(codes) == len(set(codes)), "country preset ISO codes are unique")
print("INFO preset record count=" + str(len(codes)) + "; not a verified TikTok availability list")
check("uppercase(java.util.Locale.ROOT)" in regions, "country lookup uses locale-independent uppercase")

prefetch = (JAVA / "hook/CaptionPrefetch.kt").read_text(encoding="utf-8")
check("rows.mapValues" not in prefetch and ".take(128).toMap()" in prefetch, "track restore no longer calls mapValues on a List")
check(prefetch.index("CaptionTimelineDiskCache.read(identity)") < prefetch.index("val body = read(location, generation, remote)"), "timeline archive is checked before network")
cache = (JAVA / "hook/VideoCaptionCache.kt").read_text(encoding="utf-8")
restore = cache.split("fun restore(", 1)[1].split("fun diagnostic", 1)[0]
check("activeTracks[video] = source.track" in restore and "clear()" in restore, "restore resets stale state and restores active track ownership")
native = (JAVA / "hook/NativeSubtitleTranslator.kt").read_text(encoding="utf-8")
check('CaptionCacheStorage.save(it, "videos_v2", cache.snapshot())' in native, "translator submits snapshots through background archive writer")
check('if (persistenceScheduled) flushCache()' in native, "pending snapshot is submitted on cancel/pause")
ui = (JAVA / "ui/screen/LayoutCleanupSection.kt").read_text(encoding="utf-8")
check("ConfigClient.addStateListener" in ui and "subscription.close()" in ui, "cleanup UI subscribes and disposes configuration listener")
gradle = read("app/build.gradle.kts")
check('versionCode = 89' in gradle and 'versionName = "2.0.69"' in gradle and 'versionNameSuffix = "-debug"' in gradle, "next debug version is 2.0.69-debug / 89")
for name in ("CaptionCacheCodec.kt", "CaptionCacheStorage.kt", "CaptionTimelineDiskCache.kt"):
    check((JAVA / "hook" / name).is_file(), name + " is present")
plan = (JAVA / "hook/HostFeaturePlan.kt").read_text(encoding="utf-8")
module = (JAVA / "hook/TokiModule.kt").read_text(encoding="utf-8")
scan = (JAVA / "hook/HostScanSession.kt").read_text(encoding="utf-8")
check("fun liveCandidates" in plan and "fun admitLive" in plan, "late feature candidates and admission have separate contracts")
activation = module.split("private fun activateLiveFeatures()", 1)[1]
check(activation.index("install(feature, loader)") < activation.index("plan.admitLive(feature)"), "late activation installs hooks before admitting effective settings")
check("activationInFlight" in activation and "HostScanPhase.SAVING" in activation, "late activation checks both active requests and actual scan phase")
check("publish = false" in module and "if (publish || failed.isNotEmpty())" in module, "configuration refresh does not blindly republish its own notification")
check("status.phase !in setOf(HostScanPhase.SCANNING, HostScanPhase.EXTENSIONS, HostScanPhase.SAVING)" in scan, "incremental scans reject active scan stages")
appearance = (JAVA / "model/SubtitleAppearance.kt").read_text(encoding="utf-8")
style_keys = set(re.findall(r'"(subtitle_[a-z_]+)" to "', appearance.split("val ranges", 1)[0]))
check(len(style_keys) == 14, "shared style contract defines fourteen additional options")
schema = (JAVA / "provider/ConfigSchema.kt").read_text(encoding="utf-8")
check("SubtitleAppearance.defaults.keys" in schema and "SubtitleAppearance.ranges[key]" in schema, "new appearance fields are in import validation and bounded numeric ranges")
preview = (JAVA / "ui/screen/SubtitleSettings.kt").read_text(encoding="utf-8")
overlay = (JAVA / "hook/SubtitleOverlay.kt").read_text(encoding="utf-8")
check("StyledSubtitleTextView" in preview and "StyledSubtitleTextView" in overlay, "preview and playback use the same styled text renderer")
check("SubtitleCaptionPresentation.display" in overlay and '"bilingual"' in preview, "bilingual content mode has both settings and playback wiring")
renderer = (JAVA / "ui/StyledSubtitleTextView.kt").read_text(encoding="utf-8")
draw = renderer.split("override fun onDraw", 1)[1]
check("setTextColor(" not in draw and "invalidate(" not in draw, "outline draw pass does not recolor TextView or request another frame recursively")

store = (JAVA / 'provider/ConfigStore.kt').read_text(encoding='utf-8')
clock = (JAVA / 'provider/ConfigConflictClock.kt').read_text(encoding='utf-8')
check('ConfigConflictClock.KEY_REVISIONS' in store and 'ConfigConflictClock.HOST_BASELINES' in store, 'manager key revisions and host baseline tracking are wired into storage')
check('base.isNotEmpty() && previous != now && suppressed.add(key)' in store, 'conflicting manager changes persist suppression while unavailable manager data does not suppress overrides')
check('keys.filter { it !in suppressed }' in store and 'suppressed.remove(key)' in store, 'superseded overrides stay inactive until the user edits that host setting again')
check('value as Number).toLong() in 0..revision' in clock, 'per-key revisions are bounded by the published snapshot revision')
check('MessageDigest.getInstance("SHA-256")' in clock, 'conflict baselines store digests rather than duplicate setting content')

sticker = (JAVA / 'hook/CommentStickerDownloadHook.kt').read_text(encoding='utf-8')
exporter = (JAVA / 'hook/StickerImageExporter.kt').read_text(encoding='utf-8')
export_policy = (JAVA / 'hook/StickerExportPolicy.kt').read_text(encoding='utf-8')
check('chooseSource' in sticker and 'chooseFormat' in sticker and 'queueExport' in sticker, 'sticker menu exposes source and format selection connected to worker submission')
check(exporter.index('if (mode == StickerExportMode.ORIGINAL)') < exporter.index('ImageDecoder.decodeBitmap'), 'original sticker export bypasses decoding and retains original bytes')
check('source.compress(' in exporter and 'format.extension == mode.extension' in exporter, 'static conversion encodes bitmap bytes and checks output format')
check('ALLOCATOR_SOFTWARE' in exporter and 'setTargetSize' in exporter and 'MAX_EDGE = 2048' in export_policy, 'sticker decoding requests bounded software bitmap dimensions')
check('output.size().toLong() + length <= CommentStickerMedia.MAX_BYTES' in exporter, 'encoded sticker output growth is bounded before writing')
check('drawColor(Color.WHITE)' in exporter and 'StickerExportMode.JPEG && bitmap.hasAlpha()' in exporter, 'JPEG conversion has an explicit transparency-flattening path')
cleanup = (JAVA / 'hook/LayoutCleanupHook.kt').read_text(encoding='utf-8')
cleanup_policy = (JAVA / 'hook/LayoutCleanupPolicy.kt').read_text(encoding='utf-8')
for name in ('FeedSearchBottomBarAssem', 'AdFeedSearchBottomBarAssem', 'FeedEcSearchBottomBarAssem'):
    check(name + '\" to LayoutElement.BOTTOM_SEARCH_RECOMMENDATIONS' in cleanup, name + ' existing binding uses the dedicated bottom-search setting')
check('LayoutElement.BOTTOM_SEARCH_RECOMMENDATIONS -> config.boolean(LayoutElement.SEARCH_LABEL.key)' in cleanup_policy, 'legacy search-suggestion setting remains compatible')
check('owner.clean = LayoutCleanupPolicy.hidden(element, snapshot)' in cleanup, 'bottom search policy participates in the existing live visibility refresh')
check('R.string.layout_bottom_search_recommendations_hint' in ui, 'bottom search settings explain legacy-toggle compatibility')
for file in ('hook/CommentStickerDownloadHook.kt',):
    references = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / file).read_text(encoding='utf-8')))
    check(references <= base_names, file + ' export string references exist in default resources')

share_hook = (JAVA / 'hook/SharePanelCleanupHook.kt').read_text(encoding='utf-8')
share_ui = (JAVA / 'ui/screen/SharePanelCleanupSection.kt').read_text(encoding='utf-8')
share_catalog = (JAVA / 'model/SharePanelItem.kt').read_text(encoding='utf-8')
share_bindings = (JAVA / 'hook/ShareListBindings.kt').read_text(encoding='utf-8')
options = re.findall(r'^    [A-Z_]+\(R\.string\.', share_catalog, re.M)
check(len(options) == 39, 'share catalogue exposes 39 independent named options')
check('"SharePanelCleanupHook" to' in schema and '"SharePanelCleanupHook" -> SharePanelCleanupHook.init' in module, 'share cleanup is registered in configuration and runtime installation')
check('"SharePanelCleanupHook"' in plan.split('val directHost', 1)[1].split('val liveInstallable', 1)[0], 'share cleanup can use verified direct contracts without requiring unrelated symbol scans')
check('SharePanelCleanupSection()' in (JAVA / 'ui/screen/DashboardScreen.kt').read_text(encoding='utf-8'), 'standalone and injected dashboard include the selective share controls')
check('base.declaredConstructors' in share_hook and 'dataMethod(owner.javaClass, base)' in share_hook, 'concrete share setters are discovered from their own verified adapter constructor hierarchy')
check('RecyclerView\\$Adapter' in share_hook, 'nested adapter binary name escapes Kotlin string interpolation')
check('actionKey.invoke(item)' in share_hook and 'channelKey.invoke(item)' in share_hook, 'filtering uses share item business keys rather than visible button text')
check('source.toList()' in share_bindings and 'if (closed || applying) return source' in share_bindings, 'original adapter lists are retained and replay does not replace restoration data')
check('panels.remove(chain.thisObject)' in share_hook and 'let(bindings::forget)' in share_hook, 'share panel lifecycle forgets adapter state on view destruction')
check('CleanViewGate.Owner(hiddenVisibility = View.GONE)' in share_hook, 'recipient-row hiding uses the shared ownership gate')
check('HostViewVisibility.gate.detach(owner)' in share_hook and 'originals.entries.toList().forEach' in share_bindings, 'feature disposal includes original-list and recipient visibility restoration')
check('SharePanelPolicy.CUSTOM_KEYS' in share_ui and 'share_hidden_custom_keys' in schema, 'explicit custom action keys have both editing and schema validation paths')
for relative in ('model/SharePanelItem.kt', 'ui/screen/SharePanelCleanupSection.kt', 'ui/screen/HookStatusModel.kt'):
    references = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(references <= base_names, relative + ' share-cleanup string references exist')

author_hook = (JAVA / 'hook/AuthorLocationHook.kt').read_text(encoding='utf-8')
author_labels = (JAVA / 'hook/AuthorLocationLabels.kt').read_text(encoding='utf-8')
author_contract = (JAVA / 'hook/AuthorLabelContract.kt').read_text(encoding='utf-8')
author_presentation = (JAVA / 'hook/AuthorLabelPresentation.kt').read_text(encoding='utf-8')
check('AuthorLocationLabels.resolve(classLoader)' in author_hook and 'AuthorLocationLabels.install(module, contract)' in author_hook, 'primary author labels are connected before the legacy formatter fallback')
check('getDeclaredMethod("Mq", label.type, CharSequence::class.java)' in author_contract and 'getDeclaredField("LLLIIII")' in author_contract, 'author hook targets the inspected primary text binding rather than all nickname getters')
check('contract.label.get(owner) !== view' in author_labels and 'current.author != entry.author' in author_labels and 'current.video != entry.video' in author_labels, 'hot author refresh verifies both current label and video/author identity')
check('view.text !== entry.rendered' in author_labels and 'view.text.toString() != entry.renderedText' in author_labels, 'native replacement or mutation prevents stale author text restoration')
check('SpannableStringBuilder' in author_presentation and 'private class Prefix' in author_presentation, 'region decoration preserves spans and identifies only module-owned prefixes')
check('view.removeOnAttachStateChangeListener' in author_labels and 'entries.size >= 256' in author_labels, 'author-label state has detach cleanup and a capacity limit')
quick_hook = (JAVA / 'hook/QuickCommentCleanupHook.kt').read_text(encoding='utf-8')
check('NewBottomDefaultCommentInputAssem' in quick_hook and 'getDeclaredMethod("sq", ViewGroup::class.java)' in quick_hook, 'detail-page bottom input has its own inspected creation and attachment path')
check('"QuickCommentCleanupHook" to listOf' in schema and '"QuickCommentCleanupHook" -> QuickCommentCleanupHook.init' in module, 'quick-comment switch is installed independently of layout navigation')
check('.filter { it != io.github.meiyongai.toki.subtitle.model.LayoutElement.QUICK_COMMENT }' in schema, 'quick-comment key is not masked by a second navigation feature owner')
check('tracked.size >= 384' in quick_hook and 'gate.unbind(owner, view)' in quick_hook and 'gate.detach(owner)' in quick_hook, 'quick-comment visibility has bounded tracking and reversible lifetime ownership')

hold_hook = (JAVA / 'hook/HoldSpeedGestureHook.kt').read_text(encoding='utf-8')
hold_contract = (JAVA / 'hook/HoldSpeedContract.kt').read_text(encoding='utf-8')
hold_scope = (JAVA / 'hook/NativeSpeedGestureScope.kt').read_text(encoding='utf-8')
check('ThreadLocal<Int>()' in hold_scope and 'finally' in hold_scope and 'depth.remove()' in hold_scope, 'native speed gate overrides have thread-local nesting and finally restoration')
check('if (mode.active) true else native' in hold_hook and 'if (mode.active) false else native' in hold_hook, 'downward and legacy gates are overridden only within an owned synchronous call')
check('contract.touch' in hold_hook and 'Activity::class.java.getDeclaredMethod("onPause")' in hold_hook and 'dispatchTouchEvent' not in hold_hook, 'gesture extension targets the native speed delegate rather than whole-page touch dispatch')
check('cancelEvent.action = MotionEvent.ACTION_CANCEL' in hold_hook and 'cancelEvent.recycle()' in hold_hook, 'multi-touch cancellation uses a copied event and releases it')
check('contract.pending.invoke(owner, false)' in hold_hook and 'contract.finish.invoke(owner, true)' in hold_hook, 'disable/pause cancellation withdraws a pending toggle before noncommitting native finish')
check('contract.rebound' in hold_hook and 'onViewDetachedFromWindow' in hold_hook, 'native speed sessions are cancelled on content rebind and view detach')
check('if (previous == 0) depth.remove()' in hold_scope and 'sessions.remove(delegate)?.cleanup?.close()' in hold_scope, 'gesture scope and per-session attachment resources are released')
check('"HoldSpeedGestureHook" to listOf' in schema and '"HoldSpeedGestureHook" -> HoldSpeedGestureHook.init' in module, 'hold-speed option is registered in schema and installation')
check('HoldSpeedGestureSection()' in (JAVA / 'ui/screen/DashboardScreen.kt').read_text(encoding='utf-8'), 'standalone and injected dashboard expose the hold-speed option')
check('instanceMethod(component, "qj", Void.TYPE' in hold_contract and 'instanceMethod(component, "nn1", Void.TYPE' in hold_contract, 'gesture contract validates native finish and pending-state cancellation signatures')
for relative in ('ui/screen/HoldSpeedGestureSection.kt',):
    references = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(references <= base_names, relative + ' gesture setting strings exist')

check('HoldSpeedThreshold.pixels(native' in hold_hook and 'maxOf(72f * density, touchSlop * 3f)' in hold_scope, 'disabled experiment thresholds have a local nonzero fallback rather than altering global experiment values')

manual_core = (JAVA / 'hook/ManualCommentTranslation.kt').read_text(encoding='utf-8')
manual_hook = (JAVA / 'hook/ManualCommentTranslateHook.kt').read_text(encoding='utf-8')
manual_dialog = (JAVA / 'hook/ManualCommentDialog.kt').read_text(encoding='utf-8')
check('"ManualCommentTranslateHook" to listOf' in schema and '"ManualCommentTranslateHook" -> ManualCommentTranslateHook.init' in module, 'ordinary-comment fallback is independently configured and installed')
check('"ManualCommentTranslateHook" to setOf(HostSymbol.COMMENT_COPY, HostSymbol.COMMENT_TRANSLATION)' in plan, 'manual translation requires verified comment-menu and translation contracts')
check('"VideoTranslateHook", "CommentTranslateHook", "ManualCommentTranslateHook"' in module, 'manual translation waits for extension preparation rather than failing during core-only installation')
check('comment.getMethod("getText")' in manual_hook and 'getText.invoke(comment)' in manual_hook, 'manual translation reads actual comment-model source text rather than assuming the visible translation is original')
check('Mode.ORIGINAL' in manual_dialog and 'Mode.TRANSLATED' in manual_dialog and 'Mode.BILINGUAL' in manual_dialog, 'manual viewer exposes original, translated and bilingual presentation')
check('ChunkedDescriptionTranslation.split(text)' in manual_core and '6000' in manual_core and 'retryFailed' in manual_core, 'manual translation has bounded segmentation, request timeout and explicit failed-segment retry')
check('generation != token' in manual_core and 'AtomicBoolean(false)' in manual_core and 'session.close()' in manual_dialog, 'closed/restarted dialogs reject stale or duplicate callbacks')
check('if (closed || generation != token || index != current) return\n        try' in manual_core and manual_core.count('if (!closed && generation == token)') >= 3, 'manual state observers are guarded before request dispatch and after rendering (source only)')
check('CommentNativeMenuScope.active' in manual_hook and 'CommentNativeMenuScope.active' in sticker, 'manual and sticker interceptors share the explicit native-menu bypass')
check('CommentNativeMenuScope.native' in manual_hook and 'CommentNativeMenuScope.native' in sticker, 'returning to native actions bypasses both module menus to avoid chooser recursion')
check('SharedPreferences' not in manual_core and 'getSharedPreferences' not in manual_hook and 'CaptionCacheStorage' not in manual_core, 'manual-comment fallback itself adds no offline/disk-cache path')
check('ManualCommentTranslationSection()' in (JAVA / 'ui/screen/DashboardScreen.kt').read_text(encoding='utf-8'), 'standalone and injected settings expose the manual-comment option')
for relative in ('hook/ManualCommentDialog.kt', 'hook/ManualCommentTranslateHook.kt', 'ui/screen/ManualCommentTranslationSection.kt'):
    references = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(references <= base_names, relative + ' manual-translation strings exist')

local_policy = (JAVA / 'hook/TranslationNetworkPolicy.kt').read_text(encoding='utf-8')
local_ticket = (JAVA / 'hook/LocalOnlyRequest.kt').read_text(encoding='utf-8')
check('blocked = !sample()' in local_ticket and 'if (!blocked && !sample()) blocked = true' in local_ticket, 'local-only operation ticket cannot regain remote eligibility after a denial (source only)')
check('!ConfigClient.isReady' in local_policy and 'NET_CAPABILITY_VALIDATED' in local_policy and 'catch (_: Exception) { false }' in local_policy, 'unknown configuration/connectivity/permissions fail closed without a network probe')
check('"subtitle_local_only_enabled" to false' in schema and 'LocalTranslationSection()' in (JAVA / 'ui/screen/DashboardScreen.kt').read_text(encoding='utf-8'), 'local-only mode is persistent, default-off and exposed in both full settings surfaces')
check(native.index('cache[videoId, text, sourceTrack]?.let') < native.index('if (!remote.permitsNetwork()) { localMiss(videoId); return }'), 'subtitle cache hits are checked before denying remote work')
check('val remote = access()' in native and 'if (!remoteAllowed)' in native and 'job.remote.permitsNetwork()' in native, 'subtitle scheduling and result/retry handling consult operation tickets')
transport_body = native.split('return request@{ text, complete ->', 1)[1]
check(transport_body.index('if (!remote.permitsNetwork())') < transport_body.index('serviceField.get(null)') < transport_body.index('method.invoke(service, text)'), 'shared Toki translation transport guards before calling host remote service')
check('val remote = TranslationNetworkPolicy.capture()' in prefetch and 'loadCues(trackUrl, generation, remote)' in prefetch and 'loadCues(track.url, generation, remote)' in prefetch, 'caption operations capture access before submitting asynchronous work')
http_body = prefetch.split('private fun read(', 1)[1].split('fun cancel()', 1)[0]
check(http_body.index('remote.permitsNetwork()') < http_body.index('openConnection()') and http_body.count('remote.permitsNetwork()') >= 3, 'caption redirects/connect/read boundaries contain local-only checks')
check('nextLocalOnly != localOnly' in (JAVA / 'hook/SubtitleFeature.kt').read_text(encoding='utf-8'), 'changing local-only mode cancels subtitle subscriptions and fetches')
check('if (!remote.permitsNetwork())' in manual_hook and 'val guarded:' in manual_hook, 'manual translation reports unavailable local capability before opening a remote-only session')
offline_hook = (JAVA / 'hook/OfflineVideoHook.kt').read_text(encoding='utf-8')
offline_cache = (JAVA / 'hook/HostLocalVideoCache.kt').read_text(encoding='utf-8')
offline_files = (JAVA / 'hook/LocalVideoFile.kt').read_text(encoding='utf-8')
offline_export = (JAVA / 'hook/OfflineVideoExporter.kt').read_text(encoding='utf-8')
offline_contract = (JAVA / 'hook/OfflineVideoContract.kt').read_text(encoding='utf-8')
check('"OfflineVideoHook" to listOf("offline_video_controls_enabled", "offline_comment_button_enabled")' in schema and '"OfflineVideoHook" -> OfflineVideoHook.init' in module and '"HoldSpeedGestureHook", "OfflineVideoHook"' in plan, 'offline local controls have an independent live-installable direct-host unit')
check('getOfflineVideoInfo' in offline_contract and 'isOfflineVideo' in offline_contract and 'offline_mode_page' in offline_contract, 'offline entries use inspected model metadata and exact page event type')
check('OfflinePlaybackContext.state.localOnly' not in local_policy and 'OfflinePlaybackContext.state.offline' not in local_policy and 'playbackRevision' in local_policy, 'offline content no longer forces local-only networking; playback revision still rejects stale operations')
check(offline_hook.index('chain.thisObject?.let(::select) // Local classification') < offline_hook.index('val result = chain.proceed()'), 'offline selection is classified before native selection callbacks')
check('state.owns(owner, id)' in offline_hook and 'state.retire(owner)' in offline_hook and 'previous.view.get() !== video.view' in offline_hook, 'progress only refreshes the selected owner and content classification survives unbinding until a known page transition')
check('ViewConfiguration.getLongPressTimeout()' in offline_hook and 'MotionEvent.ACTION_POINTER_DOWN' in offline_hook and 'MotionEvent.ACTION_CANCEL' in offline_hook and 'videoHit' in offline_hook, 'local menu hold has slop, multitouch, cancellation and interactive-overlay boundaries')
check('cache.resolve(app, video.model)' in offline_hook and 'OfflineVideoExporter.save' in offline_hook and 'ArrayBlockingQueue(1)' in offline_hook, 'offline save action is connected to a bounded local cache/export worker')
check('OfflineCacheCodeGate.verify(context)' in offline_cache and 'getDeclaredMethod("LIZJ"' in offline_cache and '100L, TimeUnit.MILLISECONDS' in offline_cache, 'local cache reader checks inspected code and uses bounded cache-index locking')
check('extractor.setDataSource(input.fd, 0, verified.size)' in offline_cache and 'psshInfo' in offline_cache and 'hasVideoModel' in offline_cache, 'export validates local video tracks and refuses encrypted or model-based split media')
check('cached != total' in offline_files and 'file.length() != total' in offline_files and '0x6d6f6f66' in offline_files and 'source.file.lastModified() != source.modified' in offline_files, 'local exporter checks indexed completeness, container boundaries and source mutation')
check('IS_PENDING, 1' in offline_export and offline_export.index('LocalVideoFile.copy') < offline_export.index('IS_PENDING, 0'), 'gallery publication follows successful local copy rather than publishing partial bytes')
check('Build.VERSION.SDK_INT < 29' in offline_export, 'pre-Android-10 export is explicitly unsupported rather than silently using an unsafe fallback')
for relative in ('hook/OfflineVideoHook.kt', 'hook/HostLocalVideoCache.kt', 'hook/OfflineVideoExporter.kt', 'hook/LocalVideoFile.kt'):
    source = (JAVA / relative).read_text(encoding='utf-8')
    check(not any(token in source for token in ('openConnection(', 'NativeSubtitleTranslator.transport(', '.delete(', 'clearCache(', '.renameTo(')), relative + ' has no module network fallback or cache-deletion call (source inspection only)')
for relative in ('hook/OfflineVideoHook.kt', 'ui/screen/OfflineVideoSection.kt'):
    refs = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(refs <= base_names, relative + ' offline-control string references exist')

check('configurationReady && !localOnly && validatedNetwork' in local_policy and 'allowsRemote(ConfigClient.isReady' in local_policy, 'production network decision uses the explicit-local-only and connectivity rule')
check('SubtitleRemoteResume()' in (JAVA / 'hook/SubtitleOverlay.kt').read_text(encoding='utf-8') and 'translated == null) resetTranslation()' in (JAVA / 'hook/SubtitleOverlay.kt').read_text(encoding='utf-8'), 'visible subtitle render retries a missing translation on observed connectivity restoration')
check('networkResumed && cues.isEmpty()' in prefetch, 'an observed reconnect can retry the missing current subtitle timeline without discarding cached cues')
check('it.key == key && it.remote.permitsNetwork()' in native and '!it.current() || !it.remote.permitsNetwork()' in native, 'reconnect does not reuse a permanently denied old translation subscription')
comment_reader = (JAVA / 'hook/HostOfflineComments.kt').read_text(encoding='utf-8')
comment_data = (JAVA / 'hook/OfflineCommentData.kt').read_text(encoding='utf-8')
comment_dialog = (JAVA / 'hook/OfflineCommentsDialog.kt').read_text(encoding='utf-8')
comment_adapter = (JAVA / 'hook/OfflineCommentListAdapter.kt').read_text(encoding='utf-8')
check('OfflineCommentsDialog.show' in offline_hook and 'offline_comments_open' in offline_hook and 'comments?.dismiss()' in offline_hook, 'cached-comment viewer is connected to offline local menu and owner window cleanup')
check('OfflineCommentCodeGate.verify(context)' in comment_reader and '"offline_comment_" + account' in comment_reader and '"snap_" + video' in comment_reader, 'native cached comments use inspected schema and account/video-specific keys')
check('if (!accountMatches(account))' in comment_reader and 'reader.accountMatches(value.account)' in comment_dialog and 'main.postDelayed(this, 1000)' in comment_dialog, 'account identity is checked after reads and while the cached viewer is visible')
check('contract.snapshotId.get(native) != video' in comment_reader and 'contract.status.getInt(response) != 0' in comment_reader and 'contract.error.getInt(response) != 0' in comment_reader, 'wrong-video and failed native snapshots are not reported as empty successful comments')
check('OfflineCommentJsonBounds.check(raw)' in comment_reader and 'depth <= 64' in comment_data and 'MAX_ROWS = 500' in comment_data and '++visited > 1000' in comment_reader and 'depth < 3' in comment_reader, 'cached JSON depth/size, comment count, traversal and reply nesting are bounded')
check('sourceTruncated' in comment_data and 'Character.isHighSurrogate' in comment_data and 'getOriginalText' in comment_reader and 'getTranslatedText' in comment_reader, 'comment source and translation remain distinct with Unicode-safe display limits')
check('OfflineCommentPages' in comment_adapter and 'setOnScrollListener' in comment_dialog and 'adapter.append()' in comment_dialog and 'snapshot.hasMore || snapshot.truncated' in comment_dialog, 'local pagination and explicit partial-cache states are wired')
check('generation.incrementAndGet()' in comment_dialog and 'generation.get() != token' in comment_dialog and 'worker.shutdown()' in comment_dialog, 'closing/reloading or leaving cached comments invalidates old read completions')
check('Mode.ORIGINAL' in comment_adapter and 'Mode.TRANSLATED' in comment_adapter and 'Mode.BILINGUAL' in comment_adapter and 'row.translation != null' in comment_adapter, 'individual cached-comment original/translation/bilingual choices are wired without pretending a missing translation exists')
for relative in ('hook/HostOfflineComments.kt',):
    source = (JAVA / relative).read_text(encoding='utf-8')
    check(not any(token in source for token in ('openConnection(', 'NativeSubtitleTranslator.transport(', 'OfflineCommentLifecycleListener', '.storeString(', '.remove(', '.delete(')), relative + ' only reads existing comments and does not invoke remote predownload or mutate host storage (source only)')
for relative in ('hook/OfflineCommentsDialog.kt', 'hook/OfflineCommentListAdapter.kt'):
    refs = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(refs <= base_names, relative + ' cached-comment state and action strings exist')

comment_online = (JAVA / 'hook/CachedCommentTranslateHook.kt').read_text(encoding='utf-8')
comment_session = (JAVA / 'hook/CachedCommentTranslationSession.kt').read_text(encoding='utf-8')
comment_store = (JAVA / 'hook/CommentTranslationStore.kt').read_text(encoding='utf-8')
comment_key = (JAVA / 'hook/CommentTranslationKey.kt').read_text(encoding='utf-8')
check('"CachedCommentTranslateHook" to listOf("cached_comment_online_translate_enabled")' in schema and '"CachedCommentTranslateHook" -> CachedCommentTranslateHook.init' in module, 'cached-comment online channel has an independent option and installation entry')
check('"CachedCommentTranslateHook" to setOf(HostSymbol.COMMENT_TRANSLATION)' in plan and '"ManualCommentTranslateHook", "CachedCommentTranslateHook"' in module, 'optional online channel prepares translation extensions without requiring cached reading or comment-copy dependencies')
check('NativeSubtitleTranslator.transport(loader)' in comment_online and 'LLILLIZIL' in comment_online and 'target() != selected' in comment_online, 'online comment channel reuses inspected host transport and validates actual target language')
check('CachedCommentTranslateHook.watch' in comment_dialog and 'translation.close()' in comment_dialog and 'translation.cancel()' in comment_dialog, 'online comment sessions cancel on mode/feature/pause/window/reload lifecycle changes')
check('TranslationNetworkPolicy.capture()' in comment_dialog and 'if (!remote.permitsNetwork())' in comment_dialog and 'requestTranslation' in comment_dialog, 'cached comments submit online text only from an explicit row action after hybrid/local-only checks')
check('OfflineCommentListAdapter(context, ::valid, ::requestTranslation, ::renderSummary)' in comment_dialog and 'cached_comment_translate_action' in comment_adapter and 'fun update(' in comment_adapter, 'per-comment action, segmented progress and original/translation/bilingual result display are connected')
check('CommentTranslationStore.restore' in comment_dialog and 'CommentTranslationStore.save' in comment_dialog and 'adapter.saved(row, target, text, saved)' in comment_dialog, 'local translation restoration and confirmed background commits are connected to comment UI')
check('state.failed == 0' in comment_session and '!state.busy' in comment_session and '!committed' in comment_session and 'token == generation' in comment_session, 'only complete current successful translated results are submitted for persistence, once')
check('listOf(account, video, comment, source, language)' in comment_key and 'SHA-256' in comment_key and 'putInt(bytes.size)' in comment_key, 'persisted result identity includes account/video/comment/exact source/target with length-prefixed hashing')
check('MAX_RECORDS = 2000' in comment_store and 'MAX_CHARACTERS' in comment_store and '.commit()' in comment_store and 'Saved.FULL' in comment_store and '.remove(' not in comment_store and '.clear(' not in comment_store, 'private persistent cache is bounded and reports full/failed writes without eviction/deletion')
for relative in ('hook/OfflineCommentListAdapter.kt', 'hook/OfflineCommentsDialog.kt', 'ui/screen/OfflineVideoSection.kt'):
    refs = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(refs <= base_names, relative + ' online-comment and persistence status strings exist')

page_network = (JAVA / 'hook/HostCommentPageNetwork.kt').read_text(encoding='utf-8')
page_merge = (JAVA / 'hook/CommentPageMerge.kt').read_text(encoding='utf-8')
snapshot_codec = (JAVA / 'hook/CommentSnapshotCodec.kt').read_text(encoding='utf-8')
snapshot_store = (JAVA / 'hook/CommentSnapshotStore.kt').read_text(encoding='utf-8')
check('HostCommentPageNetwork(loader)' in offline_hook and 'commentNetwork, commentReplies, video.model' in offline_hook, 'offline comment viewer receives the current native video model and explicit page request adapter')
check('CommentPageCodeGate.verify(context)' in page_network and 'LJJZZI' in page_network and 'OFFLINE_COMMENT' in page_network and 'contract.request.invoke(null, video, cursor, 20' in page_network, 'comment page adapter uses inspected native request and Rx contracts for one 20-item page')
check(not any(token in page_network for token in ('OfflineCommentLifecycleListener', 'X.0FTg', 'openConnection(', '.remove(', '.clear(', '.delete(')), 'comment page adapter does not invoke the native predownload/cache-clear manager or construct its own HTTP requests')
check('remote.permitsNetwork()' in page_network and 'reader.accountMatches(account)' in page_network and 'contract.id.invoke(model) == video' in page_network and 'once.compareAndSet(false, true)' in page_network, 'page dispatch and response acceptance check explicit-network eligibility, identity and duplicate callbacks')
check('online.setOnClickListener { requestPage(0L) }' in comment_dialog and 'nextPage.setOnClickListener' in comment_dialog and 'main.postDelayed(it, 15000)' in comment_dialog, 'online first/next page buttons and 15-second timeout are wired')
check('CommentPageMerge.merge(old, page, cursor)' in comment_dialog and 'CommentSnapshotStore.save(app, merged)' in comment_dialog and 'CommentSnapshotStore.read(app, account, video)' in comment_dialog, 'received pages merge and persist privately and local reload restores them')
check('if (!active()) Saved.CANCELLED' in snapshot_store and snapshot_store.index('if (!active()) Saved.CANCELLED') < snapshot_store.index('.commit()'), 'snapshot persistence checks cancellation immediately before committing')
check('old.nextCursor == requestedCursor' in page_merge and 'page.nextCursor > requestedCursor' in page_merge and 'old.account == page.account && old.video == page.video' in page_merge, 'page merges reject wrong account/video and non-progressing or mismatched cursors')
check('previous.forEach { values.putIfAbsent(it.id, it) }' in page_merge and 'Comment snapshot capacity reached' in page_merge and 'comment_page_retained_older' in comment_dialog, 'refresh preserves earlier cached rows, reports older unverified rows and refuses overflow instead of evicting')
check('parentId' in comment_data and 'append(replies, depth + 1, id)' in comment_reader and 'children[row.id]' in page_merge and 'comment_page_reply_to' in comment_adapter, 'reply parent identity is preserved through copying, page ordering and row display')
check('MAX_BYTES = 4 * 1024 * 1024' in snapshot_codec and 'input.available() == 0' in snapshot_codec and 'seen.add(id)' in snapshot_codec and 'value.toByteArray(Charsets.UTF_8).contentEquals(raw)' in snapshot_codec, 'private snapshot codec is bounded/versioned and rejects trailing bytes, duplicate rows and malformed UTF-8')
check('all.size >= 100' in snapshot_store and 'characters > 12 * 1024 * 1024' in snapshot_store and '.remove(' not in snapshot_store and '.clear(' not in snapshot_store, 'snapshot store reports capacity without deleting existing account/video archives')
check('cancelPage()' in comment_dialog and 'pageOperation !== op' in comment_dialog and 'generation.get() != token' in comment_dialog and 'configSubscription.close()' in comment_dialog, 'page completions and listeners have owner/generation/configuration cancellation boundaries')

reply_network = (JAVA / 'hook/HostCommentReplyNetwork.kt').read_text(encoding='utf-8')
reply_merge = (JAVA / 'hook/CommentReplyMerge.kt').read_text(encoding='utf-8')
check('HostCommentReplyNetwork(loader)' in offline_hook and 'commentReplies, video.model' in offline_hook and 'replies.fetch(app, reader' in comment_dialog, 'per-root reply request adapter is connected to the selected-video comment dialog')
check('CommentPageCodeGate.verify(context)' in reply_network and 'getDeclaredMethod("LJIJJ"' in reply_network and 'root, cursor, 20' in reply_network, 'native reply adapter checks inspected code and exact authenticated suspend request contracts')
check('getDeclaredMethod("LIZ", context)' in reply_network and 'getDeclaredMethod("LIZJ", scopeType, CancellationException::class.java)' in reply_network and 'operation.attach(AutoCloseable { c.cancelScope.invoke' in reply_network, 'reply requests create an owned native coroutine Job scope and register real scope cancellation')
check('"getContext" -> c.getContext.invoke(continuation)' in reply_network and 'resumed.compareAndSet(false, true)' in reply_network and 'c.resume.invoke(continuation, unit)' in reply_network and 'response === suspended' in reply_network, 'suspend reply bridge preserves native context, single resume and immediate/suspended completion paths')
check('privacyType = type("X.0F2o")' in reply_network and 'nonPersonalized = privacyType.getDeclaredMethod("LJ")' in reply_network and 'type("X.0F3W")' not in reply_network, 'reply request reads the actual native privacy-mode provider rather than mistaking a translation gate for it')
check('root: String? = null' in comment_reader and 'responseRoot != root' in comment_reader, 'native reply responses reject an explicitly different returned root comment')
check('replyCursors: Map<String, ReplyCursor>' in comment_data and 'old.replyCursors[root]?.next == requestedCursor' in reply_merge and 'page.nextCursor > requestedCursor' in reply_merge, 'each reply thread validates its own progressing cursor without borrowing top-level pagination')
check('old.copy(rows = CommentPageMerge.ordered' in reply_merge and 'parent.parentId.isBlank()' in reply_merge and 'before.parentId == parentId' in reply_merge and 'root !in incomingIds' in reply_merge, 'reply merges preserve video cursor, parent ownership and reject root/cross-thread collisions')
check('out.writeInt(2)' in snapshot_codec and 'require(version in 1..2)' in snapshot_codec and 'snapshot.replyCursors.forEach' in snapshot_codec and 'if (version >= 2)' in snapshot_codec, 'snapshot v2 persists per-root reply cursors while still reading v1 records without destructive migration')
check('adapter.loadReplies =' in comment_dialog and 'cell.loadReplies.setOnClickListener' in comment_adapter and 'comment_reply_online_next' in comment_adapter, 'individual root-row first/next/refresh reply actions use the shared timeout/save lifecycle')
check('root = replyRoot' in comment_dialog and 'CommentReplyMerge.merge(checkNotNull(old), replyRoot, page, cursor)' in comment_dialog and 'main.postDelayed(it, 15000)' in comment_dialog, 'reply copy/merge keeps root identity and inherits bounded cancellation/persistence behavior')
check(not any(token in reply_network for token in ('openConnection(', 'OfflineCommentLifecycleListener', '.delete(', 'clearCache(')), 'reply adapter adds no raw HTTP, native predownload or cache-deletion path (source only)')

entry_contract = (JAVA / 'hook/OfflineCommentEntryContract.kt').read_text(encoding='utf-8')
entry_policy = (JAVA / 'hook/OfflineCommentEntryPolicy.kt').read_text(encoding='utf-8')
entry_gate = (JAVA / 'hook/OfflineCommentEntryCodeGate.kt').read_text(encoding='utf-8')
check('KEY_ENTRY = "offline_comment_button_enabled"' in offline_hook and 'it.boolean(KEY) || it.boolean(KEY_ENTRY)' in offline_hook and 'OfflineVideoHook.KEY_ENTRY' in (JAVA / 'ui/screen/OfflineVideoSection.kt').read_text(encoding='utf-8'), 'native offline comment-button option is independently configurable without enabling long press')
check('instance(component, "vq", Void.TYPE, View::class.java, String::class.java)' in entry_contract and 'getDeclaredMethod("sq")' in entry_contract and 'getMethod("getItem")' in entry_contract and '"mEventType"' in entry_contract, 'entry contract reads the inspected ordinary-button method and bound video model instead of guessing resource IDs')
check('selectedVideo == clickedVideo' in entry_policy and 'selectedOffline && clickedOffline && samePage && visible' in entry_policy and 'enabled && inspectedCode' in entry_policy, 'entry policy requires matching selected offline video, inspected code, same page and visible button')
check('module.trackHook(entryFeature, entry.click' in offline_hook and 'if (consume) null else chain.proceed()' in offline_hook and 'entry unavailable; native retained' in offline_hook, 'button hook consumes only a successfully opened cache viewer and otherwise preserves the original call')
check('worker.execute {' in offline_hook and 'OfflineCommentEntryCodeGate.verify(app)' in offline_hook and 'classes5.dex' in entry_gate and 'SHA-256' in entry_gate, 'comment-button code verification is submitted to a background worker before interception is eligible')
check('HookRuntime.dependent(FEATURE, entryFeature)' in offline_hook and 'HookRuntime.install(entryFeature)' in offline_hook and 'entryReady.set(false)' in offline_hook, 'optional button-hook handles and readiness share parent cleanup without disabling cache/long-press siblings on entry failure')
check('fun openComments(target: Binding): Boolean' in offline_hook and 'comments?.isShowing == true' in offline_hook and 'clicked.rootView === target.view.get()?.rootView' in offline_hook, 'long-press and native-button paths reuse the same current-owner dialog and window identity')
check('!ConfigClient.getBoolean(KEY)' in offline_hook and 'comments?.isShowing == true) {' in offline_hook and 'life.longPress' in offline_hook, 'button-only enablement does not start long-press gestures or keep exports active after that option is disabled')
check('getMethod("performClick"' not in entry_contract and 'getMethod("dispatchTouchEvent"' not in entry_contract and 'getResourceEntryName(' not in entry_contract, 'native entry adapter does not hook every view click or use guessed labels/resources')

playback_clock = (JAVA / 'hook/CaptionPlaybackClock.kt').read_text(encoding='utf-8')
subtitle_feature = (JAVA / 'hook/SubtitleFeature.kt').read_text(encoding='utf-8')
subtitle_overlay = (JAVA / 'hook/SubtitleOverlay.kt').read_text(encoding='utf-8')
check('before - positionMs > rewindToleranceMs' in playback_clock and 'rewindToleranceMs: Long = 250' in playback_clock and 'id != video' in playback_clock, 'selected playback clock detects observed backward progress with jitter tolerance, not wall-clock guessing')
check('playback.select(video, owner)' in native and 'playback.progress(video, positionMs)' in native and 'nativeFrames.clear()' in native, 'replay/holder changes invalidate stale native presentation ownership')
check('internal var requestRevision' in native and 'requestRevision++' in native.split('fun cancel()', 1)[1] and 'revision != seenRequestRevision' in subtitle_overlay, 'cancelled translation work invalidates an overlay stuck in already-requested state')
check('framePlaybackRevision != translator.playbackRevision' in subtitle_overlay and 'binding.framePlaybackRevision == binding.translator.playbackRevision' in subtitle_overlay, 'prior-pass native overlays cannot own the custom timeline fallback after observed rewind')
check('translator.playbackRevision == playback' in subtitle_overlay and 'videoId == video' in subtitle_overlay and 'val video = videoId' in subtitle_overlay, 'async subtitle delivery captures the playback pass and video identity rather than reading mutable current identity alone')
check('cacheRevision++' in native and 'cachedRevision != seenCacheRevision' in subtitle_overlay and 'translator?.cached(videoId, caption, selectedTrack)' in subtitle_overlay, 'visible bindings can recover a shared finished translation from cache without a new network request')
invalidation = native.split('private fun invalidatePlaybackPresentation()', 1)[1].split('private val pump', 1)[0]
check('releaseActive()' in invalidation and 'cache.clear()' not in invalidation and 'trackPrefetch.cancel()' not in invalidation, 'replay invalidation cancels stale foreground delivery without clearing completed translations or loaded timelines')
progress_hook = subtitle_feature.split('module.trackHook(prefetchOwner, progress)', 1)[1].split('fun selectedPlayback', 1)[0]
check(progress_hook.index('translator.playbackProgress') < progress_hook.index('chain.proceed()') and 'selection.owns(chain.thisObject, id)' in progress_hook, 'backward progress is classified for the selected holder before native frame callbacks')
selection_hook = subtitle_feature.split('Establish the playback pass before native selection', 1)[1].split('val rootField', 1)[0]
check(selection_hook.index('selectedPlayback(instance)') < selection_hook.index('chain.proceed()') and 'translator.selectVideo(id, instance)' in subtitle_feature, 'selection ownership is established before native first-frame emission and rechecked after the native call')

for relative in ('hook/TranslationNetworkPolicy.kt', 'ui/screen/LocalTranslationSection.kt'):
    references = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(references <= base_names, relative + ' local-only strings exist')

check('if (source.isBlank()) source else null' in manual_core and 'source.any(Char::isLetter)' not in manual_core, 'explicit manual translation does not silently filter emoji-only or numeric comment text')

# A lexical sanity check, not Kotlin syntax/type analysis or compilation.
def kotlin_delimiters(source):
    length = len(source)
    pairs = {')': '(', ']': '[', '}': '{'}
    def code(i, stop=None):
        stack = []
        while i < length:
            if source.startswith('//', i):
                end = source.find('\n', i)
                i = length if end < 0 else end + 1
                continue
            if source.startswith('/*', i):
                depth = 1
                i += 2
                while i < length and depth:
                    if source.startswith('/*', i):
                        depth += 1; i += 2
                    elif source.startswith('*/', i):
                        depth -= 1; i += 2
                    else:
                        i += 1
                if depth: raise AssertionError('Unclosed block comment')
                continue
            if source[i] in ('"', "'"):
                quote = source[i]
                raw = quote == '"' and source.startswith('"""', i)
                token = '"""' if raw else quote
                i += len(token)
                while i < length and not source.startswith(token, i):
                    if not raw and source[i] == '\\':
                        i += 2
                    elif quote == '"' and source.startswith('${', i):
                        i = code(i + 2, '}')
                    elif not raw and source[i] == '\n':
                        raise AssertionError('Newline inside a non-raw string/character literal')
                    else:
                        i += 1
                if i >= length: raise AssertionError('Unclosed string/character literal')
                i += len(token)
                continue
            if source[i] == '`':
                end = source.find('`', i + 1)
                if end < 0: raise AssertionError('Unclosed backtick identifier')
                i = end + 1
                continue
            char = source[i]
            if char in '([{':
                stack.append(char)
            elif char in ')]}':
                if stop == char and not stack: return i + 1
                if not stack or stack.pop() != pairs[char]: raise AssertionError('Unmatched delimiter at character ' + str(i))
            i += 1
        if stack or stop is not None: raise AssertionError('Unclosed delimiters')
        return i
    code(0)

lexical_files = [
    'hook/HostFeaturePlan.kt', 'hook/TokiModule.kt', 'hook/HostSymbols.kt', 'hook/HostScanSession.kt', 'hook/HostScanController.kt',
    'hook/SubtitleOverlay.kt', 'hook/NativeSubtitleTranslator.kt', 'model/SubtitleAppearance.kt',
    'provider/ConfigSchema.kt', 'provider/ConfigStore.kt', 'provider/ConfigConflictClock.kt',
    'ui/StyledSubtitleTextView.kt', 'ui/screen/SubtitleSettings.kt',
    'hook/CommentStickerDownloadHook.kt', 'hook/StickerImageExporter.kt', 'hook/StickerExportPolicy.kt',
    'hook/LayoutCleanupHook.kt', 'hook/LayoutCleanupPolicy.kt', 'model/LayoutCleanup.kt', 'ui/screen/LayoutCleanupSection.kt',
    'hook/SharePanelCleanupHook.kt', 'hook/ShareListBindings.kt', 'model/SharePanelItem.kt', 'ui/screen/SharePanelCleanupSection.kt',
    'hook/AuthorLabelContract.kt', 'hook/AuthorLabelPresentation.kt', 'hook/AuthorLocationLabels.kt', 'hook/QuickCommentCleanupHook.kt',
    'hook/HoldSpeedContract.kt', 'hook/HoldSpeedGestureHook.kt', 'hook/NativeSpeedGestureScope.kt', 'ui/screen/HoldSpeedGestureSection.kt',
    'hook/ManualCommentTranslation.kt', 'hook/ManualCommentTranslateHook.kt', 'hook/ManualCommentDialog.kt',
    'ui/screen/ManualCommentTranslationSection.kt', 'ui/screen/LocalTranslationSection.kt',
    'hook/LocalOnlyRequest.kt', 'hook/TranslationNetworkPolicy.kt', 'hook/HostSettingsEntry.kt', 'hook/SubtitleFeature.kt',
    'hook/OfflinePlaybackState.kt', 'hook/OfflineVideoContract.kt', 'hook/OfflineCacheCodeGate.kt',
    'hook/LocalVideoFile.kt', 'hook/HostLocalVideoCache.kt', 'hook/OfflineVideoExporter.kt',
    'hook/OfflineLongPressState.kt', 'hook/OfflineVideoHook.kt', 'ui/screen/OfflineVideoSection.kt', 'hook/SubtitleRemoteResume.kt',
    'hook/OfflineCommentData.kt', 'hook/OfflineCommentCodeGate.kt', 'hook/HostOfflineComments.kt',
    'hook/OfflineCommentListAdapter.kt', 'hook/OfflineCommentsDialog.kt',
    'hook/CachedCommentTranslateHook.kt', 'hook/CommentTranslationKey.kt', 'hook/CommentTranslationStore.kt',
    'hook/CachedCommentTranslationSession.kt', 'ui/screen/OfflineVideoSection.kt',
    'hook/CommentPageCodeGate.kt', 'hook/HostCommentPageNetwork.kt', 'hook/CommentPageMerge.kt',
    'hook/CommentSnapshotCodec.kt', 'hook/CommentSnapshotStore.kt', 'hook/HostCommentReplyNetwork.kt', 'hook/CommentReplyMerge.kt',
    'hook/OfflineCommentEntryContract.kt', 'hook/OfflineCommentEntryPolicy.kt', 'hook/OfflineCommentEntryCodeGate.kt', 'hook/CaptionPlaybackClock.kt',
]
for relative in lexical_files:
    kotlin_delimiters((JAVA / relative).read_text(encoding='utf-8'))
    check(True, relative + ' lexical delimiters and string literals are balanced')

for relative in (
    'hook/HostFeaturePlanTest.kt', 'hook/HostScanSessionTest.kt',
    'model/SubtitleAppearanceTest.kt', 'ui/StyledSubtitleTextViewTest.kt',
    'provider/ConfigStoreTest.kt', 'provider/ConfigConflictClockTest.kt',
    'hook/CommentStickerMediaTest.kt', 'hook/StickerExportPolicyTest.kt', 'hook/StickerImageExporterTest.kt',
    'hook/LayoutCleanupPolicyTest.kt', 'hook/ShareListBindingsTest.kt', 'model/SharePanelPolicyTest.kt',
    'ui/screen/HookStatusModelTest.kt', 'hook/AuthorLabelPresentationTest.kt', 'hook/QuickCommentCleanupContractTest.kt',
    'hook/HoldSpeedGestureScopeTest.kt', 'hook/ManualCommentTranslationTest.kt',
    'hook/LocalOnlyRequestTest.kt', 'hook/LocalSubtitleExecutionTest.kt', 'hook/FakeOnlineTranslator.kt',
    'hook/NativeSubtitleTranslatorTest.kt', 'hook/SubtitleOverlayTest.kt',
    'hook/OfflinePlaybackStateTest.kt', 'hook/OfflineLongPressStateTest.kt', 'hook/LocalVideoFileTest.kt', 'hook/OfflineVideoHitTest.kt', 'hook/SubtitleRemoteResumeTest.kt',
    'hook/OfflineCommentDataTest.kt', 'hook/OfflineCommentListAdapterTest.kt',
    'hook/CachedCommentTranslationSessionTest.kt', 'hook/CommentTranslationStoreTest.kt', 'hook/CachedCommentOnlineAdapterTest.kt',
    'hook/CommentPageMergeTest.kt', 'hook/CommentSnapshotCodecTest.kt', 'hook/CommentSnapshotStoreTest.kt',
    'hook/CommentReplyMergeTest.kt', 'hook/CommentReplyCursorCodecTest.kt', 'hook/CommentReplyAdapterTest.kt', 'hook/OfflineCommentEntryPolicyTest.kt',
    'hook/CaptionPlaybackClockTest.kt', 'hook/SubtitleReplayRecoveryTest.kt',
):
    test = ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative
    kotlin_delimiters(test.read_text(encoding='utf-8'))
    check(True, relative + ' test-source lexical delimiters are balanced (not executed)')

print("SOURCE CHECKS ONLY: compilation, JUnit, device runtime and TikTok 47.6.3 hooks remain UNVERIFIED.")
