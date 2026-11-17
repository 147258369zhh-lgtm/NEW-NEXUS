# NEXA 能力中心审计与修复 · 2026-11-16

本批 `0.7.7-smooth / 2026111602` 已保留数据覆盖安装至 OPPO PHY110。用户授权认真审计、修复能力中心，允许在该范围重组界面；其他产品页面未顺带调整。局域网与 Tailscale 两种 Hermes 连接均需保留。实际构建、安装结果见文末交付记录。

## 结论与证据范围

NEXA 已有统一的工具目录、运行授权、主机路由、结果回执和恢复所有者，不需要再建一套平行执行平台。本轮修复了跨模块断点：Skill 工具名与运行目录不匹配、MCP 大目录工具丢失及串行准备、A2A 任务地址/长响应/持久回执问题，以及 DSH 会话生命周期和通用连接的假成功。

证据分为源码复核、宿主定向检查、电脑真实公开服务读取、Android 安装确认。没有向真实 Hermes 或云模型发送任务，没有读取或记录用户 Token，没有修改 Hermes、重启手机或上传源码。安装成功不等于全部第三方账号、脚本依赖、网络环境均实机验收通过。

## 模块清单

| 模块 | 模型与执行链 | 本批处理 | 边界 |
| --- | --- | --- | --- |
| 内置设备、文件、浏览器与云工具 | 目录 → 当前运行策略 → 统一执行器 → 结果及成果投影 | 增加只读“模型工具平台”，按真实 descriptor 展示条件、效果与恢复策略；中文/英文、多词及用途检索 | 条件具备不等于已通过账号、系统权限和具体调用验收 |
| Linux 与能力包 | 由 `terminal`、`terminal_job`、`terminal_daemon` 执行真实已安装命令 | 清单读取当前发行版、后端和安装标记；Python、Node、PDF 等用途可找到原终端工具 | 不为每个二进制虚构独立工具；安装标记不等于依赖健康检查，不自动安装或提权 |
| Skill 核心包与跨厂商导入 | 安装校验 → 兼容检查 → 按需读指令/资源 → 原工具权限交集 | YAML 常用语法、工具别名、长文分页、Gitee 定位、静态资源桥 | 并非所有厂商扩展语义兼容；完整矩阵见下表 |
| MCP | 连接快照 → 发现及治理 → 可检索目录 → 按需激活 → 主机执行 → 文本/媒体/资源回执 | 2 并发准备、失败持久化；去除目录阶段的静默 64 项截断；保留每个服务器的资源读取器 | 没有新增每服务器总时限；网络、协议、服务端权限仍需满足；签名响应提供商保留稳定完整目录会增加输入量 |
| A2A 出站 | 卡片发现 → 明确任务地址 → 首句柄持久化 → 流/查询 → 原任务续接 | 修复 LAN 卡片转向 Tailscale、长任务超时与未知派发风险、结果遗漏、父记录 REPLACE 级联删除 | 真实手机到 Hermes 网络当前未连通；SSE 断网/休眠仍受 Hermes 对端失败语义影响 |
| A2A 入站 | 凭证身份 → 权限 → 持久任务 → 运行/查询/续接 | 统一凭证指纹主体，核对任务及 context 所属；旧自报节点权限不继承 | 共享 Token 对应一个主体，未增加多凭证身份管理 |
| DSH | 桌面桥接主机 → 会话/插槽/视图 → Android 原生呈现 → 真实用户动作 | 配置修改重建流、面板重载重订阅、过期 Cookie 有界刷新、准确版本准入原因 | 模型仅 catalog/view 只读；不能伪造用户点击；桌面插件不在 Android 原样执行 |
| REST / JSON-RPC / GraphQL / OpenAPI | 配置/凭据 → 原授权 → 协议传输 → 显式成功/错误回执 | 修复 HTTP 或协议错误被包装成成功；保留 GraphQL 部分数据；RPC 校验版本、id、结果互斥；减少多余探针 | 不按任意业务字段名 error 猜测失败；外部操作不自动重试 |
| WebSocket / SSE / 本机连接 | 使用现有连接与生命周期所有者 | 保留必须建连的探针、原确认、撤销和凭据边界，有限静态复核 | 没有逐个真实服务、系统 ContentProvider 或长期连接验收 |
| 插件与沙箱包 | 复用既有 PackageRunSnapshot、路由及运行授权 | 核对与统一目录衔接，不新增第二执行器 | DSH manifest 静态校验不等于桌面插件可安装；任意 Electron、Windows DLL 不自动兼容 |
| 能力中心入口 | 工具平台、Skills、MCP、API、A2A、DSH、连接、来源 | 移除本地模型、语音识别/合成、提示词配置的重复入口，原设置所有者保留；把配置数量与健康状态分开 | 不修改原语音、本地模型、提示词页面；插件明确请求的原跳转保留 |

