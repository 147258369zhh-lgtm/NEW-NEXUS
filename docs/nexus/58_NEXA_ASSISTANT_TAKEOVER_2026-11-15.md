# NEXA 默认数字助理与搜索手势接管（2026-11-15，1518实施与1519修复）

## 当前结论与证据边界

本批用户已明确授权：安装所需框架、重启使框架生效，将原搜索手势改为呼出 NEXA，并让 NEXA 接管默认数字助理；同时要求在软件内随时切回小布，切回后不能被更新或重启抢回。

1518 已构建、核验并覆盖安装。软件内明确选择 NEXA 的请求已消费，系统实际服务绑定为 NEXA；用户确认“成功了”，随后进一步明确电源键可以唤起 NEXA。导航条长按及双指开关已经配置，但未分别取得两种手势的独立验收结论，不能将电源键成功扩写成全部手势完成。

55号文档记录的“搜索仍转 Google、未安装框架、未通过命令设置默认角色”等结论属于此前1516阶段；57号文档记录的是框架准备阶段。后续实际授权和设备变更以本文本轮证据为准，历史记录不回写成当时已经执行。

## 1518 源码行为

- 导航条长按和双指识屏继续使用原有手势入口、已有开关与失败回退，成功触发后才接管原入口；目标已改为 NEXA。HyperOS 共用搜索触发也复用 NEXA 调用链。
- 不再安装 Google `ContextualSearchHooks`，本批 NEXA 手势入口不依赖 Google 圈选 Activity 或 `contextual_search` 系统服务。不删除或禁用系统原生服务。
- 优先请求当前 NEXA 系统助理会话；调用前核实活动服务确为 NEXA，避免设置与系统实现短暂不同步时唤起旧助手。
- 系统会话不可用时，显式启动既有 NEXA ASSIST 桥接 Activity。未建立系统 Session 的浮窗降级没有系统截图上下文，不宣称已经读取当前屏幕。
- 保持现有包名、VoiceInteractionService、ASSIST Activity 与数据标识稳定；没有新增替代手势、透明全屏触摸层或另做一套助理界面。

## 默认助手选择、退出和持久性

既有软件选择操作把目标与 `assistant_selection_request=userId:UUID` 同次提交。请求绑定发起选择的 Android 用户；同目标重新选择也可生成新请求。模块使用每用户持久消费记录区分明确选择和普通启动巡检。

显式选择 NEXA 或 Gemini 会执行一次对应系统助理角色配置，即使自动配置开关关闭。自动配置开关决定此后的启动、解锁和故障自愈；关闭后不因普通巡检覆盖用户在系统设置中另选的助手。

显式选择 OEM/小布时，电源键立即保留原厂处理路径，系统助理角色改回小布真实 ASSIST Activity。小布在本机没有标准 VoiceInteractionService，因此 `voice_interaction_service` 写入 **空字符串**，不写不存在的服务，也不以 `null` 留给系统推断。角色或最终绑定核验失败时，不记录为恢复成功，不用清空其他助手冒充完成。

模块自己的接管状态与用户手动设置分开记录。没有模块所有权时，默认 OEM 值不能在启动时清掉用户手选的 NEXA。较早异步角色请求完成时还会核对目标和请求号；过期请求先释放执行占用，再调度较新选择，避免旧 NEXA 回调覆盖刚选的小布。

本包 `PACKAGE_ADDED` / `PACKAGE_REPLACED` 的恢复监听核对包名及用户，只在 NEXA 目标、自动配置开启和已有 NEXA 接管记录同时满足时恢复绑定。不轮询，不监听卸载，不通过打开页面反复抢默认角色；已显式选回 OEM 或关闭自动配置的用户不会被本包更新重新接管。

## 真机标准角色的可逆性证据

设备为本轮 OPPO PHY110。标准系统角色命令已实测以下往返，不需要伪造 VIS，也没有使用角色资格绕过：

