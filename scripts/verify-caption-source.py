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

# Baseline language catalog additions must not silently fall back or break Android quote decoding.
def catalog(path):
    return [(node.attrib['name'], ''.join(node.itertext())) for node in ET.parse(path).getroot() if node.tag == 'string']
primary_catalog = dict(catalog(ROOT / 'app/src/main/res/values/strings.xml'))
catalog_errors = []
locale_catalogs = sorted((ROOT / 'app/src/main/res').glob('values-b+*/strings.xml'))
for path in locale_catalogs:
    entries = catalog(path)
    localized = dict(entries)
    if len(entries) != len(localized) or localized.keys() != primary_catalog.keys():
        catalog_errors.append(path.parent.name + ': missing/extra/duplicate baseline keys')
    for name, value in entries:
        source = primary_catalog.get(name, '')
        if not (value.startswith(chr(34)) and value.endswith(chr(34))) or chr(0xfffd) in value:
            catalog_errors.append(path.parent.name + '/' + name + ': quote/unicode')
        if sorted(re.findall(r'%[1-9][0-9]*\$[sd]', source)) != sorted(re.findall(r'%[1-9][0-9]*\$[sd]', value)):
            catalog_errors.append(path.parent.name + '/' + name + ': format placeholders')
check(len(locale_catalogs) == 57 and not catalog_errors, 'all 57 baseline strings.xml catalogs keep complete keys, Android quoting, Unicode and positional placeholders: ' + '; '.join(catalog_errors[:5]))

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
check('versionCode = 141' in gradle and 'versionName = "2.1.21"' in gradle and 'versionNameSuffix = "-debug"' in gradle, "next debug version is 2.1.21-debug / 141")
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
check(len(style_keys) == 15 and "subtitle_bilingual_order" in style_keys, "shared style contract retains fourteen options plus bilingual line order")
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
danmaku = (JAVA / 'hook/DanmakuEntranceHook.kt').read_text(encoding='utf-8')
check('DanmakuCreateEntranceAssem' in danmaku and 'DanmakuSwitchAssem' in danmaku and 'candidate === root' in danmaku, 'danmaku cleanup targets verified entrance fields and refuses shared lifecycle roots')
check('"DanmakuEntranceHook" -> DanmakuEntranceHook.init' in (JAVA / 'hook/TokiModule.kt').read_text(encoding='utf-8') and 'DANMAKU_ENTRANCE(LayoutGroup.SIDE' in (JAVA / 'model/LayoutCleanup.kt').read_text(encoding='utf-8'), 'danmaku entrance has independent settings and host installation wiring')
hdr_hook = (JAVA / 'hook/HdrPlaybackHook.kt').read_text(encoding='utf-8')
check('module.deoptimize(target)' in hdr_hook and 'module.deoptimize(contract.select)' in hdr_hook, 'HDR policy callers are deoptimized so ART cannot silently bypass registered getter hooks')
check('HdrSelectionContract.verify' in hdr_hook and 'selection.selecting(true)' in hdr_hook and 'selection.gate(chain.proceed() as Boolean)' in hdr_hook, 'HDR outer experiment is overridden only inside the inspected enabled selection call')
check('nativeOff || disabled' in hdr_hook and 'isForceHdrOff' in hdr_hook, 'HDR disable preserves native off preference and requests native SDR policy rather than relabeling frames')
check('HdrPlaybackSection()' in (JAVA / 'ui/screen/DashboardScreen.kt').read_text(encoding='utf-8') and '"HdrPlaybackHook" -> HdrPlaybackHook.init' in (JAVA / 'hook/TokiModule.kt').read_text(encoding='utf-8'), 'HDR disable UI and host installation are wired')
photo_targets = (JAVA / 'hook/PhotoIndicatorTargets.kt').read_text(encoding='utf-8')
check('PhotoSwipeHintAssem" to LayoutElement.PHOTO_INDICATORS' not in cleanup and 'PhotoSlideIndicatorAssem" to LayoutElement.PHOTO_INDICATORS' not in cleanup, 'photo behavior owners are never hidden as whole indicator roots')
check('if (element == LayoutElement.PHOTO_INDICATORS)' in cleanup and 'photoIndicators.targets(instance, root).forEach { bind(it, element) }' in cleanup, 'photo cleanup binds inspected indicator children rather than the carousel lifecycle root')
check('candidate === lifecycleRoot' in photo_targets and 'cursor === lifecycleRoot' in photo_targets, 'photo indicator targets exclude callback roots and detached/reused views')

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
check('val adapter = adapterType(recycler)' in share_hook and 'recycler.getMethod("getAdapter").returnType' in share_hook and 'adapter.isAssignableFrom(actionBase)' in share_hook, 'share adapter contract uses the actual obfuscated return type, not a guessed nested class name')
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
author_bindings = (JAVA / 'hook/AuthorLabelBindings.kt').read_text(encoding='utf-8')
author_native = (JAVA / 'hook/AuthorDisplayedUserContract.kt').read_text(encoding='utf-8')
check('label(owner) !== view' in author_bindings and 'current(owner) != data' in author_bindings and 'uid.invoke(latestUser) != userId' in author_native, 'author refresh rechecks the primary label and actual displayed-user identity after module writes')
check('body == expected' in author_bindings and 'entry.witnessBody == body' in author_bindings and 'AuthorLabelPresentation.ownedPrefix' in author_bindings, 'native text changes require a current native-name match or a genuine raw binding witness; restoration strips only owned spans')
check('SpannableStringBuilder' in author_presentation and 'private class Prefix' in author_presentation, 'region decoration preserves spans and identifies only module-owned prefixes')
check('view.removeOnAttachStateChangeListener' in author_bindings and 'entries.size >= capacity' in author_bindings and 'WeakReference(view)' in author_bindings, 'bounded author-label state detaches observers and does not retain views through listener values')
check('loader.loadClass("X.093W")' in author_labels and 'loader.loadClass("X.093E")' in author_labels and 'resolved.itemBound' in author_labels, 'author installation observes the inspected direct setText callbacks as well as Mq and item/view lifecycle paths')
check('field(reused, "cellProxy")' in author_native and 'field(cell.type, "LL")' in author_native and 'usesProfileUser.invoke' in author_native and 'selected.invoke' in author_native, 'native author data comes from the currently bound cell and honors the actual homepage collaborator experiment')
check('getNickname' in author_native and 'getUniqueId' in author_native and 'WeakReference(item)' in author_native and 'it.item.get() === item' in author_native and 'names.size >= 256' in author_native, 'native display-name caching is bounded, weak and tied to item/name/context rather than invoking the formatter every frame')
author_gate = (JAVA / 'hook/AuthorLocationCodeGate.kt').read_text(encoding='utf-8')
check('60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff' in author_gate and 'classes5.dex' in author_gate and 'AuthorLocationCodeGate.verify(app)' in author_labels and 'bindings.configure(ready' in author_labels, 'new native author helpers remain inactive until the inspected 47.6.3 code is matched')
check('AuthorLocationLabels.resolve(param.classLoader, quiet = true)' in module and 'AuthorLocationLabels.refreshStatus()' in module, 'known-host author observation prepares while disabled and republishes actual readiness after generic status updates')
country_display = (JAVA / 'model/CountryDisplayCode.kt').read_text(encoding='utf-8')
check('getISO3Country()' in country_display and 'Locale.ROOT' in country_display and 'code == "UK"' in country_display and 'CountryDisplayCode.resolve(region)' in author_hook, 'country label display canonicalizes known alpha-three codes and UK without changing environment configuration')
for name, markers in {
 'hook/AuthorDisplayedUserContractTest.kt': ('homepageCollaboratorRegionBelongsToTheActuallyDisplayedUser', 'nativeProfileExperimentAndFallbackAreHonoredRatherThanForcingAProfileUser', 'rebindingWhileFormattingDropsTheOldResult'),
 'hook/AuthorLabelBindingsTest.kt': ('directNativeTextRouteDecoratesOnlyTheProvenPrimaryLabel', 'holderReuseCannotPutTheNextCountryOnThePreviousNativeName', 'synchronousNativeRebindDuringModuleWriteNeverKeepsTheOldCountry'),
 'model/CountryDisplayCodeTest.kt': ('alphaThreeCodesAndUkAliasUseCanonicalChineseDisplayWithoutChangingEnvironmentSelection', 'unknownSubdivisionAndOrdinaryNamesArePreservedNotFabricatedAsCountries'),
}.items():
 check(all(marker in read('app/src/test/java/io/github/meiyongai/toki/' + name) for marker in markers), name + ' covers native author routes and conservative label identity (source only)')
quick_hook = (JAVA / 'hook/QuickCommentCleanupHook.kt').read_text(encoding='utf-8')
check('NewBottomDefaultCommentInputAssem' in quick_hook and 'getDeclaredMethod("sq", ViewGroup::class.java)' in quick_hook, 'detail-page bottom input has its own inspected creation and attachment path')
check('"QuickCommentCleanupHook" to listOf' in schema and '"QuickCommentCleanupHook" -> QuickCommentCleanupHook.init' in module, 'quick-comment switch is installed independently of layout navigation')
check('.filter { it !in setOf(io.github.meiyongai.toki.subtitle.model.LayoutElement.QUICK_COMMENT,' in schema and 'LayoutElement.VIDEO_TEMPLATE,' in schema and 'LayoutElement.DANMAKU_ENTRANCE) &&' in schema and 'it !in io.github.meiyongai.toki.subtitle.hook.TemplateAnchorPolicy.controls }.map' in schema, 'quick-comment and template keys are excluded from the second navigation feature owner')
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
check('comment.getMethod("getText")' in manual_hook and 'getter.invoke(comment)' in manual_hook and 'sourceText(comment, getText)' in manual_hook, 'manual translation reads actual comment-model source text rather than assuming the visible translation is original')
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
check('val remote = TranslationNetworkPolicy.capture()' in prefetch and 'fetch(trackUrl, generation, remote)' in prefetch and 'fetch(track.url, generation, remote)' in prefetch and '?: loadCues(location, generation, remote)' in prefetch, 'caption operations capture access before submitting asynchronous work')
http_body = prefetch.split('private fun read(', 1)[1].split('fun cancel()', 1)[0]
check(http_body.index('remote.permitsNetwork()') < http_body.index('openConnection()') and http_body.count('remote.permitsNetwork()') >= 3, 'caption redirects/connect/read boundaries contain local-only checks')
check('nextLocalOnly != localOnly' in (JAVA / 'hook/SubtitleFeature.kt').read_text(encoding='utf-8'), 'changing local-only mode cancels subtitle subscriptions and fetches')
check('if (!remote.permitsNetwork())' in manual_hook and 'val guarded:' in manual_hook, 'manual translation reports unavailable local capability before opening a remote-only session')
offline_hook = (JAVA / 'hook/OfflineVideoHook.kt').read_text(encoding='utf-8')
offline_cache = (JAVA / 'hook/HostLocalVideoCache.kt').read_text(encoding='utf-8')
offline_files = (JAVA / 'hook/LocalVideoFile.kt').read_text(encoding='utf-8')
offline_export = (JAVA / 'hook/OfflineVideoExporter.kt').read_text(encoding='utf-8')
mdl_files = (JAVA / 'hook/OfflineMdlFile.kt').read_text(encoding='utf-8')
mdl_source = (JAVA / 'hook/OfflineMdlDataSource.kt').read_text(encoding='utf-8')
native_files = (JAVA / 'hook/HostOfflineVideoFiles.kt').read_text(encoding='utf-8')
check('WHERE sec_uid=? AND aid=? AND has_cached=1 LIMIT 2' in native_files and 'OPEN_READONLY' in native_files and 'LJIIIIZZ' in native_files, 'native offline export binds current account, selected video and complete record without a broad scan')
check(offline_cache.index('HostOfflineVideoFiles(loader).find') < offline_cache.index('val helper = Class.forName') and 'OfflineMdlFile.inspect' in offline_cache, 'native offline record and retained-header media take precedence over ordinary MDL index')
check('q(56) != total || q(64) != total' in mdl_files and 'contentEquals(keyBytes)' in mdl_files and 'prefix.any' in mdl_files and 'source != it.canonicalFile' in mdl_files, 'retained-header reader rejects incomplete, wrong-key, conflicting-prefix and outside-storage input')
check('MediaDataSource()' in mdl_source and 'readFully(buffer, offset, count)' in mdl_source and 'restoration?.overlay' in mdl_source and '"r"' in mdl_source, 'virtual local MP4 restores exact header ranges using read-only media access')

