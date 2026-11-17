# 系统 Hook 框架兼容准备（2026-11-15）

## 结论与本次边界

已完成官方候选、真实发布文件、依赖、摘要和恢复路线的准备。当前可比较的方案是 **Vector 2.2 + NeoZygisk 2.4**，或 **Vector 2.2 + Zygisk Next 1.5.3**。Vector 2.2 明确提供 NEXA 所需的现代 libxposed API 102；原 LSPosed 1.9.2 的官方支持范围止于 Android 14，不作为本机方案。

后续只读补查取得 **KernelSU kernel code=35113、`su -V=35113`**。已核数值高于两种注入层的最低版本码要求，但 NeoZygisk 会命中 kernel code≥30000 的变体兼容警告；Zygisk Next 的当前安装脚本没有这一上限警告。数值条件通过不代表恢复准备、管理器匹配、SELinux检查或本机运行兼容已通过，本文不据此选择试装。

**当前 OPPO 厂商 Android 17 正式版 / API 37 未经框架兼容验收，不能标记为“已兼容”。** Vector README 的范围写到 Android 17 Beta，发布 ZIP 的 module.prop 写 Android 8.1～17，两者不是这台 ColorOS 构建的实机证明。NeoZygisk 2.4、Zygisk Next 1.5.3 各有 Android 17 的明确发布记录，但它们只解决注入层，不证明 Vector、NEXA Hook 与系统搜索整条链路成功。

本次只读核实和电脑端下载：没有安装 ZIP/APK、执行 ZIP 脚本、推送文件到手机、修改模块/权限/属性/默认助理、重启或刷写。NEXA 的 minApiVersion 未改动。后续系统变更必须等待用户最终明确确认，且应先补齐下文的兼容与恢复前提。

## 手机精确基线

以下为 2026-11-15 本轮只读查询，设备序列号 `1fe75f23`。模块目录/Root 上下文由本轮主任务已取得的只读证据提供；其余列为本准备步骤直接复核。

| 项目 | 证据 / 状态 |
| --- | --- |
| 设备 | OPPO `PHY110` |
| 系统 | Android `17`，API `37` |
| 显示构建 | `PHY110_17.0.0.100(CN01)` |
| 指纹 | `OPPO/PHY110/OP565FL1:17/UKQ1.231108.001/U.42316b4-1b7cbd4-1b8afa3:user/release-keys` |
| ABI / 页大小 | `arm64-v8a` / `4096` 字节 |
| 内核 | `6.1.157-android14-11-o-g78ff3c4bea38` |
| Root | 主任务已证实 `uid=0`、SELinux 上下文为 KernelSU `ksu` |
| `su --version` | `4.2.0-rc1-52-gaa7c82a7:KernelSU` |
| Root 下 `ksud --version` | `ksud 4.2.0-rc1-52-gaa7c82a7 (uapi: 2)` |
| `su -V` 用户空间版本码 | 设备只读返回 `35113`；这是运行中 `su` 的构建版本码，不是管理器 APK 版本码 |
| 安装器 `KSU_KERNEL_VER_CODE` | 主任务补查 `/data/adb/ksud debug version` → `Kernel Version: 35113`，退出码0；源码确认与安装器使用同一内核查询函数 |
| 安装器 `KSU_VER_CODE` 的版本证据 | `su -V=35113`；对应源码与 ksud 安装器共用 `defs::VERSION_CODE`，且此前 su/ksud 的版本名均为 `4.2.0-rc1-52-gaa7c82a7`。没有运行安装器来回显环境，也没有把管理器 APK 版本码当作此值 |
| KernelSU 管理器 | 包 `me.weishu.kernelsu`，`versionName=v3.3.1`，`versionCode=32680`，targetSdk 37 |
| 原有模块 | 主任务检查 `/data/adb/modules` 仅有 `opmask`；没有 `/data/adb/lspd` |
| Zygisk 层 | 没有取得已安装/运行的 Zygisk 注入层证据；Root 本身不等于 Zygisk |
| 系统搜索服务 | `service check contextual_search` → `Service contextual_search: not found`；`cmd contextual_search help` → `Can't find service: contextual_search` |
| Google App | `com.google.android.googlequicksearchbox` 存在，`pm path` 返回 APK 与分包 |
| 当前默认助理 | `com.openai.chatgpt/com.openai.feature.assistant.impl.AssistantVoiceInteractionService` |

