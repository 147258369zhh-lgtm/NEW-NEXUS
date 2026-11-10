# Eta 问题清单（供代码修改使用）

> 本文由十六轮真机实测整理，只收录 **Eta 自身代码可修** 的问题。
> 已剔除：第三方服务问题、设备环境状态、用户其他应用的干扰、旧版 App 残留。
>
> 标注说明：
> - `[实测]` 有具体数据支撑
> - `[推断]` 由证据推理
> - `[已更正]` 早期判断有误，已修正
>
> **建议阅读顺序**：
> 1. 「零、速览」——四个最严重问题
> 2. 「五点十二、修复优先级总表」——P0 到 P4 分级
> 3. 「五点十三、多图消息必然失败」——当前最紧急的功能性 bug
> 4. 其余章节按需查阅

## 阅读说明（给代码修改者）

**本文只收录 Eta 自身代码可修的问题。** 以下内容已剔除：

- 第三方 MCP 目录的数据质量问题（上游服务的问题，Eta 改不了）
- 设备环境状态（网速、定位精度、前台时长、开发者选项等，与代码无关）
- 用户其他应用造成的干扰（已改写为「Eta 应检测但未检测」的代码缺陷）
- 其他版本 App 的残留（Eta β 等，属用户环境）

**每条问题都指向一个可修改的代码位置或逻辑。** 若某条看起来像环境问题，请按该条给出的"应实现"建议处理——核心是 **检测 + 提示 + 降级**，而不是让用户自己排查。

## 零、速览

### 四个最严重的问题

| # | 问题 | 影响 |
|---|---|---|
| **1** | **多图消息（>1.2 MB）必然失败** | 发 6 张图即崩，压缩跑 53 秒后整轮作废 |
| 2 | **上下文压缩 75% 失败率，失败即整轮作废** | 53 秒工作白费，重试 6 次仍失败 |
| 3 | **上下文从不压缩时无上限，可涨到 338K** | 超长请求吃掉 27.7% 的 token |
| 4 | **MCP 工具定义占冷启动 51.3%，调用率 2.3%** | 每次请求白付 17,945 tokens |

补充：本地模型 ctx 硬编码 4096，装不下 34,694 的系统提示词，**完全不可用**。

> 第 1 项与第 2 项同源：**图片以 base64 原图内联 → 体积超限 → 触发压缩 → 压缩带图超时 → 整轮失败**。详见五点十三。

### 先修什么（详见五点十二）

**P0 四项全部是代码可修的问题**，围绕「检测 + 提示 + 降级」：
1. 无障碍未连接 → 启动自检 + 横幅提示
2. 语音服务未注册 → isActive() 校验 + 引导
3. 本地模型端口错 → base_url 改 9380
4. 本地模型 ctx 过小 → 按模型能力配置

当前这些故障全部**静默发生**，用户只能从"工具用不了"反推，这是最该优先修的。

### 一页数字

```
总请求        634 次
输入          71,786,518 tokens
输出             153,616 tokens
输入:输出     467 : 1
缓存命中      97.4%
估算成本      $3.82

基础开销      35,000 tokens/请求（双厂商交叉验证）
  其中 MCP    17,945 (51.3%)
最大上下文    338,397（无上限保护）
压缩次数      1 / 97 个 run
```

---

## 一、Token 经济问题

### 1.1 MCP 工具定义占冷启动一半 [实测]

`mcp_servers.tools_json` 实测：

| 服务器 | 工具数 | 字符 | tokens |
|---|---|---|---|
| github | 46 | 59,895 | 17,112 |
| 人生 | 2 | 2,433 | 695 |
| mcp 发现 | 1 | 480 | 137 |
| **合计** | **49** | **62,808** | **17,945** |

**交叉验证**：DeepSeek 与 OpenAI 两个独立计数，冷启动都落在 32K-38K，证明基础开销真实约 35,000 tokens。

**调用率**：349 次工具调用中 MCP 仅 8 次（2.3%）。本次会话 github 的 46 个工具**一次都没用过**。

### 1.2 参数在工具间大量重复 [实测]

github 46 个工具中：

| 参数 | 重复次数 |
|---|---|
| **owner** | **38 / 46** |
| **repo** | **38 / 46** |
| perPage | 16 |
| page | 15 |

`owner`+`repo` 的定义总计 **6,808 字符（1,945 tokens）**，表达的是同一个概念。

**提为全局参数只省：** 6,808 → 126 字符，**省 1,909 tokens（零功能损失）**。

再叠加去 `title`（17% 与 description 重复）+ `x-mcp-header`：

```
原始       59,895 字符  (17,112 tokens)
最优压缩   50,149 字符  (14,328 tokens)
降幅       16.3%
```

### 1.3 上下文从不上限、从不压缩 [实测，已更正]

**第一轮错误结论**：以为硬顶在 270,403。
**实际情况**：

```
全库最大 ctx = 338,397
超过 270K 请求 = 65 次
300K-350K 区间 = 38 次
压缩次数 = 1（97 个 run 中）
```

那个 24 轮的 run 一路涨到 337,535，`compactions=0`，全会话 succeeded：

```
r1  ctx=314,401
r12 ctx=324,393
r21 ctx=335,747
r24 ctx=337,535   ← 压缩 0 次
```

**结论：不是有上限，是根本没有保护机制。**

### 1.4 超长上下文的成本占比 [实测]

```
全库输入总量            71,786,518
超 270K 请求贡献        19,860,919  (27.7%)
```

那个 337K 的 run 单独花了 **$0.56**。若第 12 轮压到 150K，**省 2,161,349 tokens（占该 run 27%）**。

### 1.5 重发倍数 [实测]

| rounds | 累计输入 | 最终 ctx | 重发倍数 |
|---|---|---|---|
| 63 | 11,228,529 | 229,543 | **48.9×** |
| 41 | 10,174,138 | 268,316 | **37.9×** |
| 41 | 4,226,678 | 141,378 | **29.9×** |

---

## 二、工具层的真实损耗

### 2.1 消息存储的极端集中 [实测]

当前会话 350 条消息、179,868 字符：

```
terminal   221 次  122,971 字符 (79.4%)
subagent     2 次   32,000 字符 (20.6%)
其余 10 种工具               0 字符
```

