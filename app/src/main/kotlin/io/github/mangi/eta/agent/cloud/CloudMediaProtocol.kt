package io.github.mangi.eta.agent.cloud

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Provider schemas are kept separate from Android storage and runtime ownership. */
internal object CloudMediaProtocol {
    data class Request(val path: String, val body: JSONObject, val asynchronous: Boolean = false)

    fun image(args: JSONObject, model: String, images: List<String>, edit: Boolean): Request {
        val wan = model.startsWith("wan2.6-")
        val qwen = model.startsWith("qwen-image")
        require(wan || qwen) { "图片工具支持 wan2.6-t2i/wan2.6-image 或 qwen-image 系列，请核对云模型配置" }
        require(if (edit) images.size in 1..(if (wan) 4 else 3) else images.isEmpty()) { "编辑需提供 1–3 张图片，万相编辑最多 4 张" }
        require(!edit || model != "wan2.6-t2i") { "图像编辑不能使用文生图模型" }
        require(edit || model != "wan2.6-image") { "万相纯文生图请使用 wan2.6-t2i" }
        val prompt = text(args, "prompt", if (wan) 2000 else 4000)
        val parameters = JSONObject().put("n", integer(args, "n", 1, 1..if (wan) 4 else 6))
            .put("prompt_extend", args.optBoolean("prompt_extend", true))
            .put("watermark", args.optBoolean("watermark", false))
        optionalText(args, parameters, "negative_prompt", 500)
        if (args.has("seed")) parameters.put("seed", integer(args, "seed", 0, 0..Int.MAX_VALUE))
        val size = args.optString("size").trim()
        if (size.isNotEmpty()) {
            if (!(edit && wan && size in setOf("1K", "2K"))) {
                val parts = Regex("([1-9][0-9]{2,3})\\*([1-9][0-9]{2,3})").matchEntire(size)
                    ?: throw IllegalArgumentException("size 须为宽*高，如 1280*1280")
                val width = parts.groupValues[1].toLong(); val height = parts.groupValues[2].toLong()
                require(width * height in (if (wan) 768L * 768 else 512L * 512)..2048L * 2048 &&
                    width * 4 >= height && height * 4 >= width) { "图片分辨率或宽高比超出模型限制" }
            }
            parameters.put("size", size)
        }
        if (edit && wan) parameters.put("enable_interleave", false)
        val content = JSONArray()
        images.forEach { content.put(JSONObject().put("image", it)) }
        content.put(JSONObject().put("text", prompt))
        val body = JSONObject().put("model", checkedModel(model))
            .put("input", JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))))
            .put("parameters", parameters)
        val async = args.optBoolean("async", false)
        require(!async || wan) { "当前 Qwen 图像编辑使用同步接口" }
        return Request(if (async) "/api/v1/services/aigc/image-generation/generation" else MULTIMODAL, body, async)
    }

    fun video(args: JSONObject, model: String, image: String?): Request {
        val fromImage = image != null
        require(if (fromImage) model.startsWith("wan2.6-i2v") else model.startsWith("wan2.7-t2v")) {
            "文生视频使用 wan2.7-t2v，图生视频使用 wan2.6-i2v/wan2.6-i2v-flash"
        }
        val input = JSONObject().put("prompt", text(args, "prompt", if (fromImage) 1500 else 5000))
        if (image != null) input.put("img_url", image)
        optionalText(args, input, "negative_prompt", 500)
        val resolution = args.optString("resolution", "720P").uppercase(Locale.ROOT)
        require(resolution in setOf("720P", "1080P")) { "视频 resolution 只能是 720P 或 1080P" }
        val parameters = JSONObject().put("resolution", resolution)
            .put("duration", integer(args, "duration", 5, 2..15))
            .put("prompt_extend", args.optBoolean("prompt_extend", true))
            .put("watermark", args.optBoolean("watermark", false))
        if (!fromImage) {
            val ratio = args.optString("ratio", "16:9")
            require(ratio in setOf("16:9", "9:16", "1:1", "4:3", "3:4")) { "视频 ratio 无效" }
            parameters.put("ratio", ratio)
        }
        if (args.has("seed")) parameters.put("seed", integer(args, "seed", 0, 0..Int.MAX_VALUE))
        if (fromImage && args.has("shot_type")) {
            val shot = args.getString("shot_type")
            require(shot in setOf("single", "multi")) { "shot_type 无效" }
            parameters.put("shot_type", shot)
        }
        if (model.startsWith("wan2.6-i2v-flash") && args.has("audio")) parameters.put("audio", args.getBoolean("audio"))
        return Request("/api/v1/services/aigc/video-generation/video-synthesis",
            JSONObject().put("model", checkedModel(model)).put("input", input).put("parameters", parameters), true)
    }

    fun videoUnderstanding(args: JSONObject, model: String, video: String): Request {
        val fps = args.optDouble("fps", 2.0)
        require(fps.isFinite() && fps in 0.1..10.0) { "fps 必须在 0.1–10 之间" }
        val content = JSONArray().put(JSONObject().put("type", "video_url")
            .put("video_url", JSONObject().put("url", video)).put("fps", fps))
            .put(JSONObject().put("type", "text").put("text", text(args, "prompt", 8000)))
        return Request("/compatible-mode/v1/chat/completions", JSONObject().put("model", checkedModel(model))
            .put("stream", false).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", integer(args, "max_tokens", 2048, 1..8192)))
    }

    fun speech(args: JSONObject): JSONObject {
        val value = text(args, "text", 10_000)
        require(billableCharacters(value) <= 10_000) { "DeepSeek 单次合成最多 10,000 计费字符，汉字按两个字符计" }
        val voice = args.optString("voice_id", args.optString("voice", "stella"))
        require(voice in setOf("stella", "tide")) { "DeepSeek 音色仅支持 stella 或 tide" }
        val body = JSONObject().put("text", value).put("voice_id", voice).put("format", "wav")
            .put("filter_markdown", args.optBoolean("filter_markdown", true))
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "语音请求超过 64 KiB" }
        return body
    }

    fun billableCharacters(value: String): Int = value.codePoints().toArray().sumOf {
        if (Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN) 2 else 1
    }

    fun outputUrls(response: JSONObject, video: Boolean): List<String> {
        val output = response.optJSONObject("output") ?: throw CloudApiException("missing_media_output", outcomeUnknown = true)
        val result = linkedSetOf<String>()
        if (video) output.optString("video_url").takeIf(String::isNotBlank)?.let(result::add)
        output.optJSONArray("results")?.let { values ->
            for (i in 0 until values.length()) values.optJSONObject(i)?.optString(if (video) "video_url" else "url")
                ?.takeIf(String::isNotBlank)?.let(result::add)
        }
        if (!video) output.optJSONArray("choices")?.let { values ->
            for (i in 0 until values.length()) values.optJSONObject(i)?.optJSONObject("message")?.optJSONArray("content")?.let { content ->
                for (j in 0 until content.length()) content.optJSONObject(j)?.optString("image")
                    ?.takeIf(String::isNotBlank)?.let(result::add)
            }
        }
        require(result.size <= if (video) 1 else 6) { "供应商返回的媒体数量超出限制" }
        return result.toList()
    }

    fun text(args: JSONObject, key: String, maximum: Int): String = args.optString(key).trim().also {
        require(it.isNotEmpty() && it.codePointCount(0, it.length) <= maximum && !it.contains('\u0000')) { "$key 不能为空且最多 $maximum 个字符" }
    }

    private fun checkedModel(model: String): String = model.also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))) { "云模型名称无效" }
    }

    private fun optionalText(from: JSONObject, to: JSONObject, key: String, maximum: Int) {
        if (from.has(key)) to.put(key, from.getString(key).also { require(it.codePointCount(0, it.length) <= maximum) { "$key 超出长度限制" } })
    }

    fun integer(args: JSONObject, key: String, default: Int, range: IntRange): Int {
        if (!args.has(key)) return default
        val number = args.get(key) as? Number ?: throw IllegalArgumentException("$key 须为整数")
        val value = number.toDouble()
        require(value.isFinite() && value == value.toInt().toDouble() && value.toInt() in range) { "$key 须在 ${range.first}–${range.last} 之间" }
        return value.toInt()
    }

    const val MULTIMODAL = "/api/v1/services/aigc/multimodal-generation/generation"
}
