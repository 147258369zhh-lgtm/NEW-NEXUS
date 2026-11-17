# NEXA 云端 API 能力调研（2026-11-15）

调研日期：2026-11-15（Asia/Singapore）。范围：只读访问官方公开文档，核对端点、请求参数、返回结构与限制；没有执行收费 API、读取凭据、修改产品界面或实现新的云服务。本文件是候选能力与设计建议，不是功能验收报告。

## 1. 结论与证据边界

DeepSeek 当前官方文档确实提供托管网页搜索、网页抓取、独立 OCR、文件解析、图像理解、模型内容安全、ASR 与 TTS。它们不能与普通 function calling 混为一谈：普通 function calling 由客户端执行外部工具，而 DeepSeek 服务端联网工具由官方执行；另有可由应用直接调用的原始 Search/Fetch API。

官方总览：https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot

本次没有登录供应商控制台，因此没有验收具体账号的开通资格、余额、地域权限、模型权限、额度或实际响应。公开接口可供程序接入，不代表用户现有 Key 已获授权。价格和限制为本次官方页面读到的值，实施时应核对是否变化。

本次 DeepSeek 官方目录和已读 schema 中未查到图片生成、图片编辑或视频专用输入端点；不能据此声称其他供应商没有这些服务。阿里百炼官方文档有对应候选。

## 2. 当前已有代码

源码 `app/src/main/kotlin/io/github/mangi/eta/agent/web/ConfigurableWebBackends.kt` 已有：

- `searchDeepSeek`：第 33 行起。
- `fetchDeepSeek`：第 153 行起。
- DeepSeek 工具 URL 归一化：第 294–297 行，支持 `/v1/tools/{tool}`。

因此当前状态应描述为“搜索和抓取已有适配实现”，不能说只接了搜索。本次只核对函数存在，不将其等同于已用真实账号验证完整配置、调用链、计费和返回结果。实施新服务前先检查现有调用链，避免重复开发。

## 3. 关键能力矩阵

以下候选均为官方托管云 API。Android 可以直接通过 HTTPS 接入，供应商 SDK 不是必要依赖；阿里、百度、腾讯的完整云 SDK 不应仅因接入一个 REST 接口而加入。本次未全面审计工程依赖，不能宣称某厂商 SDK 已经存在。公开 SDK 可用性与当前工程依赖是两件事。

