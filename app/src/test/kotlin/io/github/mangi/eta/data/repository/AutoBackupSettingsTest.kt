package io.github.mangi.eta.data.repository

import android.app.Application
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AutoBackupSettingsTest {
    @Test fun settingsRoundTripFormatContentsScheduleAndRetention() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AutoBackupSettings(true, "content://tree/example", 5, 22, 3, false,
            EtaBackupOptions(EtaBackupFormat.JSON, setOf(EtaBackupContent.MEMORY)))
        AutoBackupStore.save(context, settings)
        assertEquals(settings, AutoBackupStore.read(context))
        AutoBackupStore.status(context, "失败")
        assertEquals(0, AutoBackupStore.lastSuccess(context))
        AutoBackupStore.status(context, "成功", success = true)
        assertTrue(AutoBackupStore.lastSuccess(context) > 0)
        assertEquals("成功", AutoBackupStore.status(context))
    }
    @Test fun backupAndRestoreGateRejectsOverlapAndReleasesAfterFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = launch { EtaBackupOperations.exclusive { started.complete(Unit); finish.await() } }
        started.await()
        assertTrue(runCatching { EtaBackupOperations.exclusive {} }.isFailure)
        finish.complete(Unit); first.join()
        assertTrue(runCatching { EtaBackupOperations.exclusive { error("fixture") } }.isFailure)
        assertEquals(42, EtaBackupOperations.exclusive { 42 })
    }
    @Test fun fullFormatAlwaysMeansAllContentsAndEmptySelectionIsRejected() {
        assertEquals(EtaBackupContent.entries.toSet(), EtaBackupOptions(EtaBackupFormat.FULL, setOf(EtaBackupContent.MEMORY)).validated().contents)
        assertTrue(runCatching { EtaBackupOptions(EtaBackupFormat.JSON, emptySet()).validated() }.isFailure)
    }

    @Test fun manualSuccessDoesNotSkipTheNextInterval() {
        val settings = AutoBackupSettings(days = 3, hour = 3)
        val manual = ZonedDateTime.parse("2026-11-07T12:00:00+08:00[Asia/Singapore]")
        val success = manual.toInstant().toEpochMilli()
        assertFalse(AutoBackupSchedule.isDue(settings, success, manual.plusDays(2).withHour(3)))
        assertFalse(AutoBackupSchedule.isDue(settings, success, manual.plusDays(3).withHour(2)))
        assertTrue(AutoBackupSchedule.isDue(settings, success, manual.plusDays(3).withHour(3)))
        assertEquals(manual.plusDays(3).withHour(3), AutoBackupSchedule.nextAttempt(settings, success, manual))
    }

    @Test fun missedScheduleIsMarkedForImmediateCatchUp() {
        val settings = AutoBackupSettings(days = 2, hour = 4)
        val success = ZonedDateTime.of(2026, 1, 1, 4, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai"))
        val overdue = success.plusDays(3).withHour(8)
        val fresh = success.plusDays(1)
        assertTrue(AutoBackupSchedule.isOverdue(settings, success.toInstant().toEpochMilli(), overdue))
        assertFalse(AutoBackupSchedule.isOverdue(settings, success.toInstant().toEpochMilli(), fresh))
    }

    @Test fun scheduleUsesLocalCalendarAcrossDaylightSavingAndDelayedRuns() {
        val settings = AutoBackupSettings(days = 1, hour = 3)
        val before = ZonedDateTime.parse("2026-10-31T03:00:00-04:00[America/New_York]")
        val next = ZonedDateTime.parse("2026-11-01T03:00:00-05:00[America/New_York]")
        assertEquals(next, AutoBackupSchedule.nextAttempt(settings, before.toInstant().toEpochMilli(), before))
        assertTrue(AutoBackupSchedule.isDue(settings, before.toInstant().toEpochMilli(), next))
        assertTrue(AutoBackupSchedule.isDue(settings, before.toInstant().toEpochMilli(), next.plusDays(2).withHour(1)))
    }
}