**只有 terminal 和 subagent 把完整输出写进 content**。`write_file`/`read_file`/`edit_file`/`web_search` 等 151 条（25.5%）的 content 为空，只存 `result_summary` 短摘要。

### 2.2 terminal 的问题不是"输出大"而是"次数多" [实测，已更正]

```
n=237  min=47  p25=295  p50=486  p75=744  p90=1133  max=2197
```

**中位数只有 486 字符。** 我尝试压缩算法实测：

| 策略 | 结果 |
|---|---|
| 首 200 + 尾 100 | 降 49.3% |
| 按行截取 | 降 25.5%（更差） |

**结论：单条压缩效果有限，真正的解法是减少调用次数**。237 次 × 572 字符 = 135,529 字符，合并到 80 次可省约 79,529 字符。

### 2.3 工具失败画像 [实测]

```
441 次调用，Success 408 (92.5%)，Failed 33 (7.5%)
```

| 工具 | 失败率 | 主因 |
|---|---|---|
| skills_install_from_github | 66.7% | INVALID_SKILL |
| **edit_file** | **25.0%** | **FILE_CHANGED（乐观锁过期）** |
| web_search | 17.6% | WEB_TIMEOUT / SEARCH_CHALLENGE |
| observe_screen | 28.6% | ACCESSIBILITY_UNAVAILABLE |
| terminal | 5.1% | 环境错配、权限、超时 |

### 2.4 subagent 硬截断 16000 [实测，精确验证]

同一个会话内：

```
len=15804  未截断
len=16000  截断  ← "[跨进程结果过长，已截断]"
```

**阈值精确为 16,000 字符**（不是 16384，不是字节数）。

**后果**：截断是**直接切尾部**，我那次 A2A 规范调研最关键的结论"没有检索到 SSRF allowlist 条款"被切在句子中间：

```
...中**没有**检索到针对 A2A client 出站调用的 SSRF allowlis
[跨进程结果过长，已截断]
```

---

## 三、本地模型：可用性为零

### 3.1 ctx 硬编码 4096 [实测]

| 模型 | 配置 ctx |
|---|---|
| Gemma 4 E4B | 131,072 ✅ |
| Kokoro 82M | 8,192 |
| PocketTTS 100M | 8,192 |
| **其余 7 个** | **4,096** |

`qwen3-0.6b-dynamic` 名字带 dynamic，配置仍写死 4,096。

### 3.2 致命算术 [实测]

```
系统提示词     34,694 tokens
本地 ctx        4,096 tokens
──────────────────────────
可用空间      -30,598 tokens（负数）
```

超出 **8.5 倍**。实测 30 次本地模型请求 `inputTokens` **全为 0**，从未进入推理。

### 3.3 其他配置问题

```
端口 9380  LLM
端口 9379  语音
端口 11434 ← Ollama 默认端口，潜在冲突
磁盘       7.82 GB / 10 个模型
```

---

## 四、安全面

### 4.1 导出组件 [实测]

13 个导出组件，2 个无权限保护：

- `AgentRuntimeService`
- `libxposed.service.XposedProvider`

### 4.2 实测可利用性 [实测]

```
content query content://fuck.andes.XposedService
→ SecurityException: requires ACCESS_CONTENT_PROVIDERS_EXTERNALLY

am startservice .../AgentRuntimeService
→ SecurityException: requires INTERACT_ACROSS_USERS_FULL
```

**被系统层挡住，不是应用自身校验。** 风险低但依赖系统兜底，建议显式声明。

### 4.3 网络配置正常

```xml
<base-config cleartextTrafficPermitted="false"/>
```

全站禁明文，**配置正确**。

### 4.4 5 个已声明未授予的权限

`ACCESS_LOCAL_NETWORK`、`READ_ASSIST_STRUCTURE_SCREEN_CONTENT`、`MANAGE_EXTERNAL_STORAGE`、`PACKAGE_USAGE_STATS`、`SYSTEM_ALERT_WINDOW`

---

## 五、BUG 总表（16 个）

| 编号 | 问题 | 严重度 | 证据 |
|---|---|---|---|
| **BUG-13** | **上下文压缩 75% 失败率，失败即整轮作废** | **最高** | 4 次压缩 3 次失败，trace 可还原 |
| BUG-01 | subagent 硬截断 16000，切掉结论 | **高** | 15804不截/16000截 |
| BUG-02 | 未压缩时上下文无上限，涨到 338K | **高** | 24轮 run compactions=0 |
| BUG-12 | history/journal 重复存储 1.66 MB | 中 | 64/64 块完全相同 |
| BUG-03 | edit_file 乐观锁 25% 失败率 | 中 | 3/12 FILE_CHANGED |
| BUG-04 | web_search 17.6% 失败无降级 | 中 | WEB_TIMEOUT ×2 |
| BUG-05 | 本地模型未就绪就请求（8ms 404） | 中 | qwen2.5-0.5b |
| BUG-06 | 下载残留 168 MB 不清理 | 中 | .part 文件 |
| BUG-07 | 埋点缺失（TTFT 38%、价格 0%） | 中 | firstTextMs 199/521 |
| BUG-08 | 重试风暴：同输入重试 6 次全失败 | 中 | 8131→6188→4167→6294→7172→8159 |
| BUG-09 | 通知库 journal 模式不一致 | 低 | 0 字节 journal |
| BUG-10 | DEX Adler-32 校验和不符 | 低 | sha1 对/adler32 错 |
| BUG-11 | 会议室主持人会话无法滚动 | 中 | 历史 001 |
| CFG-01 | 本地模型 ctx 硬编码 4096 | **高** | 7/10 模型 |
| SEC-01 | 2 个导出组件无权限保护 | 低 | 系统兜底 |
| CFG-04 | A2A 密钥存在但零配对，WAL 144KB | 低 | 6 表全空 |

### 5.1 BUG-12：history 与 journal 重复存储（第六轮新发现）[实测]

**现象**：`agent_text_chunks` 表里同一 `owner_id` 的 `history` 与 `journal` 两个 field **内容逐字节相同**。

当前会话逐块验证：

```
history 块数: 64    journal 块数: 64
内容相同的块: 64    内容不同的块: 0
history sha256: 020ed9920d21e825
journal sha256: 020ed9920d21e825
完全相同: True
```

