# 2026-11-15 外观、消息排版、语音与设置层级整理

## 授权范围

用户批准本批备份/项目分区、语言迁移、消息排版固定实时预览、外观效果与手势选项、语音首页层级和可用云服务扩充。仅修改这些模块及其必要消费者；保留其他界面、applicationId、既有数据与签名，不上传GitHub。按今日每批同步要求覆盖安装，不操作手机页面或调用收费模型。

## 已实施

- 备份中心按“手动与配置”“自动计划”划分。内容/格式/目录/保留与目录密码在公共配置，自动页只保留计划、执行条件与状态。手动导出逐次密码不混用目录密码。
- 项目与文件取消强制双栏：项目首页提供项目及独立工作区入口，文件/副本在深入页，保留原文件范围与回调。
- 语言移入外观与主题，设置搜索仍可定位；纯黑背景独立于Monet，在深色模式生效。
- 消息排版页顶部固定真实渲染预览，正文/思考/代码/表格样例随参数变化；小屏可收起，不申请系统悬浮权限。新增正文字重、正文/思考字距、代码行高，接实际消息渲染。拖动只更新预览，释放后后台顺序保存，离页后写入继续完成。
- 效果增加按压回弹开关、玻璃浓度65–125%、背景柔化50–200%、折射开关；手势增加上部模式滑动开关及32–96dp距离。新值归一化、默认兼容旧设置；不改变原上下40/60区域和左右侧栏分工。
- 语音首页直接提供识别开关/服务选择、停顿发送、纠错、TTS服务、聊天自动朗读、助手播报、连续与双工。复杂连接凭据/音色保留详情，播报详情不再分音色/试听页；识别详情保留服务/唤醒/声纹。
- 新增Azure Speech实际REST实现：识别上传16kHz单声道PCM WAV，最长60秒；TTS发送转义SSML，接24kHz PCM播放链路。识别与播报密钥独立加密保存，校验地域/语言/音色/密钥，密钥不进URL。Azure-only凭据也触发完整备份加密要求。
- 停顿自动发送及实时双工按识别能力限制；Azure和千问离线服务录完转写，不伪装实时识别；保留偏好及原有服务默认配置。

## 云服务边界与官方资料

原有千问、豆包、本地服务保留，播报还已有Gemini、MiniMax。此次只新增能核验请求契约的Azure，不增加不可执行占位项。

- Azure短音频识别：https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-speech-to-text-short
- Azure合成：https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech
- Azure地域：https://learn.microsoft.com/en-us/azure/ai-services/speech-service/regions
- 小米小爱开放平台：https://developers.xiaoai.mi.com/

小米确有语音服务与SDK入口，但本轮未取得适用于第三方App的完整公网请求/鉴权契约，未添加小米选项。需要后续核验资质与SDK；不能表述为“小米没有开放”。Azure真实账号调用需资源密钥，本轮不作云端成功宣称。

## 最小检查与交付

版本计划0.7.7-smooth / 2026111511。集中选择AppearanceSettingsTest、AppearanceSettingsRepositoryTest、MessageTypographySettingsTest、NexusGlassMaterialTest、AzureSpeechTest、PortableSpeechSecretsTest，同时assemblePerformance。完成结果及安装证据随后追加。

首次集中执行1m57s，26例中3失败：Azure Content-Type的codecs参数含斜杠但未加引号，OkHttp拒绝构造请求；另两例旧测试预期OMNI/FROST_GLASS保留，与现有两皮肤迁移规则不符。修参数并补其解析断言，测试按经典/液态玻璃迁移验证；同时清理整体外观更新后的原始快照，避免缓存未迁移皮肤。仅重跑相同6类及原打包任务。首次日志build-1511.log，最终日志build-1511-final.log。

### 1511 已完成并安装

最终集中检查与打包BUILD SUCCESSFUL in 1m25s，26例失败/错误/跳过均0。APK 64210465字节，SHA256 0BA50BC76A8F084EFB1F0351558B4AE438FD8E3BA52799A60E8FBBBBED33DC8D。原开发证书SHA256 f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4保持；v2通过、v3未开、ZIP16KiB对齐通过，不是生产签名或逐库ELF验收。aapt不支持当前中文路径，复制到F:/Temp/NEXA-1511.apk后确认applicationId、1511、ARM64及非debuggable。

OPPO PHY110（1fe75f23）install -r返回Success；dumpsys确认2026111511 / 0.7.7-smooth。保留数据，未导航手机页面或调用云语音。桌面C:/Users/29136/Desktop/NEXA-外观排版与语音-2026-11-15保存APK、SHA256和安装证据。旧1510保留。

最终交付统一放在ASCII路径C:/Users/29136/Desktop/NEXA-Voice-Projects-20261115，其中保留NEXA-v0.7.7-2026111511.apk；以上最初中文目录未在最终文件核对中找到，因此不再作为最终交付指针。

用户随后扩展国内提供商与项目首页重设，继续实施1512；1511不包含后续百度/讯飞/腾讯或项目首页第二次重设。