| 能力 | 官方接口或参数 | 鉴权、限制与费用证据 | 建议及状态 |
|---|---|---|---|
| 网页搜索与抓取 | DeepSeek `POST https://api.deepseek.com/v1/tools/search`、`/v1/tools/fetch` | Bearer API Key；搜索 query 1–128 个 Unicode 字符、count 1–50；Search 100 QPS、Fetch 25 QPS，按账号独立计；分别 5 元/1000 次成功且有结果的请求；Fetch 受网站可用性和访问策略影响 | 已有 `searchDeepSeek`、`fetchDeepSeek`，先核对实际调用链 |
| 对话自主联网 | `deepseek_web_search`、`deepseek_read_url`，在 `/chat/completions`、`/responses`、`/anthropic/v1/messages` 声明 | `deepseek-flash`；搜索/阅读由模型决定，不能用 tool_choice 强制指定；深度思考搜索约最多调用 20 次工具；需要保留并回传服务端工具结果 | 与 NEXA 明确调用原始 Search/Fetch 分开配置和适配 |
| 独立 OCR | DeepSeek `POST /v1/tools/ocr`，multipart `purpose` 和 `file` | Bearer；PDF、DOC/DOCX、PPT/PPTX、图片；100 MiB/1000 页；总时限 180 秒；并发 15；1.5 元/1000 个成功页；HTTP 200 后仍可能返回 error 对象 | 优先新增候选，可获取逐页文字、原始标签与坐标 |
| 文件问答解析 | DeepSeek `POST /files`，purpose 为 `text_recognition` 或 `rich_layout_recognition`，对话引用 file_id | Bearer；100 MiB/1000 页；上传 120 秒、提交后解析 120 秒；并发上传 15；单次最多 20 文档、文字合计 8 MiB；解析按页收费，引用按输入 token 收费；原件和解析结果不可下载 | 优先新增候选，用于基于文件回答；不能替代可导出结果的 OCR |
| 图像理解 | DeepSeek `deepseek-flash`，image_url/base64/file_id 内容块 | Bearer；JPEG/PNG/GIF/WebP；内联/URL 单图 32 MiB，file_id 单图 64 MiB；请求体 48 MiB，最多 600 图片，另有总大小与尺寸限制；按模型输入收费 | 截图解释、图表分析、照片问答；先检查现有模型适配是否保留图像块 |
| 内容安全 | DeepSeek `safety_check:{level:"high"}`；独立候选腾讯 `TextModeration` | DeepSeek 审核输入、输出及图片，安全 token 按输出 token 计费；拒答可在流式末尾出现。腾讯域名 `tms.tencentcloudapi.com`，TC3 签名、SecretId/SecretKey，文本最长 10000 字符 | 先考虑 DeepSeek 原生审核；跨供应商独立审核后续考虑腾讯 |
| 结构化文档与表格 | 百度 PaddleOCR-VL：`/rest/2.0/brain/online/v2/paddle-vl-parser/task` 与 `/task/query`；表格 V2：`/rest/2.0/ocr/v1/table` | `https://aip.baidubce.com`；API Key+Secret Key 换 access_token；文档解析异步，建议 5–10 秒轮询，提交 2 QPS、查询 5 QPS；返回 Markdown/JSON、行坐标、复杂元素；表格接口有行列及单元格坐标 | 复杂版面/表格增强候选，无需自行部署 PaddleOCR；百度 OCR 官方公开个人/企业认证资源 |
| 图片生成与编辑 | 阿里万相、Qwen 图像编辑；`POST /api/v1/services/aigc/multimodal-generation/generation` | 百炼 Bearer API Key；当前文档采用 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com` 等地域地址；Key、Workspace、地域不能混用；图像编辑单图不超过 10 MB，其他参数按模型选择 | 后续候选，不顺带修改绘画界面；DeepSeek 对应端点本次未查到 |
| 视频理解 | 阿里 Qwen 视觉模型，兼容 `/compatible-mode/v1/chat/completions` 或原生多模态接口 | 百炼 Bearer；地域、模型、视频输入、采样限制按官方模型文档；当前北京域名同上 | 后续候选；DeepSeek 视频专用输入端点本次未查到 |

补充传统 OCR 备选：百度高精度文字识别 `POST https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic?access_token=...`。它与基于模型的图像解释、复杂文档解析不是同一能力，适合作为明确文字识别任务的备用供应商。

## 4. 独立 OCR 与 Files 的区别

### 独立 `/v1/tools/ocr`

- 结果直接返回调用程序，不存入 Files，也不自动交给聊天模型。
- 返回 `pages[].index`、`pages[].text`、`pages[].finish_reason`。text 是模型原始输出，不做清理。
- 逐页识别文本可保留 `<｜ref｜>`、`<｜box｜>` 等标签；坐标按页面宽高归一化到 0–1000，可由 NEXA 定位文字或自行裁图。
- `finish_reason=length` 表示该页文字不完整。不能只因 HTTP 200 就认为成功，还应检查 error 与逐页完成状态。
- `text_recognition` 对不含图片的 Word/PPT 直接提取文字；其余逐页识别。`rich_layout_recognition` 对所有文件逐页识别。
- 客户端断开不等于取消，接收完文件后继续识别，成功页照常计费，未结束请求仍占用并发额度。

### `/files` + `text_recognition`

上传时完成解析，成功才返回 file_id；模型读取文字，文档图表、照片、示意图被丢弃。适合合同、纯文字论文和扫描文字材料的问答。

### `/files` + `rich_layout_recognition`

解析时自动裁出图表、照片、示意图，并与文字按原位置一起交给模型；不保证每张图都被裁出。裁出的图额外占用输入 token 与单次图片数量限制，适合财报图表、幻灯片和图文说明书。

Files 原件及解析结果不能下载，所以不能将 file_id 当作 NEXA 已保存的本地解析材料。解析与存储应分别管理。文件属账号，同账号各 Key 共享；默认永久有效，可设 1 小时至 30 天有效期，支持列出、查询和删除。文件问答应明确有效期、删除策略、历史引用失效后的恢复方案。

纯文本、XLS/XLSX、EPUB/MOBI 使用 `purpose=user_data`；表格/电子书转 Markdown；原文件最多 32 MiB、文字最多 8 MiB，这类上传不计费。PDF/Word/PPT 不能以 user_data 上传。文档 multipart 中 purpose 必须置于 file 之前。

## 5. 会话标题与语音归类

官方能力叫“会话标题”，不是“绘画标题”。没有独立标题 endpoint：在 deepseek-flash 的对话请求加入 `title:{enabled:true}`，模型回答时顺带生成；标题不计费，响应最多等待标题 5 秒；官方建议仅会话首轮开启，拒答或错误时可能没有标题。应由聊天逻辑处理现有标题状态，不将它列成独立云工具。

DeepSeek 官方语音也确实存在：

- ASR：`POST https://api.deepseek.com/v1/tools/asr`；完整音频转写，非流式；16 位 PCM WAV/Ogg Opus，最大 8 MiB、120 秒，并发 50。
- TTS：`POST https://api.deepseek.com/v1/tools/tts` 或 `wss://api.deepseek.com/v1/tools/tts`；HTTPS 音频流或 WebSocket 分段文本合成；stella/tide 两个音色；文本最多 10000 计费字符，汉字按两个字符计。

