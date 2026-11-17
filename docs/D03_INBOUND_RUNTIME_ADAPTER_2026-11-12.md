# D03 本地 Runtime → 入站 A2A 等待状态适配（首链）

日期：2026-11-12
范围：app 模块的 Runtime 暂停信号、A2A 入站执行器与最小测试；未修改 docs/nexus，未构建 APK。

## 问题

PersistentA2AInboundTaskHandler 已能把一次入站结果持久化为 INPUT_REQUIRED / AUTH_REQUIRED，并在续接时复用同一 task/context 和原授权，但 AgentRuntimeA2AInboundExecutor 只会返回完成文本：

- 模型调用 request_user_input 时，Runtime 会在本机发布问题并阻塞最长 5 分钟等待本机 UI 回答，远端任务一直停在 WORKING；A2A 授权层级里甚至没有暴露这个工具。
- 需要本机审批的工具会在 A2A 运行里继续等待本机确认框，或按本机权限模式推进，远端无法得知需要授权。
- Runtime 进程与 :a2a 进程隔离，入站执行器拿不到本机内存里的待答问题，因此暂停信号必须随最终结果跨进程返回。

## 实现

最小 seam：让 Runtime 主动结束一次"只能由入口回答"的运行，并把有界、脱敏的暂停摘要放进结果；A2A 执行器把它映射成任务等待态，绝不伪造答案。

1. AgentRuntimeWire.RunResult 新增可选 pause: RunPause?（kind/question/options/allowFreeText/reason）。
   - 走完整结果 JSON 与 pause_json Bundle 双通道，旧客户端缺省为 null。
   - compactForDrain=true 的结果不带详情，只保留 failureCode，作为降级路径。
2. AgentRunController.isRemoteA2AInbound（来源 RunSource.A2A_INBOUND）。
   - beginApproval() 对该来源直接抛 AgentRunPauseException(AUTH_REQUIRED) 并 abort 运行，不再注册审批、不再等本机确认框。
3. RunUserInput.execute() 对 A2A 来源在写本机输入账本和发布 pending 之前拦截：
   - 构造 INPUT_REQUIRED 暂停摘要并 abort；不发本机提示、不写私有账本、不等待。
   - 校验仍与聊天一致（问题 1..2000 字、最多 8 个选项、每项 1..200 字）。
4. AgentRuntimeRunExecutor 捕获该暂停，写入 failureCode = A2A_&lt;KIND&gt; 与 pause，错误文案使用固定短句，不把问题正文写进失败原因。
5. AgentRuntimeA2AInboundExecutor：
   - request_user_input 作为交互原语加入本次运行的工具策略（不写入 DB 授权，不扩大本机能力）。
   - 系统提示说明调用后任务暂停、由远端后续消息回答，且不得索要凭据。
   - 结果统一经 A2AInboundRuntimeOutcome.map() 映射；ok=false 且无暂停信号时仍按失败处理。
6. A2AInboundStatusSummary 生成状态消息：仅"问题 + 建议选项"，上限 600 字符，去掉控制字符，并对 bearer/API key/token/长 hex/长 token 形态做 [redacted]。

续接语义保持不变：handler 复用同一 taskId（即 Runtime runId）与 a2a:&lt;contextId&gt; 模型会话，历史只追加原 prompt 与续接输入；授权取存储 grant 与当前 grant 的交集，不接受远端消息作为本机审批，也不自动重放原 turn。

## 验证

命令（Windows 路径含中文，Android Gradle Plugin 需要显式放行）：

    gradlew.bat --offline -Pandroid.overridePathCheck=true :app:compileDebugKotlin
    gradlew.bat --offline -Pandroid.overridePathCheck=true :app:testDebugUnitTest
      --tests "io.github.mangi.eta.fork.a2a.*"
      --tests "io.github.mangi.eta.agent.runtime.RunUserInput*"
      --tests "io.github.mangi.eta.agent.runtime.AgentRuntimeWirePauseTest"

XML 计数（app/build/test-results/testDebugUnitTest）：

| 测试类 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| A2AInboundRuntimeOutcomeTest（新增） | 7 | 0 | 0 | 0 |
| RunUserInputA2APauseTest（新增） | 4 | 0 | 0 | 0 |
| AgentRuntimeWirePauseTest（新增） | 2 | 0 | 0 | 0 |
| RunUserInputTest | 8 | 0 | 0 | 0 |
| A2AInboundTaskCoordinatorTest | 12 | 0 | 0 | 0 |
| 其余 A2A 既有测试（10 类） | 31 | 0 | 0 | 0 |
| **合计** | **64** | **0** | **0** | **0** |

补充回归（同一 XML 目录）：AgentRunControllerTest 11、AgentPendingResultRecoveryTest 8、AgentRunRecoveryCoordinatorTest 6，全部 0 失败；两轮合计 89 tests / 0 failures / 0 errors / 0 skipped。

新增覆盖点：request_user_input → INPUT_REQUIRED（含问题与选项）、审批门 → AUTH_REQUIRED、凭据脱敏与长度上限、失败结果绝不报完成、无 pause 详情时按 failureCode 降级、跨进程 Bundle 往返、暂停任务重启后不重放、续接复用同一 task/context 且 grant 不变、重复续接消息不重复执行、聊天审批路径不回归。

## 残余风险与缺口

1. 模型上下文不包含已暂停的 tool call：暂停 turn 结束时不产出部分 transcript，续接 turn 只带原 prompt、历史续接输入和续接文本，模型不会看到自己上一轮的 request_user_input 调用与结果；任务/会话/授权连续，但工具调用级别不连续。
2. compact 排空结果丢失问题详情：若客户端在暂停结果发布时已断连，outbox 的 compact 结果只保留 A2A_* 失败码，peer 会得到通用等待文案而非原问题。
3. 凭据类工具错误仍是普通失败：Runtime 没有统一的"凭据缺失"暂停信号；SEARCH_AUTH_REQUIRED、OAuth 未授权等工具级错误继续交给模型解释，不会被伪造为 AUTH_REQUIRED。
4. 本机无 A2A 审批 UI：A2A 运行命中审批门时统一进入 AUTH_REQUIRED，本机没有针对该运行的审批入口；远端续接也不会替代本机确认（符合安全边界）。
5. 进程崩溃中的 WORKING 任务仍由既有恢复逻辑重跑原 prompt：本改动只保证暂停后不重放；崩溃恢复策略未改动。
6. 未做真机/多进程端到端验证：验证为编译 + 单元/Robolectric 测试，未构建 APK，未在真机上跑实际 A2A 往返。
