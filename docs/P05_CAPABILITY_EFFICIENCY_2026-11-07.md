# P05 能力与执行效率

本批次加入统一的 `AgentCapabilitySnapshot`，由 Root、无障碍、通知、日历、ColorOS 等运行条件生成稳定、脱敏的 JSON 快照。快照只描述当前轮可用能力，不会触发授权，也不会让远端文本或 MCP 输出扩大本地权限。

`AgentObservationPlanner` 为动作后的观察提供缓存、结构化节点优先和截图兜底策略，并在连续两次无变化时标记循环，减少重复截图和无效工具调用。

验证：`./gradlew.bat :app:testDebugUnitTest :app:lintDebug`。

本批与 P06 已完成双批出口。安装包：`[本地交付目录]/Eta-P05-P06-v4.7.0-debug.apk`，SHA-256：`9FA4354B33AD65BA9F8F9D9DA097064787C851D4562BD4B425A8B849E7E51A70`。
