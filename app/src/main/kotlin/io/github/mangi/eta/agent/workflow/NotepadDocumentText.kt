package io.github.mangi.eta.agent.workflow

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FilterInputStream
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Bounded extraction of document body text; no embedded objects or external entities are opened. */
internal object NotepadDocumentText {
    private const val MAX_FILE = 10L * 1024 * 1024
    private const val MAX_XML = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_PAGES = 100
    private const val MAX_CHARS = 64_000
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun extract(file: File, check: () -> Unit): String {
        require(file.isFile && file.length() in 1..MAX_FILE) { "文档不存在或超过 10 MB" }
        check()
        return when (file.extension.lowercase()) {
            "docx" -> docx(file, check)
            "pdf" -> {
                require(Build.VERSION.SDK_INT >= 35) { "PDF 文字提取需要 Android 15 或更高版本" }
                pdf(file, check)
            }
            else -> error("此附件格式暂不支持整理")
        }.also { require(it.isNotBlank()) { "文档没有可提取的文字；扫描件暂不支持文字识别" } }
    }

    private fun docx(file: File, check: () -> Unit): String = ZipFile(file).use { zip ->
        val entries = zip.entries()
        var document: java.util.zip.ZipEntry? = null
        var count = 0
        while (entries.hasMoreElements()) {
            check()
            val entry = entries.nextElement()
            require(++count <= MAX_ENTRIES) { "文档包含过多文件" }
            if (entry.name == "word/document.xml") {
                require(document == null && !entry.isDirectory && entry.size in 1..MAX_XML) { "文档正文无效或过大" }
                document = entry
            }
        }
        val body = requireNotNull(document) { "无法读取 DOCX 正文" }
        zip.getInputStream(body).use { input ->
            val bounded = object : FilterInputStream(input) {
                var total = 0L
                override fun read(): Int { check(); val value = super.read(); if (value >= 0) countBytes(1); return value }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    check(); val n = super.read(b, off, len); if (n > 0) countBytes(n); return n
                }
                fun countBytes(n: Int) { total += n; require(total <= MAX_XML) { "文档正文解压后过大" } }
            }
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(bounded, null)
            val text = StringBuilder()
            var inText = false
            var rootSeen = false
            while (true) {
                check()
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> error("文档包含不支持的 XML 声明")
                    XmlPullParser.ENTITY_REF -> {
                        require(parser.name in setOf("amp", "lt", "gt", "quot", "apos") || parser.name.startsWith("#")) { "文档包含不支持的 XML 实体" }
                        if (inText) text.append(parser.text)
                    }
                    XmlPullParser.START_TAG -> {
                        if (!rootSeen) {
                            require(parser.namespace == WORD_NS && parser.name == "document") { "DOCX 正文无效" }
                            rootSeen = true
                        }
                        if (parser.namespace == WORD_NS) when (parser.name) {
                            "t" -> inText = true
                            "tab" -> text.append('\t')
                            "br", "cr" -> text.append('\n')
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inText) text.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.namespace == WORD_NS) when (parser.name) {
                        "t" -> inText = false
                        "p" -> text.append('\n')
                    }
                }
                require(text.length <= MAX_CHARS) { "文档文字超过整理长度限制" }
            }
            require(rootSeen) { "DOCX 正文无效" }
            text.toString().trim()
        }
    }

    @RequiresApi(35)
    private fun pdf(file: File, check: () -> Unit): String {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
        return renderer.use {
            require(it.pageCount in 1..MAX_PAGES) { "PDF 页数超过 100 页或文件为空" }
            val text = StringBuilder()
            for (page in 0 until it.pageCount) {
                check()
                it.openPage(page).use { current ->
                    for (part in current.textContents) {
                        check()
                        val value = part.text
                        require(value.length <= MAX_CHARS - text.length) { "文档文字超过整理长度限制" }
                        text.append(value)
                    }
                }
                require(text.length < MAX_CHARS) { "文档文字超过整理长度限制" }
                text.append('\n')
            }
            text.toString().trim()
        }
    }
}
