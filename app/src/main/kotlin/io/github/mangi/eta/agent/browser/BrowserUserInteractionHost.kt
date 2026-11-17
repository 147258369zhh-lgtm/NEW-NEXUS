package io.github.mangi.eta.agent.browser

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** Only the visible user-owned window can service browser prompts. Never retained after detach. */
internal interface BrowserUserInteractionHost {
    fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams)
    fun requestMediaPermission(request: PermissionRequest)
    fun cancelMediaPermission(request: PermissionRequest? = null)
    fun cancelPageRequests()
}

internal object BrowserInteractionPolicy {
    fun sameSecureOrigin(first: String, second: String): Boolean {
        val firstUri = runCatching { Uri.parse(first) }.getOrNull() ?: return false
        val secondUri = runCatching { Uri.parse(second) }.getOrNull() ?: return false
        return isSecureOrigin(firstUri) && isSecureOrigin(secondUri) &&
            firstUri.host.equals(secondUri.host, ignoreCase = true) && port(firstUri) == port(secondUri)
    }

    fun confirmedQrWebAddress(value: String): String? {
        if (value.any { it.isISOControl() }) return null
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return null
        return value.trim().takeIf {
            uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.port in -1..65535
        }
    }

    private fun isSecureOrigin(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.port in -1..65535

    private fun port(uri: Uri): Int = if (uri.port == -1) 443 else uri.port
}
