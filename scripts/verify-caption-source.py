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
check('versionCode = 96' in gradle and 'versionName = "2.0.76"' in gradle and 'versionNameSuffix = "-debug"' in gradle, "next debug version is 2.0.76-debug / 96")
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
check('cache.resolve(app, video.model, exportActive, operation)' in offline_hook and 'OfflineVideoExporter.save' in offline_hook and 'ArrayBlockingQueue(1)' in offline_hook, 'offline video export is connected to a bounded local worker and selected playback cancellation')
check('OfflineCacheCodeGate.verify(context)' in offline_cache and 'getDeclaredMethod("LIZJ"' in offline_cache and '100L, TimeUnit.MILLISECONDS' in offline_cache, 'local cache reader checks inspected code and uses bounded cache-index locking')
check('OfflineExportCandidates(loader).read(aweme)' in offline_cache and 'LocalVideoTracks.inspect(verified, active)' in offline_cache and 'LocalVideoTracks.plan' in offline_cache and 'hasVideoModel' not in offline_cache, 'offline video export checks actual complete local tracks instead of rejecting model-based items categorically')
check('cached != total' in offline_files and 'file.length() != total' in offline_files and '0x6d6f6f66' in offline_files and 'source.file.lastModified() != source.modified' in offline_files, 'local exporter checks indexed completeness, container boundaries and source mutation')
check('IS_PENDING, 1' in offline_export and offline_export.index('LocalVideoRemuxer.write') < offline_export.index('LocalVideoRemuxer.verifyOutput') < offline_export.index('IS_PENDING, 0'), 'gallery publication follows successful local mux and destination readback rather than publishing partial bytes')
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

mode_catalog = (JAVA / 'model/VideoFitMode.kt').read_text(encoding='utf-8')
mode_geometry = (JAVA / 'hook/VideoDisplayGeometry.kt').read_text(encoding='utf-8')
mode_runtime = (JAVA / 'hook/VideoDisplayRuntime.kt').read_text(encoding='utf-8')
mode_bindings = (JAVA / 'hook/VideoLayoutBindings.kt').read_text(encoding='utf-8')
mode_contract = (JAVA / 'hook/SmartVideoLayout.kt').read_text(encoding='utf-8')
immersive = (JAVA / 'hook/ImmersiveFullScreenHook.kt').read_text(encoding='utf-8')
mode_ui = (JAVA / 'ui/screen/VideoDisplayCard.kt').read_text(encoding='utf-8')
check(all('"' + key + '"' in mode_catalog for key in ('center', 'smart', 'fill', 'contain', 'fit_width', 'fit_height')) and 'model.VideoFitMode.keys' in schema, 'all six stable video modes share settings validation keys')
check('VideoFitMode.CONTAIN' in mode_geometry and 'VideoFitMode.WIDTH' in mode_geometry and 'VideoFitMode.HEIGHT' in mode_geometry and 'VideoFillGeometry.calculate' in mode_geometry, 'contain/width/height geometry is real and old smart/fill computation remains reused')
check('mode == VideoFitMode.CENTER) return original' in mode_contract and 'VideoDisplayGeometry.calculate' in mode_contract and 'operatorConstructor.newInstance' in mode_contract, 'selected mode produces the host result type and center returns the exact original object')
check('"ImmersiveFullScreenHook" to setOf("immersive_full_screen")' in plan and '"ImmersiveFullScreenHook" to setOf("immersive_full_screen", "video_fit_mode")' not in plan, 'video mode itself is no longer startup-frozen while the immersive window master switch still is')
check('HookRuntime.dependent("ImmersiveFullScreenHook", VideoDisplayRuntime.FEATURE)' in immersive and 'HookRuntime.install(VideoDisplayRuntime.FEATURE)' in immersive, 'optional video geometry runtime is prepared even in center mode and owned separately from legacy window patches')
check('val original = bindings.original(owner, chain.args[0], reason)' in mode_runtime and 'video.replace(original' in mode_runtime and 'if (!closed && !replaying)' in mode_bindings, 'replay stores only native originals and does not accumulate its own previously transformed output')
check('main.removeCallbacks(refresh); main.post(refresh)' in mode_bindings and 'root.getGlobalVisibleRect(Rect())' in mode_bindings and 'bindings[owner] === binding' in mode_bindings, 'configuration/layout replay is coalesced and limited to measured attached visible current roots')
check('binding.outputs.any { it.get() === value }' in mode_bindings and 'bindings.transformed(owner, args[0])' in mode_runtime, 'recent module-output echoes from host caches resolve back to the native baseline rather than compounding scale')
check('bindings.invalidate(chain.thisObject)' in mode_runtime and 'bindings.forget(chain.thisObject)' in mode_runtime and 'binding.original = null' in mode_bindings, 'data rebinding, detach and release invalidate previous-video layout captures')
check('crop.configure(false)' in mode_runtime and 'mode != VideoFitMode.FILL' in mode_runtime and 'crop::unbind' in mode_runtime, 'leaving fill mode or changing geometry restores prior auto-crop transforms instead of stacking them')
check('HookRuntime.subscribe(FEATURE)' in mode_runtime and 'bindings.requestRefresh()' in mode_runtime and 'main.post { restore() }' in mode_runtime, 'runtime settings and disposal reapply or release tracked layout resources on the UI thread')
check('VideoFitMode.entries' in mode_ui and 'ConfigClient.addStateListener' in mode_ui and 'subscription.close()' in mode_ui and 'if (!editingZoom)' in mode_ui, 'six-mode UI tracks synchronized config changes without resetting an active zoom drag')
check('reports["ImmersiveVideoModes"]' in (JAVA / 'ui/screen/HookStatusModel.kt').read_text(encoding='utf-8') and '|| videoIssue' in (JAVA / 'ui/screen/HookStatusModel.kt').read_text(encoding='utf-8'), 'a failed optional video-mode adapter is surfaced in the parent user-visible feature status')
refs = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', mode_ui))
check(refs <= base_names, 'video display mode labels, descriptions and errors exist in default resources')

