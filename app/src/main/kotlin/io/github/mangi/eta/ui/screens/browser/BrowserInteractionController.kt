package io.github.mangi.eta.ui.screens.browser

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserInteractionPolicy
import io.github.mangi.eta.agent.browser.BrowserUserInteractionHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns Activity result callbacks, so no Activity or stale web prompt survives its window. */
internal class BrowserInteractionController(
    private val activity: ComponentActivity,
    private val session: AgentBrowserSession,
) : BrowserUserInteractionHost {
    private var foreground = false
    private var dialog: AlertDialog? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var filePickerInFlight = false
    private var captureUri: Uri? = null
    private var captureFile: File? = null
    private var pendingMedia: PermissionRequest? = null
    private var permissionInFlight = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private var permissionCancellation: (() -> Unit)? = null
    private var qrInFlight = false

    private val filePicker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        filePickerInFlight = false
        val callback = fileCallback
        fileCallback = null
        val captured = captureUri
        captureUri = null
        val uris = if (result.resultCode == android.app.Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?.filter { it.scheme == "content" }?.toTypedArray()
                ?.takeIf { it.isNotEmpty() }
                ?: captured?.takeIf { captureFile?.length()?.let { size -> size > 0 } == true }?.let { arrayOf(it) }
        } else null
        callback?.onReceiveValue(uris)
        captured?.let { uri ->
            activity.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        // A selected upload must stay readable while WebView consumes it. Only remove cancelled captures.
        if (callback == null || uris == null) captureFile?.delete()
        captureFile = null
    }

    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionInFlight = false
        val action = pendingPermissionAction
        val denied = permissionCancellation
        pendingPermissionAction = null
        permissionCancellation = null
        if (grants.isNotEmpty() && grants.values.all { it } && foreground) action?.invoke() else denied?.invoke()
    }

    private val cameraQr = activity.registerForActivityResult(ScanContract()) { result ->
        qrInFlight = false
        result.contents?.let(::confirmQr)
    }

    private val photoQr = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) { qrInFlight = false; return@registerForActivityResult }
        activity.lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) { BrowserQrDecoder.decode(activity.contentResolver, uri) }
            qrInFlight = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (decoded == null) toast(R.string.browser_qr_not_found) else confirmQr(decoded)
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value && !filePickerInFlight && !permissionInFlight) {
            cancelPageRequests()
        }
        session.setHostForeground(this, value)
    }

    override fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        if (!windowAvailable() || filePickerInFlight || fileCallback != null || params.mode == WebChromeClient.FileChooserParams.MODE_SAVE) {
            callback.onReceiveValue(null)
            return
        }
        cancelMediaPermission()
        dismissDialog()
        fileCallback = callback
        val types = params.acceptTypes.flatMap { it.split(',') }.map(String::trim).filter(String::isNotBlank)
            .map { type ->
                if (type.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1).lowercase()) ?: "*/*"
                else type
            }.distinct()
        val fileIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val options = mutableListOf(activity.getString(R.string.browser_upload_choose_file) to { launchFileIntent(fileIntent) })
        // Capture is an explicit user choice; a website cannot silently open a camera or recorder.
        if (types.any { it.startsWith("image/") }) {
            options += activity.getString(R.string.browser_upload_photo) to { capture(MediaStore.ACTION_IMAGE_CAPTURE, ".jpg") }
        }
        if (types.any { it.startsWith("video/") }) {
            options += activity.getString(R.string.browser_upload_video) to { capture(MediaStore.ACTION_VIDEO_CAPTURE, ".mp4") }
        }
        if (types.any { it.startsWith("audio/") }) {
            options += activity.getString(R.string.browser_upload_audio) to {
                launchFileIntent(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        if (options.size == 1) launchFileIntent(fileIntent) else {
            dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.browser_upload_title)
                .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                    dialog = null
                    options[which].second()
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> finishFile(null) }
                .setOnCancelListener { finishFile(null) }
                .show()
        }
    }

    private fun capture(action: String, suffix: String) {
        requestAndroidPermissions(arrayOf(Manifest.permission.CAMERA), onDenied = { finishFile(null) }) {
            if (fileCallback == null) return@requestAndroidPermissions
            runCatching {
                val directory = File(activity.cacheDir, "workspace-share/browser-captures").apply { mkdirs() }
                directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach(File::delete)
                val output = File.createTempFile("capture-", suffix, directory)
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", output)
                captureFile = output
                captureUri = uri
                launchFileIntent(Intent(action).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    clipData = ClipData.newRawUri("capture", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                })
            }.onFailure { finishFile(null); toast(R.string.browser_upload_unavailable) }
        }
    }

    private fun launchFileIntent(intent: Intent) {
        if (fileCallback == null || !windowAvailable()) { finishFile(null); return }
        runCatching {
            filePickerInFlight = true
            filePicker.launch(intent)
        }.onFailure {
            filePickerInFlight = false
            finishFile(null)
            toast(R.string.browser_upload_unavailable)
        }
    }

    private fun finishFile(uris: Array<Uri>?) {
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(uris)
        if (!filePickerInFlight) {
            captureUri?.let { uri ->
                activity.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            captureFile?.delete()
            captureFile = null
            captureUri = null
        }
    }

    override fun requestMediaPermission(request: PermissionRequest) {
        cancelMediaPermission()
        if (!windowAvailable() || permissionInFlight || fileCallback != null ||
            !BrowserInteractionPolicy.sameSecureOrigin(session.snapshots.value.url, request.origin.toString())
        ) { request.deny(); return }
        dismissDialog()
        pendingMedia = request
        val wanted = request.resources.mapNotNull { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> null
            }
        }.distinct().toTypedArray()
        if (wanted.isEmpty() || wanted.size != request.resources.distinct().size) { cancelMediaPermission(); return }
        val labels = wanted.joinToString("、") { permission ->
            activity.getString(if (permission == Manifest.permission.CAMERA) R.string.browser_camera else R.string.browser_microphone)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.browser_website_permission_title)
            .setMessage(activity.getString(R.string.browser_website_permission_message, request.origin.toString(), labels))
            .setNegativeButton(R.string.action_cancel) { _, _ -> cancelMediaPermission() }
            .setOnCancelListener { cancelMediaPermission() }
            .setPositiveButton(R.string.browser_allow_once) { _, _ ->
                dialog = null
                requestAndroidPermissions(wanted, onDenied = { cancelMediaPermission(request) }) {
                    if (pendingMedia === request && foreground &&
                        BrowserInteractionPolicy.sameSecureOrigin(session.snapshots.value.url, request.origin.toString())
                    ) {
                        pendingMedia = null
                        request.grant(request.resources)
                    } else cancelMediaPermission(request)
                }
            }.show()
    }

    override fun cancelMediaPermission(request: PermissionRequest?) {
        if (request != null && pendingMedia !== request) return
        val pending = pendingMedia
        pendingMedia = null
        if (pending != null) {
            dismissDialog()
            runCatching { pending.deny() }
            // Keep an already displayed Android permission launch in flight, but invalidate its action.
            pendingPermissionAction = null
            permissionCancellation = null
        }
    }

    override fun cancelPageRequests() {
        cancelMediaPermission()
        dismissDialog()
        finishFile(null)
        pendingPermissionAction = null
        permissionCancellation = null
    }

    fun showQrOptions() {
        if (!windowAvailable() || qrInFlight) return
        cancelPageRequests()
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.browser_scan_qr)
            .setItems(arrayOf(activity.getString(R.string.browser_qr_camera), activity.getString(R.string.browser_qr_album))) { _, which ->
                dialog = null
                if (which == 0) requestAndroidPermissions(arrayOf(Manifest.permission.CAMERA), onDenied = { toast(R.string.browser_camera_denied) }) {
                    runCatching {
                        qrInFlight = true
                        cameraQr.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setCaptureActivity(BrowserQrCaptureActivity::class.java)
                            .setPrompt(activity.getString(R.string.browser_qr_camera_prompt))
                            .setBeepEnabled(false).setOrientationLocked(false))
                    }.onFailure { qrInFlight = false; toast(R.string.browser_upload_unavailable) }
                } else {
                    runCatching {
                        qrInFlight = true
                        photoQr.launch("image/*")
                    }.onFailure { qrInFlight = false; toast(R.string.browser_upload_unavailable) }
                }
            }.setNegativeButton(R.string.action_cancel, null).show()
    }

    private fun confirmQr(value: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val address = BrowserInteractionPolicy.confirmedQrWebAddress(value)
        dismissDialog()
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.browser_qr_result)
            .setMessage(value.take(8192))
            .setNegativeButton(R.string.action_cancel, null)
        if (address != null) builder.setPositiveButton(R.string.browser_qr_open) { _, _ ->
            activity.lifecycleScope.launch(Dispatchers.IO) { session.navigateFromUser(activity.applicationContext, address) }
        }
        dialog = builder.show()
    }

    private fun requestAndroidPermissions(required: Array<String>, onDenied: () -> Unit, action: () -> Unit) {
        if (!windowAvailable() || permissionInFlight) { onDenied(); return }
        val missing = required.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { action(); return }
        permissionInFlight = true
        pendingPermissionAction = action
        permissionCancellation = onDenied
        runCatching { permissions.launch(missing.toTypedArray()) }.onFailure {
            permissionInFlight = false
            pendingPermissionAction = null
            permissionCancellation = null
            onDenied()
        }
    }

    private fun dismissDialog() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun windowAvailable(): Boolean = foreground && !activity.isFinishing && !activity.isDestroyed
}
