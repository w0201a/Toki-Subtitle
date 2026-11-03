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
check('versionCode = 83' in gradle and 'versionName = "2.0.63"' in gradle and 'versionNameSuffix = "-debug"' in gradle, "next debug version is 2.0.63-debug / 83")
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
check('"OfflineVideoHook" to listOf("offline_video_controls_enabled")' in schema and '"OfflineVideoHook" -> OfflineVideoHook.init' in module and '"HoldSpeedGestureHook", "OfflineVideoHook"' in plan, 'offline local controls have an independent live-installable direct-host unit')
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
):
    test = ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative
    kotlin_delimiters(test.read_text(encoding='utf-8'))
    check(True, relative + ' test-source lexical delimiters are balanced (not executed)')

print("SOURCE CHECKS ONLY: compilation, JUnit, device runtime and TikTok 47.6.3 hooks remain UNVERIFIED.")