当前 ksud/su 是 4.2.0-rc1 开发构建，管理器是 v3.3.1，不能从管理器 versionCode 32680 直接推导安装脚本读取的 `KSU_KERNEL_VER_CODE` 或 `KSU_VER_CODE`。初次准备后段设备从 ADB 离线，没有为补查而重连、重启或修改系统。随后主任务确认设备重新在线，本准备步骤进行一轮 help/version/getprop 读取取得 `su -V=35113`；主任务再以正确单行 Root 命令取得 kernel code=35113。本准备步骤未再查询手机。管理器/守护进程完整匹配状态仍未经验证。

### KernelSU 安装变量来源补查

先读取候选 ZIP 的 `customize.sh`，两种注入层都不自行计算 KSU 版本码，而是读取 Root 安装器提供的 `KSU_KERNEL_VER_CODE` 与 `KSU_VER_CODE`。再按设备版本字符串中的提交 `aa7c82a7` 查询 KernelSU 官方仓库，解析到完整提交 `aa7c82a7f5b5f8693118854383d88d522bdcd0f1`，核实：

- `userspace/ksud/src/android/module/mod.rs:68–71` 设置 `KSU_KERNEL_VER_CODE=ksucalls::get_version()`、`KSU_VER_CODE=defs::VERSION_CODE`，另行设置 `KSU_UAPI_VER`。版本码与 UAPI 版本是不同数据。
- 同提交 `userspace/ksud/src/android/su.rs:219–226` 的 `su --version` 打印 `defs::VERSION_NAME`，`su -V` 打印 `defs::VERSION_CODE`；`src/defs.rs:69–75` 则说明 ksud 的版本号与版本码来自构建生成的不同文件。
- 同提交 `userspace/ksud/src/android/cli.rs:789–791` 的公开只读入口 `ksud debug version` 直接打印 `ksucalls::get_version()`。本准备步骤的批量设备查询只有首条 `ksud --help` 处于 Root 命令上下文，后续 ksud 命令因普通 shell 无法访问而失败；这不表示设备没有 ksud。随后主任务以单行 `adb -s 1fe75f23 shell su -c '/data/adb/ksud debug version'` 重新读取，返回 `Kernel Version: 35113`、退出码0，补齐内核精确版本码。

这证明设备自报的 `gaa7c82a7` 版本标记可对应官方仓库的源码、安装变量的具体读取关系，以及设备内核实际报告的版本码；**不证明设备内核二进制未经修改、来自何种发行 ZIP，或旧管理器与当前守护进程完全匹配**。内核35113来自内核查询，用户空间35113来自 `su -V`，两者独立取得；管理器 APK 的32680不是上述两个值。

就最低版本码条件而言，实测 kernel `35113≥10940`，用户空间 `35113≥11425` 且 `35113≥11575`，因此现有版本证据满足 NeoZygisk 与 Zygisk Next 的数值下限。**NeoZygisk 2.4 会命中 kernel code≥30000 的警告**：脚本提示 KernelSU变体支持可能不完整，该分支仅警告而不直接abort，不能把通过最低值检查解释成没有兼容风险。**Zygisk Next 1.5.3 的脚本没有该30000上限分支**，其数值门槛可通过；这也不证明当前Root变体、SELinux补丁、注入或Vector已经可用。两种组合仍缺管理器/守护进程匹配、恢复能力和本厂商 Android 17 的运行验收，本轮不选择未知试装。

内核名中的 `android14` 不能把系统误判为 Android 14。KernelSU 官方安装文档解释内核 Android 分支与当前用户空间 Android 版本不必相同；若未来涉及恢复镜像，必须以本机原固件、分区布局和 KMI 为准，不能因为系统是 Android 17 就套用其他 Android 17 设备镜像。本方案不需要为了装 Hook 框架先换内核。

## NEXA 必须满足的框架条件

源码 `app/src/main/resources/META-INF/xposed/module.prop` 当前声明：

```properties
minApiVersion=102
targetApiVersion=102
staticScope=true
exceptionMode=protective
```

`ModuleMain` 使用现代 `io.github.libxposed.api.XposedModule`、`onModuleLoaded`、`onSystemServerStarting` 和 `onPackageReady`。只有旧 XposedBridge API 或现代 API 101 的框架不满足此模块要求；不能将 minApiVersion 降低来使管理器看起来可勾选。

