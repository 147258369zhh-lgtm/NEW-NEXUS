package io.github.mangi.eta.agent.model

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.LocalNetworkFailureInterceptor
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    // A streaming response resets this idle timeout on every chunk. Two minutes is long
    // enough for reasoning models to start producing data, without leaving a dead provider
    // socket stalled for five minutes on every attempt.
    const val MODEL_READ_TIMEOUT_MS = 120_000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val compactionClient: OkHttpClient by lazy {
        modelClient.newBuilder()
            .callTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val memoryClient: OkHttpClient by lazy {
        modelClient.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()
    }

    fun modelClient(purpose: ProviderRequestPurpose): OkHttpClient =
        when (purpose) {
            ProviderRequestPurpose.COMPACTION -> compactionClient
            ProviderRequestPurpose.MEMORY_EXTRACTION -> memoryClient
            else -> modelClient
        }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(LocalNetworkFailureInterceptor(
                accessDenied = { appContext?.let(LocalNetworkPermission::accessState) == LocalNetworkPermission.AccessState.DENIED },
                permissionMessage = { requireNotNull(appContext).getString(R.string.compat_lan_connection_permission) },
            ))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