for relative in ('hook/TranslationNetworkPolicy.kt', 'ui/screen/LocalTranslationSection.kt'):
    references = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', (JAVA / relative).read_text(encoding='utf-8')))
    check(references <= base_names, relative + ' local-only strings exist')

check('if (source.isBlank()) source else null' in manual_core and 'source.any(Char::isLetter)' not in manual_core, 'explicit manual translation does not silently filter emoji-only or numeric comment text')

# Page component cleanup: source contracts only; these checks do not execute Android hooks.
page_items = (JAVA / 'model/PageCleanupItem.kt').read_text(encoding='utf-8')
page_bindings = (JAVA / 'hook/PageCleanupBindings.kt').read_text(encoding='utf-8')
page_contract = (JAVA / 'hook/PageCleanupContract.kt').read_text(encoding='utf-8')
page_gate = (JAVA / 'hook/PageCleanupCodeGate.kt').read_text(encoding='utf-8')
page_hook = (JAVA / 'hook/PageCleanupHook.kt').read_text(encoding='utf-8')
page_ui = (JAVA / 'ui/screen/PageCleanupSection.kt').read_text(encoding='utf-8')
check(len(re.findall(r'^    (?:SEARCH|INBOX|LIVE)_[A-Z_]+\(PageCleanupGroup', page_items, re.M)) == 6, 'page cleanup catalog has six independent inspected items')
check('"PageCleanupHook" to io.github.meiyongai.toki.subtitle.model.PageCleanupItem.entries.map { it.key }' in (JAVA / 'provider/ConfigSchema.kt').read_text(encoding='utf-8'), 'all page cleanup switches participate in default-off configuration schema')
check('"PageCleanupHook"' in (JAVA / 'hook/HostFeaturePlan.kt').read_text(encoding='utf-8').split('val directHost =', 1)[1].split('\n', 1)[0], 'page cleanup can be installed without a navigation symbol scan')
check('"PageCleanupHook" -> PageCleanupHook.init(this, loader)' in (JAVA / 'hook/TokiModule.kt').read_text(encoding='utf-8') and 'PageCleanupSection()' in (JAVA / 'ui/screen/DashboardScreen.kt').read_text(encoding='utf-8'), 'page cleanup is connected to module installation and dashboard settings')
check('getDeclaredField(name)' in page_contract and '"LLJZ"' in page_contract and '"LLJLIL"' in page_contract, 'search cleanup reads inspected inflated fields instead of ViewStub lifecycle arguments')
check('getField("contentView")' in page_contract and 'actual?.takeIf { root.invoke(instance) === it }' in page_contract, 'widget cleanup refuses getView fallback parent containers')
check('getField("itemView")' in page_contract and 'it.name == "Ip"' in page_contract and '"Kp"' in page_contract, 'inbox cleanup reads returned or rebound holder itemView only')
check('WeakReference(component)' in page_bindings and 'it.component.get() === component' in page_bindings and 'root.parent != null' in page_bindings and 'onViewAttachedToWindow(view: View) { activate(binding, view) }' in page_bindings, 'bounded weak-identity bindings wait for holder attachment and distinguish equality collisions')
check(all(token in page_bindings for token in ('root is ViewStub', 'root is SurfaceView', 'root is TextureView', 'root === root.rootView', 'bindings.size >= capacity')), 'page cleanup excludes stubs, video surfaces and attached window roots and limits retained bindings')
check('gate.unbind(owners.getValue(binding.item), root)' in page_bindings and 'bindings.toList().forEach(::unbind)' in page_bindings and 'removeOnAttachStateChangeListener' in page_bindings, 'root reuse, detach and disposal release visibility ownership and listeners')
check('MessageDigest.getInstance("SHA-256")' in page_gate and 'Thread.currentThread().isInterrupted' in page_gate and 'matched.containsAll(required(it))' in page_gate and page_gate.count(' to "') == 6, 'component code gate verifies six expected DEX digests off the UI thread and supports cancellation')
check('Executors.newSingleThreadExecutor' in page_hook and 'worker.execute' in page_hook and 'main.post' in page_hook and 'worker.shutdownNow()' in page_hook, 'page code verification and disposal are separated from main-thread view updates')
check('val result = chain.proceed()' in page_hook and 'matches.filter { method in it.bound }' in page_hook and 'bindings.release(owner)' in page_hook, 'page native lifecycle proceeds before root binding and releases on unload')
check('requested.intersect(supported)' in page_hook and '正在核验页面组件' in page_hook and '部分功能已注册' in page_hook and 'enabled.isNotEmpty()' in page_hook and 'PageCleanupHook.refreshStatus()' in (JAVA / 'hook/TokiModule.kt').read_text(encoding='utf-8') and 'fun refreshStatus()' in page_hook, 'unverified requested controls retain actual readiness after generic hook diagnostics')
check('ConfigClient.addStateListener' in page_ui and 'subscription.close()' in page_ui and 'ConfigClient.putBoolean' in page_ui and 'PageCleanupGroup.entries' in page_ui and 'page_cleanup_save_error' in page_ui, 'grouped page settings subscribe, save independently and expose persistence failure')
for locale in ('values', 'values-b+zh+Hans'):
    strings = {n.attrib['name']: ''.join(n.itertext()) for n in ET.parse(ROOT / 'app/src/main/res' / locale / 'page_cleanup_strings.xml').getroot() if n.tag == 'string'}
    references = set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', page_items + page_ui)) - {'common_done'}
    check(references <= strings.keys(), locale + ' page cleanup labels and scope limitations exist')
