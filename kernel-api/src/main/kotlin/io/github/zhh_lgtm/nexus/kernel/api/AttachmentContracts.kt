package io.github.zhh_lgtm.nexus.kernel.api

enum class FileReferenceKind {
    File,
    Directory,
}

data class AgentFileReference(
    val displayName: String,
    val absolutePath: String,
    val kind: FileReferenceKind,
)

data class PendingImageDraft(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
)

data class PendingFileReferenceDraft(
    val id: String,
    val reference: AgentFileReference,
)

data class ConversationAttachmentDraft(
    val images: List<PendingImageDraft> = emptyList(),
    val files: List<PendingFileReferenceDraft> = emptyList(),
) {
    val isEmpty: Boolean get() = images.isEmpty() && files.isEmpty()

    init {
        require(images.size <= 24) { "Too many pending images" }
        require(files.size <= 64) { "Too many pending files" }
        require(images.map(PendingImageDraft::id).toSet().size == images.size) { "Duplicate pending image id" }
        require(files.map(PendingFileReferenceDraft::id).toSet().size == files.size) { "Duplicate pending file id" }
    }
}
