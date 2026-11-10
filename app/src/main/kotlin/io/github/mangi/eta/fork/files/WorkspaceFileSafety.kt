package io.github.mangi.eta.fork.files

import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object WorkspaceFileSafety {
    private val providerPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val rootKeyPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private const val MAX_PATH_CHARS = 4_096
    private const val MAX_SEGMENT_CHARS = 255
    private const val MAX_DISPLAY_NAME_CHARS = 512
    private const val MAX_IDENTIFIER_CHARS = 256
    private const val MAX_MIME_CHARS = 255

    fun validateProviderId(value: String) {
        require(providerPattern.matches(value)) { "WORKSPACE_FILE_PROVIDER_ID_INVALID" }
    }

    fun normalizeRootKey(raw: String): String {
        require(rootKeyPattern.matches(raw)) { "WORKSPACE_FILE_ROOT_KEY_INVALID" }
        return raw
    }

    fun normalizeRelativePath(raw: String): String {
        require(raw.isNotBlank()) { "WORKSPACE_FILE_PATH_EMPTY" }
        require(raw.length <= MAX_PATH_CHARS) { "WORKSPACE_FILE_PATH_TOO_LONG" }
        require(!raw.contains('\\')) { "WORKSPACE_FILE_PATH_BACKSLASH" }
        require(!raw.any(Char::isISOControl)) { "WORKSPACE_FILE_PATH_CONTROL_CHARACTER" }
        val segments = raw.split('/').filter(String::isNotEmpty)
        require(segments.isNotEmpty()) { "WORKSPACE_FILE_PATH_EMPTY" }
        validateSegments(segments)
        return segments.joinToString("/")
    }

    fun normalizeLinuxPath(raw: String): String {
        require(raw.startsWith('/')) { "WORKSPACE_FILE_LINUX_PATH_NOT_ABSOLUTE" }
        require(raw.length <= MAX_PATH_CHARS) { "WORKSPACE_FILE_PATH_TOO_LONG" }
        require(!raw.contains('\\')) { "WORKSPACE_FILE_PATH_BACKSLASH" }
        require(!raw.any(Char::isISOControl)) { "WORKSPACE_FILE_PATH_CONTROL_CHARACTER" }
        val segments = raw.split('/').filter(String::isNotEmpty)
        validateSegments(segments)
        return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
    }

    fun normalizeSafUri(raw: String): String {
        require(raw.isNotBlank() && raw == raw.trim()) { "WORKSPACE_FILE_URI_INVALID" }
        require(!raw.any(Char::isISOControl)) { "WORKSPACE_FILE_URI_CONTROL_CHARACTER" }
        val parsed = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("WORKSPACE_FILE_URI_INVALID")
        }
        require(parsed.scheme.equals("content", ignoreCase = true)) { "WORKSPACE_FILE_URI_SCHEME_UNSUPPORTED" }
        require(!parsed.rawAuthority.isNullOrBlank()) { "WORKSPACE_FILE_URI_AUTHORITY_MISSING" }
        require(parsed.rawUserInfo == null) { "WORKSPACE_FILE_URI_USER_INFO_UNSUPPORTED" }
        require(parsed.rawFragment == null) { "WORKSPACE_FILE_URI_FRAGMENT_UNSUPPORTED" }
        val decodedPath = parsed.path.orEmpty()
        require(!decodedPath.contains('\\')) { "WORKSPACE_FILE_URI_BACKSLASH" }
        require(decodedPath.split('/').none { it == ".." }) { "WORKSPACE_FILE_URI_TRAVERSAL" }
        return "content:${raw.substringAfter(':')}"
    }

    fun validateDisplayName(value: String) {
        require(value.isNotBlank()) { "WORKSPACE_FILE_NAME_EMPTY" }
        require(value.length <= MAX_DISPLAY_NAME_CHARS) { "WORKSPACE_FILE_NAME_TOO_LONG" }
        require(!value.any(Char::isISOControl)) { "WORKSPACE_FILE_NAME_CONTROL_CHARACTER" }
    }

    fun validateOptionalMimeType(value: String?) {
        if (value == null) return
        require(value.isNotBlank()) { "WORKSPACE_FILE_MIME_EMPTY" }
        require(value.length <= MAX_MIME_CHARS) { "WORKSPACE_FILE_MIME_TOO_LONG" }
        require(!value.any(Char::isISOControl) && value.none(Char::isWhitespace)) {
            "WORKSPACE_FILE_MIME_INVALID"
        }
        require(value.contains('/')) { "WORKSPACE_FILE_MIME_INVALID" }
    }

    fun validateOptionalIdentifier(value: String?) {
        if (value == null) return
        require(value.isNotBlank()) { "WORKSPACE_FILE_SOURCE_ID_EMPTY" }
        require(value.length <= MAX_IDENTIFIER_CHARS) { "WORKSPACE_FILE_SOURCE_ID_TOO_LONG" }
        require(!value.any(Char::isISOControl)) { "WORKSPACE_FILE_SOURCE_ID_INVALID" }
    }

    private fun validateSegments(segments: List<String>) {
        segments.forEach { segment ->
            require(segment != "." && segment != "..") { "WORKSPACE_FILE_PATH_TRAVERSAL" }
            require(segment.length <= MAX_SEGMENT_CHARS) { "WORKSPACE_FILE_PATH_SEGMENT_TOO_LONG" }
        }
    }
}

internal object WorkspaceFileIdentity {
    fun fromLocation(
        providerId: WorkspaceProviderId,
        source: WorkspaceFileSource,
        locator: WorkspaceFileLocator,
    ): WorkspaceFileId {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "${providerId.value}\n${source.name}\n${locator.identityKey}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        val value = digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return WorkspaceFileId("file_$value")
    }
}