`scope.list` 静态范围包括 `system`、`com.android.systemui`、Google、ColorDirect、Breeno、ColorOS 记忆及小米相关进程。这意味着作用域候选来自模块声明，不能假设用户可以在框架端自由缩窄静态范围。后续如需缩小范围，属于单独的源码与行为决定。

Vector 2.2 发布说明明确“brings libxposed API 102”。其 v2.2 的 `xposed/libxposed` 子模块指向 `libxposed/api` 的 `39cac0845771547c9c67a3e3ce255af110a54a0e`，该提交 `XposedInterface` 含 `API_102=102`、`LIB_API=API_102`，因此有 API 102 的发布与源码双重证据。仍须将它与“本机运行验收”分开。

## 官方候选逐项核实

核实时间：2026-11-15。使用各项目自己的 GitHub Releases 与 README，没有下载论坛转包、其他人的 PR 构建或非官方修补框架。`JingMatrix/LSPosed` 的最新发布地址重定向至 `JingMatrix/Vector`，因此当前项目名称和发布件按 Vector 记录。

| 候选 | 官方最新发布 / 明确声明 | 依赖和本机限制 | 准备结论 |
| --- | --- | --- | --- |
| **Vector 2.2** | 2026-08-04，build 3080；发布说明明确 API 102；README 写 Android 8.1～17 Beta；ZIP 描述写到 17 | 需要 Magisk/KernelSU 和独立 Zygisk 环境；含 ARM64 注入库。没有本 PHY110/API37/ColorOS 构建验收证据 | 两种组合共同的候选框架，暂不能声明实机兼容 |
| **NeoZygisk 2.4** | 2026-08-08，build 289；说明明确新增 Android 17，并修复 Android17/QPR2 specialize 签名及预览版符号差异；Vector README 推荐链接 | ZIP 要求 kernel code ≥10940、ksud code ≥11425；实测kernel35113与用户空间35113均高于下限，但kernel≥30000命中“变体支持可能不完整”警告；4.2rc/uapi2组合未验收 | 组合 A：最低数值条件满足，但存在脚本明确警告；不能宣称本机兼容 |
| **Zygisk Next 1.5.3** | 2026-11-07，build 880；明确新增 Android 17 QPR3、16KB 页兼容和死锁修复 | README/ZIP 要求 kernel code ≥10940、ksud code ≥11575；实测kernel35113与用户空间35113均高于下限，脚本无30000上限分支；不得并存多个Root实现；当前手机页是4KB | 组合 B：最低数值条件满足；仍须验证管理器匹配、SELinux、注入层、Vector和厂商正式版兼容 |
| **原 LSPosed 1.9.2** | 2023-10-11，build 7024；README 支持 Android 8.1～14 | 官方最新发布没有 API102/Android17 支持证明；Riru 也不是本机新方案 | 不采用，不下载到候选包目录 |
| **libxposed API 102.0.0** | 2026-06-14，API库；README `compileOnly(io.github.libxposed:api:102.0.0)` | AAR 是开发 API，单独安装它不会得到运行时框架 | 仅作为 API 要求证据，无需装到手机 |

KernelSU 官方明确**没有内置 Zygisk**；Vector 自己作为 Zygisk 模块，也不包含可替代的完整注入提供者。不能只装 Vector 就假设链路齐全，也不需要因为已有 KSU 再安装 Magisk。

### ZIP 阅读发现，影响安装与回滚

- NeoZygisk 与 Zygisk Next 的 `module.prop` **同为 `id=zygisksu`**。它们是互斥的备选提供者，不能同时安装。相同模块 ID 不表示配置、服务文件或版本能直接兼容；不可把其中一个当作另一个的普通升级包。
- NeoZygisk 2.4 声明 `managedFeatures=kernel_umount`，发布说明明确改变 KernelSU 卸载挂载处理。这会影响模块可见性，不能为了排障随意批量调整原有应用配置或 `opmask`。
- Vector 2.2 的模块 ID 是 `zygisk_vector`，含 `manager.apk`、`daemon.apk`、框架 dex 与 ARM64 库。管理器已在 ZIP 内，不需要从其他 APK 站下载同名管理器。
- Vector 安装脚本会禁用旧 `/data/adb/modules/zygisk_lsposed`；本机此前未发现该目录。其卸载脚本会删除 `/data/adb/lspd`，所以未来升级/回滚前应保存框架配置和日志，不能假设卸载一定保留设置。
- Vector 2.2 发布说明指出：若单独安装过 2.1 管理器 APK，需在更新前移除旧管理器，因为 2.1 管理器不能与 2.2 daemon 通信；寄生管理器无需该步骤。本机没有旧版管理器证据，不据此主动卸载任何应用。
- Zygisk Next ZIP 还包含安装到 `/data/adb/service.d` 的清理脚本以及 KernelSU `znctl` 链接；正常卸载应交给 Root 管理器处理，不能只删模块主目录来冒充清理完成。
- 三个候选 ZIP 均无 `system/` 目录；脚本/SELinux/注入功能不应仅因“最新版 KernelSU 有元模块”就再顺带装一个挂载插件。官方说明元模块只在需要修改系统目录挂载时才需要；但本机 KSU 版本和包组合仍应在后续确认中具体核对。