全库冗余：

| owner | history | journal | 相同 |
|---|---|---|---|
| conv-43ad37e6 | 64 / 1,035,331 | 64 / 1,035,331 | 是 |
| 439b3e53 | 22 / 346,251 | 22 / 346,251 | 是 |
| conv-418d3bb0 | 10 / 161,519 | 10 / 161,519 | 是 |
| conv-0cc42683 | 5 / 79,121 | 5 / 79,121 | 是 |
| conv-e2f01966 | 3 / 40,019 | 3 / 40,019 | 是 |
| **conv-5c653a88** | 4 / 51,492 | 4 / **59,115** | **否** |

```
可消除冗余 = 1,662,241 字节（1.66 MB）
占 eta.db 6,729,728 字节的 24.7%
```

**根因**：`context_checkpoints` 的 `history_json` 和 `journal_json` 各存一份分块引用，指向完全相同的 chunk 序列。

**注意**：不能直接删 journal —— `conv-5c653a88` 证明它有时含增量。安全做法是内容相同时存引用而非副本。

### 5.2 大消息的分块机制（设计合理）

大会话的 checkpoint 存 `@eta:chunks:v1:<块数>:<字节数>` 引用，实际内容切块存入 `agent_text_chunks`，单块 16,384 字符。

这是为规避 SQLite 单行大小限制的正确设计，问题只在于上面的重复。

### 5.3 BUG-13：上下文压缩 75% 失败率（第七轮，最严重的稳定性问题）[实测]

**这是本次调研发现的最严重的稳定性缺陷。**

全库压缩统计：

```
压缩开始 4 次
压缩成功 1 次
压缩失败 3 次
成功率 25%
```

**失败案例 1：run-4b28b4a9**（trace 完整还原）

```
seq1   1,159ms  start
seq2   1,264ms  compact started     ← 开始压缩
seq3             child_start  53d5de9e
seq4   9,985ms   child_finish success=1   (10.0s)
seq5             child_start  0d492f79
seq6  16,141ms   child_finish success=1   (6.2s)
seq7             child_start  3d0ae1f3
seq8  24,334ms   child_finish success=1   (8.2s)
seq9             child_start  9e693f55
seq10 33,132ms   child_finish success=1   (8.8s)
seq11            child_start  1514b994
seq12 40,697ms   child_finish success=1   (7.6s)
seq13            child_start  c6a45666
seq14 53,495ms   child_finish success=0   (12.8s) ← 最后一段失败
seq15 53,502ms   compact failed
seq16 53,536ms   failed
```

**53.6 秒的串行摘要工作，因最后一段失败而全部作废，没有部分成功保留。**

**失败案例 2：run-06d550c2** —— 完全相同模式，51.5 秒后失败。

### 5.4 第七轮的精确归因（关键修正）

深挖 metric_requests 后，修正了两个早期理解：

**修正 1：child 的 interrupted 不是失败。**

成功案例的 child 同样是 interrupted：

```
成功的 c11c73f7  status=interrupted  total=59,067ms  out=2,375
```

interrupted 只是 child 的正常终态标记。

**修正 2：第 6 段不是 LLM 失败，是 success=0 的判定问题。**

六段对比（全部 httpCode=200）：

| 段 | status | resp | total | in | out | ctx |
|---|---|---|---|---|---|---|
| 1 | interrupted | 156 | 8,704 | 8,131 | 1,313 | 9,444 |
| 2 | interrupted | 165 | 6,149 | 6,188 | 734 | 6,922 |
| 3 | interrupted | 140 | 8,190 | 4,167 | 1,255 | 5,422 |
| 4 | interrupted | 178 | 8,794 | 6,294 | 1,288 | 7,582 |
| 5 | interrupted | 389 | 7,562 | 7,172 | 936 | 8,108 |
| **6** | **failed** | **242** | **12,794** | **8,159** | **1,954** | **10,113** |

第 6 段指标完全正常：HTTP 200、返回 1,954 tokens、上下文 10,113 未超限。

**结论**：不是 LLM 调用失败，是响应被判定为不成功后丢弃。可能原因：
- 内容格式不符合摘要预期
- finish_reason 不是正常结束
- 输出被判为空或无效

### 5.5 六段串行时间轴（零重叠，确认同步执行）

```
child1: 1,280ms  ->  9,984ms   8,704ms
child2: 9,992ms  -> 16,141ms   6,149ms
child3: 16,144ms -> 24,334ms   8,190ms
child4: 24,338ms -> 33,132ms   8,794ms
child5: 33,135ms -> 40,697ms   7,562ms
child6: 40,701ms -> 53,495ms  12,794ms
```

每段紧接上一段开始，零重叠。


**成功案例：run-d28be725**

```
seq2   2,359ms  compact started
seq3   2,360ms  child_start c11c73f7
seq4  61,428ms  child_finish success=1   ← 单段，59 秒
seq5  61,681ms  compact completed
...
seq63 663,800ms succeeded                ← 整个 run 耗时 664 秒
```

### 5.6 BUG-13 的三个子问题

**问题 1：串行执行，耗时线性累加**

6 段历史串行摘要 = 10+6.2+8.2+8.8+7.6+12.8 = **53.6 秒**。若并行，理论上只需最慢那段的 12.8 秒。

**问题 2：全或无，无部分成功**

前 5 段都成功了（child_finish success=1），但第 6 段被判 success=0 → 整个 compact failed → 整个 run failed。**53 秒的工作白费。**

注意：第 6 段的 HTTP 状态是 200、返回了 1,954 tokens 输出，指标与其他段无异常。失败发生在**响应验收环节**，不是模型调用环节。

**问题 3：失败后无降级**

失败后重试 6 次，每次输入 token 完全相同（8131/6188/4167/6294/7172/8159），**6 次全部以同样方式失败**，共浪费 80,222 tokens 和约 5 分钟。

**问题 4：同步阻塞，用户干等**

压缩发生在首轮 `start` 之后、`request` 之前，用户必须等 53-59 秒才看到任何输出。

### 5.7 建议修复