offline_contract = (JAVA / 'hook/OfflineVideoContract.kt').read_text(encoding='utf-8')
check('"OfflineVideoHook" to listOf("offline_video_controls_enabled", "offline_comment_button_enabled")' in schema and '"OfflineVideoHook" -> OfflineVideoHook.init' in module and '"HoldSpeedGestureHook", "OfflineVideoHook"' in plan, 'offline local controls have an independent live-installable direct-host unit')
check('getOfflineVideoInfo' in offline_contract and 'isOfflineVideo' in offline_contract and 'offline_mode_page' in offline_contract, 'offline entries use inspected model metadata and exact page event type')
check('OfflinePlaybackContext.state.localOnly' not in local_policy and 'OfflinePlaybackContext.state.offline' not in local_policy and 'playbackRevision' in local_policy, 'offline content no longer forces local-only networking; playback revision still rejects stale operations')
offline_selection = offline_hook.split('for (method in listOf(contract.selected, contract.resumed))', 1)[1].split('module.trackHook(FEATURE, contract.progress', 1)[0]
check(offline_selection.index('chain.thisObject?.let(::select) // Local classification') < offline_selection.index('val result = chain.proceed()'), 'offline selection is classified before native selection callbacks')
check('state.owns(owner, id)' in offline_hook and 'state.retire(owner)' in offline_hook and 'previous.view.get() !== video.view' in offline_hook, 'progress only refreshes the selected owner and content classification survives unbinding until a known page transition')
check('ViewConfiguration.getLongPressTimeout()' in offline_hook and 'MotionEvent.ACTION_POINTER_DOWN' in offline_hook and 'MotionEvent.ACTION_CANCEL' in offline_hook and 'videoHit' in offline_hook, 'local menu hold has slop, multitouch, cancellation and interactive-overlay boundaries')
check('cache.resolve(app, video.model, exportActive, operation)' in offline_hook and 'OfflineVideoExporter.save' in offline_hook and 'ArrayBlockingQueue(1)' in offline_hook, 'offline video export is connected to a bounded local worker and selected playback cancellation')
check('OfflineCacheCodeGate.verify(context)' in offline_cache and 'getDeclaredMethod("LIZJ"' in offline_cache and '100L, TimeUnit.MILLISECONDS' in offline_cache, 'local cache reader checks inspected code and uses bounded cache-index locking')
check('OfflineExportCandidates(loader).read(aweme)' in offline_cache and 'LocalVideoTracks.inspect(verified, active)' in offline_cache and 'LocalVideoTracks.plan' in offline_cache and 'hasVideoModel' not in offline_cache, 'offline video export checks actual complete local tracks instead of rejecting model-based items categorically')
check('cached != total' in offline_files and 'file.length() != total' in offline_files and '0x6d6f6f66' in offline_files and 'source.file.lastModified() != source.modified' in offline_files, 'local exporter checks indexed completeness, container boundaries and source mutation')
check('IS_PENDING, 1' in offline_export and offline_export.index('LocalVideoRemuxer.write') < offline_export.index('LocalVideoRemuxer.verifyOutput') < offline_export.index('IS_PENDING, 0'), 'gallery publication follows successful local mux and destination readback rather than publishing partial bytes')
check('Build.VERSION.SDK_INT < 29' in offline_export, 'pre-Android-10 export is explicitly unsupported rather than silently using an unsafe fallback')
for relative in ('hook/OfflineVideoHook.kt', 'hook/HostLocalVideoCache.kt', 'hook/OfflineVideoExporter.kt', 'hook/LocalVideoFile.kt', 'hook/HostOfflineVideoFiles.kt', 'hook/OfflineMdlFile.kt', 'hook/OfflineMdlDataSource.kt'):
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
check('CommentTranslationStore.restore' in comment_dialog and 'OfflineCommentArchive.updateTranslation' in comment_dialog and 'adapter.saved(row,target,text,result)' in comment_dialog, 'legacy translation restoration and confirmed per-video archive commits are connected to comment UI')
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
check('CommentPageCodeGate.verify(context)' in page_network and 'LJJZZI' in page_network and 'DEFAULT' in page_network and 'contract.request.invoke(null, video, cursor, 20' in page_network, 'comment page adapter uses inspected native request and Rx contracts for one 20-item page')
check(not any(token in page_network for token in ('OfflineCommentLifecycleListener', 'X.0FTg', 'openConnection(', '.remove(', '.clear(', '.delete(')), 'comment page adapter does not invoke the native predownload/cache-clear manager or construct its own HTTP requests')
check('remote.permitsNetwork()' in page_network and 'reader.accountMatches(account)' in page_network and 'contract.id.invoke(model) == video' in page_network and 'once.compareAndSet(false, true)' in page_network, 'page dispatch and response acceptance check explicit-network eligibility, identity and duplicate callbacks')
check('online.setOnClickListener' in comment_dialog and 'onlineContinuation = true' in comment_dialog and 'nextPage.setOnClickListener' in comment_dialog and 'main.postDelayed(it, 15000)' in comment_dialog, 'explicit online continuous loading and translation actions retain a 15-second request timeout')
check('CommentPageMerge.merge(old, page, cursor)' in comment_dialog and 'OfflineCommentArchive.save(app,page.copy(rows = page.rows.filter { it.id in admitted }),cursor,replyRoot.orEmpty(), verifiedReplyTotals = true)' in comment_dialog and 'CommentSnapshotStore.read(app, account, video)' in comment_dialog, 'received pages merge and persist privately and local reload restores them')
check('if (!active()) Saved.CANCELLED' in snapshot_store and snapshot_store.index('if (!active()) Saved.CANCELLED') < snapshot_store.index('.commit()'), 'snapshot persistence checks cancellation immediately before committing')
check('old.nextCursor == requestedCursor' in page_merge and 'page.nextCursor > requestedCursor' in page_merge and 'old.account == page.account && old.video == page.video' in page_merge, 'page merges reject wrong account/video and non-progressing or mismatched cursors')
check('previous.forEach { values.putIfAbsent(it.id, it) }' in page_merge and 'Comment snapshot capacity reached' in page_merge and 'comment_page_retained_older' in comment_dialog, 'refresh preserves earlier cached rows, reports older unverified rows and refuses overflow instead of evicting')
check('parentId' in comment_data and 'append(replies, depth + 1, id)' in comment_reader and 'children[row.id]' in page_merge and 'toki_reply_to' in comment_adapter, 'reply parent identity is preserved through copying, page ordering and row display')
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
check('out.writeInt(3)' in snapshot_codec and 'require(version in 1..3)' in snapshot_codec and 'snapshot.replyCursors.forEach' in snapshot_codec and 'if (version >= 2)' in snapshot_codec, 'snapshot v3 preserves media and per-root reply cursors while reading v1/v2 records without destructive migration')
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
check(len(re.findall(r'^    (?:SEARCH|INBOX|LIVE)_[A-Z_]+\(PageCleanupGroup', page_items, re.M)) == 8, 'page cleanup catalog has eight independent inspected items')
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
    strings = {n.attrib['name']: ''.join(n.itertext()) for filename in ('page_cleanup_strings.xml', 'cleanup_individual_strings.xml') for n in ET.parse(ROOT / 'app/src/main/res' / locale / filename).getroot() if n.tag == 'string'}
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
check('"sim_country_only_enabled" to false' in schema and '"SimHook" to listOf("sim_spoof_enabled")' in schema and '+ directHost + platform' in (JAVA / 'hook/HostFeaturePlan.kt').read_text(encoding='utf-8'), 'country-only is default-off and subordinate to the live-installable SIM control rather than installing hooks independently')
check('countryOnly: Boolean = false' in sim_hook and 'if (countryOnly) ""' in sim_hook and '"00000"' not in sim_hook and 'normalizedIso = regionCode.trim().lowercase(java.util.Locale.ROOT)' in sim_hook, 'country-only SIM resolution preserves unavailable operator data and is locale-independent')
check(sim_hook.count('carrier.operatorCode.isNotBlank()') == 4 and 'carrier.operatorName.isNotBlank()' in sim_hook and 'val carrier = activeCarrier' in sim_hook, 'native operator, name and SIM/phone state are called when country-only has no carrier override')
check(all('fun resolve' + kind + 'ForIso(isoCode: String): ' + value + '?' in regions for kind, value in (('Language', 'String'), ('TimeZone', 'String'), ('Latitude', 'Double'), ('Longitude', 'Double'))) and '?: 35.6762' not in regions and '?: 139.6503' not in regions, 'missing preset metadata is explicit absence, not a different country or coordinate')
for relative, resolver in (('hook/LocaleHook.kt', 'language'), ('hook/TimeZoneHook.kt', 'timeZone'), ('hook/GpsHook.kt', 'coordinates')):
    source = (JAVA / relative).read_text(encoding='utf-8')
    check('EnvironmentRuntimeConfig.' + resolver + '(config)' in source and 'chain.proceed()' in source and 'EnvironmentApplicationStatus.clear' in source, relative + ' uses nullable resolved payloads and restores the downstream path on disable/disposal')
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
check('OfflineMdlDataSource(source.file, active)' in export_mux and 'extractor.setDataSource(local)' in export_mux and 'restoration?.verify(true)' in export_mux, 'actual remux readers use the same retained-header local adapter as source validation')
export_gate = (JAVA / 'hook/OfflineExportCodeGate.kt').read_text(encoding='utf-8')
check('getDeclaredField' in export_candidates and '"bitRateList"' in export_candidates and '"bitRateAudio"' in export_candidates and '"playAddrValue"' in export_candidates, 'offline export reads inspected raw video/rate/audio fields without computed model expansion')
check('"hitDashAudioBitrate"' in export_candidates and '"hitDashVideoBitrate"' in export_candidates and 'sounds.singleOrNull()' in export_candidates and 'rawSounds.isNotEmpty() || urls.any { selectedAudio.get(it) != null }' in export_candidates, 'explicit host track pairs take precedence and ambiguous/malformed required audio is not silently dropped')
check('"fileHash"' in export_candidates and 'val keys = boundedKeys(listOf(audioHash.get(data)))' in export_candidates and 'fileId/quality are not interchangeable' in export_candidates, 'DASH audio cache lookup uses the hash identified in the native cache reader rather than a guessed fileId')
check('OfflineExportCodeGate.verify(context)' in offline_cache and all(name in export_gate for name in ('classes2.dex', 'classes4.dex', 'classes6.dex', 'classes12.dex')) and 'matched == hashes.keys' in export_gate, 'native offline contracts require all four inspected model DEX hashes; legacy index keeps independent gates')
check('fragmented = true; waitingForData = true' in offline_files and 'if (waitingForData)' in offline_files and 'if (!moov || waitingForData' in offline_files, 'complete initialized fragment envelopes reach sample validation while missing init/dangling media remains incomplete')
check('minimumSampleTime(format)' in export_tracks and 'Digest(minimumUs)' in export_tracks and 'sampleTime < minimumUs' in export_tracks and 'KEY_ENCODER_DELAY' in export_tracks, 'AAC priming is bounded by declared encoder delay; unknown negative timestamps still fail')
check('if (preservesContainer(plan))' in export_mux and 'LocalVideoFile.copy(plan.video.file, stream, active)' in export_mux and 'val origin = if (preserve) 0L' in export_mux and 'minimumUs != track.minimumUs' in export_mux, 'complete native MP4 copies retain edit lists and AAC timing with independent sample readback')
check('Failure.PLATFORM' in offline_export and 'Failure.CODE_MISMATCH -> R.string.offline_video_code_mismatch' in offline_hook and 'Failure.TIMELINE -> R.string.offline_video_timeline_failed' in offline_hook, 'export errors identify actual platform, adaptation and timestamp failures separately')
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
check('snapshot-${System.currentTimeMillis()}' not in log_capture and 'selected-capture' in log_capture and 'if (includeLogcat) synchronized(this) { directory }' in log_capture and '8L * 1024 * 1024' in log_capture and '32L * 1024 * 1024' in log_capture, 'export includes only the current snapshot/explicit selected capture and bounds file/archive bytes instead of attaching all history')
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

for name in ('HostCommentPageNetwork.kt', 'HostCommentReplyNetwork.kt', 'HostOfflineComments.kt'):
    check('private class Contract(private val loader: ClassLoader)' in (JAVA / 'hook' / name).read_text(encoding='utf-8'), name + ' retains its classloader for member resolver functions (compiler regression)')

check('if (owner in rejected) return enabled(feature, requested)' in plan, 'revoked installation scopes keep an explicit restart requirement when still requested')
check('it != "DownloadHook" || downloadRepair || requested.boolean("download_path_enabled")' in plan and 'it != "DownloadHook"' in plan.split('fun liveSymbols', 1)[1], 'late download-path installation avoids unnecessary source scans and does not silently enable frozen repair')
check('selected.keys.any { it in own.keys }' in (JAVA / 'hook/OfflineExportCandidates.kt').read_text(encoding='utf-8'), 'a host-selected audio is not automatically paired with a different fallback video source')
check('ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))' in read('app/src/test/java/io/github/meiyongai/toki/hook/SubtitleOverlayTest.kt'), 'manual-frame subtitle tests do not spin indefinitely on automatic zero-delay vsync')
check(read('app/src/test/java/io/github/meiyongai/toki/hook/OfflineExportFixtures.kt') == read('app/src/androidTest/java/io/github/meiyongai/toki/hook/OfflineExportFixtures.kt'), 'desktop/device media fixtures are byte-identical source data rather than silently drifting samples')
device_media = read('app/src/androidTest/java/io/github/meiyongai/toki/hook/LocalVideoRemuxerDeviceTest.kt')
check('AndroidJUnit4::class' in device_media and 'Assume' not in device_media and device_media.count('@Test') == 4 and 'retainedHeaderMdlUsesSameVirtualSourceForValidationAndActualMuxing' in device_media, 'three original native remux/readback cases plus retained-header MDL integration are kept without assumptions')
check('org.junit.Assume.assumeTrue' in read('app/src/test/java/io/github/meiyongai/toki/hook/LocalVideoRemuxerTest.kt') and 'LocalVideoRemuxerDeviceTest' in read('app/src/test/java/io/github/meiyongai/toki/hook/LocalVideoRemuxerTest.kt'), 'desktop media shadow limitations are explicit capability skips, not mock successes or deleted assertions')

builder = read('scripts/build-lspatch.ps1')
check(builder.index('verify-caption-source.py') < builder.index('prepare-lspatch.py') < builder.index(':app:assembleLspatch') and 'git diff --check' in builder and 'compilation was not started' in builder, 'legacy/universal entry enforces basic source and whitespace gates before compilation rather than bypassing a failed check')