## 电脑端备妥文件与校验

目录：`C:/Users/29136/Desktop/NEXA-System-Tools-20261115/framework-preparation`。

三件 ZIP 全部从对应官方 GitHub Release 资源 URL 下载；本地文件大小和 SHA-256 均与 GitHub REST 资源的 `digest` 相符。ZIP 内可配对的 `.sha256` 也逐项只读核对：Vector 27 项、NeoZygisk 23 项、Zygisk Next 42 项，均无不一致。这证明文件与本次官方资源记录一致，不等于本机兼容或独立安全审计。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `Vector-v2.2-3080-Release.zip` | 9,316,843 | `9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc` |
| `NeoZygisk-v2.4-289-release.zip` | 2,401,208 | `93a1425c67bb89f58a0dcb9fc823ec93f6472214f221e667a70b67c6a6e061a1` |
| `Zygisk-Next-1.5.3-880-22a1bf5-release.zip` | 6,638,473 | `186a0a26c9fcb895af1954b42de831433c23d72d1bd0758b986069154359db67` |

同目录含 `artifact-manifest.json`、每件 ZIP 的 `.sha256`、本文副本及 `official-evidence/`（官方 README、最新 release REST、v2.2安装脚本与模块属性、KernelSU恢复说明、libxposed API源码证据）。只读取 ZIP 元数据与文字脚本，没有运行其中代码。

Zygisk Next 官方当前许可证禁止再分发，因此该件只保存在用户本机供本人检查使用，不打包进 NEXA APK、不上传仓库、不作为 NEXA 交付压缩包中的组件。

## 框架以外仍缺失的搜索条件

当前系统**没有 `contextual_search` Binder 服务**，这是框架兼容之外的独立阻碍。Google 包“已安装”、默认助理“可选择”、NEXA“Root已授权”都不能替代这个服务。

原有源码中，导航条长按和双指长按分别由 SystemUI/ColorDirect Hook 转发 Google 原生圈选搜索。`CircleToSearchInvoker.isAvailable` 同时检查 Google 包、实际 action `android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH` 能否解析、以及 `contextual_search` Binder 是否存活；trigger 还依赖相应隐藏 AIDL 方法签名。

NEXA 的 `ContextualSearchHooks` 有系统服务补启动尝试，但要求厂商 ROM 确实带有 `ContextualSearchManagerService` 类、可用的 `SystemServiceManager.startService(Class)` 和匹配的方法/权限入口。Hook 不能凭空提供 ROM 中不存在的系统服务实现。如果该类被厂商移除、初始化失败、Google 入口未暴露或资格条件不满足，装完框架仍不能保证原生圈选搜索可用。本次没有触发系统搜索或测试手势，也未验证 Google 对上述正确 action 的解析结果。

当前默认助理是 ChatGPT，应作为本轮基线保存；本文不把“恢复 NEXA 默认助理”混入框架安装，也不更改用户系统选择。默认助理入口与原生圈选搜索是两条不同链路。

### 1517安装后通信问题的只读复核

用户询问数字助理与搜索手势仍不可用是否源于框架通信。本轮重新读取设备：Root仍为`uid=0`/KernelSU；模块目录仅`opmask`，无`/data/adb/lspd`、待安装模块目录或可见的Vector/LSPosed守护进程；安装包只有KernelSU管理器与LSPatch相关项。没有已安装系统Hook框架的证据，不能将当前状态诊断为已装框架的管理器/daemon版本不匹配。LSPatch不提供本功能所需的系统进程接管。