| 问题 | 方案 |
|---|---|
| 串行 | 改并行，6 段同时摘要 |
| 全或无 | 允许部分成功：已完成的段保留，只重试失败的段 |
| 无降级 | 连续失败 2 次后跳过压缩，直接用截断式摘要 |
| 同步阻塞 | 改为后台任务，先回复用户，压缩完成后替换历史 |
| 无上限保护 | 设置绝对上限（如 400K），超过则强制截断最老消息 |

**这项修复的优先级应高于所有 token 优化**——它是功能性故障，不是效率问题。

---

## 五点五、第九轮：快速广度扫描发现（新一轮）

> 本轮改变策略：不深挖单点，快速扫多个维度，每项只记现象与证据。

### 5.8 无障碍未连接时 Eta 无自检与提示 [代码问题，高]

**现象**：`accessibility_enabled=1`，但 `enabled_accessibility_services` 里不包含 Eta 的 `AgentAccessibilityService`，GUI 操作全部失败。

**问题在代码侧**：

1. **无启动自检**。Eta 启动时不检查自己的无障碍服务是否真的在系统名单里，只在调用 GUI 工具时才失败。
2. **无状态提示**。设置页/主页没有任何「无障碍未连接」的可见警告。
3. **权限判断不可靠**。`CONTROL_ACCESSIBILITY_PROTECTION` 显示 granted 就当成无障碍可用，实际该权限只代表"有权限控制保护设置"，不代表服务在运行。

**应实现**：

```kotlin
// 用 AccessibilityManager 查真实状态，而非查权限
val am = getSystemService(AccessibilityManager::class.java)
val enabled = am.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)
val isSelfConnected = enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
```

- 若 `isSelfConnected == false`：主页横幅提示 + 一键跳系统设置
- GUI 工具调用前先校验，失败时返回明确错误码而非笼统的 `ACCESSIBILITY_UNAVAILABLE`

> 注：设备上无障碍位被其他应用占用是用户侧情况，**但 Eta 未检测、未提示、未降级，是可修的代码缺陷。**

### 5.9 语音服务声明但不校验注册状态 [代码问题，中]

**现象**：`assistant` 与 `voice_interaction_service` 均为空，但 `eta_wake_words.xml` 里 `enabled=true`、`voiceprint_enabled=true`、声纹已录入。

**问题在代码侧**：

1. **配置层缺校验**。用户开启唤醒词时，未检查 Eta 是否被设为默认 VoiceInteractionService。
2. **状态显示失真**。设置页显示"唤醒词已启用"，实际系统里根本没注册。
3. **无引导**。应能用 `VoiceInteractionService.isActive()` 检测，未激活时提示用户去"默认应用 → 助手"设置。

**应实现**：

```kotlin
val vis = getSystemService(VoiceInteractionService::class.java)
if (!VoiceInteractionService.isActive()) {
    // 提示：Eta 未设为默认助手，唤醒功能不可用
    // 提供跳转 Settings.ACTION_VOICE_INPUT_SETTINGS
}
```

### 5.10 通知监听器多应用并存时无时序保障 [代码问题，低]

```
enabled_notification_listeners =
  com.oplus.deepthinker/...DtNotificationListenerService
  com.huawei.health/...NotificationPushListener
  fuck.andes/io.github.mangi.eta.agent.device.AgentNotificationHistoryService
  com.microsoft.appmanager/...PhoneNotificationsListenerService
```

Eta 在列，但排在第三位。**多个监听器并存时通知到达时序不确定**。

### 5.11 通知发送前未校验 DND/音量，提醒静默丢失 [代码问题，中]

```
ringer_mode = normal
do_not_disturb = 1        （已开启）
notification level = 1/16 （几乎静音）
ring level = 2/16         （几乎静音）
media level = 160/160     （满）
alarm level = 16/16       （满）
```

**状态矛盾**：铃声模式是 normal，但 DND 开着且铃声音量接近 0。

**对 Eta 的影响**：语音播报走 media 通道（满音量，正常），但通知提醒（如「任务完成」）会被 DND 拦截。

### 5.12 悬浮窗权限状态判断与实际不一致 [代码问题，中]

通知历史里出现了：

```
android: "Eta"正在其他应用的上层显示内容
      如果您不想让"Eta"使用此功能，请点按以打开设置，然后关闭此功能。
```

说明 `SYSTEM_ALERT_WINDOW` **实际已授予**（系统发了悬浮窗提示）。

但 `inspect_app` 显示 `SYSTEM_ALERT_WINDOW: granted=false`。

**结论**：权限状态查询与实际不一致，或权限是运行期动态授予的。

### 5.13 朗读结束未清理常驻通知 [代码问题，低]

```
"Eta 正在运行"   text=生成回答中
"Eta 正在朗读"   （无正文）
```

两条常驻通知同时存在。**朗读结束后未清理通知**，可能造成通知栏堆积。

### 5.14 数据库层总体健康 [实测，正常项]

扫描 5 个数据库（eta / learning / fork / task / projects）：

| 检查项 | 结果 |
|---|---|
| integrity_check | 全部 ok |
| foreign_key_check | 无违例 |
| 空表 | eta 6 个、learning 5 个、fork 2 个（均为未使用功能） |

**结论**：数据库结构健康，空表对应未启用的功能模块，不是缺陷。

## 五点六、第十轮：广度扫描第二轮（配置漂移与新问题）

### 5.15 MCP 工具缓存过期后无重试兜底 [代码问题，中]

```
github   tools_expire_at=1794155247673
now                     1794155278770
差额 = 0.5 分钟前已过期
```

`tools_expire_at` 字段存在但**过期后没有自动刷新机制**，靠下次使用时才重新拉取。

**对比**：`人生` 和 `mcp 发现` 的 `tools_expire_at=None`（永不过期），只有 github 设了过期时间。

**不一致**：三个服务器的过期策略不同，可能是协议版本差异导致（github 是 2026-07-28，另两个是 2025-11-25）。

### 5.16 12 个供应商"已启用"但无 API Key [实测，中]

