package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.tool.requiredText
import org.json.JSONObject

internal object FileToolDispatcher {
    fun execute(backend: FileToolBackend, name: String, args: JSONObject): String {
        val operations = AgentFileOperations(backend)
        val path = if (args.has("path")) args.requiredText("path") else ""
        if ("://" in path) throw FileToolException("UNSUPPORTED_FILE_REFERENCE", "请使用已授权的文件系统路径；文档 URI 需先导入，导入路径对应副本")
        val revision = args.optString("expected_revision").takeIf(String::isNotBlank)
        return when (name) {
            "make_directory", "copy_file", "move_file", "delete_file" -> fileToolOperation(name) {
                fun guarded(raw: String): String = backend.resolve(raw).also { resolved ->
                    require("://" !in raw && java.io.File(resolved).parent != null && resolved != backend.resolve("")) { "不能修改文件系统或工作区根目录" }
                }
                val result = when (name) {
                    "make_directory" -> {
                        val target = guarded(args.requiredText("path"))
                        if (args.optBoolean("create_parents")) {
                            val ancestors = generateSequence(java.io.File(target)) { it.parentFile }.toList().asReversed()
                            require(ancestors.size <= 128) { "目录层级过深" }
                            for (ancestor in ancestors) {
                                backend.checkActive()
                                val existing = try { backend.stat(ancestor.path) } catch (missing: FileToolException) {
                                    if (missing.code != "FILE_NOT_FOUND") throw missing
                                    backend.createDirectory(ancestor.path)
                                } catch (_: java.nio.file.NoSuchFileException) {
                                    backend.createDirectory(ancestor.path)
                                }
                                require(existing.kind == FileToolKind.DIRECTORY) { "父路径不是目录" }
                            }
                            backend.stat(target)
                        } else {
                            val existing = try { backend.stat(target) } catch (_: java.nio.file.NoSuchFileException) { null }
                                catch (missing: FileToolException) { if (missing.code == "FILE_NOT_FOUND") null else throw missing }
                            if (existing?.kind == FileToolKind.DIRECTORY) existing else backend.createDirectory(target)
                        }
                    }
                    "copy_file", "move_file" -> {
                        val source = guarded(args.requiredText("source")); val destination = guarded(args.requiredText("destination"))
                        if (name == "copy_file") backend.copy(source, destination) else backend.move(source, destination)
                    }
                    else -> {
                        val target = guarded(args.requiredText("path"))
                        backend.delete(target, args.optBoolean("recursive", false))
                        return@fileToolOperation JSONObject().put("ok", true).put("tool", name).put("path", target).put("deleted", true)
                    }
                }
                JSONObject().put("ok", true).put("tool", name).put("file", result.toJson()).put("path", result.path)
                    .put("size_bytes", result.sizeBytes).put("kind", result.kind.wireName)
            }
            "stat_file" -> operations.statFile(args.requiredText("path"))
            "read_file" -> operations.readFile(
                path = args.requiredText("path"),
                offsetBytes = args.optLong("offset_bytes", 0),
                maxBytes = args.optInt("max_bytes", 16_000),
                startLine = if (args.has("start_line")) args.getInt("start_line") else null,
                maxLines = args.optInt("max_lines", 200),
                expectedRevision = revision,
            )
            "write_file" -> operations.writeFile(args.requiredText("path"), args.requiredText("content"), args.optBoolean("append", false), revision)
            "edit_file" -> operations.editFile(args.requiredText("path"), args.requiredText("old_text"), args.requiredText("new_text"), args.optBoolean("replace_all", false), revision)
            "list_directory" -> operations.listDirectory(path, args.optBoolean("show_hidden", false), args.optInt("limit", 80), args.optInt("offset", 0), revision)
            "glob_files" -> operations.globFiles(path, args.requiredText("pattern"), args.optInt("limit", 80), args.optString("cursor").takeIf(String::isNotEmpty), args.optBoolean("include_hidden", false))
            "grep_files" -> operations.grepFiles(path, args.requiredText("query"), args.optString("glob", "**/*"), args.optInt("limit", 50), args.optString("cursor").takeIf(String::isNotEmpty), args.optBoolean("include_hidden", false), args.optBoolean("case_sensitive", true))
            else -> throw FileToolException("UNKNOWN_TOOL", "未知文件工具")
        }
    }
}
