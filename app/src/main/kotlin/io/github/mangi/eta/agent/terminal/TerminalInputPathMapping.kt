package io.github.mangi.eta.agent.terminal

/**
 * 会话输入目录（App 私密工作区）在 Linux 命名空间中固定出现在 /nexus-inputs。
 *
 * 用户在会话里引用的文件来自 Android 路径或 content:// URI。只有位于该目录下的 Android
 * 绝对路径才有确定的 Linux 路径；手机 URI、相对路径和工作区外路径都没有 Linux 对应物，
 * 不能当成 Linux 路径猜测或伪造成功。
 */
internal object TerminalInputPathMapping {
    fun linuxPath(androidWorkspaceRoot: String, rawPath: String): String? {
        if ('\u0000' in rawPath || "://" in rawPath) return null
        if (!rawPath.startsWith('/')) return null
        val root = normalizeAbsolute(androidWorkspaceRoot) ?: return null
        val normalized = normalizeAbsolute(rawPath) ?: return null
        val relative = when {
            normalized == root -> ""
            normalized.startsWith("$root/") -> normalized.removePrefix("$root/")
            else -> return null
        }
        return if (relative.isEmpty()) {
            TerminalRuntime.LINUX_INPUTS_PATH
        } else {
            TerminalRuntime.LINUX_INPUTS_PATH + "/" + relative
        }
    }

    private fun normalizeAbsolute(raw: String): String? {
        val value = raw.trim()
        if (!value.startsWith('/')) return null
        val segments = mutableListOf<String>()
        value.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }
}

