# 2026-11-15 国内语音接入与项目首页重设

## 范围与实施

用户在1511安装后追加国内云语音接入、项目与文件首页再次设计。本批1512承接1511外观/排版/备份/语音整理，不动其他界面。手机只覆盖安装确认版本，不发送测试消息或调用云服务。

| 服务 | 识别 | 播报 | 配置与边界 |
| --- | --- | --- | --- |
| 百度 | 普通话1537、极速80001，16k单声道WAV≤60秒 | 官方音色ID、语速/音高/音量，120UTF8字节保守分段 | API Key/Secret Key，识别播报各自独立；极速需开通；无实时双工 |
| 讯飞 | 文件/实时听写、语种与方言、动态结果修正，≤60秒 | 音色/语速，原始16kPCM | App ID/API Key/API Secret；支持实时识别；按40ms帧节奏发送，终态/取消清理连接 |
| 腾讯 | 一句话识别、16k语种引擎、≤60秒 | 数字音色/语速/音量，按80码点分段 | SecretId/SecretKey及地域；TC3签名；本轮为录音后识别，无实时双工 |
| 小米 | 未接入 | 未接入 | 官方企业AIVS SDK未取得；用户最后更正没有SDK，不加假选项 |

保留千问、豆包、MiniMax、Gemini、本地和1511新加Azure。三家均复用现有录音、PCM24k播放与错误反馈链路；不会引入另一个播放器。凭据独立加密保存，所有新增凭据均触发完整备份加密要求。语音合成工具同步适配音色ID，避免只支持聊天按钮却漏工具入口。

## 项目首页

项目与文件仍单一入口，不强制双栏。首页直接显示项目新建/使用中与全部筛选、项目列表，以及文件导入/浏览/生成文件归档目录/自动归档/公共文件访问；常用配置不再藏在“独立工作区”二级页面。文件页面复用同一配置卡并提供导入/新建文件夹。保留项目文件范围、工作副本边界、initialFiles/initialPath/projectId、搜索/排序/子目录与内容搜索、产物、导出分享、重命名、复制移动、删除及预览。

所有者：ui/screens/projects/ProjectsWorkspaceScreen.kt、ProjectsScreen.kt、ui/screens/terminal/WorkspaceScreen.kt；不改其他路由和后台数据行为。

## 官方资料与接入条件

- 百度ASR官方REST示例：https://github.com/Baidu-AIP/speech-demo/blob/master/rest-api-asr/python/asr_json.py
- 百度TTS官方REST示例：https://github.com/Baidu-AIP/speech-demo/blob/master/rest-api-tts/python/tts.py
- 百度产品限制：https://ai.baidu.com/tech/speech/asr 、 https://ai.baidu.com/tech/speech/tts_online
- 讯飞听写：https://www.xfyun.cn/doc/asr/voicedictation/API.html
- 讯飞合成：https://www.xfyun.cn/doc/tts/online_tts/API.html
- 腾讯一句话识别：https://cloud.tencent.com/document/product/1093/35646
- 腾讯合成：https://cloud.tencent.com/document/product/1073/37995
- 腾讯签名：https://cloud.tencent.com/document/api/213/30654
- 腾讯官方SDK签名与请求模型：https://github.com/TencentCloud/tencentcloud-sdk-python
- 小米资质：https://developers.xiaoai.mi.com/api/doc/render_markdown/new/Qualification
- 小米Android SDK：https://developers.xiaoai.mi.com/api/doc/render_markdown/new/VoiceserviceAccess/Device/develop/SDKDocument/AndroidInstruction

百度ai-doc页面当次返回空正文，实际协议核验来自官方REST示例和产品页。腾讯产品页与SDK都实际读取；SDK签名源下载保存在build/speech-provider-research。小米并非无开放平台：企业申请AIVS SDK可接入纯ASR/TTS，但个人开发者不可直接获取，本轮没SDK。不得将技能平台、小爱Hook或电视反编译文件作为AIVS替代。

三家云账号真实调用均未验证，需用户填写有效凭据并开通对应服务与音色；代码、测试及安装成功不等于账户调用验收。

## 验证交付

仅选择BaiduSpeechTest、IflytekSpeechTest、TencentSpeechTest、PortableSpeechSecretsTest，连同assemblePerformance集中执行，日志build-1512.log。待最终结果与设备版本确认后追加证据。

### 1512检查、打包与安装已完成

一次集中执行BUILD SUCCESSFUL in 2m15s。BaiduSpeechTest 7例、IflytekSpeechTest 8例、TencentSpeechTest 5例、PortableSpeechSecretsTest 2例，共22例全部通过，失败/错误/跳过为0；未扩展测试或操作手机页面。腾讯签名fixture使用独立Python HMAC计算，对照官方SDK规范。

APK 0.7.7-smooth / 2026111512，64245225字节；SHA256 8EBE265D8A525803414046A607D2FD68C061064A1FECEB21793132B213921C4E。applicationId io.github.zhh_lgtm.nexus、ARM64、非debuggable。原开发证书SHA256 f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4保持；apksigner验证成功、ZIP16KiB对齐检查通过；不是正式生产签名或逐库ELF验收。

ADB 1fe75f23（OPPO PHY110）install -r返回Success，dumpsys确认2026111512 / 0.7.7-smooth；保留数据。桌面交付目录C:/Users/29136/Desktop/NEXA-Voice-Projects-20261115，包含1512 APK/SHA256/installation-1512.json，并保留1511 APK。无真实云账号请求验收。

用户随后要求继续网上寻找小米SDK/API，单独只读调查；1512不包含小米接入。

后续找到小米MiMo官方公网ASR/TTS，已按授权实现并以1513覆盖安装，见50_XIAOMI_MIMO_SPEECH_2026-11-15.md。小爱AIVS SDK边界仍成立，但不限制MiMo API，当前最新包是1513。