# Execution evidence is distinct from registration, configuration receipt and device acceptance.
execution_model = (JAVA / 'provider/ExecutionEvidence.kt').read_text(encoding='utf-8')
execution_overview = (JAVA / 'provider/DiagnosticOverview.kt').read_text(encoding='utf-8')
execution_speed = (JAVA / 'hook/PlaybackSpeedHook.kt').read_text(encoding='utf-8')
execution_runtime = (JAVA / 'hook/HookRuntime.kt').read_text(encoding='utf-8')
check('enum class Scope' in execution_model and 'enum class Metric' in execution_model and 'metrics.toMap()' in execution_model, 'execution evidence is enum-keyed numeric data with a defensive copy rather than user-content metadata')
check('format' in execution_model and 'return null' in execution_model and '旧配置执行证据' in execution_model and '功能与执行证据不匹配' in execution_model, 'unknown codecs, configuration revisions and mismatched feature identities cannot prove current operation success')
check('if (partial) Outcome.PARTIAL.title' in execution_model and '不测量真实播放速率' in execution_model and '不证明完整截图' in execution_model, 'failures and deferred/raced work override optimistic labels and retain per-scope acceptance boundaries')
check('for (index in 5 downTo 1)' in execution_runtime and 'executionSignature' in execution_runtime and 'executionObservations' in execution_runtime and 'Long.MAX_VALUE' in execution_runtime, 'execution changes are bounded to six per feature while repeated samples and saturated counters stay explicit')
check('Diagnostic failure must not alter' in execution_runtime and 'feature != evidence.scope.feature' in execution_runtime, 'execution logging isolates its exceptions and rejects cross-feature writes')
check('Metric.DEFERRED to result.deferred' in execution_speed and 'recordPlayerEvidence(restored' in execution_speed and 'revisionBefore' in execution_speed and 'playerRates.observation(target)' in execution_speed, 'player application, restoration and successful native setter callbacks report distinct evidence with pre-call configuration association')
check('Outcome.NEXT_READ' in execution_speed and 'Action.READ' in execution_speed and 'Metric.OPTIONS' in execution_speed, 'menu configuration receipt and actual native source reads are reported separately')
check('ExecutionEvidence.Scope.AUDIO' in (JAVA / 'hook/BackgroundAudioHook.kt').read_text(encoding='utf-8') and 'val snapshot = ConfigClient.snapshot()' in (JAVA / 'hook/BackgroundAudioHook.kt').read_text(encoding='utf-8'), 'audio execution evidence records actual getter returns from one effective snapshot, not playback started/stopped')
check('fun statistics()' in (JAVA / 'hook/TemplateRootBindings.kt').read_text(encoding='utf-8') and 'counts.attached' in (JAVA / 'hook/TemplateCleanupHook.kt').read_text(encoding='utf-8') and 'recordEvidence(failed = true)' in (JAVA / 'hook/TemplateCleanupHook.kt').read_text(encoding='utf-8'), 'template counts distinguish observed/attached roots and failed reads without claiming pixel coverage')
check('fun capture(): Pair' in log_journal and 'render() to brief()' in log_journal and 'operations.first' in execution_runtime and 'operations.second' in execution_runtime, 'operation headlines and detailed phases are captured under one journal lock')
check('保留窗口没有操作证据' in log_journal and '全局已覆盖记录' in log_journal and '上报成功=' in log_journal and '仍无终态' in log_journal, 'operation headline counts disclose eviction, absence, running work and latest outcomes instead of hiding new failures behind old success')
check('四项一眼状态' in execution_overview and 'executionHistory' in execution_overview and '尚未收到关闭／恢复回执' in execution_overview, 'overview puts the four target evidence areas first and never infers a successful shutdown from a requested false flag')
manual_fallback = (JAVA / 'hook/ManualCommentTranslateHook.kt').read_text(encoding='utf-8')
check('sourceText(comment, getText)' in manual_fallback and 'contract.getId.invoke' not in manual_fallback and '当前评论原文不可读，保留原生菜单' in manual_fallback, 'explicit per-dialog comment translation accepts readable ID-less comments and preserves native actions on read failure')
for file, names in {
 'provider/ExecutionEvidenceTest.kt': ('codecRoundTripAndNumericOrderingAreStable', 'missingUnknownOrMalformedPayloadNeverBecomesSuccess', 'failuresAndDeferredWorkOverrideOptimisticOutcomeLabels', 'crossFeatureEvidenceIsNotAcceptedForAnotherFeature'),
 'hook/HookRuntimeExecutionTest.kt': ('identicalSamplesUpdateCountWithoutFillingSemanticHistory', 'changingConfigurationsRetainOnlySixNewestDistinctSamples', 'executionEvidenceDoesNotRewriteApplicationRegistrationOrErrors'),
 'provider/OperationJournalTest.kt': ('newestFailureIsVisibleDespiteOlderSuccessInRetainedWindow', 'briefMakesWindowEvictionMissingEvidenceAndRunningWorkExplicit'),
 'hook/PlayerRateOverridesTest.kt': ('pendingRequestsAndUnchangedRecordsAreNotCountedAsSuccessfulCommands',),
 'provider/DiagnosticOverviewTest.kt': ('disabledRequestWithoutAReleaseReceiptRemainsUnconfirmed', 'quickOverviewShowsOldExecutionAndKeepsItSeparateFromRegistration'),
 'hook/ManualCommentTranslationTest.kt': ('readableCommentWithoutIdIsEligibleForExplicitPerDialogTranslation',),
}.items():
 check(all(name in read('app/src/test/java/io/github/meiyongai/toki/' + file) for name in names), file + ' covers execution evidence or comment fallback (source inspection only)')

# Fixed-rate runtime state and independent option sources (source assertions, not executed tests).
speed_hook = (JAVA / 'hook/PlaybackSpeedHook.kt').read_text(encoding='utf-8')
rate_scope = (JAVA / 'hook/PlaybackRateScope.kt').read_text(encoding='utf-8')
rate_state = (JAVA / 'hook/PlayerRateOverrides.kt').read_text(encoding='utf-8')
field_lease = (JAVA / 'hook/SpeedFieldLease.kt').read_text(encoding='utf-8')
speed_status = (JAVA / 'ui/screen/HookStatusModel.kt').read_text(encoding='utf-8')
check('"PlaybackSpeedHook" to listOf("fixed_speed_enabled")' in schema and '"SpeedOptions" to listOf("speed_expand_enabled")' in schema, 'fixed-rate and option menu switches belong to independent installation scopes')
check('"PlaybackSpeedHook" to fixedSpeedSymbols' in plan and '"SpeedOptions" to speedMenuSymbols' in plan, 'fixed speed and menu resolve only their own symbol dependencies')
check('"BackgroundAudioHook" to setOf(HostSymbol.BACKGROUND_AUDIO)' in plan, 'late audio installation has an explicit symbol dependency instead of missing getValue entry')
check(all(('"' + name + '"') in plan.split('val liveInstallable = setOf(', 1)[1].split('fun liveSymbols', 1)[0] for name in ('PlaybackSpeedHook', 'SpeedOptions', 'BackgroundAudioHook')), 'fixed rate, menu and future audio getter reads are independently live-installable')
check('"SpeedOptions" -> PlaybackSpeedHook.initOptions' in module and 'feature == "SpeedOptions"' not in module, 'module installs and reports menu scope directly rather than aliasing fixed-rate status')
check('requested.importRevision' in plan and 'checkNotNull(startup).importRevision' not in plan, 'current imported speed settings are not silently frozen to the startup import revision')
check('HookRuntime.subscribe("SpeedOptions")' in speed_hook and 'ArrayList(value.speeds)' in speed_hook, 'menu source reads use live immutable state and return a defensive list copy')
check('private val generated = ThreadLocal' in rate_scope and 'private val selections = ThreadLocal' in rate_scope and 'finally' in rate_scope and 'depth.remove()' in rate_scope, 'module writes and native selections have independent nested thread-local scopes with finally cleanup')
check('val result = rateScope.nativeSelection { chain.proceed() }' in speed_hook and '!rateScope.selecting' in speed_hook, 'native selections complete before remembered-rate updates and do not override a native 1x selection mid-call')
check('HookRuntime.subscribe("PlaybackSpeedHook")' in speed_hook and 'releaseFixed(snapshot?.revision)' in speed_hook, 'configuration invalidation releases known fixed-rate overrides without waiting for a ready-only application refresh')
check('playerRates.request(target, supplied, replacement' in speed_hook and 'if (rateScope.applying)' in speed_hook, 'only actual concrete player setter requests establish native restoration evidence')
check('if (entries.size < capacity)' in rate_state and 'it.pending++' in rate_state and '?: return proceed(native)' in rate_state, 'native requests reserve bounded capacity before any override and pass through when untrackable')
check('var native: Float? = null' in rate_state and 'it.observed && it.pending == 0' in rate_state and 'speed ?: checkNotNull(it.native)' in rate_state, 'unknown player baselines are absent rather than fabricated as 1x')
check('it.target.get() === target' in rate_state and 'WeakReference(target)' in rate_state, 'player observations use weak identity rather than equals or strong player retention')
check('writer(target, desired)' in rate_state and 'entry.revision != token' in rate_state and 'raced++' in rate_state, 'external player writes have revision race accounting rather than overwriting newer observations as successful')
check('private fun eachField' in field_lease and 'failure.addSuppressed(error)' in field_lease and 'baseline.remove(name)' in field_lease, 'field restoration continues per field, aggregates failures and releases only completed ownership')
check('current != written[name]' in field_lease and 'observeExternalChanges = false' in speed_hook, 'unchanged module fields and module-generated mutations are not blindly adopted as native baselines')
check('val restored = playerRates.apply(null)' in speed_hook and 'restoreFailure?.let { throw it }' in speed_hook, 'field restoration errors do not prevent attempting known-player restoration')
check('speedMenu?.appliedEnabled == true' in speed_status and '(speedMenu?.registered ?: 0) > 0' in speed_status and 'report.appliedEnabled != false' in speed_status, 'menu-only readiness requires actual menu registration and confirmed fixed-rate disable')
for file, names in {
    'hook/PlaybackRateScopeTest.kt': ('nestedModuleAndSelectionScopesAreIndependentAndFinallyRestored', 'anotherThreadDoesNotInheritModuleOrNativeSelectionScopes'),
    'hook/PlayerRateOverridesTest.kt': ('pendingNativeRequestReservesCapacityBeforeOverride', 'failedNativeRequestDoesNotCreateBaselineOrLeakReservedCapacity', 'changedNativeRevisionDuringModuleWriteIsReportedNotCommittedAsOldState', 'equalObjectsHaveIndependentIdentityAndTargetedUpdates'),
    'hook/SpeedFieldLeaseTest.kt': ('partialRestoreContinuesAggregatesErrorsAndRetainsRetryEvidence', 'successfulNativeCallDoesNotAdoptUnchangedModuleFieldsAsNative', 'moduleGeneratedStateMutationCannotReplaceNativeBaseline'),
    'hook/HostFeaturePlanTest.kt': ('fixedAndMenuLateAdmissionAndRejectionAreIndependent', 'speedMemoryReceivesLatestImportRevisionWithoutRestart', 'backgroundAudioUsesCurrentEffectiveConfigAndLateSymbolAdmission'),
    'hook/PlaybackSpeedRegistrationTest.kt': ('fixedRefreshAndIndependentMenuChangesDoNotDisableEachOther', 'nativeSelectionLeavesUnchangedModuleFieldsRestorable', 'invalidConfigurationReleasesFixedOverridesAndRecoveryCanEnableAgain', 'failedMenuContractDoesNotRollBackTheIndependentlyRegisteredFixedChain'),
    'ui/screen/HookStatusModelTest.kt': ('independentMenuCanBeReadyWithFixedDisabledButUnknownReportsRemainPending',),
}.items():
    source = read('app/src/test/java/io/github/meiyongai/toki/' + file)
    check(all(name in source for name in names), file + ' contains runtime-state regression scenarios (source only; not executed)')

# Live platform environment settings: installation/admission, immutable payloads and reversible downstream reads.
environment_runtime = (JAVA / 'hook/EnvironmentRuntimeConfig.kt').read_text(encoding='utf-8')
environment_module = (JAVA / 'hook/TokiModule.kt').read_text(encoding='utf-8')
environment_plan = (JAVA / 'hook/HostFeaturePlan.kt').read_text(encoding='utf-8')
check('+ directHost + platform' in environment_plan and 'it !in directHost && it !in platform' in environment_plan, 'platform environment controls are live-installable and excluded from unrelated host-symbol dependency resolution')
startup_contract = environment_plan.split('private val startupKeys = mapOf(', 1)[1].split('private val fixedSpeedSymbols', 1)[0]
check(all(('"' + feature + '" to ') not in startup_contract for feature in ('SimHook', 'LocaleHook', 'TimeZoneHook', 'GpsHook')), 'environment keys are no longer frozen to startup values once their hooks are installed')
check('ImmersiveFullScreenHook' in startup_contract and 'feature in liveFeatures' in plan and 'permitImmersiveLive' in plan, 'unverified immersive hosts retain startup values; code-verified installed live paths are promoted explicitly')
check(all(feature not in startup_contract for feature in ('BackgroundAudioHook', 'PlaybackSpeedHook', 'SpeedOptions')), 'fixed speed, independent menu and audio read settings are no longer frozen to startup')
activation_body = environment_module.split('private fun activateLiveFeatures() {', 1)[1].split('private fun installAdapted', 1)[0]
check(activation_body.index('activatePlatformFeatures(app)') < activation_body.index('val info = hostInfo') < activation_body.index('HostScanPhase.SCANNING'), 'platform late setup precedes host-classloader and DEX scan prerequisites')
platform_body = environment_module.split('private fun activatePlatformFeatures(app: Application) {', 1)[1].split('private fun activateLiveFeatures()', 1)[0]
check(platform_body.index('install(feature)') < platform_body.index('plan.admitLive(feature)') < platform_body.index('ConfigClient.refreshHostSession()'), 'platform business settings stay masked until installation and explicit admission succeed')
check('processName != app.packageName' not in platform_body and 'activationInFlight' not in activation_body.split('activatePlatformFeatures(app)')[0], 'platform-only activation is not blocked by a main-process-only rule or an in-flight unrelated adapted installation')
check('private val receipts = mutableMapOf' in environment_runtime and 'Receipt(config.revision, enabled)' in environment_runtime and 'EnvironmentApplicationStatus.get(feature)' in environment_module, 'actual environment payload receipts keep applied revision/activation separate from requested settings')
check('receipt == null || receipt.revision != effective.revision' in environment_module and '环境配置待应用' in environment_module and 'receipt.enabled' in environment_module, 'pending payload updates are not reported as already applied just because hooks exist')
for relative, payload in (('hook/SimHook.kt', 'activeCarrier'), ('hook/LocaleHook.kt', 'activeLocale'), ('hook/TimeZoneHook.kt', 'current'), ('hook/GpsHook.kt', 'current')):
    source = (JAVA / relative).read_text(encoding='utf-8')
    check('@Volatile private var ' + payload in source and 'applyConfiguration(ConfigClient.snapshot())' in source and 'EnvironmentApplicationStatus.record' in source and 'EnvironmentApplicationStatus.clear' in source, relative + ' publishes one nullable payload from one effective revision and clears its receipt on disposal')
zone_runtime = (JAVA / 'hook/TimeZoneHook.kt').read_text(encoding='utf-8')
gps_runtime = (JAVA / 'hook/GpsHook.kt').read_text(encoding='utf-8')
check('TimeZone.setDefault(' not in zone_runtime and 'Locale.setDefault(' not in (JAVA / 'hook/LocaleHook.kt').read_text(encoding='utf-8'), 'live environment application does not rewrite process-global timezone/locale defaults')
check('current?.java?.clone()' in zone_runtime and 'current?.icu?.clone()' in zone_runtime and 'val value = javaOverride()' in zone_runtime and 'val value = icuOverride()' in zone_runtime, 'mutable Java/ICU timezone results are copies while null returns to native getter paths')
check('Location(real).apply' in gps_runtime and 'realResult.latitude =' not in gps_runtime and 'val coordinates = current ?: return real' in gps_runtime and 'overrideLastKnown(realResult, provider)' in gps_runtime, 'GPS late changes never overwrite the downstream last-known Location object and disable returns the original')
check('latitude == null || longitude == null' in environment_runtime and 'latitude.isFinite()' in environment_runtime and 'if (!config.boolean(' in environment_runtime, 'missing or invalid environment data resolves to no override rather than stale foreign-region payloads')
check('运行时配置已处理' in sim_hook and '不记录坐标' in gps_runtime and '宿主已有时间对象不强制重建' in zone_runtime, 'environment diagnostics state the actual later-read scope without logging coordinates or promising host-cache reconstruction')
for locale in ('values', 'values-b+zh+Hans'):
    hint = ET.parse(ROOT / 'app/src/main/res' / locale / 'country_catalog_strings.xml').getroot().find("string[@name='country_catalog_hint']").text
    check('still require reopening' not in hint and '启动设置仍需重新打开' not in hint, locale + ' country/environment hint no longer claims the old module startup freeze')

