# 数字助理入口恢复与搜索手势边界（2026-11-15）

## 本批授权和范围

用户要求数字助理与搜索手势实际可用，不以安装框架教程作为完成结果。允许修复原有预期功能；未授权新增替代手势、浮窗设计或其他产品界面。云能力只调研，结果见54号文档，没有新增收费调用或云配置页面。

## 已查明的问题

1. `ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT` 指向不存在的 `NexusVoiceInteractionService`。真实类和Manifest均为 `EtaVoiceInteractionService`，无别名。系统自动绑定由此使用错误组件。
2. `EtaVoiceAssistActivity` 请求Session失败后立即结束。服务未ready或系统仅登记assistant而未启动VoiceInteractionService时，请求静默丢失。
3. 无悬浮权限时系统Session没有自身界面，原OverlayService只记录日志并停止，用户看不到权限引导。
4. 默认助手状态只识别Service，不识别真正的ASSIST桥接Activity。

手机只读基线（1515）：secure assistant为实际NEXA Service；voice_interaction_service为空；dumpsys voiceinteraction显示`No active implementation`；SYSTEM_ALERT_WINDOW为allow。这些是系统登记状态，不是用户手势执行验收。

## 修复内容

- 自动绑定改用实际已注册服务组件，保留applicationId和数据兼容性。
- 未ready或showSession异常时，由前台ASSIST桥接复用已有助理浮窗。此路径没有系统Session屏幕上下文，不声称获得系统截图。
- 桥接无悬浮权限时打开已有Android悬浮权限页面；系统Session无权限时也引导授权。捕获系统启动拒绝，保留可定位日志，不绕过系统要求。
- Service销毁清理活动引用，避免对失效Service请求；调用效果和锁屏启动失败不会直接崩溃。
- 默认助手识别覆盖真实Service与ASSIST Activity。
- 原“数字助理应用”入口在Android支持且尚未持有角色时优先发起系统ROLE_ASSISTANT请求；不可用或已持有时回退原系统默认应用设置。由用户完成系统选择，不通过ADB强写默认助手设置。

不改原有助理面板布局、材质、开关语义和搜索手势设计。

## 搜索手势的实际限制与后续决定

当前“长按导航条圈选搜索”由SystemUI Hook消费；“双指长按圈选搜索”由ColorDirect Hook消费。两者转发系统Google Contextual Search，并不只是NEXA发起一次普通网页搜索。普通APK无法接收系统导航条或其他应用上的同等全局触摸事件，打开无障碍权限也不自动取得这两个手势。

无框架时不能把RemotePreferences改成本地开关后宣称可用；本批保留原保护状态。无需框架的可行路线是系统默认助理入口及原有NEXA助理面板，系统正常创建Session时可接收允许提供的屏幕内容。若希望另设全局触发入口，需要先具体批准替代交互；它不能冒充Google原生圈选搜索。

本批不安装框架、不刷机、不新增跨应用透明触摸覆盖层、不修改系统安全设置。不能从编译、安装或设置状态推断两个系统手势已经可用。

## 验证和交付

最小入口回归、performance构建和覆盖安装已完成，最终结果见下一节。没有手机页面测试、发送模型消息、收费云请求或搜索手势验收。

## 2026111516 最终验证与覆盖安装

入口最小检查仅执行一次：EtaAssistantEntryTest 8例在SDK33/36合计16项，AssistantBindingTest 4项，总20项通过、0失败/错误/跳过，37秒。实际类与合并Manifest比对、服务未ready/未绑定、默认Service/Activity、权限与启动拒绝分支有模拟环境证据；不代表真机系统手势验收。

一次assemblePerformance成功1m38s，原开发证书SHA256 f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4，v2签名、非debuggable、ARM64、ZIP16KiB对齐确认；不声称生产签名或逐库ELF对齐验收。

APK版本0.7.7-smooth / 2026111516，64317141字节，SHA256 0134B90A1AEFBA4B183C9A1CB5D9A2870A973615D4EF02A954810E3484AEB9CD。桌面C:/Users/29136/Desktop/NEXA-Cloud-Assistant-20261115/NEXA-v0.7.7-2026111516.apk。

OPPO PHY110（1fe75f23）adb install -r返回Success，dumpsys确认2026111516 / 0.7.7-smooth，lastUpdateTime=2026-11-15 19:57:48。保留数据，未卸载、未清数据、未启动页面、未测试手机手势或模型请求。日志build/assistant-entry-test-20261115.log与build/assistant-entry-package-20261115.log。

用户需在原“数字助理应用”入口重新选择NEXA，让系统重新登记有效的语音交互服务；本批没有通过ADB替用户强写系统默认角色。仍未完成的是两个系统级圈选手势在无框架条件下的同等实现。建议下一步经用户批准增加控制中心“识屏搜索”快捷入口，以已授权无障碍截图及现有助理/网页工具提供无需框架的搜索触发方式；它是替代入口，不冒称原长按导航条/双指手势已恢复。
