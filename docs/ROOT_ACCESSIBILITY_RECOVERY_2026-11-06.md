# P01 第二组：独立 Root 无障碍恢复

日期：2026-11-06。属于 P01.2 的恢复后端与退避部分，接续[兼容基线](PLATFORM_FOUNDATION_2026-11-06.md)。不依赖 LSPosed，不代表全部后台稳定性或 ColorOS 真机验证已经完成。

## 已实现

- 权限健康页在既有“可选增强”卡中加入“Root 无障碍恢复”开关、状态说明和受限重试入口，沿用现有 Miuix 组件、字号、间距与主题；提供中、繁、英资源。默认关闭，开启不会主动申请 Root。
- 应用进程存活时，订阅 Root、无障碍设置与真实服务连接事件，正常连接时无轮询；给系统 1.5 秒正常绑定时间。GUI 操作前也可请求同一恢复后端，专用互斥锁避免两路同时修改。
- 独立一次性 `app_process` helper 只处理应用所属用户、应用自己签发的 Eta 组件。短期随机 lease 只经 stdin/内存传递；HealthProvider 验证 UID0、协议、阶段和到期时间，不接受模型指定任意目标。
- 只重绑已经启用但未实际连接的 Eta 服务，不写总开关。修改前同步保存 pending 标记，恢复时重新读取并合并最新服务列表；其他服务及间隔内新增服务尽量保留。
- 关闭恢复开关会阻止新尝试，但仍允许当前事务还原刚临时移除的 Eta；Root 撤销或系统总开关关闭后不继续写。恢复中断或状态不确定时保留 pending，停止自动重放，引导到系统设置检查。
- 每轮最多三次，第二次至少间隔 5 秒，第三次至少 20 秒。需要明确重试或一次持续至少一分钟的实际连接才能重置；快速连接/崩溃不能刷新次数，同一旧连接历史也不能反复刷新。
- helper 成功仅代表已请求重绑，必须再确认真实连接，最多等待 6 秒。失败时 GUI 操作不执行，提示词禁止改走 Shell/坐标盲重放。旧 LSPosed 保护启用时不并发写入。

## 复核发现并修复的最后一个服务风险

Android 16 的 AOSP `AccessibilityManagerService.updateAccessibilityEnabledSettingLocked()` 会根据实际服务状态写入 `ACCESSIBILITY_ENABLED`。临时移除最后一个服务可能导致系统把总开关派生为关闭，随后无法可靠区分“系统派生关闭”和“用户主动关闭”。

因此，在移除前两次读取中发现 Eta 是最后一个服务时，**不修改设置、不反复重试**，页面提示到系统设置重新开启 Eta。重复的完整/缩写 Eta 组件不算另一个服务。不要求用户为此额外开启无关服务。

来源：[AOSP Android16 分支源码](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)。本次核对的本地快照为 `F:\eta\aosp-accessibility-manager-android16.java`；这是系统设计依据，不是 ColorOS 实测证据。

## 明确边界

- 只在应用进程存活期间恢复，不保证强行停止后复活或绕过 Android 后台限制。
- ROM 删除 Eta 配置与用户主动关闭服务不能可靠区分，均不自动开启。普通模式继续使用准确状态、设置入口与失败说明；不会虚报自动恢复。
- SettingsProvider 隐藏接口及 Root provider 的 ROM 兼容仍需 Android16/17、ColorOS16/17 设备确认。列表读回合并不等于与外部写入者构成全局原子事务；外部同时修改、其他服务失效等情况仍可使恢复停止。
- pending/开关位于 SharedPreferences，当前完整备份会包含这些值。恢复含 pending 的备份后可能要求用户在系统设置重新启用，这是防止不确定修改被盲目重放的阻断。本组没有修改备份格式或导出实现。
- P01.2 尚有跨任务挂起/恢复体验及长期设备稳定性验收，不能整项标为完成。UI 尚未做设备截图、大字体或 ColorOS 手感验收。

## 验证

最终 **20 套件、167 项测试通过，0 失败/错误/跳过**，以[最终验证 JSON](research/root-accessibility-recovery-verification-2026-11-06.json)为准。覆盖 API33/36/37 的 provider 边界、Root 缺失/撤销策略、租约、journal、总开关/最后服务保护、并发列表更新、失败还原、有限重试，以及既有健康页、LAN、导航与 GUI 错误传递回归。

Lint：**0 错误、422 警告、2 提示**，本组新增文件的四项写法建议均已消除。journal 的同步 commit 保留并检查返回值，失败不会放行 Root 修改或假装已清理。首轮 131 项是历史结果，不与最终 167 项重复累计。

日志：`F:\eta\root-accessibility-recovery-final-verification.log`。仅执行测试及 Lint 所需编译，没有 assemble/package/export APK，没有执行真实 su/app_process。

## 用户后续累计真机检查

1. 普通模式不申请 Root；Root 未授权时文字、正常无障碍等原功能继续可用，恢复开关默认关闭。
2. 已有 Root、无 LSP 时开启恢复，正常连接不出现频繁重启；断开但配置保留时观察状态与最多三次退避。
3. Eta 是唯一服务时确认显示手动恢复且不把服务临时关掉；多个服务时检查其他服务未被删除。
4. 恢复期间分别关闭恢复开关、关闭系统服务、撤回 Root；确认没有无限重试、盲点屏幕或不确定状态自动重放。
5. 强停/重启、熄屏、Doze、后台长测、用户切换及 Android16→17 OTA 分别记录；目前均未真机验收。


2026-11-07 接续：[P01.2 任务等待](P01_ACCESSIBILITY_TASK_WAIT_2026-11-07.md) 已补齐 GUI action 外的可取消累计等待、跨运行恢复干预 token、恢复后重新观察、聊天/通知/会议事件与重启结束语义。仍沿用此文独立 Root 恢复与退避，不依赖 LSPosed；目标设备长测继续保留。