| 选择 | 实测角色及系统绑定 |
| --- | --- |
| NEXA | ASSISTANT 角色为 `io.github.zhh_lgtm.nexus`；`assistant` 和 `voice_interaction_service` 均指向真实 `io.github.mangi.eta.agent.voice.EtaVoiceInteractionService`；系统活动实现、Session 和 Recognition 绑定均已核实 |
| 小布 | ASSISTANT 角色为 `com.heytap.speechassist`；`assistant` 自动成为 `com.heytap.speechassist/.business.lockscreen.FloatSpeechActivity`；`voice_interaction_service` 为空；查询无标准 VoiceInteractionService |
| 再选 NEXA | 角色及两个 secure 绑定重新回到 NEXA，标准往返成功 |

这证明系统角色路径可逆；不代替1518软件选择页面提交、导航条长按或双指手势的实际验收。

## 框架安装及临时 Root 边界

经本轮明确授权，已安装并核实 **Zygisk Next 1.5.3 + Vector 2.2 / API 102**。通过 KernelSU 的 `ksud soft-reboot` 重启用户空间后，框架完成加载，Zygisk Next 报告 `inject_state=1`、`modules_with_issue=0`。

该次 soft reboot 前后 Root 均为 `uid=0`，内核 `boot_id` 保持一致：

```text
b8d78302-95f7-4402-99b1-88b8e76571b5
```

此前第一次执行的是整机重启，当时未预先确认 Root 的临时性质，重启后临时 Root 消失；用户随后恢复 Root。本机 bootloader 仍为 locked、verified boot 为 green。未解锁 bootloader，未刷写启动分区，也不为本批接管进行此类变更。

**本机后续不能再按普通整机 reboot 处理框架生效。** 当前已验证的是保留同一内核启动会话的 `ksud soft-reboot` 路径。整机重启会丢失临时 Root，是当前环境的硬边界；软件不能承诺跨整机重启永久保持 Hook 接管。若临时 Root 丢失，普通默认数字助理角色与依赖注入的全局手势也必须分开判断。

## 后续普通应用更新

NEXA applicationId `io.github.zhh_lgtm.nexus` 与原签名保持不变。后续正常覆盖更新应保留应用数据、稳定组件和已有框架设置；常驻 Hook 通过稳定组件调用新 APK，不依赖旧 APK 安装路径。

本包更新监听负责在已授权的自动接管范围内重新核验角色与服务绑定。Hook 自身代码变更仍需框架重新加载，本批未启用 `autoHotReload`，不能把普通 APK 覆盖安装等同于所有进程已运行新的 Hook。需要重载时使用已经验证的 soft reboot 路径，不再普通整机重启。

## 测试与打包记录

最小测试仅有一轮已完成记录：**34项通过，39秒**。

| 测试类 | 通过项数 |
| --- | ---: |
| `AssistantBindingTest` | 6 |
| `NexaAssistantGestureInvokerTest` | 8 |
| `EtaAssistantEntryTest` | 16 |
| `PowerAssistantTargetTest` | 4 |

覆盖显式选择与关闭自动配置、无所有权时不恢复 OEM、跨用户请求隔离，以及助理桥接和手势调用边界。其后又有两处小修和剩余 HyperOS 搜索切换；这些后续改动将由1518打包编译核验，**不声明34项测试已经在后续最终源码上全部重跑**。

1518 最终 `assemblePerformance` 成功（1分49秒）；原开发证书、v2签名、非debuggable、ARM64、ZIP16KiB对齐核验通过，ADB `install -r` 返回 Success。

## 1518 最终交付与验收

| 项目 | 最终证据 |
| --- | --- |
| 1518构建结果及日志 | 成功；桌面交付目录 `1518-package.log` |
| APK版本、绝对路径、大小与SHA-256 | `0.7.7-smooth / 2026111518`；`C:/Users/29136/Desktop/NEXA-Assistant-Takeover-20261115/NEXA-v0.7.7-2026111518.apk`；64,417,133字节；`0545DECEACD1589AA5A997809372AAE21DF572B40743918988D384AC5F894C6B` |
| 签名一致性及覆盖安装 | 原证书 `f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4`；install-r成功，未卸载或清数据 |
| 软件内 NEXA 目标及 `userId:UUID` 实际提交 | 已消费 `0:1b3e53ed-75c0-466d-a122-af21a3a0a346`；两个secure绑定为真实NEXA服务，系统Session已显示 |
| 电源键长按 | 用户确认唤起成功 |
| 导航条长按与双指识屏 | 两开关已开启，尚未分别独立验收 |
| 软件内切回小布、再次选择 NEXA | 标准系统角色命令往返已通过；软件内下拉往返未单独验收 |
| 框架作用域 | NEXA仅启用system/0、com.android.systemui/0、com.coloros.colordirectservice/0；未启用Breeno/Google作用域 |

