# Nexus 阶段 7/8：语音、本地模型、迁移与发布

## 已有后端

- 本地/云端 ASR、TTS、播放服务、音频焦点、打断和语音 Overlay 已存在。
- 本地 GGUF、LiteRT-LM、MNN 和语音模型生命周期已有实现。
- 完整备份、选择性备份、密码加密、staging 恢复、回滚和分层归档已有实现。

## 本轮补充

- 新增 `VoiceStateMachine`，明确 IDLE、LISTENING、TRANSCRIBING、THINKING、SPEAKING、INTERRUPTED 和 FAILED 状态。
- 新增 AudioOwner，语音打断时播放所有权明确转给用户。
- 新增 WorkloadType 和 ResourcePriorityPolicy，固定“前台对话 > 用户任务 > 夜间整理 > 插件预热 > Linux 空闲服务”的资源顺序。
- 高压或省电模式下，夜间整理、插件预热和空闲 Linux 服务自动禁止继续。
- 新增 Nexus Migration 校验器：拒绝错误 schema、无加密凭据、空包、重复路径和超大包。
- LocalVoiceSessionCoordinator 已把听、转写、思考、朗读、打断和结束同步到 VoiceStateMachine；Overlay 状态同时暴露 canonical snapshot。
- 迁移与备份页增加旧 Eta 安全迁移向导，复用加密导出、SAF 校验、staging 恢复和重启。
- 现有 Migration v1 JSON Schema、完整备份和恢复实现继续作为存储后端。
- 2026-11-10 最终源码已通过 `:app:assembleRelease`，产物为 `Nexus-v0.1.0.apk`，applicationId 为 `io.github.zhh_lgtm.nexus`，SHA-256 为 `701FFE1D2E380689D185008EB3B6396C8E1B2573F22C0A5BDE4B1C6CDA605825`。
- 该 Release 构建没有配置 release signingConfig；`apksigner verify` 返回 `DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`，因此只能作为未签名构建验证产物，不能作为正式发布包。

## 尚未完成

- 真机全双工语音、长期音频焦点冲突和不同 ROM 后台限制尚未验收。
- 运行中的 Goal 自动恢复尚未切换到 Execution Ledger。
- 旧 Eta 迁移向导已接入现有预览与恢复链路；跨设备 Keystore 重包裹仍需真机换机验收。
- 正式发布证书、真机后台/重启/Doze 和签名发布包尚未执行。