`assistant`、`voice_interaction_service`及`dumpsys voiceinteraction`当前均指向ChatGPT。NEXA的标准默认助理角色入口本身不依赖框架，系统支持的标准助理操作需先由用户选中NEXA；OPPO厂商电源键接管仍依赖PowerHooks在系统进程生效。

本轮补齐先前未验的Google入口：正确action `android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH`可解析到Google的`ContextualSearchServiceEntrypoint`；但`service check contextual_search`仍为not found。因此Google入口已存在，缺少系统服务仍是独立阻碍。原导航长按/双指长按源码目标为Google原生圈选，不是NEXA浮窗；不能将两者视为相同能力，也不能无授权切换手势目标。

静态复核另发现安装后需处理的风险：`AssistantBinding.assistantSelectionAction`对OEM目标无条件选择恢复，`AssistantManager`可能清除用户手工选定的NEXA/Gemini默认项，即使自动配置关闭。该逻辑尚未修改，当前框架未加载时它不解释手机的ChatGPT默认状态；后续接管实施应区分模块自身设置与用户手工选择，避免覆盖用户选择。

此次没有修改产品界面、默认助理、系统模块或权限，没有安装、重启、触发语音或搜索。原有框架安装与重启须另行确认的边界保持不变。

## 待最终确认后才可执行的分阶段方案

以下是准备方案，**尚未执行**，也不是新的安装授权。

1. **先确认恢复能力和精确 KSU 前提。** 保存手机当前固件构建、Root 来源/版本、内核 versionCode、ksud versionCode、现有模块与 NEXA 数据备份。确认可用的 OPPO 启动/Recovery 路线、电脑 ADB 授权和同一固件的原厂恢复镜像；仅有通用“fastboot”教程不足以证明本机有可用救援通道。不要为准备备份就刷写其他设备镜像。
2. **选一个注入提供者。** 组合 A 与 B 只能选其一。最低版本码数值条件已核，仍须确认当前 KSU 4.2rc、uapi2、管理器3.3.1的对应关系，并具体评估NeoZygisk对kernel35113的变体警告。不得通过修改安装脚本、放宽SELinux或降低NEXA API要求绕过检查；遇到门槛失败就停在该阶段。
3. **仅安装选定注入层并单独确认。** 经用户确认后通过现有 Root 管理器装官方 ZIP，用户确认重启时机。先看它是否正常注入及系统是否稳定，再决定装 Vector。不要同次顺带升级Root、换内核、改opmask、装隐藏插件或更换挂载架构。
4. **再单独装 Vector 2.2。** 核实实际 libxposed API≥102、daemon与管理器版本一致、system_server注入无异常；NEXA开启前先保存干净框架日志。Vector官方最新Debug是提交缺陷报告的要求，不因目前只有Release候选就声称已完成上游诊断。
5. **最后启用NEXA并验收原功能。** 核实模块真实加载、remote preferences连接、SystemUI/ColorDirect具体Hook结果、系统服务是否出现、Google正确搜索入口解析，再由用户验证两种原手势。每层失败都记录具体原因；停止把失败继续归因于“没换源/没装框架”。

即使用户未来批准试装，仍应在这五层中逐段验证，不能以“ZIP安装成功”“管理器显示启用”或“进程里看到模块”代替搜索验收。

## 恢复、回滚与后续升级

### 正常进入系统时

优先在框架管理器关闭NEXA模块，保存日志；系统Hook失效通常需要进程重启或重启手机，实际操作仍需用户确认。如果框架本身造成问题，先在KernelSU管理器禁用 `zygisk_vector`，再按需禁用选定的 `zygisksu`。保留原 `opmask`，不做整目录删除。恢复稳定后才考虑正常卸载，并注意Vector卸载清理框架数据的行为。

### 启动循环时

KernelSU官方记录：第一启动画面后、启动动画前快速**按下并松开音量下键超过三次**可尝试内核安全模式；不是一直按住。成功后模块全部禁用，可在管理器卸载问题模块。这依赖当前内核实际集成和时序，未在本OPPO上演练，不作保证。

若ADB仍可连接且Root可用，官方提供 `ksud module disable <id>` / `ksud module uninstall <id>` 的救援机制。后续只能先确认当前ksud帮助和模块ID，再针对 `zygisk_vector`、`zygisksu` 操作；本次未执行任何禁用、卸载或重启命令。新版官方Recovery清理方案还依赖可解密的data/metadata和可用Recovery，本机这些能力未确认，因此不能将其描述为已经备妥的兜底。