既有小米 MiMo、豆包、千问、百度、讯飞、腾讯、Azure 语音供应商本轮不重复实施。DeepSeek 仅列为可扩展候选，仍归原“语音”分类；不另造一个重复语音入口。

## 6. 后台配置与推荐顺序（尚未实施）

建议采用“供应商凭据档案 + 能力路由”结构，而不是把所有工具 URL 和 Key 混进聊天模型配置：

- DeepSeek 的一个凭据档案可被搜索、抓取、OCR、Files 等能力显式引用；工具 URL 不写入聊天模型 Base URL。
- 阿里档案包含 Workspace 与地域，百度档案独立记录 access_token 鉴权模式，腾讯档案记录 TC3 签名模式；均不要求增加 NEXA 自建后端。
- 各能力分别选择主供应商、备用供应商、超时、文件限制、费用策略；不因同一品牌支持聊天就自动认为该品牌全部工具已可用。
- 若将来用户批准“云能力中心”界面，可归为网页检索、OCR 与文档、视觉理解、图片生成编辑、内容审核。语音保持原分类，会话标题保持聊天辅助参数。本文件没有授权或实现新 UI。

推荐顺序：

1. 核对已有 DeepSeek Search/Fetch 配置与调用链。
2. 独立 OCR，先满足截图、扫描文档和可保存的文字结果。
3. Files 文档问答，分别支持纯文字和图文解析。
4. DeepSeek 图像内容块适配，结合现有视觉模型能力。
5. 原生安全/会话标题参数；拒答影响显示行为时遵守用户界面授权范围。
6. 百度复杂结构化文档和表格增强。
7. 阿里图片生成编辑、视频理解，按用户具体任务再确定实施范围。

## 7. 官方证据链接

### DeepSeek

- 总览：https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot
- 原始网页搜索/抓取（含 schema、计费、QPS）：https://api-docs.deepseek.com/zh-cn/guides/search
- Search API Reference：https://api-docs.deepseek.com/zh-cn/api/search
- Fetch API Reference：https://api-docs.deepseek.com/zh-cn/api/fetch
- 对话服务端联网工具：https://api-docs.deepseek.com/zh-cn/guides/web_tools
- OCR（含逐页结果、标签/坐标、费用、错误）：https://api-docs.deepseek.com/zh-cn/guides/ocr
- 文件解析：https://api-docs.deepseek.com/zh-cn/guides/document_parsing
- Files 管理、有效期、存储限制：https://api-docs.deepseek.com/zh-cn/guides/files_api
- 图像理解：https://api-docs.deepseek.com/zh-cn/guides/vision
- 内容安全：https://api-docs.deepseek.com/zh-cn/guides/safety
- 会话标题：https://api-docs.deepseek.com/zh-cn/guides/conversation_title
- ASR：https://api-docs.deepseek.com/zh-cn/guides/asr
- TTS：https://api-docs.deepseek.com/zh-cn/guides/tts
- Chat Completions schema：https://api-docs.deepseek.com/zh-cn/api/create-chat-completion

### 百度、腾讯、阿里

- 百度高精度 OCR：https://cloud.baidu.com/doc/OCR/s/1k3h7y3db
- 百度 OCR 个人/企业认证资源及价格：https://cloud.baidu.com/doc/OCR/s/9k3h7xuv6
- 百度 PaddleOCR-VL 托管解析：https://cloud.baidu.com/doc/OCR/s/7mh8u7ruk
- 百度表格识别 V2：https://cloud.baidu.com/doc/OCR/s/Al1zvpylt
- 腾讯文本安全 TextModeration：https://cloud.tencent.com/document/api/1124/51860
- 腾讯 API TC3 鉴权：https://cloud.tencent.com/document/api/866/33519
- 阿里万相生成与编辑：https://help.aliyun.com/zh/model-studio/wan-image-generation-api-reference
- 阿里 Qwen 图像编辑：https://help.aliyun.com/zh/model-studio/qwen-image-edit-api
- 阿里图像/视频理解：https://help.aliyun.com/zh/model-studio/vision
- 阿里 API Key、归属账号、地域：https://help.aliyun.com/zh/model-studio/get-api-key

验收状态：官方文档证据已整理；真实账号、调用效果、费用和设备端行为未验收。新增云 API 尚未实现。本次不修改 31 执行计划和 22 进度文件；数字助理修复信息由独立的 55 文档记录。

