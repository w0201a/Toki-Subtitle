# Legacy Xposed compile API

`xposed-api-82.jar` is the public API stub artifact `de.robv.android.xposed:api:82`, downloaded from the Aliyun Maven public mirror. It is a compile-only dependency of the LSPatch build and must never be packaged; LSPatch supplies these classes at runtime.

Upstream: https://github.com/rovo89/XposedBridge
Artifact: https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar

The normal Debug build uses framework-provided modern libxposed API 102. The separate LSPatch build bundles the modern facade used by shared Toki code and adapts it to the traditional framework entry. It does not turn a traditional injector into a full API 102 framework.

SHA-256: `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`

Build the integrated-module APK with `scripts/build-lspatch.ps1`. The script relocates the modern API facade into a private namespace and rewrites generated source copies; original sources stay unchanged. This is required because VectorModuleManager rejects modules defining `io.github.libxposed.api.*`. `scripts/verify-lspatch.py` checks actual DEX class definitions, the legacy entry and bundled discovery resources.

For a single distributable, use `scripts/build-universal.ps1`. It builds the private legacy payload first, copies it into generated assets, then builds the outer modern module plus traditional bootstrap. No framework API definitions are bundled in the primary DEX. The bootstrap loads the private payload only when a modern framework API is unavailable. Both stages run serially with one worker and no persistent Gradle daemon. `scripts/verify-universal.py` checks both entry points, API class definitions, superclass isolation and matching discovery profiles.