# Actual template/effect business ownership, not broad FCP UI-schema class hiding.
template_contract = (JAVA / 'hook/TemplateAnchorContract.kt').read_text(encoding='utf-8')
template_roots = (JAVA / 'hook/TemplateRootBindings.kt').read_text(encoding='utf-8')
template_hook = (JAVA / 'hook/TemplateCleanupHook.kt').read_text(encoding='utf-8')
template_gate = (JAVA / 'hook/TemplateCleanupCodeGate.kt').read_text(encoding='utf-8')
individual_policy = (JAVA / 'hook/TemplateAnchorPolicy.kt').read_text(encoding='utf-8')
live_contract = (JAVA / 'hook/LiveSwipeLockContract.kt').read_text(encoding='utf-8')
live_controller = (JAVA / 'hook/LiveSwipeTouchController.kt').read_text(encoding='utf-8')
live_gestures = (JAVA / 'hook/LiveSwipeGestureGate.kt').read_text(encoding='utf-8')
check('fragment.type.getDeclaredField("LLLLLLJ")' in live_contract and 'pager(field.type)' in live_contract and 'pagerField?.get(it) === view' in live_contract, 'live lock resolves and scopes the actual pager owned by the inspected listener instead of treating the listener as the pager')
check('view.onTouchEvent(cancel)' in live_controller and 'view.onInterceptTouchEvent(cancel)' in live_controller and 'cancelling.get()' in live_controller and 'entry != Entry.INTERCEPT' in live_controller, 'live lock cancels native drag state once and routes blocked parent interception separately from consumed touch/listener events')
check('if (state.blocked) return Decision(true)' in live_gestures and 'if (!enabled) state.armed = false' in live_gestures and 'state.origins[event.getPointerId(index)]' in live_gestures and 'state.ended = true' in live_gestures, 'live gestures latch vertical ownership, drain terminal events, preserve active-pointer identity and do not steal newly enabled sequences')
check('TemplateAnchorPolicy.element(code)' in template_contract and 'binding.element == value.element' in template_roots and 'rules.forEach { (element, rule)' in template_roots, 'independent anchor controls validate the current business category as well as root/model/video identity before applying their own visibility owner')
check('element.aggregate?.let' in (JAVA / 'hook/LayoutCleanupPolicy.kt').read_text(encoding='utf-8') and 'layout_individual_component_hint' in (JAVA / 'ui/screen/LayoutCleanupSection.kt').read_text(encoding='utf-8'), 'individual component controls retain legacy aggregate behavior and explain aggregate precedence in settings')
check('providerDisplayAnchor' in template_contract and 'providerAweme' in template_contract and '"getType"' in template_contract and 'in allowed' in template_contract, 'template cleanup reads the selected business anchor and current video context instead of inferring type from schema class names')
check('inspectedAnchorCodes' in template_contract and 'check(it == inspectedAnchorCodes.getValue(name))' in template_contract and '"CAPCUT" to 54' in template_contract and '"DONATION_STICKER" to' not in template_contract, 'only inspected symbolic template/effect codes plus the user-reported CapCut category are accepted, with donation entries left native')
check('method(type, "rootView", View::class.java)' in template_contract and '"LLJJLIIIJLLLLLLLZ"' in template_contract and 'TemplateRootBindings.Snapshot(view, selected, item' in template_contract, 'standard renderer root ownership is tied to the actual selected anchor/provider rather than the whole multi-tag area')
check('"LLJLL"' in template_contract and 'cursor !== view' in template_contract and 'depth++ < 32' in template_contract and '"onDestroyView"' in template_contract, 'specific template-button binding requires its child inside its own content root and explicit release callbacks')
check('WeakReference(source.identity)' in template_roots and 'WeakReference(source.context)' in template_roots and 'binding.identity.get() === value.identity' in template_roots and 'binding.context.get() === value.context' in template_roots, 'root refresh validates exact anchor/video identity so recycled contexts cannot inherit prior hiding')
check('rows.size >= capacity' in template_roots and 'root !is ViewStub' in template_roots and 'root !is SurfaceView' in template_roots and 'root !is TextureView' in template_roots and 'root === root.rootView' in template_roots, 'bounded template bindings refuse stubs, video surfaces and attached window roots')
check('onViewAttachedToWindow(view: View) { validate(binding) }' in template_roots and 'onViewDetachedFromWindow(view: View) { gate.unbind(rules.getValue(binding.element), view) }' in template_roots and 'rules.values.forEach(gate::detach)' in template_roots, 'detach releases visible ownership and reattachment/close revalidate or restore without stale root rules')
check('roots.configure(if (ready) requested else emptySet())' in template_hook and 'if (!ready) return' in template_hook and 'owners.mapNotNull { it.get() }' in template_hook, 'observed pre-verification owners are replayed only after fingerprint success and cleanup remains off without both configuration and code readiness')
check('if (owner == null || closed.get() || reading' in template_hook and 'reading = true' in template_hook and 'finally { reading = false }' in template_hook and 'val result = chain.proceed()' in template_hook, 'template lifecycle always proceeds natively and reflective root observation suppresses recursive getter callbacks')
check('codeReady=$ready' in template_hook and 'owners.size >= 256' in template_hook and 'worker.shutdownNow()' in template_hook and 'roots.close()' in template_hook, 'template readiness/capacity metadata and asynchronous disposal are explicit without logging source identities')
check(all(name in template_gate for name in ('classes4.dex', 'classes5.dex', 'classes6.dex', 'classes41.dex')) and 'found == hashes.keys' in template_gate and 'isInterrupted' in template_gate, 'template business/root contracts are gated by all four inspected DEX files with cancellation support')
check('"TemplateCleanupHook" to listOf(io.github.meiyongai.toki.subtitle.model.LayoutElement.VIDEO_TEMPLATE.key)' in schema and 'LayoutElement.VIDEO_TEMPLATE,' in schema and 'LayoutElement.DANMAKU_ENTRANCE) &&' in schema and 'it !in io.github.meiyongai.toki.subtitle.hook.TemplateAnchorPolicy.controls }.map' in schema, 'template switch has its own configuration installation owner and is excluded from navigation-cleanup switches')
check('"QuickCommentCleanupHook", "TemplateCleanupHook"' in environment_plan and '"TemplateCleanupHook" -> TemplateCleanupHook.init(this, loader)' in environment_module, 'template business control is direct/live-installable without unrelated host-symbol scanning')
prepare_host = environment_module.split('private fun prepareHost(param: PackageReadyParam) {', 1)[1].split('private fun activatePlatformFeatures', 1)[0]
check(prepare_host.index('install("TemplateCleanupHook", param.classLoader)') < prepare_host.index('if (!plan.requiresScan && !manualRequest)') and 'plan.admitLive("TemplateCleanupHook")' in prepare_host and 'if (it !in installed) install(it, loader)' in prepare_host, 'main-feed observation is prepared while disabled and admitted once without duplicate direct-hook installation')
check('TemplateCleanupHook.refreshStatus()' in environment_module and 'HookFeatureDefinition("TemplateCleanupHook"' in (JAVA / 'ui/screen/HookStatusModel.kt').read_text(encoding='utf-8') and 'layout_template_business_hint' in (JAVA / 'ui/screen/LayoutCleanupSection.kt').read_text(encoding='utf-8'), 'template diagnostic readiness and precise current-page/unknown-business scope are visible in existing settings/status surfaces')
for locale in ('values', 'values-b+zh+Hans'):
    names = {n.attrib['name'] for n in ET.parse(ROOT / 'app/src/main/res' / locale / 'template_business_strings.xml').getroot() if n.tag == 'string'}
    check('layout_template_business_hint' in names, locale + ' contains template-business scope and legacy-switch warning')

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
    'hook/AuthorDisplayedUserContract.kt', 'hook/AuthorLabelBindings.kt', 'hook/AuthorLocationCodeGate.kt', 'model/CountryDisplayCode.kt',

    'provider/ExecutionEvidence.kt', 'hook/BackgroundAudioHook.kt', 'hook/ManualCommentTranslateHook.kt',

    'hook/PlaybackSpeedHook.kt', 'hook/PlaybackRateScope.kt', 'hook/PlayerRateOverrides.kt', 'hook/SpeedFieldLease.kt',

    'hook/TemplateAnchorContract.kt', 'hook/TemplateRootBindings.kt', 'hook/TemplateCleanupCodeGate.kt', 'hook/TemplateCleanupHook.kt',
    'hook/EnvironmentRuntimeConfig.kt', 'hook/HostFeaturePlan.kt', 'hook/TokiModule.kt',
    'hook/TemplateComponentSemantics.kt',
    'provider/DiagnosticText.kt', 'provider/TrialLog.kt', 'provider/OperationJournal.kt', 'provider/DiagnosticOverview.kt',
    'provider/TrialLogCapture.kt', 'ui/screen/TrialLogSection.kt', 'hook/HookRuntime.kt',
    'hook/OfflineExportCandidates.kt', 'hook/OfflineExportCodeGate.kt', 'hook/LocalVideoTracks.kt', 'hook/LocalVideoRemuxer.kt',
    'hook/AnimatedStickerData.kt', 'hook/AnimatedStickerContainers.kt', 'hook/HostAnimatedStickerDecoder.kt', 'hook/AnimatedStickerExporter.kt',
    'model/FontFileFormat.kt', 'model/AdministrativeRegions.kt', 'model/RegionEnvironmentPolicy.kt', 'model/CountryCatalog.kt', 'model/RegionPresets.kt', 'hook/SimHook.kt', 'hook/LocaleHook.kt', 'hook/TimeZoneHook.kt',
    'hook/GpsHook.kt', 'ui/screen/DashboardScreen.kt', 'ui/screen/ConfirmedConfigEdits.kt',
    'model/PageCleanupItem.kt', 'hook/PageCleanupBindings.kt', 'hook/PageCleanupContract.kt',
    'hook/PageCleanupCodeGate.kt', 'hook/PageCleanupHook.kt', 'ui/screen/PageCleanupSection.kt',
    'hook/HostFeaturePlan.kt', 'hook/TokiModule.kt', 'hook/HostSymbols.kt', 'hook/HostScanSession.kt', 'hook/HostScanController.kt',
    'hook/SubtitleOverlay.kt', 'hook/NativeSubtitleTranslator.kt', 'model/SubtitleAppearance.kt',
    'provider/ConfigSchema.kt', 'provider/ConfigStore.kt', 'provider/ConfigConflictClock.kt',
    'ui/StyledSubtitleTextView.kt', 'ui/screen/SubtitleSettings.kt',
    'hook/CommentStickerDownloadHook.kt', 'hook/StickerImageExporter.kt', 'hook/StickerExportPolicy.kt',
    'hook/DanmakuEntranceHook.kt', 'hook/HdrPlaybackHook.kt', 'hook/HdrSelectionScope.kt', 'hook/HdrSelectionContract.kt', 'ui/screen/HdrPlaybackSection.kt', 'hook/PhotoIndicatorTargets.kt', 'hook/LayoutCleanupHook.kt', 'hook/LayoutCleanupPolicy.kt', 'model/LayoutCleanup.kt', 'ui/screen/LayoutCleanupSection.kt',
    'hook/SharePanelCleanupHook.kt', 'hook/ShareListBindings.kt', 'model/SharePanelItem.kt', 'ui/screen/SharePanelCleanupSection.kt',
    'hook/AuthorLabelContract.kt', 'hook/AuthorLabelPresentation.kt', 'hook/AuthorLocationLabels.kt', 'hook/QuickCommentCleanupHook.kt',
    'hook/HoldSpeedContract.kt', 'hook/HoldSpeedGestureHook.kt', 'hook/NativeSpeedGestureScope.kt', 'ui/screen/HoldSpeedGestureSection.kt',
    'hook/ManualCommentTranslation.kt', 'hook/ManualCommentTranslateHook.kt', 'hook/ManualCommentDialog.kt',
    'ui/screen/ManualCommentTranslationSection.kt', 'ui/screen/LocalTranslationSection.kt',
    'hook/LocalOnlyRequest.kt', 'hook/TranslationNetworkPolicy.kt', 'hook/HostSettingsEntry.kt', 'hook/SubtitleFeature.kt',
    'hook/OfflinePlaybackState.kt', 'hook/OfflineVideoContract.kt', 'hook/OfflineCacheCodeGate.kt',
    'hook/OfflineMdlFile.kt', 'hook/OfflineMdlDataSource.kt', 'hook/HostOfflineVideoFiles.kt', 'hook/LocalVideoFile.kt', 'hook/HostLocalVideoCache.kt', 'hook/OfflineVideoExporter.kt',
    'hook/OfflineCommentArchive.kt', 'hook/OfflineCommentDownloadHook.kt', 'hook/OfflineCommentDownloadContract.kt', 'hook/OfflineCommentDownloadProgress.kt', 'hook/OfflineNativeHoldContract.kt', 'hook/OfflineLongPressState.kt', 'hook/OfflineVideoHook.kt', 'ui/screen/OfflineVideoSection.kt', 'hook/SubtitleRemoteResume.kt',
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
    'provider/ExecutionEvidenceTest.kt', 'hook/HookRuntimeExecutionTest.kt',
    'hook/AuthorDisplayedUserContractTest.kt', 'hook/AuthorLabelBindingsTest.kt', 'model/CountryDisplayCodeTest.kt',
    'hook/LocalVideoRemuxerGuardTest.kt', 'hook/CaptionTrackFingerprintTest.kt',
    'hook/PlaybackSpeedRegistrationTest.kt', 'hook/PlaybackSpeedMemoryTest.kt',
    'hook/PlaybackRateScopeTest.kt', 'hook/PlayerRateOverridesTest.kt', 'hook/SpeedFieldLeaseTest.kt',
    'hook/HostFeaturePlanTest.kt', 'hook/HostScanSessionTest.kt',
    'model/SubtitleAppearanceTest.kt', 'ui/StyledSubtitleTextViewTest.kt',
    'provider/ConfigStoreTest.kt', 'provider/ConfigConflictClockTest.kt',
    'hook/CommentStickerMediaTest.kt', 'hook/StickerExportPolicyTest.kt', 'hook/StickerImageExporterTest.kt',
    'hook/LayoutCleanupPolicyTest.kt', 'hook/ShareListBindingsTest.kt', 'model/SharePanelPolicyTest.kt',
    'ui/screen/HookStatusModelTest.kt', 'hook/AuthorLabelPresentationTest.kt', 'hook/QuickCommentCleanupContractTest.kt',
    'hook/HoldSpeedGestureScopeTest.kt', 'hook/ManualCommentTranslationTest.kt',
    'hook/LocalOnlyRequestTest.kt', 'hook/LocalSubtitleExecutionTest.kt', 'hook/FakeOnlineTranslator.kt',
    'hook/NativeSubtitleTranslatorTest.kt', 'hook/SubtitleOverlayTest.kt',
    'hook/OfflineMdlFileTest.kt', 'hook/OfflineCommentArchiveTest.kt', 'hook/OfflineCommentDownloadProgressTest.kt', 'hook/OfflineNativeHoldContractTest.kt', 'hook/OfflinePlaybackStateTest.kt', 'hook/OfflineLongPressStateTest.kt', 'hook/LocalVideoFileTest.kt', 'hook/OfflineVideoHitTest.kt', 'hook/SubtitleRemoteResumeTest.kt',
    'hook/OfflineCommentDataTest.kt', 'hook/OfflineCommentListAdapterTest.kt',
    'hook/CachedCommentTranslationSessionTest.kt', 'hook/CommentTranslationStoreTest.kt', 'hook/CachedCommentOnlineAdapterTest.kt',
    'hook/CommentPageMergeTest.kt', 'hook/CommentSnapshotCodecTest.kt', 'hook/CommentSnapshotStoreTest.kt',
    'hook/CommentReplyMergeTest.kt', 'hook/CommentReplyCursorCodecTest.kt', 'hook/CommentReplyAdapterTest.kt', 'hook/OfflineCommentEntryPolicyTest.kt',
    'hook/CaptionPlaybackClockTest.kt', 'hook/SubtitleReplayRecoveryTest.kt', 'hook/CaptionTimelineFetchTest.kt',
    'hook/VideoDisplayModesTest.kt', 'hook/VideoLayoutBindingsTest.kt', 'hook/SmartVideoLayoutTest.kt', 'hook/VideoFillGeometryTest.kt',
    'hook/PageCleanupBindingsTest.kt', 'hook/PageCleanupContractTest.kt', 'hook/PageCleanupPolicyTest.kt',
    'model/FontFileFormatTest.kt', 'model/SubtitleCaptionPresentationTest.kt', 'model/AdministrativeRegionsTest.kt', 'model/CountryCatalogTest.kt', 'ui/screen/ConfirmedConfigEditsTest.kt',
    'hook/AnimatedStickerFixtures.kt', 'hook/AnimatedStickerDataTest.kt', 'hook/AnimatedStickerContainersTest.kt',
    'hook/OfflineExportFixtures.kt', 'hook/OfflineExportCandidatesTest.kt', 'hook/LocalVideoTracksTest.kt', 'hook/LocalVideoRemuxerTest.kt',
    'provider/OperationJournalTest.kt', 'provider/DiagnosticTextTest.kt', 'provider/DiagnosticOverviewTest.kt', 'provider/TrialLogCaptureTest.kt',
    'provider/TrialLogTest.kt',
    'hook/DanmakuEntranceHookTest.kt', 'hook/HdrPlaybackHookTest.kt', 'hook/HdrSelectionScopeTest.kt', 'hook/HdrSelectionCodeGateTest.kt', 'hook/PhotoIndicatorTargetsTest.kt', 'hook/LayoutComponentSelectorTest.kt',
    'hook/EnvironmentLivePlanTest.kt', 'hook/EnvironmentRuntimeConfigTest.kt', 'hook/EnvironmentOverridesTest.kt',
    'hook/TemplateAnchorContractTest.kt', 'hook/TemplateRootBindingsTest.kt', 'hook/TemplateCleanupPlanTest.kt',
    'hook/HostFeaturePlanTest.kt', 'model/CountryCatalogTest.kt', 'provider/DiagnosticOverviewTest.kt', 'ui/screen/HookStatusModelTest.kt',
):
    test = ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative
    kotlin_delimiters(test.read_text(encoding='utf-8'))
    check(True, relative + ' test-source lexical delimiters are balanced (not executed)')

