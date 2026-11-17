# 会话与会议室媒体、网页作品和浏览接管（2026-11-15）

本轮由用户明确授权此前方案全部实施，另修会议室误用聊天文件夹及列表明度层级。用户随后明确表示底部拖动彩光已知原因、不必处理，本轮未修改该系统反馈。

## 实施内容

| 链路 | 结果与主要所有者 |
| --- | --- |
| 模型和工具媒体输出 | 新的`AgentArtifactReference`保存多成果ID、名称和MIME；事件/Wire/会议工具元数据保留原单产物字段兼容历史，首个媒体消息ID不变。聊天与会议均投影所有成功登记的成果，会议SETTLED后仍保留媒体引用。模型内嵌图片/音频/视频落成已验证文件，纯媒体回复不再被当作空回复。所有者：`ProviderMediaOutput`、`AgentModelClient`、provider适配、`RunArtifactCapture`、`AgentRunMessageProjector`、`MeetingConversationTimeline` |
| 媒体类型与历史 | MIME规范化、别名和有界文件头识别替代只看显示名后缀；旧FilePart可在读取时分类为图片/音频/视频/网页，不改历史ID和正文。SVG进入网页作品预览。所有者：`NexusArtifactMedia`、`NexusArtifactAccess`、`NexusContentPartRenderer` |
| 原生媒体展示 | 图片点开缩放、保存；音频/视频点播后才创建播放器，支持暂停、进度、视频静音/全屏及保存分享。后台暂停、离开条目释放；仅播放时更新进度，同一时刻切换播放会暂停前一媒体。消除历史视频自动prepare及闲置音频/视频持续轮询。图片工具栏修正层级避免被图片盖住。所有者：`NexusMediaPlayback`、`NexusContentPartRenderer`、`NexusImagePreview` |
| 用户发送的附件 | 会话与会议共用`UserMessageBlock`：发送图片可放大，普通可读文件中的音频/视频可播放和SAF保存，HTML/SVG可预览。目录、非媒体或不可读文件仍保留原文件标签。只用普通应用读权限，自身私有目录仅放行workspace；不借预览执行Root。所有者：`SentMediaAttachments`、`ChatMessageItem` |
| HTML/SVG作品 | HTML/SVG代码块有预览入口，裸完整网页文档和对应成果文件可进入独立作品窗口；HTML/CSS/JS/Canvas按网页执行，提供源码、ZIP保存分享。打开后生成静态缩略图供消息卡后续显示，不在每条消息中常驻WebView。所有者：`NexaWebArtworkStore`、`NexaWebArtworkActivity`、`MarkdownContent` |
| 作品资源与登录隔离 | 作品窗口独立`:artwork`进程、独立WebView数据目录，每个作品版本独立HTTPS来源，不接入Agent JS桥或网站登录Cookie；禁止file/content访问、网页媒体权限与混合内容。依赖仅从同run、同subject的已验证成果映射，读取常见静态HTML属性、CSS URL/import和JS import/fetch/new URL资源，限制实际字节与数量，临时目录完成后按内容版本发布。外部网页链接经确认转内置浏览器，未验证的网页不能读取任意私有目录 |
| 网站登录与Agent接管 | 复用系统WebView，不捆绑另一套Chromium。新增`browser_use(open_for_user)`从原工具审批链打开同会话页面，不重载表单，提示通过`request_user_input`等待；用户接管期间阻止Agent网页动作。退出后可继续操作同会话。初始URL只接受正常HTTP(S)，异常导航反馈失败；保留同浏览器Cookie，关闭本地文件访问，启用安全浏览。所有者：`AgentBrowserSession`、`BrowserSessionRegistry`、`AgentBrowserActivity`、工具schema/prompt |
| 网站上传与媒体授权 | 系统文件选择、多选和accept过滤，显式拍照/录像/录音上传。网页相机/麦克风限当前顶层同HTTPS来源，逐次用户确认并请求Android权限；跨源、隐藏、未知资源拒绝，导航/撤销/销毁清理旧回调。所有者：`BrowserInteractionController`、`BrowserUserInteractionHost` |
| 二维码 | 浏览器内相机扫码与相册识码，使用ZXing而非额外浏览器引擎，不要求Google服务。识别结果先展示确认；仅有效HTTP(S)地址可继续打开，非网页内容只展示。相册先采样再解码以限制内存。所有者：`BrowserQrDecoder`、`BrowserQrCaptureActivity` |
| 远程结果 | 新增显式`download_media`工具：匿名HTTP(S)逐跳校验、流式预算/长度/可选SHA256核对，成功才登记成果；不使用浏览器Cookie或认证，不扫描正文隐式下载。运行配额允许时，为启用MCP服务器提供显式资源读取适配，经原服务器快照/撤权/审批调用`resources/read`后把真实内容登记为成果。`resource_link`本身仍只是链接，不伪造成已下载文件 |
| 会议文件夹隔离 | 上一轮误共用聊天文件夹树及管理回调。现有独立会议文件夹定义、归属、创建、子目录、移动、置顶、改名及删除；原错误聊天目录关联迁回会议普通组，聊天目录及记录不受影响。宿主按subject校验对应文件夹，会议不再通过聊天仓库验证。所有者：`ConversationPaneOrganizationStore`、`ConversationPaneOrdering`、`ConversationPanePanel`、`AgentAppRoot` |
| 列表视觉层级 | 浅色侧栏底层浅灰白，普通条目更白净，保留运行/选中色和两种皮肤的透明度区别；深色维持原关系。彩色拖动光未修改 |

