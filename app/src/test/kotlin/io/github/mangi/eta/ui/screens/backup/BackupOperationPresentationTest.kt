package io.github.mangi.eta.ui.screens.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOperationPresentationTest {
    @Test fun stagedRestoreIsNotPresentedAsCompleted() {
        assertFalse(isBackupStatusPositive("备份已校验并暂存，请重启完成恢复"))
        assertFalse(isBackupStatusPositive("恢复待重启完成"))
        assertTrue(isBackupStatusPositive("备份导出完成"))
    }

    @Test fun validationFailuresAndCancellationAreNotPresentedAsStillProcessing() {
        assertEquals(BackupStatusKind.ERROR, backupStatusKind("签名校验不通过，请重新选择文件"))
        assertEquals(BackupStatusKind.ERROR, backupStatusKind("操作未完成，请重试"))
        assertEquals(BackupStatusKind.CANCELED, backupStatusKind("操作已取消"))
        assertEquals(BackupStatusKind.PENDING, backupStatusKind("备份已暂存，请重启完成恢复"))
    }

    @Test fun startupRestoreResultsReportSuccessAndRollbackAccurately() {
        assertEquals(BackupStatusKind.SUCCESS, backupStatusKind("完整备份已恢复"))
        assertEquals(BackupStatusKind.ERROR, backupStatusKind("备份校验未通过，现有数据已保留"))
        assertEquals(BackupStatusKind.ERROR, backupStatusKind("上次恢复被中断，已回退到恢复前的数据"))
        assertEquals(BackupStatusKind.ERROR, backupStatusKind("操作未完成 · 无法暂存恢复数据"))
        assertEquals(BackupStatusKind.PENDING, backupStatusKind("正在处理备份，可先取消操作再返回"))
    }

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
