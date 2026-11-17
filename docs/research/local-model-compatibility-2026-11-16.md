# 本地模型兼容与语音运行时核验（2026-11-16）

本页记录子任务的研究与兼容边界；随后主任务的统一检查与交付以 [63 号实施记录](../nexus/63_RUNTIME_MODELS_MEMORY_UI_2026-11-16.md) 为准。

## 面壁 MiniCPM

- 新增目录项采用 OpenBMB 官方 `MiniCPM4-0.5B-QAT-Int4-GGUF`，固定 revision `4d70679dbea99c0dfa7bef0c6fa1bffc25997246`。文件 `MiniCPM4-0.5B-QAT-Int4_gptq_aware_q4_0.gguf`，265,307,040 字节，SHA-256 `fa4ad3f448355578ce5e4021204be319e5a3cb665fb173607f92bc139c96a290`。
- 官方模型卡明确提供 llama.cpp 使用方法。公开仓库 API 的 GGUF 元数据为 `architecture=minicpm`、上下文 32,768、ChatML 模板、BOS `<s>`、EOS `<|im_end|>`。原始 tokenizer 配置为 `LlamaTokenizer`、`add_bos_token=true`；通过 GGUF 内置词表与模板交给 llama.cpp，不拼接另一个模型的提示词。
- 当前 APK 的 `libiris_llama.so` 包含 `minicpm`、`minicpm3` 架构与 `hasChatTemplate` 元数据。加载 MiniCPM 时同时检查 GGUF 架构、完整词表、模板、单文件布局，以及引擎实际识别的聊天模板。这里是引擎与格式核验，尚无手机实测、质量或速度结论。
- 此目录项仅声明纯文本输入输出。虽然原生库有 mtmd 符号，应用现有冻结 JNI 只暴露文本生成；MiniCPM-V、MiniCPM-o、MiniCPM-ASR、视觉投影文件、BitCPM、自定义 GPTQ/MLX/RKLLM 与裸 Safetensors 不能据此声明可运行。没有对应的 LiteRT-LM 转换包，不能把这些格式改扩展名加载。
- MNN 3.6.1 的独立目录导入路径保留。读取核验了 MNN 官方组织 `taobao-mnn/MiniCPM4-0.5B-MNN` 的 `config.json` 和 `llm_config.json`：包含 llm 图、外置权重、tokenizer、Jinja ChatML 模板。导入和运行前现在校验完整的包内文件引用；MiniCPM 目录还必须有 Jinja 模板。没有新增未核实大小或校验值的 MNN 下载目录项。

公开只读来源（未下载模型权重）：

- https://huggingface.co/openbmb/MiniCPM4-0.5B-QAT-Int4-GGUF
- https://hf-mirror.com/api/models/openbmb/MiniCPM4-0.5B-QAT-Int4-GGUF?blobs=true
- https://hf-mirror.com/openbmb/MiniCPM4-0.5B-QAT-Int4-GGUF/raw/main/README.md
- https://modelscope.cn/models/OpenBMB/MiniCPM4-0.5B/resolve/master/tokenizer_config.json
- https://modelscope.cn/models/OpenBMB/MiniCPM4-0.5B/resolve/master/config.json
- https://hf-mirror.com/taobao-mnn/MiniCPM4-0.5B-MNN/raw/main/config.json
- https://hf-mirror.com/taobao-mnn/MiniCPM4-0.5B-MNN/raw/main/llm_config.json

固定版本 GGUF 的国内下载地址 HEAD 返回 200、`Content-Length: 265307040`、`Accept-Ranges: bytes`。直接 Hugging Face 与 raw.githubusercontent.com 网络不稳定，因此 API/模型卡的读取使用国内镜像和 OpenBMB 的 ModelScope 仓库交叉核验。

## 此次修复

- GGUF 导入原来只检查四字节 magic；现在有界读取元数据，拒绝截断头、危险长度、分片与投影文件。只扫描元数据，不加载张量。
- MNN 原来仅凭 `config.json + llm.mnn` 判完整；现在确认外置权重、tokenizer、llm_config 及包内安全引用。多模型目录不再静默任选一个。
- MNN 预热原来仅有协程超时，无法终止阻塞 JNI；增加独立取消看门狗。三个文本引擎和语音后端在加载阶段尊重协程取消。GGUF GPU 首次图失败转 CPU 后，停止回调现在指向当前引擎。
- 一次性 MediaPipe ASR 原来只返回最后一段；现在和流式路径共用时间段累积器，保留前面的最终结果。
- MediaPipe/Sherpa 的 ASR 统一在原生调用前限制 PCM 总长度和完整采样；Sherpa 在线识别原来缺少此限制。TTS 限制无效采样率、超长输出与空输出。
- 本地语音模型成功使用后按空闲时间卸载；排队请求仍算持有者，空闲卸载不会取消正在录音或播报的任务。
- LiteRT 语音模型设置原来展示不生效的 LLM 上下文/采样参数；改用既有标准设置项展示实际使用的后端与空闲卸载时间。ONNX 语音仍展示真正生效的 CPU 线程数。
- Kokoro 声线家族不再依赖随机下载记录 ID；用已核实目录的文件身份识别。选择另一个本地 TTS 时清除前一模型的声线 ID；设置页仅在该模型确实就绪时查询它的声线，避免打开设置意外加载另一个大模型。

新增回归：`GgufModelMetadataTest`、`LocalSpeechInputBudgetTest`，扩展 `MnnModelPackageTest`。本子任务未运行 Gradle、构建、安装或 Git 写操作；主任务统一验证。手机未连接，原生推理与实际停止时延仍需设备验收。