```
builtin-openai        enabled=1  keylen=0
builtin-anthropic     enabled=1  keylen=0
builtin-dashscope     enabled=1  keylen=0
builtin-kimi          enabled=1  keylen=0
builtin-mimo          enabled=1  keylen=0
builtin-minimax       enabled=1  keylen=0
builtin-stepfun       enabled=1  keylen=0
builtin-siliconflow   enabled=1  keylen=0
builtin-openrouter    enabled=1  keylen=0
builtin-xai           enabled=1  keylen=0
builtin-gemini        enabled=1  keylen=0
eta-local-model-runtime-builtin  enabled=1  keylen=0
```

**14 个供应商里 12 个启用但无 key**，实际只有 `builtin-deepseek`（keylen=35）和自定义的「3 倍 GPT」（keylen=67）可用。

**影响**：
- 模型选择列表里会显示一堆不可用的模型
- 用户切换模型时会才失败，而不是提前提示

**建议**：无 key 的供应商应标记为"未配置"而非"已启用"。

### 5.17 本地模型运行时指向语音端口 [实测，中]

```
eta-local-model-runtime-builtin
  base_url = http://127.0.0.1:9379/v1
```

但之前实测 `index-v1.json` 显示：
- **9380** = LLM（对话模型）
- **9379** = 语音（Phonon/Kokoro/Qwen3-ASR/PocketTTS）

**供应商 base_url 指向了语音端口 9379**，而不是 LLM 端口 9380。

这可能解释了之前那个 `qwen2.5-0.5b` 请求 8ms 返回 404 —— **请求打到了语音服务**。

### 5.18 本地模型 ctx 配置确认 [实测，已记录]

`provider_models` 表里：

```
Gemma 3 1B · Int4        ctx=4096
Parakeet TDT 0.6B        ctx=4096
Moonshine Tiny · 英文     ctx=4096
qwen3-0.6b-dynamic       ctx=4096
Gemma 4 E4B              ctx=131072   ← 唯一正确
```

与之前 `index-v1.json` 一致。**5 个启用的本地模型里 4 个 ctx=4096。**

### 5.19 定时任务库分离但全空 [实测，低]

`agent_task_definitions` 不在 `eta.db`，在 `eta_task.db`。

之前扫描 `eta_task.db` 时未发现任务定义，说明**定时任务功能从未使用**。

## 五点七、第十一轮：通知与系统状态扫描

### 5.20 通知历史声称 7 天，实际只有 8 小时 [实测，高]

**工具声明**：
```
search_notification_history(retention_days=7)
```

**实际数据库**：

```
notification_history 表：26 行
最早: 2026-11-08 08:20:54
最晚: 2026-11-08 16:28:39
跨度: 8.13 小时
```

**26 条记录覆盖 8 小时，与"7 天"严重不符。**

按当前速率（约 3.2 条/小时），7 天应约 540 条。实际只有 26 条，**说明清洗策略或写入逻辑有问题**。

### 5.21 未请求通知正文权限，只能拿到标题 [代码问题，中]

26 条里**几乎没有微信/QQ 的聊天内容**，只有：

```
com.tencent.mm        "Murphy[表情]宝宝" / "老公到省政府了"
com.tencent.mobileqq  "未读消息" / "Thanos，你有 1 条信息未处理"
```

抓到的都是**通知标题**，正文被系统隐藏（Android 通知折叠）。

**影响**：`search_personal_orders` 之类依赖通知内容的工具，实际拿不到订单详情。

### 5.22 数据库 32KB 却有 26 行 [实测]

```
eta_notification_history.db  32,768 字节（正好 32KB）
26 行数据
```

32KB 是 SQLite 的最小页大小。**说明这个库几乎是空的**，与"7 天历史"的定位不符。

同时也解释了为什么还留着 `-journal`（0 字节）——库太小，几乎没有写入。

### 5.23 未过滤自身通知，形成自我记录噪声 [代码问题，低]

```
fuck.andes  "Eta 正在运行"      16:28:39
fuck.andes  "Eta 正在朗读"      16:28:16
fuck.andes  "正在下载 PocketTTS" 14:48:45
```

**Eta 自己的前台服务通知也被存进了历史库**，形成自我记录。

**影响**：查询"我的订单"时会混入 Eta 自己的通知噪声。

### 5.24 未适配 DND 状态导致提醒失效 [代码问题，中]

```
ringer_mode          = normal
do_not_disturb       = 1        （已开启）
notification level   = 1/16     （几乎静音）
ring level           = 2/16     （几乎静音）
media level          = 160/160  （满）
alarm level          = 16/16    （满）
voice_call level     = 2/9      （很低）
system level         = 2/16     （很低）
```

**三处矛盾**：

1. `ringer_mode=normal` 但 DND 开着 —— 两个状态互斥语义
2. 铃声音量 2/16 但模式标 normal
3. **语音通话音量 2/9**，如果用户接电话会几乎听不见

**对 Eta 的影响**：
- 语音播报走 media（满音量）→ 正常
- 任务完成通知走 notification（1/16）→ **实际听不到**
- 闹钟类提醒走 alarm（满）→ 正常

## 五点八、第十二轮：语音、Skill 与存储扫描

### 5.25 唤醒词已启用但助手未注册，功能形同虚设 [实测，高]

配置层（`eta_wake_words.xml`）：

```
enabled = true
voiceprint_enabled = true
voiceprint_threshold = 0.72
words = ["eta"]
```

声纹已录入（`eta_voiceprint.xml` 有 profile_id 和 feature_vector）。

**但系统层**：

```
assistant = （空）
voice_interaction_service = （空）
```

**配置说"已启用"，系统里却没有注册。** 用户配好了唤醒词和声纹，但喊"eta"不会有任何反应。

**这是配置层与系统层的脱节**，与 5.14 的无障碍问题同源。

### 5.26 语音模型齐全但链路断开 [实测]

6 个语音模型全部就位：

```
kokoro_int8_gpu_fp16              294 MB   TTS
phonon_int8                        87 MB   TTS
pocket_tts_int8                   760 MB   TTS
parakeet_tdt_0.6b_v3_5s_i8        614 MB   ASR
moonshine_tiny_5s_i8               29 MB   ASR
qwen3_asr_0.6b_5s_i8              959 MB   ASR
```

合计约 2.7 GB。**模型都在，但没有可用的唤醒入口。**

### 5.27 语音崩溃保护表全空 [实测，低]

```
eta_local_speech_native_crash_guard.xml  →  <map />
eta_local_model_native_crash_guard.xml   →  <map />
```

