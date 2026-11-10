package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 将当前已配置的在线或本地语音合成能力暴露为受控 Agent 工具。 */
internal object AgentVoiceToolCatalog {
    const val SYNTHESIZE = "voice_synthesize"
    val names: Set<String> = setOf(SYNTHESIZE)

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = SYNTHESIZE,
                description = "把文本合成为语音 WAV 文件，并使用语音设置中选择的服务和音色。需要生成有声内容、朗读稿、通知语音或导出音频时调用。默认保存到 Nexus 的音频目录；返回可直接分享的绝对路径。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("text", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 10_000)
                                .put("description", "要合成的原始文本，不要包含 Markdown 控制符或文件内容摘要。"))
                            .put("voice", JSONObject().put("type", "string").put("maxLength", 160)
                                .put("description", "可选音色 ID；省略时使用当前语音设置。本地模型仅接受模型 listVoices 返回的 ID。"))
                            .put("language", JSONObject().put("type", "string").put("maxLength", 32)
                                .put("description", "可选 BCP-47 语言，例如 zh-CN、en-US；主要适用于本地 TTS。"))
                            .put("speed", JSONObject().put("type", "number").put("minimum", 0.5).put("maximum", 2.0)
                                .put("description", "可选语速倍率；本地 TTS 与 MiniMax 生效。"))
                            .put("output_name", JSONObject().put("type", "string").put("maxLength", 80)
                                .put("description", "可选文件名，不含目录。扩展名会自动规范为 .wav。"))
                    )
                    .put("required", JSONArray().put("text"))
                    .put("additionalProperties", false),
            ),
        )
    }
}
