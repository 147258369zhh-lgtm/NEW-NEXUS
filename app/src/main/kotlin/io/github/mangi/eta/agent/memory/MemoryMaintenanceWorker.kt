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
import io.github.mangi.eta.data.model.ModelRolePurpose
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
        if (MemoryAutomationCapture.activeRuns.get() > 0) { status("等待 Agent 任务完成"); return@withContext Result.retry() }
        if (!manual && settings.screenOffOnly && context.getSystemService(PowerManager::class.java).isInteractive) {
            status("等待熄屏"); return@withContext Result.retry()
        }
        val now = System.currentTimeMillis()
        if (!manual && now - MemoryAutomationSettingsStore.lastActivity(context) < settings.quietMinutes * 60_000L) {
            status("等待空闲 ${settings.quietMinutes} 分钟"); return@withContext Result.retry()
        }
        val db = LearningDatabase.get(context); val dao = db.memoryDao()
        val memoryEnabled = settings.extractMemory && AgentMemoryRepository.isEnabled()
        val learningEnabled = LearningSettingsStore.automaticCaptureEnabled(context)
        if (!memoryEnabled && !learningEnabled) { status("自动提取已关闭"); return@withContext Result.success() }
        dao.recoverJobs()
        dao.pruneJobs(now - 30L * 86_400_000)
        suspend fun learningBudgetExhausted() = dao.attemptsSince(now - 86_400_000L) >= settings.dailyLimit
        var processed = 0
        repeat(settings.batchSize) {
            ensureActive()
            if (MemoryAutomationCapture.activeRuns.get() > 0) return@repeat
            if (learningBudgetExhausted()) { status("今日学习额度已用完（每日 ${settings.dailyLimit} 次）"); return@withContext Result.success() }
            val job = dao.nextJob() ?: return@repeat
            if (now - job.createdAt > 7L * 86_400_000) { dao.updateJob(job.copy(text = "", status = "expired", detail = "来源超过 7 天", updatedAt = now)); return@repeat }
            val config = RuntimeConfigRepository.currentRuntimeConfig()?.let { primary ->
                RuntimeConfigRepository.modelRoleRuntimeConfig(ModelRolePurpose.MEMORY, primary)
            } ?: run { status("等待配置可用模型"); return@withContext Result.success() }
            MaintenanceEligibility.reason(context, settings, config, manual, learningBudgetExhausted())?.let { reason -> status(reason); return@withContext Result.retry() }
            val controller = AgentRunController()
            val requestStarted = System.nanoTime()
            val watchdog = launch {
                while (isActive) {
                    delay(500)
                    if (System.nanoTime() - requestStarted > 90_000_000_000L ||
                        MemoryAutomationCapture.activeRuns.get() > 0 || !manual &&
                        MaintenanceEligibility.reason(context, MemoryAutomationSettingsStore.read(context), config, manual) != null) controller.cancel()
                }
            }
            val working = job.copy(status = "running", attempts = job.attempts + 1, updatedAt = System.currentTimeMillis())
            dao.updateJob(working); status("正在整理第 ${processed + 1} 个来源")
            try {
                val keys = job.scopes.lines().filter { it.isNotBlank() }
                val entries = withTimeout(90_000) { runInterruptible { MemoryAutomaticExtractor.extract(context, config, job.text, keys, controller, job.id) } }
                db.withTransaction {
                    // A logical restore discards old sources, including an extraction already in flight.
                    if (dao.job(working.id) != working) return@withTransaction
                    val repo = MemoryReviewRepository(db)
                    var proposals = 0
                    var corrections = 0
                    for (item in entries) {
                        val alias = MemoryAutomaticExtractor.resolveScope(item.scope, keys) ?: continue
                        val kind = LearningKind.fromWire(item.kind) ?: continue
                        if (kind == LearningKind.PREFERENCE && !settings.capturePreferences) continue
                        if (kind == LearningKind.FACT && !settings.captureDecisions) continue
                        if (kind in setOf(LearningKind.PROCEDURE, LearningKind.FAILURE_FIX) && !settings.captureProcedures) continue
                        if (MemoryAutomaticExtractor.isExperience(kind)) {
                            if (!learningEnabled) continue
                            val proposal = MemoryAutomaticExtractor.learningProposal(item, alias, job.source) ?: continue
                            when (LearningRepository.get(context).propose(proposal)) {
                                is LearningProposalResult.Created, is LearningProposalResult.Existing -> proposals++
                                else -> Unit
                            }
                        } else if (item.target == "memory" && memoryEnabled) {
                            val scope = MemoryAutomationPolicy.parseScope(alias)
                            val correction = item.correction && MemoryAutomationPolicy.looksLikeCorrection(item.evidence)
                            repo.propose(scope, item.layer, item.topic, item.content, item.evidence, job.source,
                                MemoryAutomationPolicy.ttl(scope, settings.retentionDays, now), correction)?.let { entry ->
                                proposals++
                                if (entry.status == "active" && entry.supersedes.isNotBlank()) corrections++
                            }
                        }
                    }
                    val correctionDetail = if (corrections > 0) "，已传播纠正 $corrections 条" else ""
                    dao.updateJob(working.copy(text = "", status = "done",
                        detail = "已整理，处理候选 $proposals 条（含去重）$correctionDetail", updatedAt = System.currentTimeMillis()))
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
                val modelFailure = failure as? io.github.mangi.eta.agent.model.AgentModelFailure
                val budgetDenied = modelFailure != null && MaintenanceEligibility.isBudgetDenial(modelFailure.code)
                val yields = budgetDenied || modelFailure != null &&
                    modelFailure.code in setOf("RESOURCE_YIELD", "BACKUP_IN_PROGRESS", "DATA_RESTORE_PENDING")
                if (controller.isCancelled || yields) {
                    dao.updateJob(working.copy(status = "queued", attempts = job.attempts,
                        detail = if (budgetDenied) "预算不足，等待下次空闲" else "等待下次空闲",
                        updatedAt = System.currentTimeMillis()))
                    status(if (budgetDenied) "预算不足，等待下次空闲" else "等待下次空闲")
                    return@withContext Result.success()
                }
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
