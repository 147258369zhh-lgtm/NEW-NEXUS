# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# libxposed 通过 META-INF/xposed/java_init.list 中的类名字符串加载模块入口；
# 允许入口类混淆时，需要同步改写 java_init.list，避免 release 裁剪后模块失效。
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation class io.github.mangi.eta.ModuleMain {
    public <init>();
}

# R8 默认规则已覆盖 Compose 运行时；Miuix 图标是普通 Kotlin 代码，允许 R8 裁掉未使用图标。
# -dontwarn 仅抑制 KMP 依赖在 Android 侧可能出现的可选平台 warning，不阻止裁剪。
-dontwarn top.yukonga.miuix.**

# libxposed service 通过静态调用和 manifest provider 接入，交给 R8/Android 默认规则保留可达代码。
-dontwarn io.github.libxposed.service.**

# WorkManager resolves the input merger from the serialized class name. Older
# WorkManager releases do not ship a complete R8 consumer rule for this path,
# and release builds then enqueue a job whose no-argument constructor has been
# removed. Keep the tiny reflective surface instead of disabling shrinking.
-keep class * extends androidx.work.InputMerger { public <init>(); }

# LiteRT-LM 的 Java/Kotlin objects cross the JNI boundary by exact class, method and getter
# names. Its 0.19.1 AAR does not ship a complete consumer rule for SamplerConfig,
# ThinkingConfig and ConversationConfig, so narrowing this to benchmark members is unsafe:
# nativeCreateConversation aborts with `mid == null` after R8 renames those getters.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.Conversation {
    public com.google.ai.edge.litertlm.BenchmarkInfo getBenchmarkInfo();
    public int getTokenCount();
}
-keepclassmembers class com.google.ai.edge.litertlm.BenchmarkInfo {
    public int getLastDecodeTokenCount();
    public double getLastDecodeTokensPerSecond();
}

# MediaPipe Task Core encodes file-backed model options through generated Protobuf Lite
# messages. GeneratedMessageLite resolves these fields by their `*_` names at runtime, but
# the tasks AAR does not ship consumer rules. Release R8 otherwise strips fields that only
# participate in that reflective path (for example `ExternalFile.fileContent_`), which
# makes every local ASR/TTS model fail before the native engine is created.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$Builder {
    <fields>;
}

# MediaPipe audio's JNI callback constructs Java result objects by their exact class and
# constructor signatures. Keeping only referenced Kotlin/Java members is insufficient: R8 can
# rewrite a constructor that is only called from native code, producing a process crash in the
# first TTS audio chunk. Preserve the small Java/Kotlin bridge surface used by ASR and TTS.
-keep class com.google.mediapipe.tasks.audio.** { *; }

# llama.cpp resolves its JNI methods and callback descriptors by exact Java names.
# Keep this frozen compatibility surface even after future refactors of Eta's adapter.
-keep class com.iris.localai.engine.llama.LlamaNative { *; }
-keep interface com.iris.localai.engine.llama.PieceSink { *; }
-keep interface com.iris.localai.engine.llama.LoadProgressListener { *; }

# MNN resolves nativeCreate/nativeLoad/nativeGenerate by their exact JVM names and invokes the
# callback interface by class/method lookup. The bundled runtime is IRIDIS 2.4.5 (Apache-2.0).
-keep class com.iris.localai.engine.mnn.MnnNative { *; }
-keep interface com.iris.localai.engine.mnn.MnnPieceSink { *; }
-keep class com.iris.localai.engine.mnn.MnnSink { *; }

# 配置 key 是字符串常量并通过静态调用访问，不需要保留类名或成员名。

# ── Release 日志策略 ────────────────────────────────────────────────────────
# 仅删除 Eta 自有代码中的 Android VERBOSE/DEBUG 调用；INFO/WARN/ERROR 必须保留，
# 第三方依赖的日志策略由依赖自身决定。
-maximumremovedandroidloglevel 3 class io.github.mangi.eta.** { *; }

# XposedModule.log 不是 android.util.Log，R8 无法通过上面的规则识别。
# debug supplier 是纯观察 API；禁止在 supplier 内执行任何业务副作用。
-assumenosideeffects interface io.github.mangi.eta.core.AgentLogger {
    public abstract void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.mangi.eta.core.AndroidAgentLogger {
    public void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.mangi.eta.core.ModuleLogger {
    public void debug(kotlin.jvm.functions.Function0);
}

# ── 序列化与网络依赖 ─────────────────────────────────────────────────────────
# DataStore、kotlinx.serialization、OkHttp 与 Okio 均自带精确的 consumer rules；
# 不在 App 层重复保留整个类或包，避免阻断裁剪、内联和混淆。
# Nimbus 内嵌 Gson 携带此 CLASS 级静态检查注解，运行时不依赖它。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue

# 保留源码与行号属性，便于使用 release mapping 还原线上堆栈。
-keepattributes SourceFile,LineNumberTable

# app_process 通过固定类名启动受控的一方应用与虚拟屏操作入口。
-keep class io.github.mangi.eta.agent.phone.PhoneCommandMain {
    public static void main(java.lang.String[]);
}
-keep class io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain {
    public static void main(java.lang.String[]);
}
-keep class io.github.mangi.eta.agent.accessibility.AccessibilityRecoveryCommandMain {
    public static void main(java.lang.String[]);
}
# Plugin runtime is called from WebView through reflection by Android's
# @JavascriptInterface bridge. Keep the bridge and its annotated members.
-keepclassmembers class io.github.mangi.eta.agent.capability.ToolPackageWebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