for relative in ('PageCleanupBindingsTest.kt', 'PageCleanupContractTest.kt', 'PageCleanupPolicyTest.kt'):
    check((ROOT / 'app/src/test/java/io/github/meiyongai/toki/hook' / relative).exists(), relative + ' regression source exists (not executed)')

# Full platform country directory is distinct from operational presets and server availability.
country_catalog = (JAVA / 'model/CountryCatalog.kt').read_text(encoding='utf-8')
dashboard = (JAVA / 'ui/screen/DashboardScreen.kt').read_text(encoding='utf-8')
confirmed_edits = (JAVA / 'ui/screen/ConfirmedConfigEdits.kt').read_text(encoding='utf-8')
sim_hook = (JAVA / 'hook/SimHook.kt').read_text(encoding='utf-8')
check('Locale.getISOCountries().toSet() + RegionPresets.ALL_PRESETS.map { it.isoCode }' in country_catalog, 'country directory includes all available platform ISO countries plus existing presets, not just carrier records')
check('Normalizer.Form.NFD' in country_catalog and 'lowercase(Locale.ROOT)' in country_catalog and 'item.chineseName, item.displayName(locale)' in country_catalog, 'country directory search handles Chinese, English, local names, codes and accent/case normalization')
check('"sim_country_only_enabled", item.preset == null' in country_catalog and 'item.preset?.let' in country_catalog and '"00000"' not in country_catalog, 'directory selection writes one country-only or complete-carrier configuration without fake defaults')
check('"sim_country_only_enabled" to false' in schema and '"SimHook" to setOf("sim_spoof_enabled", "sim_country_only_enabled"' in (JAVA / 'hook/HostFeaturePlan.kt').read_text(encoding='utf-8'), 'country-only is default-off, not an independent installation trigger, and follows the SIM startup contract')
check('countryOnly: Boolean = false' in sim_hook and 'if (countryOnly) ""' in sim_hook and '"00000"' not in sim_hook and 'normalizedIso = regionCode.trim().lowercase(java.util.Locale.ROOT)' in sim_hook, 'country-only SIM resolution preserves unavailable operator data and is locale-independent')
check(sim_hook.count('getActiveCarrier().operatorCode.isNotBlank()') == 4 and 'getActiveCarrier().operatorName.isNotBlank()' in sim_hook, 'native operator, name and SIM/phone state are called when country-only has no carrier override')
check(all('fun resolve' + kind + 'ForIso(isoCode: String): ' + value + '?' in regions for kind, value in (('Language', 'String'), ('TimeZone', 'String'), ('Latitude', 'Double'), ('Longitude', 'Double'))) and '?: 35.6762' not in regions and '?: 139.6503' not in regions, 'missing preset metadata is explicit absence, not a different country or coordinate')
for relative, token in (('hook/LocaleHook.kt', 'requestedValue == null'), ('hook/TimeZoneHook.kt', 'requestedValue == null'), ('hook/GpsHook.kt', 'latitude == null || longitude == null')):
    source = (JAVA / relative).read_text(encoding='utf-8')
    check(token in source and 'isEnabled = false' in source, relative + ' leaves native environment values when follow-region metadata is missing')