kotlin_delimiters(device_media)
check(True, 'real-device MP4 integration test-source delimiters are balanced; execution still requires Android')

check('DiscardOldestPolicy' not in prefetch and 'ThreadPoolExecutor.AbortPolicy()' in prefetch and 'catch (_: RejectedExecutionException)' in prefetch, 'caption downloads never silently discard accepted loading markers')
check('foregroundWorker.execute' in prefetch and 'worker.execute' in prefetch and 'foregroundWorker.queue.clear()' in prefetch, 'current timeline has its own bounded lane and selection clears obsolete queued work')
check('ensureCurrentTimeline()\n            warmUpcoming()' in prefetch and 'currentRetryAt =' in prefetch and 'retryDelay(currentFailures)' in prefetch, 'missing current timelines have bounded retries even when the cue does not change')
check('timelines[track.url]' not in prefetch and 'timelines[identity]' in prefetch, 'timeline memory reuse keys on stable track identity rather than signed URL tokens')

immersive_windows = read('app/src/main/java/io/github/meiyongai/toki/hook/ImmersiveWindowBindings.kt')
immersive_screen = read('app/src/main/java/io/github/meiyongai/toki/hook/ImmersiveScreenBindings.kt')
immersive_runtime = read('app/src/main/java/io/github/meiyongai/toki/hook/ImmersiveLiveRuntime.kt')
immersive_prepare = read('app/src/main/java/io/github/meiyongai/toki/hook/ImmersiveLayoutPreparation.kt')
check('decorFitsSystemWindows' in immersive_windows and 'row.owned' in immersive_windows and 'LAYOUT_MASK.inv()' in immersive_windows, 'immersive window restoration reads the native decor-fit baseline and preserves unrelated flag bits')
check('nativeBackground' in immersive_windows and 'nativeAttributes' in immersive_windows and 'beforeWrite(property)' in immersive_windows, 'immersive native writes and per-field failure isolation retain restoration bookkeeping')
check('copy.invoke(original' in immersive_screen and 'cache.set(handler, entry.original)' in immersive_screen and 'current !== input' in immersive_screen, 'immersive screen projection is copied and restores only exact still-owned cache objects')
check('CONTAINER_ADAPTION' in immersive_screen and 'reason.invoke(feed, changed.get(null), null, null)' in immersive_screen, 'current cells use the inspected native invalidation reason without guessed video dimensions')
check('2757ca393a37b513c91d4cbea971b9933e6bee17985a6cbb4d9f31c9b2f60c10' in immersive_prepare and '60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff' in immersive_prepare, 'immersive live code requires the inspected original classes4 and classes5 fingerprints')
check('ImmersiveRefreshPlan' in immersive_runtime and 'layoutRefresh.panelApplied(owner, screens.original' in immersive_runtime and 'screens.restoreCaches()' in immersive_runtime, 'runtime records actual projection ownership for deferred disable restoration')
for relative in ('hook/ImmersiveWindowBindings.kt','hook/ImmersiveScreenBindings.kt','hook/ImmersiveLiveRuntime.kt','hook/ImmersiveLayoutPreparation.kt','hook/ImmersiveRefreshPlan.kt'):
    kotlin_delimiters((JAVA / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' lexical delimiters are balanced (not compilation)')
for relative in ('hook/ImmersiveWindowBindingsTest.kt','hook/ImmersiveScreenBindingsTest.kt','hook/ImmersiveRefreshPlanTest.kt'):
    kotlin_delimiters((ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' test delimiters balanced (not executed)')

live_gate = read('app/src/main/java/io/github/meiyongai/toki/hook/LiveApplicationGate.kt')
background_guard = read('app/src/main/java/io/github/meiyongai/toki/hook/BackgroundWriteGuard.kt')
check('scope.committed' in live_gate and 'if (!ready()) return null' in live_gate and 'return if (ready()) snapshot else null' in live_gate, 'live business activation requires committed registration, admission and a still-current scope')
check('ConfigClient.refreshHostSession()\n            if (!ImmersiveLiveRuntime.admit())' in read('app/src/main/java/io/github/meiyongai/toki/hook/TokiModule.kt'), 'immersive admission follows effective-session refresh and fails closed without crashing host callbacks')
check('gate.configuration()' in immersive_runtime and 'effectiveSnapshotOrNull()' in immersive_runtime and 'configuration = config' not in immersive_runtime, 'immersive work reads the latest nullable effective snapshot rather than a stale registration callback')
check('listOf("setBackground", "setBackgroundDrawable")' in immersive_runtime and 'IdentityHashMap<View, Unit>' in background_guard and 'finally { active.remove(view) }' in background_guard, 'both native background setters share a per-view exception-safe delegation guard')
check(immersive_windows.index('val captured = EnumMap') < immersive_windows.index('for (property in row.baseline.keys.toList()) try {\n            if (enabled'), 'window baseline fields are sampled together before coupled native setters can change another field')
for relative in ('hook/LiveApplicationGate.kt','hook/BackgroundWriteGuard.kt','hook/ImmersiveFailure.kt'):
    kotlin_delimiters((JAVA / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' lexical delimiters balanced (not compilation)')
for relative in ('hook/LiveApplicationGateTest.kt','hook/BackgroundWriteGuardTest.kt','hook/ImmersiveFailureTest.kt'):
    kotlin_delimiters((ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' test delimiters balanced (not executed)')

manual_request = read('app/src/main/java/io/github/meiyongai/toki/provider/ManualHostAdaptation.kt')
manual_dialog = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/ManualHostAdaptationDialog.kt')
manual_home = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/HomeScreen.kt')
manual_host = read('app/src/main/java/io/github/meiyongai/toki/hook/HostSymbols.kt')
check('const val KEY = "adaptation_retry_token"' in manual_request and 'hosts-v1|' in manual_request, 'manual adaptation reuses the existing schema with package-scoped nonces')
check('pair[0] in result' in manual_request and 'previousToken' in manual_request, 'malformed or duplicate requests never silently become global retries')
check(manual_home.index('R.string.home_host_current') < manual_home.index('R.string.manual_adaptation_title'), 'manual adaptation entry follows the current-host row')
check('adaptationDialog(tikTokStatus.packageName)' in manual_home and 'ManualHostAdaptationDialog(host, onDismiss = dismiss)' in manual_home, 'home entry opens actual installed-host selection')
check('saveManualHostAdaptation' in manual_dialog and 'ConfigClient.syncError?.let { throw it }' in manual_dialog, 'request saving is confirmed before presenting restart instructions')
check('getInstalledTikTokStatuses(context).any' in manual_dialog and 'host.isInstalled && !saving && savedHost == null' in manual_dialog, 'uninstalled hosts cannot be requested and saved selections are locked')
check('if (retryRequested) HostSymbol.entries.toSet()' in manual_host and 'if (retryRequested) java.util.Properties()' in manual_host, 'manual retry bypasses prior mappings and requests all known symbols')
check('retryRequested || HostScanPlan' in manual_host and 'ExtensionSymbols.load(identity, checkNotNull(cache.parentFile), retryToken)' in manual_host, 'manual retry also bypasses bundled profiles and invalidates extension cache by nonce')
check('!plan.requiresScan && !manualRequest' in read('app/src/main/java/io/github/meiyongai/toki/hook/TokiModule.kt'), 'explicit manual retry can run with optional features disabled')
check(manual_host.index('HostSymbolCache.write(cache, result)') > manual_host.index('result.setProperty("cache.retryToken", retryToken)'), 'retry nonce is consumed with verified scan-result publication')
for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
    entries = catalog(ROOT / 'app/src/main/res' / locale / 'manual_adaptation_strings.xml')
    check(len(entries) == len(dict(entries)) == 18, locale + ' manual-adaptation strings have unique complete keys')
for relative in ('provider/ManualHostAdaptation.kt', 'ui/screen/ManualHostAdaptationDialog.kt'):
    kotlin_delimiters((JAVA / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' lexical delimiters are balanced (not compilation)')
for relative in ('provider/ManualHostAdaptationTest.kt', 'util/TikTokVersionHelperTest.kt', 'hook/HostManualAdaptationPlanTest.kt'):
    kotlin_delimiters((ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' test delimiters balanced (not executed)')

check('ConfigClient.deliveryConfirmed(changes)' in manual_dialog, 'standalone local persistence is not mistaken for delivery to the injected host')
check('context.packageName == target' in manual_dialog and 'it.packageName == context.packageName' in manual_dialog, 'host-private adaptation cannot silently target another installed package')
check('ManualHostAdaptationDialog(' in read('app/src/main/java/io/github/meiyongai/toki/hook/HostSettingsDialog.kt'), 'injected settings expose the same manual adaptation flow without a manager service')
check('(if (host) local else remote)' in read('app/src/main/java/io/github/meiyongai/toki/provider/ConfigStore.kt'), 'delivery readback verifies host-private storage or the actual framework publication')
check('CompositionLocalProvider(LocalContext provides context)' in manual_dialog, 'actual dialog body retains the chosen module language instead of reverting to window locale')
mirror = read('app/src/main/java/io/github/meiyongai/toki/provider/HostSettingsMirror.kt')
wire = read('app/src/main/java/io/github/meiyongai/toki/provider/HostSettingsPatch.kt')
provider = read('app/src/main/java/io/github/meiyongai/toki/provider/SubtitlePositionProvider.kt')
check('HostSettingsMirror.kick()' in read('app/src/main/java/io/github/meiyongai/toki/provider/ConfigClient.kt'), 'host setting mutations schedule the root and no-root mirror after local commit')
check('HostSettingsWire.PENDING' in store and 'HostSettingsWire.PAYLOAD' in store and 'HostSettingsWire.REQUEST' in store, 'user values, immutable pending patch and request id share the host commit')
check('HostSettingsWire.remember(receipts, receipt, revision)' in store and 'applyHostPatch' in store, 'standalone config and idempotence receipt share one commit')
check('it.host in allowed' in provider and 'Binder.getCallingUid()' in provider and 'Binder.clearCallingIdentity()' in provider and 'Binder.restoreCallingIdentity(identity)' in provider, 'local settings provider authenticates host uid before applying as manager and restores identity')
check('attempts <= 3' in mirror and 'getString(HostSettingsWire.REQUEST, null) == id' in mirror, 'retries are bounded and old acknowledgements cannot erase newer pending edits')
check('HostSettingsSyncStatus' in mirror and 'HostSettingsMirror.status.collectAsState()' in read('app/src/main/java/io/github/meiyongai/toki/hook/HostSettingsDialog.kt'), 'host UI exposes pending, acknowledged and failed standalone delivery')
for relative in ('provider/HostSettingsPatch.kt', 'provider/HostSettingsMirror.kt'):
    kotlin_delimiters((JAVA / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' lexical delimiters balanced (not compilation)')
for relative in ('provider/HostSettingsSyncTest.kt', 'provider/HostSettingsProviderTest.kt'):
    kotlin_delimiters((ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' test delimiters balanced (not executed)')
check('preferences.getString(HostSettingsWire.REQUEST, null) != id' in mirror and 'draining = true' in mirror, 'host mirror checks generation before IPC and has one in-flight delivery')
for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
    entries = catalog(ROOT / 'app/src/main/res' / locale / 'host_sync_strings.xml')
    check(len(entries) == len(dict(entries)) == 6, locale + ' host-sync state strings are unique and complete')
check('return synchronized(preferences)' in store and 'synchronized(preferences)' in mirror, 'local host configuration commit and ACK compare-and-clear share one monitor')
check('ManualHostAdaptation.values(before, patch.host' in store, 'mirroring one authenticated host preserves another hosts pending rescan request')
check('CommentStickerDownloadHook.isStickerComment(comment)' in read('app/src/main/java/io/github/meiyongai/toki/hook/ManualCommentTranslateHook.kt'), 'ordinary manual translation preserves sticker-menu ownership')
download_comments = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentDownloadHook.kt')
durable_comments = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentArchive.kt')
check('X.0rF7' in download_comments and 'completion' in download_comments and 'fun sourcePage(' in download_comments and 'fun translateRow(' in download_comments and 'ingress.offer(video,' in download_comments, 'comment original and translated pages are produced by native offline-download completion rather than only opening the viewer')
check('NativeSubtitleTranslator.transport(loader, ::capture)' in download_comments and 'NET_CAPABILITY_VALIDATED' in download_comments and 'reader.accountMatches(job.getString("account"))' in download_comments, 'separate opt-in download cache uses authenticated account and network checks independently from playback revision')
check('noBackupFilesDir' in durable_comments and 'AtomicFile' in durable_comments and 'MAX_RECORDS' not in durable_comments and 'all.size >= 100' not in durable_comments, 'durable comment companions are per-video atomic files without legacy global record eviction')
check('CLICK_CLEAR_ICON' in download_comments and 'clearAccountAfterManualClear' in download_comments and 'epoch:' in download_comments, 'only the inspected manual clear-all event schedules account-bound cleanup and cancels stale writers')
check('OfflineCommentArchive.scoped' in comment_dialog and 'OfflineCommentPreferences.limit' in comment_dialog, 'offline viewer renders a bounded local comment tree past the legacy single snapshot cap')
native_offline = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineNativeHoldContract.kt')
offline_hold = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineVideoHook.kt')
check('OfflineNativeHoldContract.verify' in offline_hold and 'fresh.id != target.id' in offline_hold and '!fresh.offline' in offline_hold, 'offline native hold requires matched code and re-read selected offline identity before consumption')
check('surface === selectedRoot' in native_offline and 'surface.rootView !== window' in native_offline and 'descendant(surface) && descendant(player)' in native_offline, 'native offline hold belongs to selected card/window without loosening generic overlay hit checks')
check('rect.contains(videoBounds)' in read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineVideoHook.kt'), 'offline holds allow a native full-player sibling while still inspecting its interactive children')
check('maxOf(200L, ViewConfiguration.getLongPressTimeout().toLong() - 100L)' in read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineVideoHook.kt'), 'offline local hold claims before the native disabled-action toast deadline')
check('LiveRoomFragment;->mY(IIZ)Z' in read('app/src/main/resources/toki-host-rules.tsv'), 'live lock follows the inspected room touch listener rather than removed UX(float) contract')
check('HistoricalLocalValuesNotConfirmed' in mirror and 'queueUnsyncedHostValues' in store, 'historic local settings are not advertised as fully synchronized from an old ACK')
for relative in ('hook/AuthorRegionMemory.kt','model/LocationPresets.kt','ui/screen/LocationPresetDialog.kt'):
    kotlin_delimiters((JAVA / relative).read_text(encoding='utf-8-sig'))
    check(True, relative + ' lexical delimiters balanced (not compilation)')
check('patch.onlyAbsent' in store and 'it !in snapshot().configuration()' in store, 'historical replay fills only currently absent manager keys inside the manager transaction')
print("SOURCE CHECKS ONLY: this script does not verify compilation, JUnit, device runtime or TikTok 47.6.3 hook behavior.")

# Imported public geography and stale-renderer ownership are separate from device acceptance.
admin_regions = read('app/src/main/java/io/github/meiyongai/toki/model/AdministrativeRegions.kt')
admin_dialog = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/LocationPresetDialog.kt')
check('AdministrativeRegions.read(context.assets.open' in admin_dialog and 'AdministrativeRegions.search(regions, country, search)' in admin_dialog, 'administrative directory is loaded from module assets and scoped to selected country')
check('require(item.hasLocation)' in admin_regions and 'custom_latitude' in admin_regions and 'gps_follow_region' in admin_regions, 'region selection uses verified point and existing atomic configuration keys')
check('binding.translator.hasNativeFrame(videoId)' in read('app/src/main/java/io/github/meiyongai/toki/hook/SubtitleOverlay.kt') and 'yieldToTimeline()' in read('app/src/main/java/io/github/meiyongai/toki/hook/SubtitleOverlay.kt') and 'timelineBindings.remove(this)' in read('app/src/main/java/io/github/meiyongai/toki/hook/SubtitleOverlay.kt'), 'stale native overlay cannot permanently block a current timeline; lifetime cleanup remains paired')

font_store = read('app/src/main/java/io/github/meiyongai/toki/provider/SubtitleFontStore.kt')
font_provider = read('app/src/main/java/io/github/meiyongai/toki/provider/SubtitleFontProvider.kt')
font_resolver = read('app/src/main/java/io/github/meiyongai/toki/ui/SubtitleFontResolver.kt')
check('subtitle_bilingual_order' in preview and 'subtitle_bilingual_order' in overlay and 'SubtitleCaptionPresentation.display' in preview, 'bilingual line order is live-wired to preview and playback')
check('val nativeMode = nativeAppearance && !bilingual' in overlay, 'bilingual ordering composes Toki text even when typography follows the native appearance')
check('FontFileFormat.inspect(destination)' in font_store and 'validateTypeface(destination)' in font_store and 'createNewFile()' in font_store and '.delete(' not in font_store, 'font registration follows bounded structural/native validation without source deletion')
check('authorize()' in font_provider and 'Binder.clearCallingIdentity()' in font_provider and 'mode != "r"' in font_provider and 'MODE_READ_ONLY' in font_provider, 'font provider authenticates host/manager UIDs and exposes read-only descriptors')
check('selections[it] == key' in font_resolver and 'worker.execute(it)' in font_resolver and 'retryAt' in font_resolver, 'async font completion checks the current view selection and bounds retry/worker use')

# Download companion scheduling is independent of playback and keeps UI diagnostics read-only.
comment_plan = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentWorkPlan.kt')
comment_jobs = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentJobStore.kt')
comment_ingress = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentIngress.kt')
comment_failure = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentRequestFailure.kt')
comment_status = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/OfflineCommentStatusSection.kt')
check(all(name in download_comments for name in ('worker("toki-comment-index")', 'worker("toki-comment-pages")', 'worker("toki-comment-translations")')) and 'translatePage(' not in download_comments, 'comment registration, source pages and subtitle-channel translation have independent workers rather than whole-page translation blocking ingestion')
check('const val BATCH_SIZE = 5' in comment_plan and 'if (!ready) return emptyList()' in comment_ingress and 'ingress.take(true)' in download_comments, 'pre-verification completion identities are retained and consumed in five-video batches after readiness')
check('current.optLong("sourceRevision") != sourceRevision' in comment_jobs and 'epoch:' in comment_jobs and 'register(key' in comment_jobs, 'durable source/translation updates merge through source-revision and manual-clear epoch guards')
check('fun nextDelay(' in comment_plan and '300_000L' in comment_plan and 'attempts")>=3' not in download_comments, 'future failed jobs retain scheduled wakeups with capped backoff instead of a three-attempt permanent stop')
check('getCronetInternalErrorCode' in comment_failure and 'getStatusCode' in comment_failure and 'getRequestLog' not in comment_failure and '.message' not in comment_failure, 'comment failure diagnostics extract only safe numeric codes and cause types without request logs or messages')
check('LaunchedEffect(open, refresh)' in comment_status and 'if (open)' in comment_status and 'HookDiagnostics.request(context)' in comment_status and 'putBundle("offlineComments"' in read('app/src/main/java/io/github/meiyongai/toki/hook/HookRuntime.kt'), 'offline cache progress is available only on explicit settings-dialog queries through existing signed diagnostics')
check('fun commitTranslation(' in durable_comments and 'it.id == row.id && it.source == row.source' in durable_comments and 'var archiveReadFailed = false' in read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentsDialog.kt'), 'background translation matches exact archived originals and corrupt archive reads preserve local fallbacks')
check('ready() && ConfigClient.isReady && ConfigClient.getBoolean(KEY) && draining.compareAndSet' in download_comments and 'if (ConfigClient.isReady && !ConfigClient.getBoolean(KEY)) return' in download_comments, 'early completion identities survive both code and configuration readiness without starting work while the cache option is disabled')
check('maxOf(beforeRevision, current.optLong("sourceRevision"))' in comment_jobs, 'legacy job migration cannot roll source revisions back and admit a stale translation completion')

sticker_hook = read('app/src/main/java/io/github/meiyongai/toki/hook/CommentStickerDownloadHook.kt')

# Rich offline companions and uninterrupted timeline presentation (2.1.0).
rich_preferences = read('app/src/main/java/io/github/meiyongai/toki/model/OfflineCommentPreferences.kt')
rich_media = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentMediaStore.kt')
rich_views = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentMediaViews.kt')
rich_reader = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentMediaReader.kt')
rich_dialog = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentsDialog.kt')
rich_adapter = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentListAdapter.kt')
rich_budget = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentBudget.kt')
rich_override = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentLimitOverrides.kt')
rich_limit = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentLimitDialog.kt')
rich_hold = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineHoldArbiter.kt')
rich_speed_gate = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineSpeedGestureCodeGate.kt')
current_build = read('app/build.gradle.kts')
version = re.search(r'versionName = "(\d+)\.(\d+)\.(\d+)"', current_build)
# Keep the approved 2.1.0 /120 release series; architectural changes do not promote its minor component.
release_code = re.search(r'versionCode = (\d+)', current_build)
release_ordinal = int(release_code[1]) - 120 if release_code else -1
expected_release = (2, 1 + release_ordinal // 100, release_ordinal % 100)
check(version is not None and release_ordinal >= 0 and tuple(map(int, version.groups())) == expected_release,
    'release sequence follows 2.1.0 /120 at base 100; no early minor-version increment')
check(all(k in rich_preferences for k in ('MAX_COMMENTS = 2500', 'REQUEST_PAGE_SIZE = 20', 'DEFAULT_MODE = "translated"', '1..125')), 'offline comment count and page shortcuts share the 2500-entry ceiling')
check(all(k in rich_dialog for k in ('sheet.modes', 'adapter.setMode(mode)', 'syncingMode', 'offline_comment_mode_bilingual')), 'three global comment modes persist and follow external settings without recursive writes')
check('if (!valid() || adapter.snapshot?.account != snapshot.account' in rich_dialog, 'stale per-video limit dialogs cannot write after an account or playback change')
check('No offline media source' in rich_media and 'noBackupFilesDir' in rich_media and 'sha256' in rich_media, 'media bytes stay in checked durable account/video companion storage')
check('ImageDecoder.decodeBitmap' in rich_media and 'setOnPartialImageListener { false }' in rich_media and 'setTargetSize(1, 1)' in rich_media, 'media persistence validates decodable complete images with bounded decoding')
check('OfflineCommentMediaStore.bytes' in rich_views and 'CommentStickerDownloadHook.fetch' not in rich_views, 'offline previews and saves cannot trigger a missing-media download')
check('AnimatedImageDrawable' in rich_views and 'onViewDetachedFromWindow' in rich_views, 'animated cached stickers play with attachment-aware drawable cleanup')
check(all(k in rich_reader for k in ('getImageList', 'getOriginUrl', 'getCropUrl', 'CommentStickerDownloadHook.Reader')), 'photo and sticker extraction uses inspected native data contracts')
check('saveCached(' in sticker_hook and 'publish(' in sticker_hook, 'cached comment media shares the existing static and animated gallery publication path')
check('admitted.size >= limit' in rich_budget and 'existing.forEach' in rich_budget and 'old.translation' in rich_budget, 'legacy bounded page merge preserves existing rows and exact-source translations')
check('putInt' in rich_override and 'clear(' not in rich_override and 'delete(' not in rich_override, 'per-video limit overrides never clear downloaded pages')
check('fromPages(value)' in rich_limit and 'value !in 1..2500' in rich_limit and 'value !in 1..125' in rich_limit, 'count and page inputs validate their bounds before writing')
check('fun display(' in durable_comments and 'window(context, account, video, 0, limit, limit)' in durable_comments and 'MAX_ROWS = 500' in read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentData.kt'), '2500-row presentation aggregation leaves individual serialized page bounds intact')
check('expanded.add(row.id)' in rich_adapter and 'localReplies > 0' in rich_adapter and 'root(it)?.let(expanded::contains)' in rich_adapter, 'cached replies expand locally including replies whose native count became stale')
check('modes[row.id] = OfflineCommentData.Mode.TRANSLATED' not in rich_adapter, 'manual translation preserves an explicitly selected original or bilingual display mode')
check('Lane.MEDIA' in comment_plan and 'finishMedia' in comment_jobs and 'worker("toki-comment-media")' in download_comments, 'media uses an independent revision-guarded background lane')
check('!job.optBoolean("refreshMedia")' in download_comments and 'metadataPage(' in download_comments, 'expired missing media URLs force a native metadata refresh instead of reusing a stale page')
check('mediaReady", true' in comment_jobs and 'sourceRevision") != sourceRevision' in comment_jobs, 'media completion is required and cannot complete a newer source revision')
check('speedPress.owns' in offline_hook and 'module.deoptimize(speed.begin)' in offline_hook and 'module.deoptimize(speed.touch)' in offline_hook, 'native speed ownership is observed even through compiled call sites before the offline menu competes')
check('WeakReference' in rich_hold and 'it.revision == revision' in rich_hold and 'classes6.dex' in rich_speed_gate, 'native speed arbitration is weak, playback-scoped and guarded by the inspected host DEX')
translator_source = read('app/src/main/java/io/github/meiyongai/toki/hook/NativeSubtitleTranslator.kt')
check('if (!visible) { nativeFrames.remove(video); nativeFrameTexts.remove(video); return }' in translator_source and 'translator?.noteNativeFrame(id,' in subtitle_feature, 'hidden or empty native frames immediately release their timeline suppression lease')
check('translating() && translator.canPrefetch(id)' in subtitle_feature and 'timelineEnabled = ::captionEnabled' in translator_source, 'native typography can use the cached playback timeline without enabling custom translation requests')
check('private val ends = LongArray' in read('app/src/main/java/io/github/meiyongai/toki/hook/CaptionTimeline.kt') and 'cueIndex.at(positionMs)' in read('app/src/main/java/io/github/meiyongai/toki/hook/CaptionPrefetch.kt'), 'indexed movie timelines retain longer active cues under expired overlapping cues without per-frame index rebuilding')
rich_zh = dict(catalog(ROOT / 'app/src/main/res/values-b+zh+Hans/offline_comment_rich_strings.xml'))
rich_en = dict(catalog(ROOT / 'app/src/main/res/values/offline_comment_rich_strings.xml'))
check(rich_zh.keys() == rich_en.keys() and all(sorted(re.findall(r'%[1-9][0-9]*\$[sd]', rich_zh[k])) == sorted(re.findall(r'%[1-9][0-9]*\$[sd]', rich_en[k])) for k in rich_en), 'rich comment labels retain matching Chinese and default keys and placeholders')

check('if (!timelineSource) translator?.noteNativeFrame(video,' in overlay and overlay.index('if (!timelineSource) translator?.noteNativeFrame(video,') < overlay.index('state = displayState'), 'current native overlay frames renew ownership before rendering or replay handoff')
ownership = overlay[overlay.index('if (!singleOwner && timelineSource && nativeBindings.keys.any'):overlay.index('if (!frameVisible()')]
check('presentationEnabled' not in ownership and 'binding.frameVisible()' in ownership, 'visible native captions retain ownership even when custom translation presentation is disabled')

# Unified Toki manager and injected UI. Appearance never writes feature settings on entry.
ui_hub = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/TokiSettingsHub.kt')
ui_theme = read('app/src/main/java/io/github/meiyongai/toki/ui/TokiColors.kt')
ui_main = read('app/src/main/java/io/github/meiyongai/toki/ui/MainActivity.kt')
ui_host = read('app/src/main/java/io/github/meiyongai/toki/hook/HostSettingsDialog.kt')
ui_dashboard = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/DashboardScreen.kt')
ui_dialogs = read('app/src/main/java/io/github/meiyongai/toki/hook/TokiDialogs.kt')
ui_preferences = read('app/src/main/java/io/github/meiyongai/toki/ui/component/PreferenceComponents.kt')
ui_home = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/HomeScreen.kt')
check('TokiSettingsHub(' in ui_main and 'TokiSettingsHub(' in ui_host, 'standalone and injected settings use one shared category navigation surface')
check('rememberSaveable' in ui_hub and 'BackHandler(enabled = category != null)' in ui_hub and 'selected = null' in ui_hub, 'category navigation retains its selection and returns to the category list before closing the host')
check('ConfigClient.put' not in ui_hub and all('SettingsCategory.'+name in ui_dashboard for name in ('SUBTITLES','COMMENTS','CLEANUP','PLAYBACK','DOWNLOADS','REGION','TOOLS')), 'all seven settings categories remain reachable without writing configuration on entry')
check('category: SettingsCategory? = null' in ui_dashboard and 'category == null || category ==' in ui_dashboard, 'the existing complete dashboard remains available alongside category filtering')
check('TokiPalette' in ui_theme and 'dynamicLightColorScheme' not in ui_theme and 'Typography(' in ui_main and 'Shapes(' in ui_main, 'manager and injected Compose surfaces share stable deep and light colors typography and corner sizes')
check('Theme_Toki_EmbeddedDialog' in ui_dialogs and 'Gravity.BOTTOM' in ui_dialogs and 'ContextThemeWrapper(context' in ui_dialogs and 'trackHook' not in ui_dialogs, 'framework styling is limited to explicit module-owned dialog constructors and never hooks host-owned dialogs')
check('Theme_Toki_EmbeddedDialog' in read('app/src/main/java/io/github/meiyongai/toki/hook/ModuleResources.kt'), 'module-inflated custom dialog views resolve the same night-aware theme as their windows')
check('TokiDialogs.segmented(modes)' in rich_dialog and 'button.buttonDrawable = null' in ui_dialogs and 'state_checked' in ui_dialogs, 'compact mode tabs preserve native exclusive radio selection and state descriptions')
check('cell.more.setOnClickListener' in rich_adapter and 'tag = "comment-actions"' in rich_adapter and 'visibility = View.GONE' in rich_adapter, 'row translation controls remain in a compact explicit menu instead of three visible buttons per comment')
check('toki_cached_counts' in rich_dialog and 'toki_count_breakdown' in rich_dialog and 'snapshot.rows.count' in rich_dialog, 'comment summaries distinguish cached top-level replies and media from platform totals')
check(ui_home.index('LSPosedStatusCard(lsposedStatus') < ui_home.index('PreferenceSection(title = strings.getString(R.string.toki_about_title))'), 'the standalone overview presents real activation and host status before version attribution and support')
check('DropdownMenu(' in ui_host and 'ManualHostAdaptationDialog(' in ui_host and 'HostSettingsMirror.status.collectAsState()' in ui_host, 'compact injected header retains language manual adaptation diagnostics and real settings delivery status')
for variant in ('values', 'values-night'):
    xml = ROOT / 'app/src/main/res' / variant / 'toki_ui_themes.xml'
    check('toki_ui_accent' in xml.read_text(encoding='utf-8') and 'toki_dialog_background' in xml.read_text(encoding='utf-8'), variant + ' framework dialogs share palette and rounded backgrounds')
ui_en = dict(catalog(ROOT / 'app/src/main/res/values/toki_ui_strings.xml'))
ui_zh = dict(catalog(ROOT / 'app/src/main/res/values-b+zh+Hans/toki_ui_strings.xml'))
check(ui_en.keys() == ui_zh.keys() and all(sorted(re.findall(r'%[1-9][0-9]*\$[sd]', ui_en[k])) == sorted(re.findall(r'%[1-9][0-9]*\$[sd]', ui_zh[k])) for k in ui_en), 'unified UI labels keep matching Chinese/default keys and count placeholders')

log_limits = read('app/src/main/java/io/github/meiyongai/toki/provider/TrialLogStorage.kt')
log_process = read('app/src/main/java/io/github/meiyongai/toki/provider/BoundedLogProcess.kt')
log_ui = read('app/src/main/java/io/github/meiyongai/toki/ui/screen/TrialLogSection.kt')
check('redirectOutput(' not in log_capture and 'BoundedLogProcess' in log_capture and 'limit-written' in log_process, 'live and snapshot logcat bytes pass through a bounded pipe rather than an unlimited file redirect')
check('MAX_STREAM_BYTES = 8L * 1024 * 1024' in log_limits and 'MAX_TOTAL_BYTES = 64L * 1024 * 1024' in log_limits and 'MAX_CAPTURE_MILLIS = 10L * 60 * 1000' in log_limits, 'log files capture duration and aggregate storage have independent hard ceilings')
check('watchdog.schedule' in log_process and 'Reason.TIME' in log_process and 'expiry?.cancel(false)' in log_process and 'process.destroy()' in log_process, 'capture expiry stops its owned process and cancels its timer even when the stream is idle')
check('documents=linkedMapOf<String,ByteArray>()' in log_capture and 'ZipOutputStream(output)' in log_capture and 'snapshot-' not in log_capture, 'new exports write in-memory snapshots directly into the chosen ZIP without accumulating internal snapshot folders')
check('reserved[key] = pending + bytes' in log_limits and 'canReserve(used,pending,bytes)' in log_limits, 'concurrent capture reservations count against total storage before files are created')
check('clearPlan?.let' in log_ui and '确认清除日志' in log_ui and 'clearConfirmed(context,plan)' in log_ui and 'only an explicitly confirmed file plan' in log_limits, 'manual log removal first shows a concrete count size scope and irreversible-action confirmation')
check('Files.isSymbolicLink(named.toPath())' in log_limits and 'it.parentFile == parent' in log_limits and 'file.canonicalFile.toPath().startsWith(root.toPath())' in log_limits and 'deleteRecursively' not in log_limits, 'log cleanup rejects symlink roots and escaped files and never recursively deletes a computed directory')
check(log_ui.index('TrialLogCapture.stop()};refresh()') < log_ui.index('export.launch('), 'capture stops before the file picker so cancelling export cannot leave logcat running')

check('finishOutput()' in log_process and 'synchronized(sinkLock)' in log_process and 'released.compareAndSet(false,true)' in log_process, 'timeout closes the owned output and releases its reservation even if a vendor pipe reader does not return')
check('adapter.dismissMenus()' in rich_dialog and 'dismissMenus(); generation++' in rich_adapter, 'row action windows are dismissed when their parent list is replaced or closed')

check('private fun SubtitleStylePreview' in read('app/src/main/java/io/github/meiyongai/toki/ui/screen/SubtitleSettings.kt') and 'if (help) AlertDialog' in read('app/src/main/java/io/github/meiyongai/toki/ui/screen/SubtitleSettings.kt'), 'subtitle overview shows the real saved-style preview and keeps detailed translation guidance in a readable disclosure')

comment_sheet = read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentsSheet.kt')
check('OfflineCommentsSheet(context)' in rich_dialog and '.setPositiveButton(' not in rich_dialog, 'cached comments use a dedicated sheet instead of a framework three-button alert')
check('View.GONE' in comment_sheet and 'cache-details' in comment_sheet and 'LinearLayout.LayoutParams(-1, 0, 1f)' in comment_sheet, 'cache details are collapsed and comments own the remaining bounded sheet area')
check('continueStream = {' in rich_dialog and 'adapter.append()' in rich_dialog and 'sheet.reload.setOnClickListener' in rich_dialog and 'limitButton.setOnClickListener' in rich_dialog, 'continuous scroll reload and per-video limits retain their real callbacks')
check('cell.mediaScroll.visibility' in rich_adapter and 'cell.replies.visibility' in rich_adapter, 'empty comment media and reply placeholders do not consume row height')
check('viewport.height * .78f - textHeight' in (JAVA / 'hook/SubtitleOverlay.kt').read_text(encoding='utf-8') and 'lastBaseY' not in (JAVA / 'hook/SubtitleOverlay.kt').read_text(encoding='utf-8'), 'all composed subtitle bindings share a page anchor independent of host caption relayout')

check('HostCaptionFile.read(app.filesDir, location)' in prefetch and prefetch.index('if (HostCaptionFile.isLocal(location))') < prefetch.index('CaptionTimelineDiskCache.read(identity)'), 'local realtime captions are read as bounded host files before the archived or HTTPS path')
check('file.parentFile == allowed' in prefetch and 'MAX_BYTES = 1_048_576' in prefetch and 'check(current())' in prefetch, 'local caption reads enforce the canonical host directory, size and cancellation boundaries')
check('nativeFrameTexts[video] == text' in translator_source and 'frameText.get(frame) as? String)' in subtitle_feature, 'duplicate native text cannot renew a stale frame suppression lease forever')
check('installRequestPolicy(module, loader)' in read('app/src/main/java/io/github/meiyongai/toki/hook/CommentTranslateHook.kt') and 'CommentPageCodeGate.verify(app)' in read('app/src/main/java/io/github/meiyongai/toki/hook/CommentTranslateHook.kt'), 'native automatic comment request translation has its own exact-code guarded policy, not only a feature gate')
check('OfflineCommentJobStore.repairSource(row' in download_comments and 'row.put("schema", 7)' in comment_jobs and 'refreshSource' in download_comments and '.name == "DEFAULT"' in page_network, 'existing offline jobs receive an additive fresh first-page repair through the normal server comment scene')
check('!row.translation.isNullOrBlank() && CommentTranslationKey.normalizedTarget(row.language) == target' in read('app/src/main/java/io/github/meiyongai/toki/hook/CommentTranslationStore.kt'), 'older auxiliary preferences cannot replace a current matching-language archive translation')

check('"CAPCUT" to 54' in template_contract and '54, 110 -> LayoutElement.ANCHOR_CAPCUT' in read('app/src/main/java/io/github/meiyongai/toki/hook/TemplateAnchorPolicy.kt'), 'explicit CapCut anchors 54 and 110 share the existing independent CapCut category')
check('CCTemplateAreaAssem' in template_contract and 'findViewById(2131367119)' in template_contract and 'TemplateRootBindings.Snapshot(view, owner, owner, true, LayoutElement.ANCHOR_CAPCUT)' in template_contract, 'dedicated CapCut-area cleanup binds only its checked child, never its player container')
check('standardComponent(load(name)' in template_contract and 'anchorKey.invoke(it) == componentKey' in template_contract and 'singleOrNull()' in template_contract and 'renderer.getDeclaredField("LJIIJ")' in template_contract, 'standardized component cleanup requires a unique anchor matching its renderer component key and current video instead of hiding shared class families')

check('legacyCoordinator(load("X.0B4Q")' in template_contract and 'candidates?.singleOrNull()' in template_contract, 'legacy anchor rendering only hides a verified single business anchor and refuses shared multi-anchor areas')

check('fun normalizeReplyPage' in durable_comments and 'normalizeReplyPage(read(replyPage), row.id)' in durable_comments, 'old and newly saved flat reply pages regain their real parent identity without deleting archives')
check('tag = "comment-reply-relation"' in rich_adapter and 'drawLine(' in rich_adapter and 'R.string.toki_view_cached_replies' in rich_adapter, 'reply rows have explicit reply-to labels smaller avatars indentation and a thread rail')
check('val choices = cell.buttons' in rich_adapter and 'button.isEnabled = true' in rich_adapter, 'all three row presentation choices remain visible even before translation is cached')
check('prepareMedia(value.first)' in rich_dialog and 'OfflineCommentMediaStore.cache(app, account, video, asset)' in rich_dialog, 'explicit online additions prepare missing image and sticker bytes separately from local read-only preview')
check('OfflineCommentLimitOverrides.configured(app, account, video)' in rich_dialog and 'toki_limit_overridden' in rich_dialog, 'reading and online continuation use the same actual per-video and inherited limit shown in the sheet')

check('CommentResponseTranslationQueue<Row>' in read('app/src/main/java/io/github/meiyongai/toki/hook/CommentTranslateHook.kt') and 'val getItems = list.getMethod("getItems")' in read('app/src/main/java/io/github/meiyongai/toki/hook/CommentTranslateHook.kt'), 'automatic comment translation admits complete returned response lists and replies rather than only visible cells')
check('sheet.feedback(message, busy)' in rich_dialog and 'tag = "cache-action-progress"' in comment_sheet and 'R.string.toki_action_stopped' in rich_dialog, 'comment actions publish visible busy results errors and explicit cancellation feedback')

check('OfflineCommentStreamPlan.next(' in rich_dialog and 'nextPage.setOnClickListener { translationOnly = true' in rich_dialog, 'network continuation is an explicit bounded stream plan with independent reply cursors, not a visible next-page control')
check('normalizeReplyPage(page, root)' in durable_comments and 'CommentSnapshotCodec.encode(durable)' in durable_comments, 'server reply roots are persisted and same-source rich metadata remains intact across reopen')

check('data class Quotas' in rich_budget and 'fun mergeScoped' in rich_budget and 'val rootFull = quotas.rootCount' in read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentStreamPlan.kt'), 'root comments and replies have independent counters with per-thread caps in reading and continuation')
check('offline_comment_reply_limit' in schema and 'offline_comment_thread_reply_limit' in schema and 'fun replies(context: Context' in rich_limit, 'reply total and per-comment quotas are validated saved settings with visible editing controls')

check('val reusable = previous?.owner == owner' in rich_views and 'tile.image.drawable == null && !tile.loading' in rich_views, 'unchanged comment media reuse attached tiles and drawables without repeated decode or animation restart')
check('OfflineCommentArchive.observe' in rich_dialog and 'adapter.submit(read.snapshot, retain = true)' in rich_dialog and 'archiveSubscription.close()' in rich_dialog, 'open comment counts observe committed archive updates and keep scroll and reply expansion')
check('storedRoots.size, storedReplies.size' in durable_comments and 'if (row.id in admitted) continue' in durable_comments and 'toki_count_breakdown' in rich_dialog, 'stored versus quota-filtered counts are explicit and duplicate reply pages do not consume unique reply quota')

check('"getReplyCommentTotal", java.lang.Long.TYPE' in comment_reader and 'depth, replyTotal, shortened' in comment_reader, 'offline reply scheduling reads the actual native reply_comment_total rather than only the unrelated zero reply_count')
check('replyTotalsVersion' in durable_comments and 'OfflineCommentArchive.replyTotalsVerified' in download_comments, 'old pages with unverified reply totals are refreshed through metadata repair without removing originals or media')
check('placementViewport()' in overlay and 'android.R.id.content' in overlay and 'viewportLocation[1] - overlayLocation[1]' in overlay, 'native and timeline mounting hosts share a window-content coordinate space rather than each host height')

# v3: prove the installed path, not just preserved legacy implementation text.
v3_repo = read('app/src/main/java/io/github/meiyongai/toki/hook/CommentRepository.kt')
v3_engine = read('app/src/main/java/io/github/meiyongai/toki/hook/CommentEngine.kt')
v3_reader = read('app/src/main/java/io/github/meiyongai/toki/hook/CommentReaderSheet.kt')
v3_presenter = read('app/src/main/java/io/github/meiyongai/toki/hook/SubtitleSessionPresenter.kt')
v3_controller = read('app/src/main/java/io/github/meiyongai/toki/hook/SubtitleDisplayController.kt')
check('CommentEngine.install(module, loader)' in download_comments and 'private fun initLegacy' in download_comments, 'rollout selector installs exactly one engine; legacy worker remains a mutually exclusive recovery path')
check('return CommentReaderSheet.show' in rich_dialog and 'private fun showLegacy' in rich_dialog, 'new comment entry bypasses the independent legacy dialog pipeline only after rollout selection')
check('SQLiteDatabase.openOrCreateDatabase' in v3_repo and all(x in v3_repo for x in ('comment_versions','translations','assets','streams','tasks','lane_turns','migration')), 'comment facts, history, translations, media, stream cursors, leased tasks and fair turns are durable SQLite data')
check('ORDER BY q.ticket' in v3_repo and 'UPDATE lane_turns SET ticket' in v3_repo and 'LIMIT 1' in v3_repo, 'every claim advances a durable video lane ticket before execution')
check('lease=?' in v3_repo and 'v.epoch=?' in v3_repo and 'source_hash' in v3_repo, 'late work must match item lease, video epoch and source hash')
check('observeVideo' in v3_reader and 'submitCommand' in v3_reader and 'readVideo' in v3_reader and '.fetch(' not in v3_reader and 'NativeSubtitleTranslator.transport' not in v3_reader, 'reader only observes facts, reads locally and submits commands without direct network workers')
reader_index = read('app/src/main/java/io/github/meiyongai/toki/hook/CommentReaderIndex.kt')
check('it.take(2)' in reader_index and 'expanded' in reader_index and 'hasStableIds()=true' in v3_reader, 'two cached reply previews and stable row identities survive incremental refresh')
check('val presenter = SubtitleSessionPresenter' in subtitle_feature and 'SubtitleOverlay(' not in subtitle_feature, 'installed subtitle hooks share exactly one session presenter, never competing renderer overlays')
check('timelineReady=ready' in v3_controller and 'SystemClock' not in v3_controller and 'nativeSources' in v3_presenter, 'caption controller uses track/playback state rather than a two-second native-frame expiry')

check('private var selected: Boolean?' in read('app/src/main/java/io/github/meiyongai/toki/hook/CommentCoreRollout.kt') and 'if (!CommentCoreRollout.enabled())' in subtitle_feature and 'else initLegacy' in download_comments, 'rollout is frozen for a process, retains a default-off recovery route and cannot install both writers')

regression_reader = read('app/src/main/java/io/github/meiyongai/toki/hook/CommentReaderSheet.kt')
valid_guard = regression_reader[regression_reader.index('fun valid()'):regression_reader.index('fun notify(')]
check('current()' not in valid_guard and 'source.accountMatches(account)' not in valid_guard, 'pinned cached reader survives playback-holder and login-account changes while retaining Activity guards')
check('runtime.readers.execute' in v3_engine and 'onlyVideo=video' in v3_engine, 'reader registration is not queued behind full backfill and only migrates its own video')
check('progressCounts(reader.account(app))' in v3_engine and 'mapNotNull{repo.readVideo' not in v3_engine and 'scope_stats' in v3_repo, 'background diagnostics read compact persisted counters instead of decoding every comment under the global monitor')
check('repo.nextDelay' in v3_engine and 'main.postDelayed(tick,1500)' not in v3_engine and 'registerDefaultNetworkCallback' in v3_engine, 'idle comment workers have no permanent polling loop and resume on due work or network callbacks')
check('nativeFrames[root]' in v3_presenter and 'nativeOwner' in v3_presenter and 'presenter.nativeRevision(root)!=previousRevision' in subtitle_feature, 'hidden sibling renderers cannot erase the selected native cue and a VM duplicate cannot overwrite a delivered renderer frame')

local_identity = read('app/src/main/java/io/github/meiyongai/toki/hook/HostOfflineComments.kt')
check('token == LOCAL_SCOPE ||' in local_identity and 'reflected.isInitialized() && token' not in local_identity, 'fresh local readers never report a false account change because their host contract was not initialized')
check('migrateLocalScope' in v3_repo and 'copyLocalVideo' in v3_repo and 'restored.copy(account=account)' in v3_repo, 'old account partitions and legacy pages migrate additively into the device-local cache without deleting originals')
check('Renderer geometry is not playback visibility' in v3_presenter and 'it.isShown&&it.getGlobalVisibleRect' not in v3_presenter, 'verified expanded captions survive collapsed native containers; selected page visibility still guards presentation')

check('repo.leaseAlive(w)' in v3_engine and 'repo.authorized(w)' not in v3_engine and 'ConcurrentHashMap<String,Work>' in v3_repo, 'main-thread transport eligibility uses a memory lease, never waits for SQLite migration monitor')
check('DigestHex.encode' in v3_repo and 'String.format' not in v3_repo and '"%02x"' not in v3_repo, 'bulk migration digest encoding does not re-enter host Locale hooks per byte')

check('capturedSnapshot(' in local_identity and 'truncated || total > sourceRows.size' not in local_identity and 'db.version=4' in v3_repo, 'native 20-row pages do not stop merely because server total exceeds page size; old premature stops reopen non-destructively')

check("if (CommentReaderSheet.isReading) return@intercept false" in read("app/src/main/java/io/github/meiyongai/toki/hook/AutoScrollHook.kt") and "openReaders.decrementAndGet()" in regression_reader, "cached reading temporarily blocks native auto-advance without rewriting auto-scroll settings")

check('if (replyTotal > replies.orEmpty().size) truncated = true' not in local_identity and 'if(!seen.add(id))continue' in local_identity and '!page.hasMore&&(page.truncated||insufficientReply)' in v3_repo, 'missing embedded replies and duplicate IDs do not prematurely stop a valid main-comment cursor')

check('CommentConcurrency(4)' in v3_engine and 'newFixedThreadPool(concurrency.limit(lane))' in v3_engine and 'NOT EXISTS(SELECT 1 FROM tasks flight' in v3_repo, 'comment translation has four bounded concurrent workers with one in-flight item per video and durable fair admission')

check('SELECT payload FROM comments WHERE account=? AND video=? AND id=? AND source_hash=?' in v3_repo and 'UPDATE scope_stats SET text_ready=MIN(text_total,text_ready+1)' in v3_repo, 'translation admission and completion use keyed reads and incremental counts rather than whole-video reconciliation')

check('scope(feature).takeIf { it.active }' in read("app/src/main/java/io/github/meiyongai/toki/hook/HookRuntime.kt") and '(startupFeatures + liveFeatures) - rejected' in read("app/src/main/java/io/github/meiyongai/toki/hook/HostFeaturePlan.kt"), "revoked hooks remain disabled across repeated package callbacks without crashing host startup")
check('connection.died(service)' in read("app/src/main/java/io/github/meiyongai/toki/util/LSPosedStatusHelper.kt") and 'current !== service' in read("app/src/main/java/io/github/meiyongai/toki/util/FrameworkServiceConnection.kt"), "only the currently bound framework service can disconnect configuration and activation state")

check('android.permission.ACCESS_NETWORK_STATE' in read('app/src/main/AndroidManifest.xml'), 'network state observation declares its normal manifest permission')
check('main.hasCallbacks(archiveChanged)' not in read('app/src/main/java/io/github/meiyongai/toki/hook/OfflineCommentsDialog.kt'), 'cache notification coalescing remains compatible with API28')
import xml.etree.ElementTree as lint_et
lint_backlog = lint_et.fromstring(read('app/lint-baseline.xml'))
check(len(lint_backlog) == 389 and all(x.get('id') == 'MissingTranslation' for x in lint_backlog), 'lint baseline is limited to enumerated existing extension translation fallback debt')

check('root in rootIds&&limits.acceptsReplies(scope,root)' in v3_repo and 'if(q.acceptsReplies(scope,root))1 else 0' in v3_repo,
    'only currently required reply streams affect scope completion; fulfilled quota does not erase historical failures')
check('root_shortfall:' in v3_repo and 'coverageExplanation(s.reason)' in read('app/src/main/java/io/github/meiyongai/toki/hook/CommentReaderSheet.kt'),
    'real configured deficits and stream stop reasons remain readable rather than falsely prepared')

# Defaults are product defaults, not the maximum supported archival capacity.
check('DEFAULT_LIMIT = 200' in rich_preferences and 'DEFAULT_THREAD_LIMIT = 15' in rich_preferences and '?: DEFAULT_LIMIT' in rich_preferences, 'unset comment quotas use independent 200/1000/15 product defaults while retaining the 2500 ceiling')
check('OfflineCommentPreferences.DEFAULT_LIMIT' in read('app/src/main/java/io/github/meiyongai/toki/ui/screen/OfflineCommentCacheSettings.kt') and 'save(OfflineCommentPreferences.DEFAULT_LIMIT)' in rich_limit and 'save(OfflineCommentPreferences.DEFAULT_REPLY_LIMIT, OfflineCommentPreferences.DEFAULT_THREAD_LIMIT)' in rich_limit, 'global count and reply resets share the product defaults; per-video reset retains inheritance')
check('CREATE TABLE IF NOT EXISTS migration_scopes' in v3_repo and 'bindMigration(account,v,folder.name,checkpoint)' in v3_repo and 'inheritMigrations(old,v,scope)' in v3_repo and 'val errors=migrationErrors(account,video)' in v3_repo, 'migration provenance survives local-scope copies and drives both reader and background incomplete status')

check('dialog.setCanceledOnTouchOutside(true)' in regression_reader, 'cached comment sheet allows explicit outside-window dismissal without cache commands')
check('task_video_due' in v3_repo and 'task_running' in v3_repo and 'Select the fair VIDEO first' in v3_repo, 'comment scheduling uses indexed per-video probes while preserving fair lane tickets and one lease per video')
check('reason=renderer-text-changed' in v3_presenter and 'watchDraw(null)' in v3_presenter, 'posted text changes from the selected native renderer update the single caption owner and the listener is released')

check('state=="EXPANDED"&&(owner==null||owner===root)' in v3_presenter and 'state!="EXPANDED"&&owner===root' in v3_presenter, 'empty expanded frames retain only their current renderer observer; explicitly hidden frames cannot resurrect')
check('rowIndex.replies(r.id)' in regression_reader and 'rowIndex.byId[r.parentId]' in regression_reader and 'rowIndex.matches(owner,source)' in regression_reader, 'comment adapter uses snapshot-scoped parent reply and source indexes rather than nested scans per visible row')
