package io.github.mangi.eta.agent.workflow

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotepadDocumentTextTest {
    private fun docx(body: String): File = File.createTempFile("notepad", ".docx").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(body.toByteArray())
            zip.closeEntry()
        }
        deleteOnExit()
    }

    private fun document(body: String) = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""

    @Test fun extractsParagraphsAndEscapedText() {
        val file = docx(document("<w:p><w:r><w:t>A &amp; B</w:t><w:tab/><w:t>C</w:t></w:r></w:p><w:p><w:r><w:t>D</w:t></w:r></w:p>"))
        assertEquals("A & B\tC\nD", NotepadDocumentText.extract(file) {})
    }

    @Test fun rejectsOverBudgetAndXmlDeclarations() {
        val oversized = docx(document("<w:p><w:r><w:t>${"x".repeat(64_001)}</w:t></w:r></w:p>"))
        assertThrows(IllegalArgumentException::class.java) { NotepadDocumentText.extract(oversized) {} }
        val entity = docx("""<!DOCTYPE x [<!ENTITY x "secret">]>${document("<w:p><w:r><w:t>&x;</w:t></w:r></w:p>")}""")
        assertThrows(Exception::class.java) { NotepadDocumentText.extract(entity) {} }
    }

    @Test fun cancellationPropagates() {
        val file = docx(document("<w:p><w:r><w:t>text</w:t></w:r></w:p>"))
        assertThrows(InterruptedException::class.java) { NotepadDocumentText.extract(file) { throw InterruptedException() } }
    }

    @Test
    @Config(sdk = [33, 34])
    fun pdfRequiresAndroid15() {
        val file = File.createTempFile("notepad", ".pdf").apply { writeText("%PDF-1.4"); deleteOnExit() }
        val error = assertThrows(IllegalArgumentException::class.java) { NotepadDocumentText.extract(file) {} }
        assertEquals("PDF 文字提取需要 Android 15 或更高版本", error.message)
    }

    @Test fun rejectsOversizedFileAndExpandedBody() {
        val huge = File.createTempFile("notepad", ".docx").apply { deleteOnExit() }
        RandomAccessFile(huge, "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
        val fileError = assertThrows(IllegalArgumentException::class.java) { NotepadDocumentText.extract(huge) {} }
        assertEquals("文档不存在或超过 10 MB", fileError.message)

        val expanded = docx(document("<!--${"x".repeat(2 * 1024 * 1024)}-->"))
        val bodyError = assertThrows(IllegalArgumentException::class.java) { NotepadDocumentText.extract(expanded) {} }
        assertEquals("文档正文无效或过大", bodyError.message)
    }

    @Test fun rejectsExcessiveZipEntriesAndEmptyDocument() {
        val many = File.createTempFile("notepad", ".docx").apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip ->
                repeat(513) { index ->
                    zip.putNextEntry(ZipEntry("entry$index"))
                    zip.closeEntry()
                }
            }
        }
        val countError = assertThrows(IllegalArgumentException::class.java) { NotepadDocumentText.extract(many) {} }
        assertEquals("文档包含过多文件", countError.message)

        val empty = docx(document("<w:p/>"))
        val emptyError = assertThrows(IllegalArgumentException::class.java) { NotepadDocumentText.extract(empty) {} }
        assertEquals("文档没有可提取的文字；扫描件暂不支持文字识别", emptyError.message)
    }
}
