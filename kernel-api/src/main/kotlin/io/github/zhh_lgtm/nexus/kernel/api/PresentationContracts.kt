package io.github.zhh_lgtm.nexus.kernel.api

sealed interface ContentPart {
    val partId: String
}

data class TextPart(override val partId: String, val text: String) : ContentPart
data class MarkdownPart(override val partId: String, val markdown: String) : ContentPart
data class MathPart(override val partId: String, val latex: String) : ContentPart
data class CodePart(override val partId: String, val language: String?, val code: String) : ContentPart
data class ImagePart(override val partId: String, val artifactId: ArtifactId, val mimeType: String) : ContentPart
data class AudioPart(override val partId: String, val artifactId: ArtifactId, val mimeType: String, val durationMs: Long?) : ContentPart
data class FilePart(override val partId: String, val artifactId: ArtifactId, val displayName: String) : ContentPart
data class ToolTracePart(override val partId: String, val itemId: ItemId) : ContentPart
data class PlanCardPart(override val partId: String, val planId: PlanId) : ContentPart
data class UserInputPart(
    override val partId: String,
    val requestId: String,
    val question: String,
    val options: List<String>,
    val allowFreeText: Boolean,
) : ContentPart
data class ApprovalPart(override val partId: String, val approvalId: String) : ContentPart

data class PresentationProfile(
    val id: String,
    val fallbackProfileId: String? = null,
    val capabilities: Set<String> = emptySet(),
)
