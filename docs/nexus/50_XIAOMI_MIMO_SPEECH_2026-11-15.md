# 2026-11-15 小米MiMo语音接入

## 发现与授权

用户要求继续网上寻找小米SDK/API，找到就直接接入。公开小爱AIVS SDK未取得，但进一步核验发现小米MiMo官方开放平台提供可直接调用的语音识别与合成API，无需Android SDK。本批使用MiMo；不是将小爱Hook、反编译代码、第三方SDK镜像或小爱技能平台作为语音服务。MiMo支持个人/企业实名认证后充值，AIVS企业SDK限制不适用于MiMo。

## 接入范围

- 国内按量API https://api.xiaomimimo.com/v1/chat/completions，独立识别/播报API Key加密保存，密钥不出现在URL。
- ASR模型mimo-v2.5-asr，input_audio以Base64 WAV发送，语言auto/zh/en；官方编码后音频串上限10MB。其stream只表示返回文本流，不能作为实时麦克风上传，停顿自动发送/实时双工不启用。
- TTS模型mimo-v2.5-tts，朗读内容必须使用assistant角色，audio.format=pcm16、stream=true；官方示例注明24kHz PCM16LE mono，接原PCM播放器。SSE处理delta.audio.data、终态与音频完整性。
- 内置音色：mimo_default、冰糖、茉莉、苏打、白桦、Mia、Chloe、Milo、Dean。界面只展示真实支持的内置集合，不开放无法执行的任意音色ID或声音克隆/设计。
- ASR/TTS服务列表明确标为“小米 MiMo”，连接、音色、错误说明沿用本批统一组件，不修改其他界面。语音合成工具与完整备份敏感凭据判定同步接入。
- 国内按量Key与Token Plan/海外Key不可混用不同域名，本轮只支持国内按量账户。没取得真实Key，不做云调用成功宣称。

## 实际读取的官方来源

- 官方文档索引：https://mimo.mi.com/llms.txt
- 识别API：https://mimo.mi.com/static/docs/api/audio/Speech-Recognition-post.md
- 合成API：https://mimo.mi.com/static/docs/api/audio/tts-post.md
- 识别指南：https://mimo.mi.com/static/docs/quick-start/usage-guide/audio/Speech-Recognition.md
- 合成指南：https://mimo.mi.com/static/docs/quick-start/usage-guide/audio/speech-synthesis-v2.5.md
- 首次调用：https://mimo.mi.com/static/docs/quick-start/summary/first-api-call.md

## 最小验证与交付

拟1513，承接1512全部功能。只检查XiaomiMiMoSpeechTest和PortableSpeechSecretsTest并assemblePerformance；完成后追加结果及覆盖安装版本证据。不操作手机页面、发模型消息或请求收费云服务。

### 1513已打包并覆盖安装

一次集中执行BUILD SUCCESSFUL in 1m51s，日志build-1513.log。XiaomiMiMoSpeechTest 8例、PortableSpeechSecretsTest 2例，共10例全部通过，无失败/错误/跳过。覆盖官方请求、音色/Key/语言/音频限制、转写终态、SSE完整性、截断与过滤、奇数PCM采样、分段Unicode及新增凭据备份加密；不重复跑1512其他服务测试。

APK 0.7.7-smooth / 2026111513，64252409字节，SHA256 8D92C8062E03243FD81534CFF59591F2221A349365998A4868FDBB2EF44E284F。applicationId io.github.zhh_lgtm.nexus、ARM64、非debuggable；原开发证书SHA256 f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4一致。apksigner验证及ZIP16KiB对齐检查通过；不代表正式生产签名或逐库ELF验收。

OPPO PHY110（1fe75f23）install -r返回Success，系统确认2026111513 / 0.7.7-smooth。保留应用数据，无手机页面或云账号请求验收。桌面C:/Users/29136/Desktop/NEXA-Voice-Projects-20261115/NEXA-v0.7.7-2026111513.apk，附SHA256、installation-1513.json、文档和官方资料快照，保留1511/1512旧包。

本包已包含1511外观/排版/备份/语音首页、1512项目首页重设与百度/讯飞/腾讯、1513小米MiMo。账号调用需用户填写国内按量API Key；不宣称在线语音效果已实测。
