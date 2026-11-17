package io.github.mangi.eta.agent.capability

import io.github.zhh_lgtm.nexus.application.api.CapabilitySummary
import java.util.Locale

/** Local search hints only; matching never grants access or claims a package is installed. */
internal object CapabilitySearch {
    private val terms = Regex("[\\p{L}\\p{N}_-]+")
    private val hints = mapOf(
        "terminal" to "linux shell bash python node npm pip uv git pdf ffmpeg pandoc tex 编程 脚本 终端 命令 文档 视频 转换",
        "terminal_job" to "linux shell python node 后台 脚本 长任务",
        "terminal_daemon" to "linux 服务 后台 web server",
        "skills_list" to "skill skills 技能 技能库",
        "skills_read" to "skill skills 技能 指令",
        "skills_read_resource" to "skill skills 技能 资源 参考",
        "web_search" to "web search 联网 搜索 网页 资料",
        "fetch_url" to "web fetch 网页 抓取 读取 链接",
        "read_file" to "file files 文件 读取 文本",
        "write_file" to "file files 文件 写入 保存",
        "glob_files" to "file files find 文件 查找 路径",
        "grep_files" to "file files search 文件 搜索 内容",
        "cloud_document" to "ocr 识别 文档 解析 表格 翻译 向量 embedding rerank",
        "cloud_media" to "image video 图片 视频 绘图 生成 媒体",
        "connector_call" to "connector api rest graphql websocket 连接 服务",
    )

    fun rank(query: String, rows: List<CapabilitySummary>): List<CapabilitySummary> {
        val phrase = query.trim().lowercase(Locale.ROOT)
        val words = terms.findAll(phrase).map { it.value }.distinct().take(12).toList()
        if (words.isEmpty()) return emptyList()
        return rows.mapNotNull { row ->
            val id = row.id.lowercase(Locale.ROOT)
            val text = "$id ${row.namespace} ${row.description}".lowercase(Locale.ROOT)
            val aliases = hints[row.id].orEmpty()
            val matched = words.count { it in text || it in aliases }
            if (matched == 0) return@mapNotNull null
            val exact = when { id == phrase -> 500; phrase in text -> 150; else -> 0 }
            val score = exact + matched * 25 + (if (matched == words.size) 80 else 0) +
                words.count { it in id } * 10
            row to score
        }.sortedWith(compareByDescending<Pair<CapabilitySummary, Int>> { it.first.available }
            .thenByDescending { it.second }.thenBy { it.first.id }).map { it.first }
    }
}
