package io.github.mangi.eta.ui.screens.backup

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.data.repository.EtaBackupProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class BackupOperation { EXPORT, INSPECT, IMPORT }

internal class BackupViewModel : ViewModel() {
    val optionsState = mutableStateOf(io.github.mangi.eta.data.repository.EtaBackupOptions())
    val exportDialogState = mutableStateOf(false)
    val importUriState = mutableStateOf<android.net.Uri?>(null)
    val importDialogState = mutableStateOf(false)
    val previewState = mutableStateOf<io.github.mangi.eta.data.repository.EtaBackupSummary?>(null)
    val passwordState = mutableStateOf("")
    val destinationPasswordState = mutableStateOf<String?>(null)
    val selectedOptionsState = mutableStateOf(io.github.mangi.eta.data.repository.EtaBackupOptions())
    var operation by mutableStateOf<BackupOperation?>(null); private set
    var progress by mutableStateOf<EtaBackupProgress?>(null); private set
    var status by mutableStateOf<String?>(null)
    var pendingRestart by mutableStateOf(false)
    private var job: Job? = null
    private var generation = 0L
    fun cancel() { job?.cancel() }
    fun start(kind: BackupOperation, block: suspend ((EtaBackupProgress) -> Unit) -> String) {
        if (operation != null) return
        operation = kind
        val current = ++generation
        progress = EtaBackupProgress(if (kind == BackupOperation.EXPORT) "checking_runtime" else "verify_file")
        status = null
        job = viewModelScope.launch {
            try {
                status = block { value -> viewModelScope.launch { if (generation == current && operation != null) progress = value } }
            } catch (cancelled: CancellationException) {
                status = "操作已取消"
                throw cancelled
            } catch (failure: Exception) {
                status = failure.message ?: "操作未完成，请重试"
            } finally {
                operation = null
                generation++
                progress = null
                job = null
            }
        }
    }
}
