-keep class com.ai.assistance.operit.core.tools.javascript.QuickJsNativeBridge { *; }
-keep class com.ai.assistance.operit.util.ToolPkgWasmNative { *; }
-keep class com.ai.assistance.operit.core.tools.javascript.QuickJsNativeRuntime$HostBridge { *; }
-keepclassmembers class * implements com.ai.assistance.operit.core.tools.javascript.QuickJsNativeRuntime$HostBridge {
    public java.lang.String onCall(java.lang.String, java.lang.String);
}
