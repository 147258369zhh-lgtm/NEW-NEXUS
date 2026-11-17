# 2026-11-16 模型、在线图片与 A2A 变更证据

本页保留子任务冻结时的调查记录。“未运行 Gradle”等措辞只描述子任务自身；随后主任务的统一检查、修正与交付以 [63 号实施记录](nexus/63_RUNTIME_MODELS_MEMORY_UI_2026-11-16.md) 为准。

## 处理模型选择崩溃

用户纠正：报告来自 DeepSeek 官方 API 的“模型服务商 → 模型策略 → 处理模型”选择过程。没有发送任何模型请求。

查到的确定代码故障链：`RuntimeConfigRepository.setModelRoleSettings` 把未指定用途写成 `JSONObject.NULL`；`settings.model_roles` 的解码此前用 `optString`，把 JSON null 转为字符串 `"null"`，随后对不存在的模型 `"null"` 做 `requireNotNull` 验证。选择器的 `scope.launch` 未捕获保存异常，因而可造成应用崩溃。这与 DeepSeek 官方 API 请求本身无关，其他供应商也会遇到同一故障。

现将模型角色 JSON 字段只从真实字符串读取；回滚路径同样修复。保留原先模型存在/启用/供应商归属校验。界面保存异常会报告错误，不结束进程。检查了已保存角色的容错解析以及 picker 的状态：当前 role target 使用 `remember` 内存枚举，不把该枚举序列化；数据读取已有无效值回退。本次不虚构手机异常栈：ADB 没有连接设备，未获得崩溃日志。

回归类：`RuntimeConfigRepositoryTest.selectingOneProcessingModelKeepsUnassignedJsonNullRolesEmpty`。

## 在线文生图

入口在“模型服务商 → 在线图片模型 → 文生图接口与模型”。使用单独 API Key，写入原有加密 cloud credential profile；API Key 不进入普通设置，完整凭据备份沿用加密备份路径。

支持明确的同步 OpenAI Images-compatible 协议，POST 到用户配置的 `/images/generations`。API base 必须包含供应商需要的版本路径（例如 `/v1`），也可填写完整生成地址。发送配置的 model、prompt、n（1–4）及可选 size；默认不发送 response_format，供 GPT 图片模型使用。只有用户指定且供应商支持时发送 b64_json 或 url。不宣称兼容所有图片接口，不支持此入口的异步任务、编辑或 SVG。

运行路径：`AgentCloudTools` 的原有会话/会议宿主 → `CloudMediaTools` → `OnlineImageGeneration` → 原有 `saveOutput`、`RunArtifactCapture`、artifact ledger。结果进入当前普通绘图/会议成果，而非仅新增设置字段。

HTTP JSON Content-Type、响应总量（32 MiB）、每图（最多16 MiB且受配置限制）、输出数量、base64、PNG/JPEG/WebP 魔数及解码尺寸（边长≤8192，总像素≤33,554,432）均校验。URL 结果只做匿名公开 HTTPS 下载，不传 API Key；禁私有/loopback/metadata DNS 结果及不安全重定向。结果沿用同一成果收据。

收费提交复用 `CloudRequestLedger`，同一 scope/request_id 恢复不会重新提交。传输无自动重试/重定向，one-shot POST 防止 OkHttp 跟随 503 再次发送。结果未知或保存中断时保留提交记录，不能静默再次收费。没有向真实供应商发送付费请求；HTTP 测试使用内存拦截器。

公开只读来源（实际读取于本次，固定官方 SDK commit `3dae6090cc31cc64a437fe0bcee740b6d81ed2cc`）：

- https://github.com/openai/openai-python/blob/3dae6090cc31cc64a437fe0bcee740b6d81ed2cc/src/openai/resources/images.py ：POST `/images/generations`、字段映射。
- https://github.com/openai/openai-python/blob/3dae6090cc31cc64a437fe0bcee740b6d81ed2cc/src/openai/types/image_generate_params.py ：response_format 为 url/b64_json；GPT 图片模型不支持该字段，默认 base64；n、size。
- https://github.com/openai/openai-python/blob/3dae6090cc31cc64a437fe0bcee740b6d81ed2cc/src/openai/types/image.py ：`data[].b64_json` 与供应商支持时的 `url`。

以上为官方 OpenAPI 生成 SDK 类型与实现。官方 docs 页面抓取返回 HTTP 403，未把失败页面当证据。实际源码通过 api.github.com 读取并确认同一 commit。接口实现仅据已核实字段，不假设未验证的服务商扩展。

回归类：`OnlineImageGenerationTest`（配置启用、协议、base64成果收据、重复提交与未知结果防重复计费、非法图片与URL）。需要 root 统一运行，子任务未启动 Gradle。

## A2A 精细授权

保留原有只读/受限预设和持久化键，增加 custom 精确工具清单编辑。新/轮换 token 仍默认只读，授权继续绑定 token SHA-256 指纹，不采信调用方自报节点名。旧记录不自动迁移到当前 token。

具体工具编辑仅放在 A2A 授权页。工具列表使用本地固定可配置工具与本地 effect 分类；不接受通配符或调用方伪造 descriptor，权限设置/自动化/子代理/高风险工具不能靠此界面开启。界面显示工具本地 effect 类别；不声称这是另一个独立 effect 授权选择器。

运行时原精确 allowlist 同时限制工具目录/调用；`NexusExecutionGate` 在每次真正调用前查询原入站任务身份、原始 grant 与当前 grant，取交集并检查任务仍为 WORKING。撤销/任务结束拒绝后续调用；扩大当前授权也不会扩大已接受任务。原有 Android 权限、全局审批和远端高危阻断继续生效。

现有 A2A 入站任务没有可用的 project binding 或独立 effect scope owner，本次不提供虚假的项目/效果开关。任务的已接受精确工具范围及 token 身份由本机记录控制。

回归类：`A2AAccessPolicyGranularTest`（持久化、轮换与旧预设、不接受通配符/提权工具、撤销复核与不扩大原任务）；原有 `A2AInboundCrossProcessConfigTest`、`A2AInboundTaskCoordinatorTest` 与 `NexusExecutionGateTest` 需统一回归。

## 验证边界

未运行 Gradle、构建、安装、真实 API 请求或 Git 写操作。当前环境未连接 ADB 手机，真实手机选择器恢复、图片输出与 A2A 跨进程撤销需后续验证。不改变 applicationId 或持久化数据身份。
