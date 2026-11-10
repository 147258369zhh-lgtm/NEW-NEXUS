# ColorOS 无障碍稳定性与 Release 适配修复（2026-11-08）

## 取证结论

`扫描文档20261108_113934-格式转换.docx` 记录了 OPPO Find X7 Ultra、ColorOS 16 / Android 16 的现场取证：

- ColorOS 在 `USER_UNLOCKED` 后约 1 秒执行风险无障碍检查，并直接改写 `enabled_accessibility_services`。
- 系统模块可见类名为 `com.android.server.oplus.OplusRiskAccessibilityController`。
- 本机 `pkgFlags` 含 `DEBUGGABLE`，且包名、签名与同机原包的组件身份不一致，属于系统风险判断的敏感特征。
- 厂商包级豁免键为 secure `accessibility_turn_off_skip_package`，原白名单不含 `fuck.andes`。
- 故障期间短时写入可被读回，但解锁后会被系统再次覆盖，因此只在出错后重绑会形成拉锯。

以上是现场证据；ColorOS 的具体私有判定算法不可由公开 API 保证。

## 修复

- `release` 构建显式设置 `isDebuggable = false`，不再依赖 AGP 默认值。
- 用户显式开启 Eta Root 无障碍恢复后，恢复事务会检查厂商是否提供 `accessibility_turn_off_skip_package`。
- 厂商键存在时，仅幂等追加当前包名 `fuck.andes`，保留已有所有包和分隔符语义，写后立即读回。
- 非 ColorOS 或厂商键不存在时不创建该键，返回 `UNSUPPORTED`。
- 写入失败时显示需要人工处理，避免在风险控制仍生效时虚报已稳定。
- 该事务不关闭无障碍总开关，不添加其他包，也不在恢复开关关闭后继续写入。
- Root helper 新增独立 `exempt` 操作；原有重绑仍由短期租约、UID/Root 校验、journal 和读回验证保护。

## Release 检查

- `:app:assembleRelease`：通过。
- R8 后二进制 Manifest 保留 `AgentAccessibilityService` 和 `AgentAccessibilityHealthProvider`。
- R8 seeds 保留 `AccessibilityRecoveryCommandMain.main(String[])` 与无障碍 Provider 构造器。
- Release Manifest 不含 `application-debuggable` 标记，即 `debuggable=false`。
- 当前仓库没有正式签名材料；未配置环境变量时生成的是未签名 release APK。正式发布必须提供 `ETA_RELEASE_STORE_FILE`、`ETA_RELEASE_STORE_PASSWORD`、`ETA_RELEASE_KEY_ALIAS`、`ETA_RELEASE_KEY_PASSWORD`。不得使用 debug key 冒充正式包。

## 真机验收

1. 安装使用正式签名且 `debuggable=false` 的 APK。
2. 授予 Root 后，在“权限健康”中开启 Eta Root 无障碍恢复。
3. 确认 `settings get secure accessibility_turn_off_skip_package` 包含 `fuck.andes`，其他条目保留且无重复。
4. 锁屏再解锁，等待 5 秒。
5. `dumpsys accessibility` 的 Bound services 仍包含 Eta，随后执行一次观察和点击。
6. 若 OEM 在系统更新后更换键名，非破坏性检测会返回不支持，Eta 不会再自行猜测或关闭系统保护。
