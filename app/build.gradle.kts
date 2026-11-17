import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStoreFile = System.getenv("NEXUS_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("NEXUS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("NEXUS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("NEXUS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// CI 对非发版构建传入提交号，按 SemVer 构建元数据追加为 +<sha>，不影响版本先后；本地与发版构建不传
val buildMetadata = providers.gradleProperty("nexus.buildMetadata").orNull?.takeIf { it.isNotBlank() }
require(buildMetadata == null || buildMetadata.matches(Regex("[0-9A-Za-z.-]+"))) {
    "nexus.buildMetadata 只能包含字母、数字、点和连字符"
}
val buildMetadataSuffix = buildMetadata?.let { "+$it" }.orEmpty()

android {
    namespace = "io.github.mangi.eta"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        // Nexus is a separate product and intentionally cannot overwrite an Eta install.
        applicationId = "io.github.zhh_lgtm.nexus"
        minSdk = 33
        targetSdk = 37
        versionCode = 2026111701
        versionName = "0.7.8"

        ndk {
            // 同时过滤本地与依赖 AAR 的原生库，确保 APK 仅包含 ARM64。
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 版本名带构建类型标记，设置页与 APK 文件名据此区分 Debug 包
            versionNameSuffix = "-debug" + buildMetadataSuffix
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
            // Android Studio Live Edit uses the debug variant. Sign that variant with
            // the production key when available so it can update an installed release
            // in place instead of asking the user to erase app data after a signature mismatch.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
        release {
            versionNameSuffix = buildMetadataSuffix
            signingConfig = signingConfigs.findByName("release")
            // ColorOS 会依据 pkgFlags 中的 DEBUGGABLE 将侧载无障碍服务判为风险。
            // release 必须始终关闭调试标志，不能依赖 AGP 的默认值。
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("performance") {
            initWith(getByName("release"))
            // Same package and signing key as the installed development build: update in place.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            versionNameSuffix = "-smooth" + buildMetadataSuffix
            isDebuggable = false
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // versionCode 采用日期编码，已发布值只能递增；上限 2100000000 足够覆盖到 2099 年。
        disable += "HighAppVersionCode"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 在高版本 JDK 下需要访问内部 API，参数只作用于测试 JVM。
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        // 文件名只取 versionName：构建类型标记与 CI 构建元数据都由 versionNameSuffix 带出
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "Nexus-v$it.apk" })
        }
    }
}

dependencies {
    implementation(project(":kernel-api"))
    implementation(project(":application-api"))
    implementation(project(":plugin-runtime"))
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.jsoup)
    implementation(libs.exp4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    // 只使用 GFM 解析器；聊天渲染层由 ui/markdown 自建，按块冻结并接入逐字显现。
    implementation(libs.intellij.markdown)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    // Bounded native SVG and TeX rasterization; no per-message WebView or document engine.
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation(libs.hidden.api.bypass)
    // Native QR capture/decoding; no Google services or additional browser engine.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.work.runtime)

    // Google LiteRT-LM for .litertlm. The separately vendored llama.cpp arm64 runtime handles
    // GGUF through a dedicated adapter and is never routed through LiteRT-LM.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    // MediaPipe audio tasks provide the production ASR/TTS adapters for LiteRT speech bundles.
    implementation("com.google.mediapipe:tasks-audio:1.2.2")
    implementation("com.k2fsa.sherpa:sherpa-onnx-static-link-onnxruntime:2.0.1@aar")
    // Qualcomm dispatch is kept in the APK, while the licensed QNN runtime is installed on demand
    // into app storage. This keeps the LiteRT-LM/NPU ABI paired and avoids redistributing QNN.
    implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.2")

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)
    // ToolPkg compatibility: Operit-compatible manifests may use HJSON as well as JSON.
    implementation("org.hjson:hjson:3.1.0")

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}

val sherpaVerifiedArtifact = configurations.detachedConfiguration(
    dependencies.create("com.k2fsa.sherpa:sherpa-onnx-static-link-onnxruntime:2.0.1@aar"),
)
val verifySherpaRuntime = tasks.register("verifySherpaRuntime") {
    inputs.files(sherpaVerifiedArtifact)
    doLast {
        // Read the resolved input through TaskInputs. Capturing the detached configuration
        // object inside the action prevents Gradle configuration-cache serialization.
        val artifact = inputs.files.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575") { "Sherpa runtime SHA-256 mismatch" }
    }
}
tasks.named("preBuild") { dependsOn(verifySherpaRuntime) }