真实设备没有 HyperOS，因此共用 HyperOS 路径的源码和编译核验不能记为 HyperOS 真机验收。任何未完成的操作继续保留“待补”，不从框架运行或标准角色命令成功推导完成。

## 框架控制通道的独立待查项

首次框架加载后API102、Vector2.2和控制CLI正常。再次执行 `ksud soft-reboot` 后，旧vectord仍存在，新服务脚本再次启动daemon；CLI报 `Socket Failure: Connection refused`。用户此后确认电源键唤起成功，且只读复核Root、同一boot_id、两个NEXA服务绑定仍正常。控制通道不可达不能被解释为整个助理没有运行。

现存截取日志缺fatal头，不能确诊崩溃进程或把Binder递归栈直接定为重复daemon根因。此项保留独立待查；1519压缩修复不重启、不替换框架、不再尝试整机重启。

## 1519：助理会话归属与首轮压缩修复

用户反馈电源键唤起后提示“上下文压缩失败，原始上下文已保留”，并询问对话保存位置。本轮只修改后台预算与压缩触发，不修改任何界面。

已核源码完整归属链：系统Session负责启动及提供屏幕上下文；`EtaAssistantOverlayService` 使用NEXA Runtime执行，`AgentRunArchiveStore`先保存结果和事件到本应用数据库；`AgentAppState.importArchivedExternalRuns`在应用加载/恢复时导入会话列表，助理记录ID使用 `assistant-` 前缀，标题来自首条用户请求。同一助理服务内连续对话共用conversationKey，服务重新建立后生成新key；不承诺每次按键必定新建。只读本地有效快照实际查询到3条助理会话、每条3条消息；诊断副本已删除，不保存正文或凭据。未修改手机数据库。

确认存在的故障：`ContextBudgetPolicy.estimateMessages`把`image_url`中的Base64或URL按文本估算；自动截图足以让首条短问题误达压缩阈值，而当前只有user消息，无历史可安全压缩。两个估算重载现改为结构化图片预算，每图沿用已有1,024 token估算；文字和非图片字段仍计入，实际图片请求、历史和记录不变。自动压缩在没有完整可切分历史时跳过，手动压缩及服务端溢出后的force路径继续返回真实错误，不隐藏错误或删除内容。

仅运行相关两类最小测试一轮：`ContextBudgetPolicyTest` 9项、`AgentContextCompactionTest` 19项，合计28项全部通过，17秒。新回归验证大Base64不误触发压缩、图片仍完整发送、多个图片及长文字预算、无历史时不虚假启动压缩、force路径仍如实失败；原有大历史和失败保留用例亦通过。尚未发送真机聊天或使用付费模型，不能将确定性的源码缺陷推定为用户该次运行唯一错误原因，也不宣称修复所有模型/网络/检查点压缩错误。

1519最终 `assemblePerformance` 成功（1分44秒）。APK `0.7.7-smooth / 2026111519`，64,416,865字节，SHA-256 `B74F2B30CE2C86EE48D42A4B37D89B6C7787D81DD5CFD16BE2261C3B3727D65B`，原开发签名、v2、非debuggable、ARM64和ZIP16KiB对齐核验通过。交付路径 `C:/Users/29136/Desktop/NEXA-Assistant-Takeover-20261115/NEXA-v0.7.7-2026111519.apk`。OPPO PHY110（1fe75f23）覆盖安装Success，系统确认1519、更新时间2026-11-15 23:15:02、首次安装时间保持2026-11-14 03:13:13；更新后VIS仍绑定NEXA。未重启手机，未触发语音识别或发送模型测试消息；设备端修复效果待实际使用验证。

当前后续边界：单张图片1,024 token是统一的本机估算，服务商实际usage优先。24K真实屏幕文字、未知窗口或服务商不返回usage时，回答后的final压缩仍可能遇到无缩减等真实失败；本轮没有扩大为所有压缩策略重写。框架控制端点异常、两种搜索手势独立验收、软件内小布往返继续保留待查，不通过本次APK安装推定完成。
