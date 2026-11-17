# DSH 手机端插件兼容性研究

日期：2026-11-17。本文仅为只读研究和后续决策依据，不包含接入实现，也不代表真机验收。

## 结论

DSH 插件的声明式界面可以通过协议由手机呈现，但插件的 Node/Cordis 服务端逻辑仍需在兼容的主机运行。手机原生能力需要单独实现并经过权限、生命周期和协议协商；不能将桌面插件包、Windows 原生依赖或 Electron 界面视为可直接在 Android 执行。对每个具体插件，必须分别核查其依赖、声明的能力、最低版本和实际运行环境。

## 上游与许可

- 本次上游固定参考 [dsh-Go revision 09b5980](https://github.com/ccch1mneyyy/dsh-Go/tree/09b5980a2951d5ab449cd83ff12ae63140f28b69)，与本项目先前审计的 `e0c96705f8efe92dafd07765ed1de5c1445a7fe4` 不同；不能假设两个版本完全一致，实施前需做协议差异核对。
- 上游 [根许可证](https://github.com/ccch1mneyyy/dsh-Go/blob/09b5980a2951d5ab449cd83ff12ae63140f28b69/LICENSE) 为 GNU AGPL v3；[文件示例许可证](https://github.com/ccch1mneyyy/dsh-Go/blob/09b5980a2951d5ab449cd83ff12ae63140f28b69/examples/plugins/dsh-go-example-files/LICENSE) 为 MIT。各插件及依赖必须分别核查，不能把示例许可推广到整个项目。复制、修改或分发上游代码前需进行具体许可审查；本文不作法律结论。
- 上游 [主机说明](https://github.com/ccch1mneyyy/dsh-Go/blob/09b5980a2951d5ab449cd83ff12ae63140f28b69/docs/plugins/hosts.md)、[插槽](https://github.com/ccch1mneyyy/dsh-Go/blob/09b5980a2951d5ab449cd83ff12ae63140f28b69/docs/plugins/slots.md)、[原生协议](https://github.com/ccch1mneyyy/dsh-Go/blob/09b5980a2951d5ab449cd83ff12ae63140f28b69/docs/design/native-plugins-protocol.md) 和 [视图协议](https://github.com/ccch1mneyyy/dsh-Go/blob/09b5980a2951d5ab449cd83ff12ae63140f28b69/docs/design/native-plugins-views.md) 是后续实现的核对依据。插件通过 `package.json` 中 `dshGo` 声明贡献；主机负责插件运行，客户端通过目录、视图、调用、资源和流协议交互。
- 上游手机应用包含 Flutter 插件视图实现及 Android 文件、媒体等原生模块，例如 [文件模块](https://github.com/ccch1mneyyy/dsh-Go/blob/09b5980a2951d5ab449cd83ff12ae63140f28b69/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/nativefiles/NativeFilesPlugin.kt)。这些模块证明上游有手机端实现，不证明其可直接移植或与 NEXA 完全一致。

## NEXA 现状和边界

- `app/src/main/kotlin/io/github/mangi/eta/agent/dsh/DshProtocol.kt` 独立实现协议解析，列出七类 UI 插槽、v1/v2 视图及文件、音频、语音、微通道和覆盖层能力。代码对目录数量、JSON 大小、嵌套和文件大小设置限制；能力声明不等于所有插件均能运行。
- `DshHostClient.kt` 和 `DshStreamClient.kt` 提供目录、视图、调用、文件传输和流的客户端路径；`DshUiSession.kt` 管理真实点击及会话；`DshPickedFileReader.kt`、`DshAndroidMediaBackend.kt` 和 `DshMediaController.kt` 包含手机文件与媒体处理路径。文件限制包括单文件 8 MiB、总计 16 MiB，具体还受协议和上下文约束。
- `DshConversationFeeds.kt` 等提供插槽订阅路径，现有测试目录覆盖协议、表单、文件、流、媒体和呈现。测试文件存在不代表本次已运行、对所有插件兼容或完成设备验收。
- 现有 [能力中心审计](62_CAPABILITY_CENTER_AUDIT_2026-11-16.md) 记载模型仅有 catalog/view 只读路径，真实用户点击不能伪造；App 版本按实际版本协商，不能冒充上游版本。桌面主机的插件共享宿主进程，上游协议不构成恶意插件隔离。Remote relay 与 SSH 的可用路由应按上游主机文档和实际部署验证。

## 插件分类

| 类别 | 可行路径 | 主要限制 |
| --- | --- | --- |
| 声明式面板、操作和数据 | 主机运行插件，手机通过协议呈现并发送用户动作 | 需核对组件、插槽、效果、版本与作用域；不支持的部分应明确拒绝 |
| 文件、音频、语音及覆盖层 | 主机发起协议请求，手机使用本地系统能力 | 需能力协商、真实手势、授权、有限大小和生命周期管理；不代表上游所有功能已实现 |
| 依赖 Node/Cordis、主机文件或网络的插件 | 在兼容主机运行，手机通过受认证的桥接访问 | 主机需要在线并承担插件信任风险；手机本地不能仅凭 manifest 执行 |
| 依赖 Windows DLL、Electron 或桌面专有 API 的插件 | 保留桌面主机，或由插件作者提供独立 Android 实现 | 不能保证跨平台；Web 页面亦需单独评估安全和交互 |

## 建议顺序与验收

1. 固定目标上游版本，比较协议、组件、效果、最低版本及插件清单差异，形成逐插件兼容矩阵；不以修改版本号绕过门槛。
2. 先用真实主机和官方示例验证目录、面板、七类插槽、调用及断线重连，逐项记录不支持的声明与清晰错误。验证 LAN 或 SSH 实际认证和网络路径，不假定 relay 支持桥接。
3. 分别验证文件选择、取消、超限、令牌过期和流断开；再验证麦克风授权、撤销、前后台切换、音频中断和资源清理。确保插件无法伪造用户手势或扩大授权范围。
4. 每个目标插件检查依赖与许可证，在 Android 真机、真实主机及目标网络上完成端到端验证，包括插件重启、会话切换、进程回收和异常恢复。记录设备、版本、步骤和结果，再声明对应范围的兼容性。

本次未运行 Gradle、未部署插件、未执行端到端测试，也未验证 Android 或 ColorOS 真机行为。
