# Legacy Xposed compile API

`xposed-api-82.jar` is the public API stub artifact `de.robv.android.xposed:api:82`, downloaded from the Aliyun Maven public mirror. It is a compile-only dependency of the LSPatch build and must never be packaged; LSPatch supplies these classes at runtime.

Upstream: https://github.com/rovo89/XposedBridge
Artifact: https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar

The normal Debug build uses framework-provided modern libxposed API 102. The separate LSPatch build bundles the modern facade used by shared Toki code and adapts it to the traditional framework entry. It does not turn a traditional injector into a full API 102 framework.

SHA-256: `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`
