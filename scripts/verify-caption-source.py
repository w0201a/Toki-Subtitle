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
check(prefetch.index("CaptionTimelineDiskCache.read(identity)") < prefetch.index("val body = read(location, generation)"), "timeline archive is checked before network")
cache = (JAVA / "hook/VideoCaptionCache.kt").read_text(encoding="utf-8")
restore = cache.split("fun restore(", 1)[1].split("fun diagnostic", 1)[0]
check("activeTracks[video] = source.track" in restore and "clear()" in restore, "restore resets stale state and restores active track ownership")
native = (JAVA / "hook/NativeSubtitleTranslator.kt").read_text(encoding="utf-8")
check('CaptionCacheStorage.save(it, "videos_v2", cache.snapshot())' in native, "translator submits snapshots through background archive writer")
check('if (persistenceScheduled) flushCache()' in native, "pending snapshot is submitted on cancel/pause")
ui = (JAVA / "ui/screen/LayoutCleanupSection.kt").read_text(encoding="utf-8")
check("ConfigClient.addStateListener" in ui and "subscription.close()" in ui, "cleanup UI subscribes and disposes configuration listener")
gradle = read("app/build.gradle.kts")
check('versionCode = 76' in gradle and 'versionName = "2.0.56"' in gradle and 'versionNameSuffix = "-debug"' in gradle, "next debug version is 2.0.56-debug / 76")
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
]
for relative in lexical_files:
    kotlin_delimiters((JAVA / relative).read_text(encoding='utf-8'))
    check(True, relative + ' lexical delimiters and string literals are balanced')

for relative in (
    'hook/HostFeaturePlanTest.kt', 'hook/HostScanSessionTest.kt',
    'model/SubtitleAppearanceTest.kt', 'ui/StyledSubtitleTextViewTest.kt',
    'provider/ConfigStoreTest.kt', 'provider/ConfigConflictClockTest.kt',
    'hook/CommentStickerMediaTest.kt', 'hook/StickerExportPolicyTest.kt', 'hook/StickerImageExporterTest.kt',
    'hook/LayoutCleanupPolicyTest.kt',
):
    test = ROOT / 'app/src/test/java/io/github/meiyongai/toki' / relative
    kotlin_delimiters(test.read_text(encoding='utf-8'))
    check(True, relative + ' test-source lexical delimiters are balanced (not executed)')

print("SOURCE CHECKS ONLY: compilation, JUnit, device runtime and TikTok 47.6.3 hooks remain UNVERIFIED.")