check('fun reloadDashboard()' in dashboard and 'val current = checkNotNull(ConfigClient.requestedSnapshotOrNull())' in dashboard and 'ConfigClient.addStateListener { value -> if (value != null) reloadDashboard() }' in dashboard and 'subscription.close()' in dashboard and 'Snapshot.withMutableSnapshot' in dashboard, 'dashboard subscribes and reloads one requested snapshot without replacing disconnected values with fake defaults')
reload_body = dashboard.split('fun reloadDashboard() {', 1)[1].split('    DisposableEffect(context)', 1)[0]
check(len(re.findall(r'^            [A-Za-z][A-Za-z0-9]* = current\.(?:boolean|string)\(', reload_body, re.M)) == 58, 'dashboard synchronization covers all 58 persisted top-level fields rather than only newly added controls')
check('try { write(values) } catch' in confirmed_edits and 'try { reload() } catch' in confirmed_edits and 'return failure == null' in confirmed_edits, 'confirmed edit helper reloads after failure and refuses success on write/readback failure')
check('saveValues(CountryCatalog.selection(option))' in dashboard and 'saveValues(CountryCatalog.custom(iso, code, name, onlyCountry))' in dashboard and 'saveValues(mapOf("custom_latitude" to lat, "custom_longitude" to lng))' in dashboard, 'country/carrier and coordinate pairs are saved in one transaction with confirmed dialog closure')
check('CountryCatalog.search(searchQuery, locale)' in dashboard and 'option.preset?.let' in dashboard and 'region_country_only_hint' in dashboard and 'val finalCode = code.ifBlank' not in dashboard, 'directory and custom settings expose country-only capability and reject fabricated carrier defaults')
for locale in ('values', 'values-b+zh+Hans'):
    names = {n.attrib['name'] for n in ET.parse(ROOT / 'app/src/main/res' / locale / 'country_catalog_strings.xml').getroot() if n.tag == 'string'}
    check({'country_catalog_hint', 'region_country_only', 'region_country_only_hint', 'region_environment_missing', 'region_custom_invalid', 'dashboard_save_error'} <= names, locale + ' country capability, missing-data and persistence messages exist')
