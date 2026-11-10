# Nexus 阶段 0 验收清单

## 已完成

- [x] 新仓库目标：`https://github.com/147258369zhh-lgtm/NEW-NEXUS.git`。
- [x] 新工程目录：`D:\AI\NexusAgent`，旧 ETA 保持原样。
- [x] applicationId 切换为 `io.github.zhh_lgtm.nexus`，与旧 Eta 可并存。
- [x] 根工程名、版本和用户可见应用名切换为 Nexus。
- [x] 独立自定义权限 authority、无障碍健康 Provider authority 与 OAuth scheme。
- [x] APK 输出名切换为 `Nexus-v...apk`。
- [x] 新增纯 Kotlin `kernel-api`，禁止 Android、Compose、旧 App 和 Miuix import。
- [x] 冻结 Session、Run、Turn、Item、Task、Goal、Plan、Capability、Settings 和 Migration 契约。
- [x] 冻结入口映射、数据库所有权和进程/服务边界。
- [x] 草拟 Nexus Migration v1 JSON Schema。
- [x] 生成逐文件 import 基线审计。
- [x] 旧 Eta 与 Nexus 并存安装、SAF 迁移、不读取旧私有目录的决定。

## 本轮代码门禁

目标命令：

```powershell
cmd.exe /c gradlew.bat checkNexusArchitecture :kernel-api:testDebugUnitTest :app:compileDebugKotlin
```

通过标准：

- 架构检查无禁止依赖。
- Kernel 契约单测通过。
- App Kotlin 编译通过。
- Gradle configuration cache 可复用，无脚本对象序列化失败。

本轮实测结果：

```text
BUILD SUCCESSFUL
24 actionable tasks: 1 executed, 23 up-to-date
Configuration cache entry stored.
```

Release 门禁：

```text
cmd.exe /c gradlew.bat :app:assembleRelease
BUILD SUCCESSFUL in 10m 36s
72 actionable tasks: 72 executed
```

产物元数据已确认：

- `applicationId=io.github.zhh_lgtm.nexus`
- `versionCode=1`
- `versionName=0.1.0`
- `outputFile=Nexus-v0.1.0.apk`
- `minSdk=33`、`targetSdk=37`

该 APK 仅用于第一阶段构建验证，没有安装到真机，也未声明为稳定发布包。

## 明确留给阶段 1

- 接入影子 `ExecutionRecorder`，旁路记录普通聊天、会议、Subagent、A2A 和定时任务。
- 建立 Item 投影和只读 Timeline Reader。
- 映射现有 Checkpoint、Metrics、conversation ID 与 Artifact。
- 对现有工具补齐 EffectClass、ReplayPolicy 和 verifier 清单。
- 用真实任务对照新账本与旧 Metrics/Checkpoint，观察期通过后才切换恢复依据。

## 不在阶段 0 伪造的完成项

- 旧 Eta 一键迁移 UI 与加密导入尚未实现。
- UI 大改、目标/计划模式、记事本、双 UI Profile、DSH、Linux Host 和语音重构尚未实现。
- 当前代码仍保留旧 Kotlin namespace、旧仓库内部类名和部分兼容数据文件名；这些是迁移期实现细节，不影响新 applicationId 并存安装。

