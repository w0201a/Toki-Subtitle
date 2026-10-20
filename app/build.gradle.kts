import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertiesFile = rootProject.file("keystore/keystore.properties")
val releaseSigningProperties = Properties()
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use { releaseSigningProperties.load(it) }
}
val hasReleaseSigning = releaseSigningPropertiesFile.isFile
val productionUnified = providers.gradleProperty("tokiProduction").orNull == "true"
check(!productionUnified || hasReleaseSigning) { "Official unified build requires release signing" }

android {
    namespace = "io.github.meiyongai.toki.subtitle"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.meiyongai.toki.subtitle"
        minSdk = 28
        targetSdk = 35
        versionCode = 56
        versionName = "2.0.36"
        // 默认仍可构建通用包；真机调试包可只带目标 ABI。
        providers.gradleProperty("subtitleAbi").orNull?.let { ndk.abiFilters.add(it) }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseSigningProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        create("universal") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName(if (productionUnified) "release" else "debug")
            isDebuggable = !productionUnified
            matchingFallbacks += "debug"
        }
        create("lspatch") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName(if (productionUnified) "release" else "debug")
            isDebuggable = !productionUnified
            matchingFallbacks += "debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("Release signing is not configured; create keystore/keystore.properties before publishing.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // PrivateApiDetector 无法解析宿主 X.08ZA 等合法 DEX 混淆类名，会在报告前崩溃。
        // 此检查针对 Android 隐藏 API；宿主反射契约另由代码身份和运行时检查限定。
        disable += setOf("BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi")
    }

    // 应用内语言选择必须在离线环境下可用，所有语言随同一安装包分发。
    bundle {
        language {
            enableSplit = false
        }
    }

    // 语言测试直接验证编译后的Android资源选择，而非只比较XML键名。
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // libxposed 现代化 API：仅在编译期间参与类型检查，运行时由 LSPosed 框架在宿主进程中提供
    compileOnly("io.github.libxposed:api:102.0.0")
    // LSPatch supplies legacy Xposed; only this build bundles the modern facade
    // consumed by the shared implementation. The normal Debug stays framework-owned.
    if (providers.gradleProperty("tokiLegacy").orNull == "true")
        add("lspatchImplementation", files(layout.buildDirectory.file("legacy-api/relocated-api.jar")))
    add("lspatchCompileOnly", files("libs/xposed-api-82.jar"))
    add("universalCompileOnly", files("libs/xposed-api-82.jar"))
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.smali:dexlib2:2.5.2") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.7.1-android")
    implementation("org.luckypray:dexkit:2.0.7")

    // Jetpack Compose BOM 依赖版本对齐
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
    // 在 JVM 中驱动真实模块入口，验证按功能安装与全关会话诊断。
    testImplementation("io.github.libxposed:api:102.0.0")
    // 仅在 JVM 单元测试中提供 Android org.json 的实现，不进入 APK。
    testImplementation("org.json:json:20240303")
    // 仅用于在 JVM 中验证 Android Bundle 诊断数据，不进入模块 APK。
    testImplementation("org.robolectric:robolectric:4.17")
    // 仅用于本地多语言布局测量及交互回归，不进入模块 APK。
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

// The LSPatch loader rejects modules defining io.github.libxposed.api.* even
// when those classes are an adapter. A private namespace is required throughout.
if (providers.gradleProperty("tokiLegacy").orNull == "true") {
    android.sourceSets.getByName("main").kotlin.setSrcDirs(listOf(layout.buildDirectory.dir("legacy-src/main")))
    android.sourceSets.getByName("lspatch").kotlin.setSrcDirs(listOf(layout.buildDirectory.dir("legacy-src/lspatch")))
    android.sourceSets.getByName("main").java.setSrcDirs(listOf(layout.buildDirectory.dir("legacy-src/main")))
    android.sourceSets.getByName("lspatch").java.setSrcDirs(listOf(layout.buildDirectory.dir("legacy-src/lspatch")))
}

tasks.matching { it.name == "compileLspatchKotlin" }.configureEach {
    doFirst {
        check(providers.gradleProperty("tokiLegacy").orNull == "true") {
            "Use scripts/build-lspatch.ps1 to prepare the private API namespace."
        }
    }
}

android.sourceSets.getByName("universal").assets.srcDir(layout.buildDirectory.dir("universal-assets").get().asFile)
tasks.matching { it.name == "mergeUniversalAssets" }.configureEach {
    doFirst {
        check(layout.buildDirectory.file("universal-assets/toki/legacy-payload.apk").get().asFile.isFile) {
            "Build and verify the isolated LSPatch payload first."
        }
    }
}