## 使用方式

- 让模型生成“鹈鹕骑自行车”的HTML/Canvas作品后，点代码块或作品卡的“预览”，进入软件内部查看动画；顶部可查看源码、保存或分享作品ZIP。模型写文件时应将相关资源同批登记，避免丢失依赖。
- 遇到网站登录，Agent可调用`browser_use(action="open_for_user")`打开原页面，用户自己登录并返回确认，再继续运行；二维码入口在浏览器菜单中。
- 远程媒体链接需要模型显式调用`download_media`或对应MCP资源读取；下载为可信成果后会显示媒体组件。纯文字URL不会自动变成本地文件。

## 实际边界

- 媒体解码取决于Android/Media3与设备支持，不能把“image/audio/video类型可展示”理解成每一种编码都可解码。网络媒体和CDN脚本仍依赖网络；缺失、动态计算或未登记的本地依赖不能凭空恢复。
- 作品主源码最大2MiB，单资源32MiB、资源总量96MiB、最多192个候选；模型内嵌媒体沿可信捕获限制（单件8MiB、总16MiB、最多8件）。显式匿名下载默认8MiB、可设到32MiB。
- 网页作品不是网站登录浏览器；不共享网站登录态。禁止WebView或依赖弹窗opener通信的网站不保证内嵌登录兼容，外部浏览器Cookie也不会自动导入。
- 正常HTML/CSS/JS/SVG/Canvas可运行；需要构建服务器、Node后端或无法取得的依赖，应先由Agent生成可运行的前端产物。
- MCP普通工具优先使用每次运行64项配额，资源读取适配仅使用剩余额度；已满时不会挤掉用户工具。读取器使用保留命名域，避免与服务器同名工具相互过滤；不以空的资源列表推断不支持读取，实际不支持时只失败该次资源调用，不禁用普通工具。
- 本轮不操作手机导航，不发模型消息、不做相机/网站登录测试；安装成功不代表这些设备交互已经验收。

## 定向验证与交付

本轮先运行媒体、网页路径、浏览接管/来源授权、文件夹隔离及相关工具投影的106项定向用例，103项通过、3项失败。排查后补齐工具目录测试的既有项目能力期望、API连接测试的持久化规范配置，以及媒体测试的真实工具开始事件；生产保留原授权校验，另修复了完整事件重放时附件排到工具记录之前的问题。只重建本次完整事件可恢复的附件，缺事件的旧附件和其他运行保持原状。

MCP复核另修读取器与服务器同名工具冲突及配额挤占，补3项命名、64项配额和资源能力回归。仅重跑受影响的5类48项用例，47项通过；最后一个API二进制附件用例进一步改用持久化后已规范的GET配置，单项复跑通过。三轮合并为109个唯一用例，无未关闭失败，**不是一次全量测试，也没有重复执行全部106项**。记录：`build/media-browser-test-consolidated-20261115.json`。

最终生产源码冻结后，`:app:assemblePerformance`成功，耗时1m34s；日志`build/media-browser-20261115-assemble.log`。最后的单项测试只修改测试夹具，生产源码与安装包一致。版本为**0.7.7-smooth / 2026111505**，应用ID保持`io.github.zhh_lgtm.nexus`，R8及资源收缩开启，非Debug，沿当前开发证书支持保留数据覆盖，不作为正式生产签名发布。

APK大小**62,926,621 bytes**，SHA-256：`78A13F1D731F38A03D82B437B843095F32DFE308FA7B3CD92059866E5D6F5AFD`。包身份、签名与16KiB ZIP对齐通过；未据此声称本轮逐库ELF或真实网页/媒体兼容性全部验证。OPPO PHY110（`1fe75f23`）覆盖安装返回Success，系统包信息确认1505，未卸载或清数据，未操作手机界面。

桌面交付目录：`C:/Users/29136/Desktop/NEXA-媒体与内置网页-2026-11-15`，安装包`NEXA-v0.7.7-媒体与网页预览-2026111505.apk`，附哈希、安装确认、定向回归记录和本说明。安装报告：`build/media-browser-verification-20261115.json`。后续验收以实际图片/音视频、作品资源、网站登录/上传及二维码交互为准；不宣称全软件所有问题已清零。
