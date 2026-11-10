package io.github.mangi.eta.agent.memory

import android.content.Context
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.fork.learning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

internal object MemoryAutomationCapture {
    val activeRuns = AtomicInteger(0)
    suspend fun capture(context: Context, runId: String, source: String, text: String, scopes: List<AgentMemoryScope>) {
        val s = MemoryAutomationSettingsStore.read(context)
        if ((!s.extractMemory || !AgentMemoryRepository.isEnabled()) && !LearningSettingsStore.automaticCaptureEnabled(context)) return
        if (!MemoryAutomationPolicy.allowed(text) || text.length < 12) return
        // Drop secret-bearing lines before storing or sending a maintenance request.
        val safe = text.lineSequence().filterNot(MemoryAutomationPolicy::sensitive).joinToString("\n")
            .let { MemoryAutomationPolicy.safe(it, 6000) }
        if (safe.length < 12) return
        val dao = LearningDatabase.get(context).memoryDao()
        if (dao.queueSize() >= 100) return
        dao.insertJob(MemoryJob(runId, source.take(256), scopes.distinctBy { it.key }.joinToString("\n") { it.key }, safe, System.currentTimeMillis()))
        MemoryMaintenanceScheduler.enqueue(context)
    }
}

internal class MemoryMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        io.github.mangi.eta.data.repository.EtaBackupOperations.exclusive { gate.withLock { withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = MemoryAutomationSettingsStore.read(context)
        val manual = inputData.getBoolean("manual", false)
        fun status(text: String) = MemoryAutomationSettingsStore.status(context, text)
        if (!manual && !settings.idleEnabled) return@withContext Result.success()
        if (MemoryAutomationCapture.activeRuns.get() > 0) { status("等待 Agent 任务完成"); return@withContext Result.success() }
        if (!manual && settings.screenOffOnly && context.getSystemService(PowerManager::class.java).isInteractive) {
            status("等待熄屏"); return@withContext Result.success()
        }
        val now = System.currentTimeMillis()
        if (!manual && now - MemoryAutomationSettingsStore.lastActivity(context) < settings.quietMinutes * 60_000L) {
            status("等待空闲 ${settings.quietMinutes} 分钟"); return@withContext Result.success()
        }
        val db = LearningDatabase.get(context); val dao = db.memoryDao()
        val memoryEnabled = settings.extractMemory && AgentMemoryRepository.isEnabled()
        val learningEnabled = LearningSettingsStore.automaticCaptureEnabled(context)
        if (!memoryEnabled && !learningEnabled) { status("自动提取已关闭"); return@withContext Result.success() }
        dao.recoverJobs()
        dao.pruneJobs(now - 30L * 86_400_000)
        var processed = 0
        repeat(settings.batchSize) {
            ensureActive()
            if (MemoryAutomationCapture.activeRuns.get() > 0) return@repeat
            if (dao.attemptsSince(now - 86_400_000L) >= settings.dailyLimit) { status("已达到每日 ${settings.dailyLimit} 次额度"); return@withContext Result.success() }
            val job = dao.nextJob() ?: return@repeat
            if (now - job.createdAt > 7L * 86_400_000) { dao.updateJob(job.copy(text = "", status = "expired", detail = "来源超过 7 天", updatedAt = now)); return@repeat }
            val config = RuntimeConfigRepository.currentRuntimeConfig() ?: run { status("等待配置可用模型"); return@withContext Result.success() }
            val controller = AgentRunController()
            val requestStarted = System.nanoTime()
            val watchdog = launch {
                while (isActive) {
                    delay(500)
                    if (System.nanoTime() - requestStarted > 90_000_000_000L ||
                        MemoryAutomationCapture.activeRuns.get() > 0 || !manual &&
                        (!MemoryAutomationSettingsStore.read(context).idleEnabled || settings.screenOffOnly && context.getSystemService(PowerManager::class.java).isInteractive)) controller.cancel()
                }
            }
            val working = job.copy(status = "running", attempts = job.attempts + 1, updatedAt = System.currentTimeMillis())
            dao.updateJob(working); status("正在整理第 ${processed + 1} 个来源")
            try {
                val keys = job.scopes.lines().filter { it.isNotBlank() }
                val entries = withTimeout(90_000) { runInterruptible { MemoryAutomaticExtractor.extract(config, job.text, keys, controller) } }
                db.withTransaction {
                    // A logical restore discards old sources, including an extraction already in flight.
                    if (dao.job(working.id) != working) return@withTransaction
                    val repo = MemoryReviewRepository(db)
                    var proposals = 0
                    for (item in entries) {
                        val alias = when (item.scope) {
                            "global" -> "global"
                            "project" -> keys.firstOrNull { it.startsWith("project:") }
                            "current" -> keys.lastOrNull { it != "global" }
                            else -> item.scope.takeIf { it in keys }
                        } ?: continue
                        if (alias !in keys) continue
                        val kind = LearningKind.fromWire(item.kind) ?: continue
                        if (kind == LearningKind.PREFERENCE && !settings.capturePreferences) continue
                        if (kind == LearningKind.FACT && !settings.captureDecisions) continue
                        if (kind in setOf(LearningKind.PROCEDURE, LearningKind.FAILURE_FIX) && !settings.captureProcedures) continue
                        if (memoryEnabled && (item.target == "memory" || alias != "global")) {
                            val scope = MemoryAutomationPolicy.parseScope(alias)
                            repo.propose(scope, item.layer, item.topic, item.content, item.evidence, job.source,
                                MemoryAutomationPolicy.ttl(scope, settings.retentionDays, now))?.let { proposals++ }
                        } else if (item.target == "learning" && alias == "global" && learningEnabled && kind in setOf(LearningKind.PROCEDURE, LearningKind.FAILURE_FIX)) {
                            when (LearningRepository.get(context).propose(LearningProposalInput(kind, item.topic, item.content, item.evidence, job.source))) {
                                is LearningProposalResult.Created, is LearningProposalResult.Existing -> proposals++
                                else -> Unit
                            }
                        }
                    }
                    dao.updateJob(working.copy(text = "", status = "done", detail = "已整理，处理候选 $proposals 条（含去重）", updatedAt = System.currentTimeMillis()))
                    processed++
                }
            } catch (timeout: TimeoutCancellationException) {
                dao.updateJob(working.copy(status = if (working.attempts >= 3) "failed" else "queued",
                    text = if (working.attempts >= 3) "" else working.text, detail = "整理超时", updatedAt = System.currentTimeMillis()))
                status("整理超时，稍后重试")
                return@withContext Result.success()
            } catch (cancelled: CancellationException) {
                controller.cancel()
                withContext(NonCancellable) { dao.updateJob(working.copy(status = "queued", detail = "等待下次空闲", updatedAt = System.currentTimeMillis())) }
                throw cancelled
            } catch (failure: Exception) {
                dao.updateJob(working.copy(status = if (working.attempts >= 3) "failed" else "queued",
                    text = if (working.attempts >= 3) "" else working.text, detail = "整理未完成：${failure.javaClass.simpleName}", updatedAt = System.currentTimeMillis()))
                status("整理未完成，稍后重试")
                return@withContext Result.success()
            } finally { controller.cancel(); watchdog.cancel() }
        }
        status(if (processed > 0) "本次已整理 $processed 个来源" else "暂无待整理来源")
        Result.success()
        } } }
    } catch (_: io.github.mangi.eta.data.repository.EtaBackupBusyException) { Result.retry() }
    companion object { private val gate = Mutex() }
}