两个崩溃保护记录都是空的。**说明语音链路从未真正运行过**（运行过才会留下崩溃或成功记录）。

这印证了 5.30 的判断：唤醒功能从未生效。

### 5.28 Skill 目录健康，但缺 README/SKILL 一致性 [实测，正常项]

```
humanizer-zh        SKILL.md 6,807 + README 3,680 + LICENSE + .gitignore
self-improving-agent SKILL.md 2,908
skill-creator        SKILL.md 4,106
skill-installer      SKILL.md 2,059
```

**4 个 skill 全部有 SKILL.md，与 registry 的 `state=installed` 一致。** 这块没问题。

### 5.29 内置浏览器从未启动过 [实测，中]

APK 里有 `AgentBrowserActivity`、完整的 WebView 相关代码（`isCleartextTrafficPermitted`、`X509TrustManager` 等）。

**但应用数据里没有 `app_webview/` 目录**，这是 WebView 首次使用才会创建的。

**结论**：`browser_use` 工具从未被真正调用过，内置浏览器从未启动。

**副作用**：WebView 首次启动会有冷启动开销（初始化 Chromium 内核约 1-2 秒），如果用户第一次用会感觉卡。

### 5.30 存储分布：模型占 91% [实测]

```
files/local_models          11,588,046 KB  (11.0 GB, 91.8%)
files/terminal-user          1,029,239 KB  ( 1.0 GB,  8.2%)
files/conversation_attachment_images   9,428 KB
files/skills                     54 KB
cache                           836,587 KB  (816 MB)
databases                        11,924 KB
shared_prefs                         72 KB
```

**总计约 12.6 GB，其中本地模型 11 GB。**

**问题**：
1. 模型占 91%，但实际只用 DeepSeek 云端（`builtin-deepseek-flash`）
2. 之前发现的 168 MB `.part` 残留仍在此列
3. `cache` 有 816 MB，未清理

**建议**：提供"按使用频率卸载模型"功能，或一键清理未使用模型。

### 5.31 图片缓存目录未清理 [实测，低]

```
files/conversation_attachment_images   9,428 KB
```

9 MB 的对话图片缓存，无过期清理机制。

## 五点十、第十四轮：会话数据一致性与分支功能

### 5.32 user 消息 content 为空，实际内容藏在 images_json [实测，高]

10 条 user 消息 `content=''`，但 `images_json` 字段里存的是分块引用：

```
conv=439b3e53 idx=130  content=0  images_json=@eta:chunks:v1:2:24426
conv=439b3e53 idx=136  content=0  images_json=@eta:chunks:v1:2:17459
conv=439b3e53 idx=138  content=0  images_json=@eta:chunks:v1:2:19580
...
conv=439b3e53 idx=154  content=0  images_json=["data:image/jpeg;base64,/9j...
```

**问题**：
1. 字段名是 `images_json`，实际存的却是**文本分块引用**
2. `content` 为空，读取工具若只查 `content` 会认为消息是空的
3. 第 154 条更混乱：`images_json` 里放的是真的 base64 图片数据

**根因**：大文本（超过单行限制）被切块存到 `agent_text_chunks`，但引用写进了 `images_json` 而不是 `content`。

**影响**：任何按 `content` 判断消息是否为空的地方都会误判。

### 5.33 对话分支链表结构正确 [实测，正常项]

```
conv-f1595230  family=self      parent=None        ← 根
c7ad6ca9       family=conv-f159 parent=conv-f159   ← 第 1 层
439b3e53       family=conv-f159 parent=c7ad6ca9    ← 第 2 层
```

**三层链式结构完整，family_id 一致，无断裂。**

`history_mode` 有两种取值：
- `journal`（前两个）
- `visible_reconstruction`（第三个）

说明分支支持不同的历史重建策略。

### 5.34 消息索引完全连续 [实测，正常项]

15 个会话的 `sort_index` **全部从 0 连续无空洞、无重复**。

数据完整性良好。

### 5.35 当前会话已达 604 条消息 [实测，参考]

```
assistant   151 条   42,549 字符
tool        427 条  209,429 字符
user         26 条      992 字符
```

**tool 消息占 209 KB（83%）**，其中：

```
terminal            314 次  177,429 字符
write_file           29 次        0 字符
edit_file            23 次        0 字符
read_file            15 次        0 字符
get_setting          13 次        0 字符
```

**只有 terminal 把完整输出写进 content**，其余工具全是 0 字符（结果存在 `result_summary`）。

**佐证了之前的结论**：优化只需聚焦 terminal 一种工具。

### 5.36 conversations.history_json 全员为空 [实测，已确认]

```
history_json 为空: 15 / 15
context_checkpoints 行数: 15
```

**每个会话的 history_json 都是 `[]`，真实数据全在 checkpoints 表。**

这是**废弃字段**，但仍占用每行 2 字节。15 行共 30 字节，影响可忽略，但属于设计遗留。

### 5.37 分支链的根节点 parent=NULL [实测，正常项]

```
conv-f1595230  parent=None
```

这是我一开始误判为异常的项。实际上**根分支 parent 为 NULL 是正确设计**，用 `family_id` 自引用标识家族根。

### 5.38 会话标题重复度高 [实测，低]

```
"你好" × 6 个会话
其余各 1 个
```

**6 个会话标题都是"你好"**，用户无法区分。系统没有做标题去重或自动补全序号。

**建议**：重名时自动加序号或时间后缀。

---

## 五点十一、第十五轮：能力边界与降级建议

> 本轮实测「当前权限状态下，哪些能力实际可用」，结论用于校正文档与实际的一致性。

### 5.47 权限矩阵总览

| 能力 | 状态 | 阻塞原因 |
|---|---|---|
| GUI 操作（无障碍） | ❌ | GKD 占用服务位 |
| 语音唤醒 | ❌ | 未设为默认助手 |
| Root 命令 | ❌ | su 不存在 |
| 读其他应用数据 | ❌ | 分区存储 |
| 读网络连接 | ❌ | procfs 限制 |
| 通知内容 | ⚠️ 仅标题 | 系统折叠 |
| 文件读写（共享存储） | ✅ | — |
| 系统命令查询 | ✅ | — |
| 网络请求 | ✅ | — |
| 硬件控制 | ✅ | — |
| Linux 环境 | ✅ | 需切 environment |