内核或启动分区问题才涉及同一固件的原厂 `boot/init_boot` 恢复，分区选择取决于本机布局。不要盲目刷写、格式化data、删除ksud或自动套用一组“救砖命令”；这些是独立高影响操作，必须在精确设备证据和授权下决定。

### 升级纪律

- 保留每个已验证版本的官方ZIP、摘要、配置备份和工作基线；一次只变动注入层、框架、NEXA或Root中的一层。
- NeoZygisk与ZygiskNext切换前，先保存各自配置并按各自正常卸载/重启要求处理；相同ID会覆盖模块，不能直接混装来比较效果。
- 每次ColorOS/Android/ART更新后重新核实Vector与注入层官方说明；Android17支持不自动覆盖所有QPR/厂商ART。若升级后未有支持证据，维持“待验收”，不自动更新并启用系统Hook。
- 不跟随PR未知构建，不为绕过新系统不兼容而降NEXA minApiVersion。框架API102热重载能力也不等于NEXA已选择热重载，当前模块源码明确未启用该路径。

## 官方来源

- Vector：[最新发布](https://github.com/JingMatrix/Vector/releases/tag/v2.2)、[v2.2 README](https://github.com/JingMatrix/Vector/blob/v2.2/README.md)、[v2.2安装脚本](https://github.com/JingMatrix/Vector/blob/v2.2/zygisk/module/customize.sh)、[模块属性](https://github.com/JingMatrix/Vector/blob/v2.2/zygisk/module/module.prop)、[libxposed子模块](https://github.com/JingMatrix/Vector/tree/v2.2/xposed/libxposed)。
- NeoZygisk：[v2.4发布](https://github.com/JingMatrix/NeoZygisk/releases/tag/v2.4)、[README](https://github.com/JingMatrix/NeoZygisk#readme)。精确安装门槛以下载ZIP内 `customize.sh` 为本次审阅证据。
- Zygisk Next：[v1.5.3发布](https://github.com/Dr-TSNG/ZygiskNext/releases/tag/v1.5.3)、[README及要求](https://github.com/Dr-TSNG/ZygiskNext#requirements)。
- 原 LSPosed：[v1.9.2发布](https://github.com/LSPosed/LSPosed/releases/tag/v1.9.2)、[官方支持版本](https://github.com/LSPosed/LSPosed#supported-versions)。
- libxposed：[102.0.0发布](https://github.com/libxposed/api/releases/tag/102.0.0)、[Vector所用API提交](https://github.com/libxposed/api/blob/39cac0845771547c9c67a3e3ce255af110a54a0e/api/src/main/java/io/github/libxposed/api/XposedInterface.java)。
- KernelSU：[模块指南](https://kernelsu.org/guide/module.html)、[FAQ](https://kernelsu.org/guide/faq.html)、[启动循环救援](https://kernelsu.org/guide/rescue-from-bootloop.html)、[安装/KMI说明](https://kernelsu.org/guide/installation.html)、[元模块边界](https://kernelsu.org/guide/metamodule.html)。本次也保存了相应官方仓库Markdown快照。
- KernelSU 设备版本标记对应源码：[完整提交](https://github.com/tiann/KernelSU/commit/aa7c82a7f5b5f8693118854383d88d522bdcd0f1)、[安装环境变量](https://github.com/tiann/KernelSU/blob/aa7c82a7f5b5f8693118854383d88d522bdcd0f1/userspace/ksud/src/android/module/mod.rs#L64-L72)、[`su -V`实现](https://github.com/tiann/KernelSU/blob/aa7c82a7f5b5f8693118854383d88d522bdcd0f1/userspace/ksud/src/android/su.rs#L219-L226)、[`ksud debug version`实现](https://github.com/tiann/KernelSU/blob/aa7c82a7f5b5f8693118854383d88d522bdcd0f1/userspace/ksud/src/android/cli.rs#L789-L791)、[版本常量](https://github.com/tiann/KernelSU/blob/aa7c82a7f5b5f8693118854383d88d522bdcd0f1/userspace/ksud/src/defs.rs#L69-L75)。仅源码读取，没有执行安装器。

准备阶段完成：**下载和方案可供用户审阅；手机系统状态未改动；安装、重启以及手势验收均待用户最终确认。**
