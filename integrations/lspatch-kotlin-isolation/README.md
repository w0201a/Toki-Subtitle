# LSPatch Kotlin runtime isolation — reusable framework build fix

This directory contains the source change actually used to build the generic compatibility-test manager and CLI. It does **not** contain a modified TikTok APK, user logs, signing keys, or a target-version byte-offset patch.

## What is fixed

A parent-first LSPatch framework loader can resolve the application's old `kotlin.jvm.internal.Intrinsics` before the copy bundled with Vector. The reported failure occurs in `VectorInlinedCallers.<clinit>` before the Toki module is entered.

The `:patch-loader:copyDexDebug` / `copyDexRelease` build producer now isolates the framework's bundled `kotlin` and `kotlinx` references to framework-private `kotlim` / `kotlimx` names. Both the manager's normal APK patch flow and the standalone CLI consume that output. An invalid DEX, damaged checksum, reserved namespace collision, or changed string ordering aborts packaging. Raw, unisolated DEX is kept in build intermediates and is not a manager/CLI asset.

This same-length DEX transform preserves offsets and table ordering, and recalculates DEX SHA-1/Adler32. `IsolateKotlinDex.java` implements the complete deterministic transform; no Python or external bytecode tool is needed during the Gradle build.

## Pinned source and build

Exact source commits and reviewed optional native asset input are recorded in `source-lock.json`.

1. Clone the pinned LSPatch source and initialize its core and required Java submodules. Use an independent checkout; do not reset an existing working tree.
2. Apply `apply-framework-fix.py` to the clean checkout. It rejects a different source revision or a dirty checkout.
3. Provide Android SDK 37/build-tools37 and Java21+. The default native path also requires the pinned NDK and native submodules.
4. Build `:manager:assembleDebug :jar:buildDebug`. The loader isolation is required in debug and release packaging, not an optional target-app flag.

For the tested Java-only compatibility build, the exact487 manager supplies **only** the four native core7306 `.so` files. The loader, metaloader, manager and CLI are freshly compiled from pinned source. Its SHA256 must match the lock; this is not a claim that the native library was rebuilt.

Test build properties:

```text
-PtokiNativeAssets=<reviewed487-manager.apk>
-PtokiManagerPackage=io.github.meiyongai.toki.lspatch
-PtokiVersionCode=1000487
-PtokiVersionName=1.2-kotlinfix-test.1
-PtokiCoreVersionCode=7306
-PtokiCoreVersionName=canary-3106
-PtokiCoreVersionHash=e00c5c5038bbcc14e0b382ab893301d6993e6989
```

Use an argument-array launcher rather than unquoted PowerShell dotted `-P...` values. Never run `clean`, remove user data, or replace existing signing keys for this build.

## Test delivery and upgrade boundary

- `Toki-LSPatch-KotlinFix-test.1.apk` is a **framework compatibility-test manager**, not Toki's module APK and not an official LSPatch release.
- It uses `io.github.meiyongai.toki.lspatch` and can coexist with `org.lsposed.lspatch`. It does not overwrite or migrate the original manager's settings. It is debug-signed; this is not a production signing/upgrade policy.
- Applications previously bound to `org.lsposed.lspatch` do not automatically change managers. Do not silently retarget them. Existing installations require a separately planned normal migration/patch flow with matching app signing identity and data preservation; a different manager signature must never be solved by instructing users to uninstall their data.
- New users can use the same framework build and the standard manager/CLI flow with the standalone Toki module; there is no need to send private TikTok APKs to the maintainer for bespoke edits.
- Toki remains a standalone module. Framework compatibility is not proof that every Toki hook or every host version works.

## Verified in this run

- Full source build succeeded: 203 Gradle tasks.
- 8 build-tool regressions passed (valid transform; damaged SHA; malformed header; in-place input refusal; namespace collision; sort change; missing runtime; unsorted input).
- Manager APK signature, 16KiB alignment, all four native ABI assets and identical isolated loader in manager/CLI verified.
- Final CLI patched two independent synthetic apps in both integrated and manager modes. No target-app-specific modifications were used.
- On Android13 physical hardware, two integrated and two manager-mode synthetic apps all bootstrapped the framework and entered their test module. Manager-mode scope writes targeted only these synthetic packages in the new test manager's own DB, via `run-as`, not `su`. Existing TikTok, Toki settings, framework scopes and network settings were not changed.
- The manager itself was installed side-by-side and launched without a crash in the observed window. Shizuku was not granted automatically.

## Not yet verified

- The report's Huawei Android10/API29 full startup and module features.
- A clean API29 software emulator was attempted twice, but did not complete boot without host hardware acceleration. Its test process was gracefully shut down and AVD/logs retained; no API29 runtime success is claimed.
- A production manager signing/upgrade policy and broad device/host compatibility matrix.
- Subtitle-stall resolution and comment-throughput improvement in the reported real playback/cache workload. These remain separate Toki acceptance tasks.

`build-regression-fixtures.py` creates the two independent test app APKs and an intentionally small test module. These are synthetic fixtures, **not** a stand-in for full Toki feature tests.

## Licensing and provenance

Upstream is JingMatrix/LSPatch with pinned Vector core. `LICENSE-LSPatch` is included. The integration patch, complete transformation source, source lock and regression source are retained here so the general repair can be rebuilt and reviewed. Private APK inputs and derived full target APKs are not published.