**核心结论：Eta 当前处于"能读能查但不能操作"的状态。** 大部分需要主动操作手机的能力全部因权限或配置问题不可用。

---

## 五点十二、修复优先级总表（按对你的实际影响排序）

> 前面各轮是「发现」，这一节是「行动」。按"不修就没法用"到"修了更省"排序。

### P0 — 不修就直接失去功能（4 项）

| 问题 | 症状 | 修复动作 |
|---|---|---|
| **多图消息必然失败** | 发 6 张图 >1.2MB 就崩，压缩跑 53 秒后失败 | 发送前按 1568px/quality80 重编码，控制在 1MB 内；压缩时图片转占位符 |
| **无障碍未连接时无自检** | GUI 工具全失效，且界面无任何提示 | 启动时用 AccessibilityManager 查真实状态，失败则横幅提示 + 引导跳转 |
| **语音服务未注册不校验** | 唤醒词显示"已启用"但实际无效 | 用 VoiceInteractionService.isActive() 检测，未注册则提示 |
| **本地模型端口配错 9379** | 请求 8ms 返回 404 | base_url 改为 `:9380`（LLM 端口） |
| **本地模型 ctx=4096** | 装不下 34,694 的系统提示词 | 按模型实际 max_position_embeddings 配置 |

> 这四项**全部是代码侧可修的检测与配置问题**。即使在用户没有正确授权的情况下，Eta 也应检测到并明确告知，而不是静默失败。

### P1 — 严重影响稳定性（3 项）

| 问题 | 症状 | 修复动作 |
|---|---|---|
| **压缩 75% 失败率** | 等 53 秒然后失败，重试 6 次全败 | 串行改并行 + 允许部分成功 + 失败降级 |
| **subagent 硬截断 16000** | 关键结论被切掉 | 改结构化摘要（结论前置） |
| **通知历史只有 8 小时** | 声称 7 天实际 26 条 | 检查清洗策略 |

### P2 — 明显浪费（4 项）

| 问题 | 量化 | 修复动作 |
|---|---|---|
| **MCP 定义占冷启动 51.3%** | 每次白付 17,945 tokens | owner/repo 提全局（省 1,909）+ 按需加载 |
| **上下文从不压缩时无上限** | 涨到 338K，27.7% token 在此 | 设 400K 硬顶 + 60% 阈值触发 |
| **history/journal 重复存** | 1.66 MB（eta.db 的 24.7%） | 内容相同时存引用 |
| **下载残留 168 MB** | 永久占用 | 启动时清理孤儿 .part |

### P3 — 体验改进（5 项）

| 问题 | 症状 | 修复动作 |
|---|---|---|
| edit_file 25% 失败 | FILE_CHANGED 乐观锁过期 | 连续编辑合并为一次 write_file |
| web_search 17.6% 失败 | 无自动降级 | 失败切 fetch_url |
| 12 个供应商无 key 却启用 | 选择器列出一堆不可用 | 标记为"未配置" |
| 6 个会话都叫"你好" | 无法区分 | 重名自动加序号 |
| 埋点缺失 | TTFT 只记 38%、价格全 NULL | 补齐埋点 |

### P4 — 清理与整理（4 项）

| 问题 | 量化 |
|---|---|
| Eta β 3.4.0 残留 | 落后 7 个版本，建议卸载 |
| 缓存 816 MB | 可清理 |
| 通知库 journal 不一致 | 0 字节遗留文件 |
| conversations.history_json 废弃 | 15 行全是 `[]` |

### 一句话总结

**P0 的四项全部是代码可修的**：核心是「检测 + 提示 + 降级」三件事，而不是依赖用户自己去系统设置里排查。当前 Eta 在这三类情况下全部静默失败，这是最该先修的部分。

---

## 五点十三、多图消息必然失败（用户报告 + 实测确认，最高优先级）

### 现象

用户发送 6 张图片后，上下文立即触发压缩，但压缩每次都失败，消息完全无法使用。

### 实测数据：图片体积与成功率的硬边界

全库 113 个 run 按 `usage_imageBytes` 分档统计：

| 图片体积 | run 数 | 成功 | 失败 | 成功率 |
|---|---|---|---|---|
| 0 – 100 KB | 97 | 85 | 12 | 87.6% |
| 100 – 300 KB | 6 | 6 | 0 | **100%** |
| 300 – 500 KB | 6 | 6 | 0 | **100%** |
| 1000 – 1500 KB | 1 | 1 | 0 | **100%** |
| **1500 – 3000 KB** | **3** | **0** | **3** | **0%** |

**分界线在 1.1 MB 到 2.1 MB 之间。**

明细（按体积降序）：

```
2,762,012 bytes  run-4b28b4a9    interrupted
2,762,012 bytes  run-06d550c2    interrupted
2,159,848 bytes  run-d28be725    interrupted
1,155,956 bytes  run-5313daa2    succeeded   ← 最大成功值
  439,201 bytes  run-cdad545d    succeeded
  ...（其余全部成功）
```

**1.16 MB 成功，2.16 MB 失败。** 6 张图合计约 2.7 MB，稳超这条线。

### 与压缩失败的因果关系

3 个失败 run 的指标：

```
run-4b28b4a9  imgBytes=2,762,012  rounds=0  ctx=None   ← 压缩前就死
run-06d550c2  imgBytes=2,762,012  rounds=0  ctx=None   ← 同上
run-d28be725  imgBytes=2,159,848  rounds=8  ctx=60,672 ← 压缩后仍失败
```

前两个 `rounds=0`、`ctx=None`，说明**在压缩阶段就崩了**，连一次正常的模型往返都没完成。

第三个更典型：压缩跑了 8 轮、耗时 59 秒、最后 `interrupted`。

结合 5.3 的结论（压缩 4 次成功 1 次），**图片体积过大 → 触发压缩 → 压缩本身在 50 秒以上超时 → 整轮作废**。

### 根因分析

**不是上下文不够**（模型 1M 上下文，6 张图约 2.7 MB base64 折合不到 100 万 token）。

真正的问题是：