## 统一工具链与效率

普通任务及会议工作沿同一规则：任务和当前边界 → 检索能力摘要 → 查看具体 schema → 运行时复核授权、连接修订和系统条件 → 原主机执行 → 标准结果/成果文件 → 持久回执或明确失败 → 按原任务身份恢复。

- 检索不再要求整句连续命中。“Linux Python”“PDF 转换”等可命中已存在的终端；不会把检索命中当成已安装。
- 每次检索只取一次目录快照，活动集合只计算一次，避免逐结果重复全量检查。
- 懒加载仍最多 16 个已激活 schema。新增显式 `replace_tool_id`，满额时可替换闲置 schema；先验证新项才替换，失败保留旧项，不取消在途调用，也不扩大权限。恢复时不会重新装回已替换的旧项。
- MCP 目录完整保留已启用且治理通过的工具与资源读取器，由激活预算控制按需输入。旧 64 项目录截断曾使后面的服务器不可发现，现已去除。80 个工具与 2 个资源读取器的定向用例验证全部可解析。
- MCP 首次/过期发现最多 2 路并发，保留用户顺序、运行控制器、取消和资源归属；失败落入原状态记录。没有凭空宣称每服务器有新增总时限。
- JSON-RPC/GraphQL 正常调用不再先多发一次发现/健康请求；显式检查和真正需要建连的协议仍沿旧路径。

签名响应链（Anthropic Messages、Gemini Interactions）沿既有稳定 schema 策略，不能在响应链中任意改变目录。本轮没有将其谎称为全部按需加载，也没有替它隐式删除工具。

## Skill 兼容与资源执行

| 来源/功能 | 当前范围 | 不能等价执行的部分 |
| --- | --- | --- |
| Agent Skills 标准核心包 | name、description、常用 frontmatter、正文及相对资源 | 轻量 YAML 子集，并非完整 YAML |
| Anthropic/Claude 基础 Skill | 符合核心格式的包、常用工具声明 | 缺省字段、动态上下文、变量替换等扩展未完整适配 |
| 腾讯 CodeBuddy | 常见核心包及 Read/Write/Edit/Glob/Grep/Bash/WebFetch/WebSearch 映射 | context:fork、hooks、disable-model-invocation、动态命令及专有产物协议 |
| WorkBuddy/OpenClaw | 标准 SKILL.md 核心 | requires/env/install 等专有元数据、SkillHub 私有服务接口 |
| 本地 ZIP | 单 Skill、脚本、references、二进制 assets | 多 Skill 仓库 ZIP 仍明确拒绝 |
| GitHub/Gitee/GitLab | 公共来源、固定提交及归档链 | 私库、自建域名及超出既有仓库大小/目录预算的情况 |
| CLAUDE.md/AGENTS.md 回退 | 文本规则转换 | 不迁移 sidecar，不等同原始完整 Skill 包 |
| 脚本型 Skill | 静态资源准备后可通过原终端解释器执行 | 系统依赖、解释器版本、厂商运行时语义仍需满足 |

`allowed-tools` 支持标准空格、旧逗号和 YAML 列表。确切可等价的厂商工具映射至 NEXA 对应项，仍与父运行权限取交集。命令级 Bash 限制、未知 MCP 表达式和未知工具不扩大成任意终端权限；显式空白名单也不当成无限制。不能等价的 fork/hooks/仅手动限制显示具体不兼容原因，不静默忽略。

新增 `skills_prepare_runtime` 只复制已启用 Skill 的静态文件到既有运行工作区 `.nexa-skills/<id>/<contentDigest>/`，返回真实 Android/Linux 路径、版本、摘要及数量。保留 8 MiB 单文件、32 MiB 总量、512 文件限制；拒绝链接、越界和目标篡改。只读 Skill 不因此获得写准备权限；复制不等于脚本执行、依赖安装或新增终端授权。

