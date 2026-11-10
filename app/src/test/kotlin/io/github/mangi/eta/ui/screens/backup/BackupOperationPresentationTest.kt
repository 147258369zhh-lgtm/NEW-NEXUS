package io.github.mangi.eta.ui.screens.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOperationPresentationTest {
    @Test fun exportAndImportLoadingStatesNeverShareTheSameIndicator() {
        assertTrue(isExportOperation(BackupOperation.EXPORT))
        assertFalse(isImportOperation(BackupOperation.EXPORT))

        assertTrue(isImportOperation(BackupOperation.INSPECT))
        assertTrue(isImportOperation(BackupOperation.IMPORT))
        assertFalse(isExportOperation(BackupOperation.INSPECT))
        assertFalse(isExportOperation(BackupOperation.IMPORT))

        assertFalse(isExportOperation(null))
        assertFalse(isImportOperation(null))
    }
}