check(set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', dashboard)) <= base_names, 'all dashboard string references resolve in default resources')
check('RegionEnvironmentPolicy.missingPreset(feature, effective)' in (JAVA / 'hook/TokiModule.kt').read_text(encoding='utf-8') and '&& !missingEnvironment' in (JAVA / 'hook/TokiModule.kt').read_text(encoding='utf-8') and '地区环境预设缺失' in (JAVA / 'ui/screen/HookStatusModel.kt').read_text(encoding='utf-8'), 'missing environment data is diagnosed and not reported as an applied hook')

# User-selected animation work; no compilation/native codec or pixel behavior is certified here.
animation_data = (JAVA / 'hook/AnimatedStickerData.kt').read_text(encoding='utf-8')
animation_containers = (JAVA / 'hook/AnimatedStickerContainers.kt').read_text(encoding='utf-8')
animation_exporter = (JAVA / 'hook/AnimatedStickerExporter.kt').read_text(encoding='utf-8')
animation_decoder = (JAVA / 'hook/HostAnimatedStickerDecoder.kt').read_text(encoding='utf-8')
check((ROOT / 'docs/USER_WORK_ORDER.md').is_file() and '!/docs/USER_WORK_ORDER.md' in read('.gitignore'), 'user-selected four-item work order and deferred backlog are saved for publication')
check('ANIMATED_GIF("gif", "image/gif")' in export_policy and 'ANIMATED_WEBP("webp", "image/webp")' in export_policy and 'val animated:' in export_policy, 'animated GIF and WebP have explicit export intents separate from static modes')
check('source != StickerSource.STATIC && hasAnimation' in export_policy and 'asset.candidates(StickerSource.ANIMATED)' in sticker, 'animated export choices do not silently fall back to a static preview source')
check('AnimatedStickerExporter.convert(context, loader, bytes, mode, active, operation)' in sticker and 'HostAnimatedStickerDecoder.requireInspectedCode(context)' in sticker, 'worker dispatch reaches real animation conversion and checks code availability before downloading animation assets')
check('mode != StickerExportMode.ANIMATED_GIF' in exporter and 'Animation must not use a first-frame encoder' in exporter, 'static encoder rejects animated intent instead of exporting just the first frame')
check('0x3b ->' in animation_data and 'input.position == bytes.size' in animation_data and 'Unsupported GIF disposal' in animation_data and 'GIF plain-text rendering cannot be preserved' in animation_data, 'GIF parsing requires a complete trailer and understood disposal/rendering semantics')
check('NETSCAPE2.0' in animation_data and 'repetitions + 1' in animation_data and 'totalCs = (sourceMs + 5) / 10' in animation_containers, 'GIF repetition normalization and cumulative centisecond rounding are explicit')
check('"ANIM" ->' in animation_data and '"ANMF" ->' in animation_data and 'input.long() == bytes.size.toLong() - 8' in animation_data and 'Frame outside animation canvas' in animation_data, 'WebP parsing checks RIFF length, animation/frame boundaries and canvas geometry')
check('MAX_FRAMES = 300' in animation_data and 'MAX_WORK_PIXELS = 64_000_000L' in animation_data and 'MAX_DURATION_MS = 120_000L' in animation_data and 'MAX_PIXELS = 1_048_576L' in animation_data, 'animation frame, canvas, cumulative pixel and duration budgets are bounded')
check('com.facebook.animated.gif.GifImage' in animation_decoder and 'com.facebook.animated.webp.WebPImage' in animation_decoder and 'ByteArray::class.java' in animation_decoder and 'Native frame geometry differs from container' in animation_decoder, 'inspected host byte-array decoders and concrete native frame geometry are checked')
check('"classes10.dex"' in animation_decoder and '"classes13.dex"' in animation_decoder and 'MessageDigest.getInstance("SHA-256")' in animation_decoder and 'matched == hashes.keys' in animation_decoder, 'host animation JNI contracts are gated by both inspected DEX digests')
check('disposeFrame.invoke(frame)' in animation_decoder and 'disposeImage.invoke(handle)' in animation_decoder and 'HostAnimatedStickerDecoder.open(context, loader, source, bytes).use' in animation_exporter, 'native image and per-frame handles are released including exceptional paths')
check('Disposal.RESTORE' in animation_exporter and 'Disposal.CLEAR' in animation_exporter and 'if (frame.blend) null else replace' in animation_exporter and 'PorterDuff.Mode.SRC' in animation_exporter, 'animation composition implements patch blend, clear and restore-previous before output encoding')
check('output.text("GIF89a")' in animation_containers and 'HashMap<Int, Int>(256)' in animation_containers and 'if (next < 500)' in animation_containers and 'code(256)' in animation_containers, 'GIF writer emits indexed animated image frames with bounded 9-bit LZW dictionary resets')
check('output.text("ANMF")' in animation_containers and 'output.byte(2)' in animation_containers and '"VP8 ", "VP8L"' in animation_containers and 'encodedStaticWebp' in animation_containers, 'animated WebP muxes actual encoded bitstreams into complete non-blended frames')
check('Bitmap.CompressFormat.WEBP_LOSSLESS' in animation_exporter and 'Build.VERSION.SDK_INT >= 30' in animation_exporter and 'canvasBitmap.compress(webpFormat(), 100, output)' in animation_exporter, 'WebP frames use real platform compression with explicit pre-Android-11 fallback semantics')
check('result.frames.size == source.frames.size' in animation_exporter and 'result.plays == source.plays' in animation_exporter and 'abs(expected - actual) <= 5' in animation_exporter and 'result.frames.map { it.durationMs } == source.frames.map { it.durationMs }' in animation_exporter, 'encoded output is reparsed and checked for full frame count, loop, geometry and timing')
check('check(active() && !Thread.currentThread().isInterrupted)' in sticker and 'check(active() && !Thread.currentThread().isInterrupted)' in animation_exporter and 'Animation conversion cancelled' in animation_data, 'conversion observes cancellation before publication and through frame/container processing')
check('sticker_animation_help' in sticker and 'sticker_animation_back' in sticker and 'sticker_animation_failed' in sticker, 'animation limits and failure semantics are visible without replacing the format list with a message')
for locale in ('values', 'values-b+zh+Hans'):
    names = {n.attrib['name'] for n in ET.parse(ROOT / 'app/src/main/res' / locale / 'animated_sticker_strings.xml').getroot() if n.tag == 'string'}
    check({'sticker_export_animated_gif', 'sticker_export_animated_webp', 'sticker_animation_limits', 'sticker_animation_failed', 'sticker_animation_help'} <= names, locale + ' animation labels, format limits and failure strings exist')

# Offline-video export, not an unsolicited cache-format conversion catalogue.
export_candidates = (JAVA / 'hook/OfflineExportCandidates.kt').read_text(encoding='utf-8')
export_tracks = (JAVA / 'hook/LocalVideoTracks.kt').read_text(encoding='utf-8')
export_mux = (JAVA / 'hook/LocalVideoRemuxer.kt').read_text(encoding='utf-8')
export_gate = (JAVA / 'hook/OfflineExportCodeGate.kt').read_text(encoding='utf-8')
check('getDeclaredField' in export_candidates and '"bitRateList"' in export_candidates and '"bitRateAudio"' in export_candidates and '"playAddrValue"' in export_candidates, 'offline export reads inspected raw video/rate/audio fields without computed model expansion')
check('"hitDashAudioBitrate"' in export_candidates and '"hitDashVideoBitrate"' in export_candidates and 'sounds.singleOrNull()' in export_candidates and 'rawSounds.isNotEmpty() || urls.any { selectedAudio.get(it) != null }' in export_candidates, 'explicit host track pairs take precedence and ambiguous/malformed required audio is not silently dropped')
check('"fileHash"' in export_candidates and 'val keys = boundedKeys(listOf(audioHash.get(data)))' in export_candidates and 'fileId/quality are not interchangeable' in export_candidates, 'DASH audio cache lookup uses the hash identified in the native cache reader rather than a guessed fileId')
check('OfflineExportCodeGate.verify(context)' in offline_cache and all(name in export_gate for name in ('classes2.dex', 'classes4.dex', 'classes6.dex', 'classes12.dex')) and 'matched == hashes.keys' in export_gate, 'new raw-field contracts require all four inspected model DEX hashes in addition to existing cache-index gates')
check('fragmented = true; waitingForData = true' in offline_files and 'if (waitingForData)' in offline_files and 'if (!moov || waitingForData' in offline_files, 'complete initialized fragment envelopes reach sample validation while missing init/dangling media remains incomplete')
check('LocalVideoFile.verify(file, active)' in export_tracks and 'extractor.psshInfo' in export_tracks and 'SAMPLE_FLAG_ENCRYPTED' in export_tracks and 'SAMPLE_FLAG_PARTIAL_FRAME' in export_tracks, 'local descriptor sampling rejects DRM/encrypted/partial samples and observes source/cancellation checks')
check('MAX_SAMPLE = 16 * 1024 * 1024' in export_tracks and 'MAX_SAMPLES = 2_000_000L' in export_tracks and 'extractor.readSampleData(buffer, 0)' in export_tracks, 'encoded sample memory/count and actual readable sample sizes are bounded before muxing')
check('requireAudio' in export_candidates and 'needsAudio && !nativeAudio && audio == null' in export_tracks and 'video.tracks.count' in export_tracks and 'audio.file.file == video.file.file' in export_tracks, 'export plan requires one video and any required uniquely paired audio without duplicated source tracks')
check('v.lastUs - v.firstUs + 500_000L < expectedUs' in export_tracks and 'sound.firstUs - v.firstUs' in export_tracks and 'sound.lastUs - v.lastUs' in export_tracks, 'measured sample coverage and audio/video timeline agreement are checked instead of trusting duration headers alone')
check('MessageDigest.getInstance("SHA-256")' in export_tracks and 'hash.update((size ushr' in export_tracks and 'fun sample(buffer: ByteBuffer' in export_tracks, 'track fingerprints include every encoded payload and sample boundary')
check('MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)' in export_mux and 'writer.addTrack' in export_mux and 'muxer.writeSampleData' in export_mux and 'writer.stop()' in export_mux, 'export creates a real indexed MP4 from actual local track samples rather than concatenating files or changing suffixes')
check('val origin = readers.minOf { it.track.firstUs }' in export_mux and 'val presentation = timestamp - origin' in export_mux and 'minByOrNull { it.time }' in export_mux and 'do not sort/reorder samples within a track' in export_mux, 'muxing uses one shared timestamp origin and retains each track decode order, including B-frame presentation order')
check('writer.setOrientationHint(rotation)' in export_mux and 'rotation != track.rotation' in export_mux, 'video rotation is applied and checked on destination readback')
check('digest.count != track.count' in export_mux and 'digest.finish() != track.fingerprint' in export_mux and 'track.firstUs - origin' in export_mux and 'extractor.trackCount != expected.size' in export_mux, 'finished destination is independently read back for expected track count, sample payloads and boundary timestamps')
check('source.file.canonicalFile != source.file' in offline_files and 'Thread.currentThread().isInterrupted' in offline_files and 'plan.verify(active)' in export_mux, 'path identity, source size/time and cancellation checks surround local export')
check('selectedRevision = state.revision' in offline_hook and 'state.revision == selectedRevision && state.owns(owner, target.id)' in offline_hook, 'leaving/reusing selected offline playback invalidates a queued or running export')
check('openFileDescriptor(target, "rw")' in offline_export and 'destination.fileDescriptor.sync()' in offline_export and 'destination.statSize' in offline_export, 'gallery output uses a seekable local descriptor, sync and actual size before readback/publication')
for relative in ('hook/OfflineExportCandidates.kt', 'hook/OfflineExportCodeGate.kt', 'hook/LocalVideoTracks.kt', 'hook/LocalVideoRemuxer.kt'):
    source = (JAVA / relative).read_text(encoding='utf-8')
    check(not any(token in source for token in ('openConnection(', 'getUrlList(', '.delete(', '.renameTo(', 'clearCache(', 'setVideoModel')), relative + ' does not request missing video bytes or alter source cache (source inspection)')

# Readable logs report what was observed, not an invented all-green feature verdict.
log_journal = (JAVA / 'provider/OperationJournal.kt').read_text(encoding='utf-8')
log_text = (JAVA / 'provider/DiagnosticText.kt').read_text(encoding='utf-8')
log_overview = (JAVA / 'provider/DiagnosticOverview.kt').read_text(encoding='utf-8')
log_capture = (JAVA / 'provider/TrialLogCapture.kt').read_text(encoding='utf-8')
trial_log = (JAVA / 'provider/TrialLog.kt').read_text(encoding='utf-8')
hook_runtime = (JAVA / 'hook/HookRuntime.kt').read_text(encoding='utf-8')
check('Instant.ofEpochMilli' in trial_log and 'System.nanoTime()' in trial_log and '#${++sequence}' in trial_log, 'module log lines include UTC time, monotonic elapsed time and ordered sequence numbers')
check('MAX_LINES = 600' in trial_log and 'MAX_CHARACTERS = 96 * 1024' in trial_log and 'dropped++' in trial_log, 'process log line/character budgets declare overwritten history instead of growing indefinitely')
check('DiagnosticText.line(tag' in trial_log and 'DiagnosticText.line(message)' in trial_log and 'safeMessage' in hook_runtime and 'reportEvent(safeTag, safeMessage)' in hook_runtime, 'module log and framework event sinks both receive redacted one-line technical metadata')
check(all(token in log_text for token in ('authorization', 'api[_-]?key', 'cookie', 'Bearer', 'sk-', '本地路径已隐藏', '地址已隐藏')), 'metadata redaction includes URL, credential, bearer-key and private-path patterns')
check('accountId' not in log_journal and 'videoId' not in log_journal and 'OFFLINE_EXPORT' in log_journal and 'STICKER_EXPORT' in log_journal and 'op=${record.kind.name}-${record.id}' in log_journal, 'typed generated operation identifiers correlate video/sticker phases without video/account identifiers')
check('Reason(val title: String, val suggestion: String)' in log_journal and all(token in log_journal for token in ('CODE_MISMATCH', 'CACHE_INCOMPLETE', 'SOURCE_CHANGED', 'OUTPUT_FAILED', 'QUEUE_FULL', 'MEMORY_LIMIT')), 'operation outcomes include readable reason categories and actionable next-step suggestions')
check('record.result != Result.RUNNING' in log_journal and 'record.ended = clock()' in log_journal and '最多16条' in log_journal and 'record.history.size == 16' in log_journal, 'each bounded operation keeps recent phase evidence and a first-wins terminal result/time')
check('capacity: Int = 64' in log_journal and 'overwritten++' in log_journal and 'value.length <= 48 * 1024' in log_journal and 'emitSafely' in log_journal, 'operation journal and exported summary have explicit bounds and logging failures do not fail media work')
check('stats.size <= 8' in log_journal and 'value >= 0' in log_journal and 'error.javaClass.simpleName' in log_journal and 'error.message' not in log_journal, 'structured operation metrics are numeric and exception messages/user content are not copied into the journal')
check(all(token in offline_cache for token in ('Step.CODE_CHECK', 'Step.CANDIDATES', 'Step.CACHE_LOOKUP', 'Step.SAMPLE_CHECK', 'Step.PLAN_READY')) and all(token in offline_export for token in ('Step.MUX', 'Step.READBACK', 'Step.PUBLISH')), 'offline video export reports code, candidate/cache, sample, mux, readback and publication stages')
check('OperationDiagnostics.start(OperationJournal.Kind.OFFLINE_EXPORT)' in offline_hook and 'operation.finish(OperationJournal.Result.SUCCESS)' in offline_hook and 'operation.failed(error, !exportActive())' in offline_hook, 'video export reports business success only after export returns, with cancellation and failures distinguished')
check('OperationDiagnostics.start(OperationJournal.Kind.STICKER_EXPORT)' in sticker and 'operation.failed(error, cancelled)' in sticker and 'Step.SOURCE_FETCH' in sticker and 'Step.ANIMATION_PARSE' in animation_exporter and 'Step.ANIMATION_ENCODE' in animation_exporter, 'sticker jobs report real source/animation progress and terminal codec/cancellation outcomes, not just menu registration')
check('row.getString("state") != safe' in hook_runtime and 'stateAt' in hook_runtime and 'appliedAt' in hook_runtime and '不代表业务效果已验证' in hook_runtime, 'runtime state/config transitions are deduplicated and timestamped without per-sample logging')
check('putString("operations"' in hook_runtime and 'putString("moduleVersion"' in hook_runtime and 'putInt("androidSdk"' in hook_runtime and 'putString("hostVersion"' in hook_runtime, 'host diagnostics include operation summaries and technical module/host/SDK context')
check('已注册并报告应用；实际业务结果另看操作摘要' in log_overview and '请求开启，但未实际应用' in log_overview and '未收到该功能报告' in log_overview, 'human overview distinguishes registration, effective application, unknown evidence and real operation results')
check('requested.forEach features@' in log_overview and 'ConfigSchema.featureSwitches.keys' in log_overview and 'row.getLong("appliedRevision") != report.getLong("configRevision")' in log_overview and '快照超过60秒' in log_overview, 'all configured features, config-revision differences and stale snapshots are represented in the overview')
check((ROOT / 'docs/FEATURE_STATUS_ZH.md').is_file() and 'docs/FEATURE_STATUS_ZH.md' in read('README.md') and '!/docs/FEATURE_STATUS_ZH.md' in read('.gitignore'), 'Chinese at-a-glance source/limitation matrix is saved and linked without declaring runtime completion')
check('00-先看这里-诊断速览.txt' in log_capture and '01-阅读说明.txt' in log_capture and '-operations.txt' in log_capture and 'archive-status.txt' in log_capture, 'diagnostic archive includes a first-read overview, instructions, host operation reports and an archive manifest')
check('root.walkTopDown()' not in log_capture and 'selected-capture' in log_capture and 'if (includeLogcat) synchronized(this) { directory }' in log_capture and '8L * 1024 * 1024' in log_capture and '32L * 1024 * 1024' in log_capture, 'export includes only the current snapshot/explicit selected capture and bounds file/archive bytes instead of attaching all history')
check('DiagnosticText.redact(value)' in log_capture and '完整logcat可能包含其他应用信息' in log_capture and '47.0.3' not in (JAVA / 'ui/screen/TrialLogSection.kt').read_text(encoding='utf-8'), 'JSON is redacted, full-logcat privacy is disclosed and obsolete hardcoded host-version UI is removed')

# Shared standardized "template" renderers are not video-template business components.
template_policy = (JAVA / 'hook/TemplateComponentSemantics.kt').read_text(encoding='utf-8')
check('TemplateComponentSemantics.permitsClassOnlyMapping(name, element)' in cleanup and cleanup.index('TemplateComponentSemantics.permitsClassOnlyMapping(name, element)') < cleanup.index('val type = loader.loadClass(name)'), 'shared-renderer semantic policy runs before class-wide cleanup hook discovery')
check('element != LayoutElement.VIDEO_TEMPLATE || !className.startsWith(SHARED_SCHEMA)' in template_policy and 'next.standardized.template.' in template_policy, 'class-only video-template ownership is refused for the shared UI schema namespace and its variants')
for name in ('FcpStandardTemplateAssem', 'FcpStandardButtonAssem', 'FcpStandardTagAssem'):
    check(re.search(r'"[^"\n]*' + name + r'"\s+to LayoutElement\.VIDEO_TEMPLATE', cleanup) is None, name + ' shared renderer is not unconditionally mapped as a video template')
check('VideoTemplateButtonAssem" to LayoutElement.VIDEO_TEMPLATE' in cleanup, 'inspected video-template-specific component mapping is retained')
selector_tests = read('app/src/test/java/io/github/meiyongai/toki/hook/LayoutComponentSelectorTest.kt')
check('genericStandardRenderersAreRejectedBeforeClassWideTemplateRegistration' in selector_tests and 'inheritedMatchDoesNotByItselfProveTemplateBusinessSemantics' in selector_tests and 'FcpStandardBottomTagVerifyAssem' in selector_tests, 'selector regression sources distinguish inheritance mechanics from template-business ownership (not executed)')
check(not re.search(r'^### .*### ', read('CHANGELOG.md'), re.M), 'changelog headings do not contain duplicated markdown/version prefixes')
check('<CURRENT_COMMIT>' not in read('docs/USER_WORK_ORDER.md'), 'work order identifies a real review baseline rather than a current-commit placeholder')

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
    'hook/TemplateComponentSemantics.kt',
    'provider/DiagnosticText.kt', 'provider/TrialLog.kt', 'provider/OperationJournal.kt', 'provider/DiagnosticOverview.kt',
    'provider/TrialLogCapture.kt', 'ui/screen/TrialLogSection.kt', 'hook/HookRuntime.kt',
    'hook/OfflineExportCandidates.kt', 'hook/OfflineExportCodeGate.kt', 'hook/LocalVideoTracks.kt', 'hook/LocalVideoRemuxer.kt',
    'hook/AnimatedStickerData.kt', 'hook/AnimatedStickerContainers.kt', 'hook/HostAnimatedStickerDecoder.kt', 'hook/AnimatedStickerExporter.kt',
    'model/RegionEnvironmentPolicy.kt', 'model/CountryCatalog.kt', 'model/RegionPresets.kt', 'hook/SimHook.kt', 'hook/LocaleHook.kt', 'hook/TimeZoneHook.kt',
    'hook/GpsHook.kt', 'ui/screen/DashboardScreen.kt', 'ui/screen/ConfirmedConfigEdits.kt',
    'model/PageCleanupItem.kt', 'hook/PageCleanupBindings.kt', 'hook/PageCleanupContract.kt',
    'hook/PageCleanupCodeGate.kt', 'hook/PageCleanupHook.kt', 'ui/screen/PageCleanupSection.kt',
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
    'model/VideoFitMode.kt', 'hook/VideoDisplayGeometry.kt', 'hook/VideoDisplayRuntime.kt', 'hook/VideoLayoutBindings.kt',
    'hook/SmartVideoLayout.kt', 'hook/VideoAutoCrop.kt', 'hook/ImmersiveFullScreenHook.kt', 'ui/screen/VideoDisplayCard.kt', 'ui/screen/HookStatusModel.kt',
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
    'hook/VideoDisplayModesTest.kt', 'hook/VideoLayoutBindingsTest.kt', 'hook/SmartVideoLayoutTest.kt', 'hook/VideoFillGeometryTest.kt',
    'hook/PageCleanupBindingsTest.kt', 'hook/PageCleanupContractTest.kt', 'hook/PageCleanupPolicyTest.kt',
    'model/CountryCatalogTest.kt', 'ui/screen/ConfirmedConfigEditsTest.kt',
    'hook/AnimatedStickerFixtures.kt', 'hook/AnimatedStickerDataTest.kt', 'hook/AnimatedStickerContainersTest.kt',
    'hook/OfflineExportFixtures.kt', 'hook/OfflineExportCandidatesTest.kt', 'hook/LocalVideoTracksTest.kt', 'hook/LocalVideoRemuxerTest.kt',
    'provider/OperationJournalTest.kt', 'provider/DiagnosticTextTest.kt', 'provider/DiagnosticOverviewTest.kt', 'provider/TrialLogCaptureTest.kt',
    'provider/TrialLogTest.kt',
    'hook/LayoutComponentSelectorTest.kt',
):
    test = ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative
    kotlin_delimiters(test.read_text(encoding='utf-8'))
    check(True, relative + ' test-source lexical delimiters are balanced (not executed)')

print("SOURCE CHECKS ONLY: compilation, JUnit, device runtime and TikTok 47.6.3 hooks remain UNVERIFIED.")