`skills_read` 与 `skills_read_resource` 现在能按 `offset` 分页，返回 `next_offset`、`offsetUnit`、`totalChars`、`truncated`。偏移单位为 UTF-16 code unit，默认 16K、每页最多 64K；不拆开 Unicode 代理对，拒绝非法或超界偏移，长文不再读到上限就无法继续。

## Hermes 的真实问题与两种连接

本机源码为 `C:/Users/29136/AppData/Local/hermes/hermes-agent`，只读 HEAD 为 `3b421578588243f920886e6fe79a6faa87ea026e`。Python PID 27728 监听 `0.0.0.0:9900`。电脑上两个公开卡片地址均返回 HTTP 200，name 为 TS，接口为 JSONRPC / A2A 1.0。不是已证实的“旧 0.3 协议”问题。

| 使用场景 | 卡片地址 | 明确任务地址 |
| --- | --- | --- |
| 手机与电脑同一局域网 | `http://192.168.1.7:9900/.well-known/agent-card.json` | `http://192.168.1.7:9900/` |
| Tailscale 远程 | `http://100.110.70.49:9900/.well-known/agent-card.json` | 可留空，跟随卡片的 `http://100.110.70.49:9900/` |

两份卡片均公布 Tailscale 任务地址。旧实现即使从 LAN 成功取卡片，发送也会转向 Tailscale。新增明确的可选任务地址，同手动卡片主机验证；已有连接可原位编辑，保留 ID、任务、停用态和凭据，留空 Token 不清旧值。可分别保存 LAN 与 Tailscale 连接；本轮未替用户写入实际连接配置、猜测 Token 或增加失败后跨地址自动重派。

Hermes 同步 `SendMessage` 会等待最终答复，而 NEXA 单次读取超时可能先到。本轮对声明 streaming 的对端使用 `SendStreamingMessage`，首任务句柄立即持久化，保持流到终态后查询完整结果。不能拿到句柄就关流：本机 Hermes 把客户端断开当作任务失败。仅明确 `-32601` 且尚未取得句柄才回退同步，未知结果不重发。输入/授权等待沿原 task/context 续接；artifacts、status.message 与末条 Agent history 共同投影结果。

定向检查额外暴露并修正了真实数据缺陷：Room `@Insert(REPLACE)` 更新 Agent 会级联删除其任务，更新任务又会级联删除防重放事件。两个父记录写入改为 `@Upsert`，保留原行及关联记录；没有通过放宽断言掩盖问题。

该修复阻止后续更新误删关联记录，不声称可以凭空找回此前已经删除的历史。

手机现场读取仅到网络层：当时没有 wlan0 地址，LAN 与 Tailscale 卡片请求均超时；已装 Tailscale 应用不代表 VPN 已连通。本轮不能据此宣称手机到 Hermes 任务已验收。需要同一局域网或实际可用的 Tailscale 路由后，再验证原连接配置；真实模型任务未发送。

入站授权主体改为凭证指纹；自报 `X-A2A-Peer-Id` 等头不决定身份。查询、取消、订阅、续接和共享 context 都校验属主。授权页按钮对应当前真实凭证；旧节点名、其他 Token 的权限不自动继承，轮换 Token 默认只读，原记录保留。

## DSH 的实际复用边界

已核对官方 dsh-Go revision `e0c96705f8efe92dafd07765ed1de5c1445a7fe4`。七种原生插槽、v1/v2 声明视图、表单、文件和媒体适配均有路径，桌面 Node/Cordis 逻辑仍在桌面主机运行。Android 不原样安装 Windows/Electron 插件。

本轮修复保存主机配置后旧会话持续失效、同 ID 面板热重载不重订阅、过期 Cookie 401 重连循环。Cookie 只在同源且实际发送的旧值匹配时清理，保留 Token 换取新 Cookie；403 不刷新，一次可见连接最多刷新一次，不重放 invoke。

官方协议的 `app` 是实际 App 版本，当前 NEXA 为 0.7.7；如 dsh-media 要求 1.0.2，会真实显示 needs-app。没有独立协议版本字段，不能冒充官方 App 版本绕过准入。模型的 DSH 工具目前仅 catalog/view；需要真实点击的 UI 动作不会伪装成模型自主操作。此边界已明确记录，不当成已经完成的模型动作平台。

## 定向检查与交付记录

