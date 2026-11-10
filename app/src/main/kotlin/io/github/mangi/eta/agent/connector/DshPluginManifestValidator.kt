package io.github.mangi.eta.agent.connector

import org.json.JSONObject

internal data class DshPluginManifest(
    val protocolVersion: Int,
    val minimumAppVersion: String?,
    val title: String?,
    val assetDirectory: String?,
    val capabilities: Set<String>,
    val contributions: Map<String, Int>,
    val contributionIds: Set<String>,
)

internal sealed interface DshManifestValidation {
    data class Valid(val manifest: DshPluginManifest) : DshManifestValidation
    data class Invalid(val code: String, val message: String) : DshManifestValidation
}

/** Conservative parser for dsh-go plugin-kit protocol v1 package.json metadata. */
internal object DshPluginManifestValidator {
    private val id = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}")
    private val semver = Regex("\\d+\\.\\d+\\.\\d+")
    private val capabilities = setOf("audio", "voice", "micro", "overlay", "files")
    private val maximumItems = mapOf(
        "composer.actions" to 2,
        "message.actions" to 2,
        "composer.strip" to 1,
        "panels" to 4,
        "media" to 2,
        "micro" to 2,
        "overlays" to 2,
        "composer.tools" to 2,
        "session.actions" to 2,
        "turn.note" to 1,
    )

    fun validate(packageJson: String): DshManifestValidation = runCatching {
        require(packageJson.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "清单超过 16 KiB"
        }
        val root = JSONObject(packageJson)
        val manifest = root.optJSONObject("dshGo") ?: error("package.json 缺少 dshGo")
        require(manifest.optInt("v", -1) == 1) { "只支持 DSH 插件协议 v1" }
        val minimumApp = manifest.optString("minApp").takeIf(String::isNotBlank)
        require(minimumApp == null || semver.matches(minimumApp)) { "minApp 必须是 x.y.z" }
        val assets = manifest.optString("assets").takeIf(String::isNotBlank)?.also {
            require(isSafeRelativePath(it)) { "assets 必须是包内相对路径" }
        }
        val capabilitySet = manifest.optJSONArray("capabilities")?.let { array ->
            buildSet {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    require(value in capabilities) { "未知 capability：$value" }
                    add(value)
                }
                require(size == array.length()) { "capabilities 不能重复" }
            }
        }.orEmpty()
        val contributions = manifest.getJSONObject("contributes")
        val counts = linkedMapOf<String, Int>()
        val ids = linkedSetOf<String>()
        contributions.keys().forEach { slot ->
            // plugin-kit v1 requires unknown surfaces to be ignored for forward compatibility.
            val maximum = maximumItems[slot] ?: return@forEach
            val items = contributions.getJSONArray(slot)
            require(items.length() <= maximum) { "$slot 最多 $maximum 项" }
            counts[slot] = items.length()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: error("$slot 第 ${index + 1} 项不是对象")
                val contributionId = item.optString("id")
                require(id.matches(contributionId)) { "$slot 包含无效 id" }
                require(ids.add(contributionId)) { "贡献 id 重复：$contributionId" }
                val requiredCapabilities = item.optJSONArray("requires") ?: continue
                for (capabilityIndex in 0 until requiredCapabilities.length()) {
                    require(requiredCapabilities.optString(capabilityIndex) in capabilities) {
                        "$slot 声明了未知 capability"
                    }
                }
            }
        }
        DshPluginManifest(
            protocolVersion = 1,
            minimumAppVersion = minimumApp,
            title = text(manifest.opt("title")),
            assetDirectory = assets,
            capabilities = capabilitySet,
            contributions = counts,
            contributionIds = ids,
        )
    }.fold(
        onSuccess = { DshManifestValidation.Valid(it) },
        onFailure = { DshManifestValidation.Invalid("INVALID_DSH_MANIFEST", it.message ?: "清单无效") },
    )

    private fun text(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf(String::isNotBlank)
        is JSONObject -> value.keys().asSequence()
            .firstOrNull { value.optString(it).isNotBlank() }
            ?.let(value::optString)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        else -> null
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." }

    private const val MAX_MANIFEST_BYTES = 16 * 1024
}
