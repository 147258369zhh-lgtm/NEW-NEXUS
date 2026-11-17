package io.github.mangi.eta.data.repository

import io.github.mangi.eta.fork.projects.ProjectDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedFilesArchivePlanTest {
    @Test fun namesArePortableAndVisible() {
        assertEquals("季度_报告_最终", GeneratedFilesArchivePlan.safeName("季度/报告:*最终", "fallback"))
        assertEquals("fallback", GeneratedFilesArchivePlan.safeName(" . ", "fallback"))
    }

    @Test fun duplicateFileNamesReceiveStableVisibleSuffixes() {
        val existing = listOf(
            document("report.md"),
            document("report-2.md"),
        )
        assertEquals("report-3.md", GeneratedFilesArchivePlan.uniqueFileName("report.md", existing))
        assertEquals("archive.zip", GeneratedFilesArchivePlan.uniqueFileName("archive.zip", emptyList()))
    }

    private fun document(name: String) = ProjectDocument(
        id = name,
        name = name,
        directory = false,
        size = 1,
        modified = 1,
        flags = 0,
    )
}