- 首轮仅运行本批相关 30 类检查，186 项中 177 通过、3 失败、6 因 Windows 宿主符号链接条件不满足跳过。生产与测试源码编译通过。
- 查明两项 A2A 失败为上面的真实级联删除缺陷；第三项为新增受终端开关控制的工具未加入旧测试期望集合，补齐精确集合及关闭开关不可见断言。
- 只复验 3 个失败用例和 1 个因 MCP 目录修复更新的用例，4 项全部通过，用时 19 秒。没有重跑通过的整套测试；最终 180 项通过、6 项环境跳过，没有未关闭的执行失败。
- 原始证据：`build/1602-minimal-check.log`、`build/1602-check-initial/`、`build/1602-focused-recheck.log`、`build/1602-check-focused/`、`build/1602-check-summary.json`。旧 64 项测试改为完整目录测试，初轮历史保留。
- 一次最终 `:app:assemblePerformance` 成功，用时 2 分 11 秒；包身份、ARM64、非 debuggable、v2 签名、ZIP 16 KiB 对齐检查通过。沿用当前 Android Debug 开发证书以保留覆盖升级；v3 为 false，不是正式生产签名，也不把 ZIP 对齐当作逐库 ELF 对齐验收。
- APK：`C:/Users/29136/Desktop/NEXA-Capability-Audit-20261116/NEXA-v0.7.7-2026111602.apk`，64,495,025 字节；SHA-256：`8B6326E2F3D42C83D74891AEB38AB2DAED15F36B2F25BD23BEE17CC2E1B488CC`。
- 手机 OPPO PHY110（`1fe75f23`）`install -r` 返回 Success，系统确认 `2026111602 / 0.7.7-smooth`。lastUpdateTime 为 2026-11-16 01:47:01，firstInstallTime 仍为 2026-11-14 03:13:13；没有卸载、清数据或重启。
- 安装后主界面启动请求 `Status: ok`，已运行实例接收 Intent，进程 PID 14626。此处 TotalTime=0 是 Intent 送达现有实例，不是冷启动性能证据。本批不再做额外页面反复测试或发送真实任务。
- 包与设备证据：`build/1602-package.log`、`build/1602-signature.txt`、`build/1602-zipalign.txt`、`build/1602-badging.txt`、`build/1602-install.txt`、`build/1602-installed-version.txt`、`build/1602-launch.txt`、`build/1602-delivery.json`；桌面交付目录同时保留报告及定向检查原始记录。

### 后续仍需现场确认

手机 LAN/Tailscale 实际路由、Hermes 长任务在休眠/断网时的对端行为、用户真实 MCP/DSH/云服务账号、Skill 系统依赖与全部第三方扩展。未做跨进程后台 SSE 守护、腾讯私有扩展适配或多 Skill 仓库 ZIP 安装。不是通过一个编译结果宣布这些均完成。

## 官方依据及分项报告

- [Agent Skills 标准](https://agentskills.io/specification)、[Anthropic Skills](https://github.com/anthropics/skills)、[Claude Code Skills](https://code.claude.com/docs/en/skills)
- [腾讯 CodeBuddy Skills](https://www.codebuddy.cn/docs/cli/skills)、[WorkBuddy](https://www.workbuddy.cn/llms.txt)、[OpenClaw Skills](https://docs.openclaw.ai/tools/skills)
- [A2A 官方规范](https://a2a-protocol.org/latest/specification/)、[Hermes 固定版本 A2A 实现](https://github.com/NousResearch/hermes-agent/blob/b9cb7a243a0a76bf76287b75a1065fe1e851e391/plugins/platforms/a2a/adapter.py)
- [DSH 主机](https://github.com/ccch1mneyyy/dsh-Go/blob/e0c96705f8efe92dafd07765ed1de5c1445a7fe4/docs/plugins/hosts.md)、[原生协议](https://github.com/ccch1mneyyy/dsh-Go/blob/e0c96705f8efe92dafd07765ed1de5c1445a7fe4/docs/design/native-plugins-protocol.md)、[七种插槽](https://github.com/ccch1mneyyy/dsh-Go/blob/e0c96705f8efe92dafd07765ed1de5c1445a7fe4/docs/plugins/slots.md)
- 工程内分项报告：`build/1602-a2a-audit.md`、`build/1602-skill-audit.md`、`build/审计摘要.md`。这些保留各审计阶段证据；最终检查和交付以本总报告为准。