## 8. 补充三类能力：知识库检索、翻译、视频生成

补充调研日期同为 2026-11-15。本节只增加三类，优先选择有明确官方端点和参数的国内托管服务；没有重新调研前文 OCR、网页等能力，没有执行收费请求。这三类都有阿里百炼官方 HTTPS API，可从 Android 直接调用，不要求自建后端或安装完整厂商 SDK。实际账号权限、额度、费用与结果仍未验收。

以下北京地域端点的主机统一为 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com`，鉴权均为 `Authorization: Bearer <百炼 API Key>`。Workspace、Key、模型和地域应匹配，不能与其他地域混用。

| 补充类别 | 已核实官方托管接口 | 参数与限制证据 | 接入价值与边界 |
|---|---|---|---|
| Embedding 向量化 + Rerank 重排 | 向量：`POST /compatible-mode/v1/embeddings`；重排原生：`POST /api/v1/services/rerank/text-rerank/text-rerank` | 示例模型 `qwen3.7-text-embedding`，最多 20 条、每条最多 128000 token，维度可配置，按输入 token 计费；`qwen3.7-text-rerank` 最多 500 文档，单条最多 30000 token，请求总量建议不超过 120000 token，query 最多 4000 token；其他模型限制不同 | 对知识库先分块向量检索，再对候选块重排。托管模型 API 不等于已有托管知识库：文本分块、索引存储、更新/删除、模型与维度版本仍由 NEXA 的知识库链路管理。可以云端计算向量并在本地保存索引，保持无需自建服务器 |
| 专门翻译 | Qwen-MT：`POST /compatible-mode/v1/chat/completions` | 模型包括 `qwen-mt-plus`、`qwen-mt-flash`、`qwen-mt-lite`；仅 user 消息作为待翻译内容；`translation_options.source_lang`、`target_lang`，源语言可为 auto；支持术语 terms、领域 domains；可流式输出，但 plus 与 flash/lite 的输出增量方式不同 | 是专门翻译模型，通过聊天形状的协议调用；仍应作为翻译能力路由，不覆盖普通聊天配置。不是已接入的通用模型自动具备了这些专用翻译参数 |
| 视频生成 | 万相文生视频/图生视频：`POST /api/v1/services/aigc/video-generation/video-synthesis`；查询：`GET /api/v1/tasks/{task_id}` | 必须有 `X-DashScope-Async: enable`；异步创建后轮询，通常 1–5 分钟；本次文生视频页新版协议仅支持 wan2.7，示例 `wan2.7-t2v`；prompt 最多 5000 字符，时长 2–15 秒整数，720P/1080P；任务 ID 和结果链接均有效 24 小时。图生视频模型及参数需按专页选择 | 与“视频理解”分开，生成新视频而非理解已有视频。需要保存任务 ID、恢复轮询、成功后及时保存 MP4；创建请求结果不确定时不能盲目再次创建收费任务。图生视频可作为后续可选输入模式 |

重排兼容协议特别注意：官方列出的地址是 `/compatible-api/v1/reranks`，不是 Embedding/Chat 的 `/compatible-mode/v1`。仅 qwen3-rerank 和 qwen3.7-text-rerank 支持这一重排兼容接口；gte-rerank-v2 和 qwen3-vl-rerank 不支持。优先使用上表原生重排端点可避免混淆。不要推荐已公告下线的旧 `gte-rerank`。

本节推荐顺序：在前文 OCR/文件问答链路明确后，先考虑 Embedding + Rerank 增强现有知识库检索；翻译按用户具体需求接入；视频生成最后按明确创作需求接入。三类各自有独立能力路由，不能直接塞入原聊天模型 Key/地址字段。若未来扩展界面，知识库检索、翻译、视频生成的入口或设置都须先获得用户明确授权；本节仅记录建议。

补充官方来源：

- 阿里向量化使用指南：https://help.aliyun.com/zh/model-studio/embedding
- 阿里文本向量 API、模型维度与批量限制：https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api
- 阿里重排 API、模型限制与原生/兼容端点：https://help.aliyun.com/zh/model-studio/text-rerank-api
- 阿里 Qwen-MT 翻译 API：https://help.aliyun.com/zh/model-studio/qwen-mt-api
- 阿里万相文生视频 API、新版协议、异步任务及有效期：https://help.aliyun.com/zh/model-studio/text-to-video-api-reference
- 阿里万相图生视频 API：https://help.aliyun.com/zh/model-studio/image-to-video-api-reference

补充验收状态：仅完成官方公开文档调研和记录，没有实施 Embedding、Rerank、翻译或视频生成，也没有验证当前工程是否已有相关适配。