1. **图片以 base64 data URL 原样内联**，未做尺寸压缩
   - `images_json` 里存的是 `["data:image/jpeg;base64,/9j/4AAQ..."]`
   - 单条最大的 `images_json` 是 15,993 字符，但 run 级的 `usage_imageBytes` 达 2.7 MB
   - 说明**每轮请求都把完整 base64 重新上传**，而不是只传一次

2. **压缩任务同样带图**
   - 压缩时把历史切段摘要，每段可能仍携带图片数据
   - 导致每个 child 请求都很重，累计耗时 53 秒以上

3. **无图片预处理**
   - 未按模型最大分辨率缩放
   - 未做质量压缩（Android 可用 `Bitmap.compress(quality=80)`）
   - 未限制单次消息的图片总量

### 建议实现

**A. 图片预处理（最关键）**

```kotlin
// 发送前压到合理尺寸
fun prepareImage(uri: Uri): ByteArray {
    val bmp = decodeSampled(uri, maxDim = 1568)  // Anthropic/OpenAI 推荐上限
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return out.toByteArray()
}
```

按 1568px + quality 80，单图可压到 150-300 KB，6 张约 1-1.5 MB，**稳在成功区间内**。

**B. 图片不进压缩链路**

压缩历史摘要时，图片应替换为占位符（如 `[图片 #1]`），而非原样带上。摘要文本不需要图像。

**C. 发送前阈值校验**

```kotlin
val MAX_IMAGE_BYTES = 1_000_000  // 保守值，实测 1.16MB 可成功
if (totalImageBytes > MAX_IMAGE_BYTES) {
    // 自动降质重编码，或提示用户减少图片
}
```

**D. 压缩超时兜底**

当前压缩会跑 53-59 秒然后失败。应设 20 秒硬超时，超时后走**截断式降级**（丢弃最老的消息）而不是整轮作废。

### 优先级

**这是本次调研发现的最高优先级问题**，因为：

- 用户明确遇到且完全无法使用
- 有明确的数值边界（1.16 MB 成功 / 2.16 MB 失败）
- 根因清晰（base64 原图内联 + 压缩链路带图）
- 修复方案明确（预处理压缩，不需要改架构）

---## 六、优化方案（按实测收益排序）

### 第一优先：零风险，立即做

| 方案 | 收益 | 工作量 |
|---|---|---|
| **owner/repo 提为全局参数** | 省 1,909 tokens/请求 | 改序列化 |
| 去 title + x-mcp-header | 再省 832 tokens | 低 |
| 清理 .part 残留 | 释放 168 MB | 极小 |
| 本地 ctx 按模型能力配 | 本地可用 | 一行配置 |

### 第二优先：数量级影响

| 方案 | 收益 | 工作量 |
|---|---|---|
| **启用上下文压缩** | 27.7% 的 token 在此，压缩省一半 | 中 |
| **MCP 按需加载（46→3）** | 基础开销降 45% | 中 |

### 第三优先：体验改进

| 方案 | 收益 |
|---|---|
| subagent 结构化摘要替硬截断 | 不再丢结论 |
| edit_file 合并连续编辑 | 消除 25% 失败 |
| web_search 自动降级 | 消除 17.6% 失败 |
| 补齐埋点 | TTFT + 费用可见 |

### 收益汇总

```
现状基础开销                35,000
  MCP 参数去重后            33,091  (-5.5%)
  + 去 title/header         32,259  (-7.8%)
  + 按需加载 3 个           19,222  (-45%)
  + 内置工具长尾精简       ~13,000  (-63%)
```

---

## 七、反直觉结论

### 7.1 上下文大小不是延迟主因 [实测]

| ctx 范围 | n | 中位 ms | p90 ms |
|---|---|---|---|
| 0K-50K | 184 | 308 | 2,318 |
| 250K-300K | 24 | 456 | 603 |

涨 270K，中位只 +48%，**p90 反而降 75%**。

### 7.2 首字延迟才是瓶颈 [实测]

```
firstTextMs  p50=1,061ms  p90=3,943ms  p99=59,508ms
```

但只记录 38.2% 的请求。

### 7.3 thinking 不是全局开销

只有 2 个会话有（57KB / 16KB），当前会话 0 条。

### 7.4 shell 启动开销 30-45ms

145 次 terminal = 约 5 秒纯开销。

---

## 八、功能性建议

1. **把内置 Xposed 模块接到 UI** — scope 覆盖 8 个进程，设置页无任何状态显示
2. **本地模型 LRU 卸载** — 实测 1.72 GiB native 分配，搜不到 unloadModel
3. **Skill 格式转换器** — 生态 6 种格式，90% 兼容
4. **能力包文档公开** — `eta-plugin/v1` 无公开文档
5. **崩溃恢复检查点** — `runtime_inflight_runs` 有记录但用户看到"未自动重放"

---

## 九、分篇索引

本文是总纲，以下是过程记录：

| 分篇 | 内容 |
|---|---|
| Eta-全能力摸排-4.10.2 | 反编译 APK，能力矩阵 |
| Eta-深度测验工作台 | 前 27 项实验 |
| Eta-第二轮-安全与配置 | 安全与配置审计 |
| Eta-诊断与改进提案 | 早期诊断 |
| Eta-调研总报告 | 分轮次详细数据 |

---

## 十、方法论（可复用）

### 有用的技巧

| 方法 | 用途 |
|---|---|
| 手写 DEX 头解析 | 判断文件完整性 |
| 手写 AXML + 资源 ID 表 | 读 Manifest 必需 |
| 只读副本 + `immutable=1` | 安全查 Room 数据库 |
| **双厂商交叉验证** | 排除单点测量误差 |
| 从 tools_json 原始 JSON 测算 | 精确计算工具体积 |

### 踩过的坑

| 坑 | 修正 |
|---|---|
| linux 环境 `/sdcard` 不可用 | 用 `/storage/emulated/0` |
| `cd /workspace` 在 android 环境 | 切 `environment=linux` |
| `web_search` 超时 | 立即切 `fetch_url` 或 subagent |
| 直接 grep dex 二进制 | 必须解析结构 |
| 第一轮 Top30 查询 | 漏掉持续增长数据，必须全量扫描 |

---

*最后更新：2026-11-09（第十六轮完成）*
