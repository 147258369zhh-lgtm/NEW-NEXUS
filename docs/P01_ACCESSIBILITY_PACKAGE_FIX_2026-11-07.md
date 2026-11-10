# 无障碍包名兼容修复（2026-11-07）

本次修复针对 `applicationId = fuck.andes` 与源码 namespace `io.github.mangi.eta` 不一致导致的系统无障碍保护失效。

- `AccessibilityServiceEnforcer` 使用 `BuildConfig.APPLICATION_ID` 构造目标包名，系统侧校验、监听和恢复均指向实际安装包。
- `ModuleConfig`、语音入口和虚拟显示自包判断同步使用实际 application id，避免系统路由继续命中旧 namespace。
- 更新了受影响单元测试中的包名断言。

验证：

- `:app:testDebugUnitTest` 定向无障碍/入口/虚拟显示测试通过。
- `:app:assembleDebug` 成功。
- 已通过 `adb install -r` 安装到 OPPO Find X7 Ultra（设备 `1fe75f23`）。
- 安装后及等待 20 秒复查：`accessibility_enabled=1`，Eta 服务仍在启用列表并已绑定，`Crashed services={}`。
- 已保留并恢复设备原有的 ColorOS 与 GKD 无障碍服务。
- 安装包副本：`app/build/outputs/apk/debug/Eta-v4.7.0-debug.apk`，并已复制到桌面。

长期息屏、ColorOS 后台策略及 Android 17 OTA 仍需要设备持续使用观察；本次没有宣称这些场景已完成真机验收。
