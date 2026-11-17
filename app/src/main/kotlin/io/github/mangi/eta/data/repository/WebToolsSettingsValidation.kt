package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.web.ConfigurableWebBackends
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings

internal class WebToolsValidationFailure(val userMessage: String) : IllegalArgumentException(userMessage)

internal fun validateWebToolsConfiguration(settings: WebToolsSettings, credentials: WebToolsCredentials, hasProviderDeepSeekKey: Boolean) {
    val usesDeepSeek = settings.searchBackend == WebToolBackend.DEEPSEEK || settings.fetchBackend == WebToolBackend.DEEPSEEK
    if (usesDeepSeek) {
        if (credentials.deepSeekApiKey.isBlank() && !hasProviderDeepSeekKey)
            throw WebToolsValidationFailure("请填写 DeepSeek API Key，或先在模型提供商中配置 DeepSeek")
        runCatching { ConfigurableWebBackends.deepSeekEndpoint(settings.deepSeek.baseUrl, "search") }
            .getOrElse { throw WebToolsValidationFailure("DeepSeek Base URL 须为合法的 HTTP(S) 地址") }
    }
    if (settings.searchBackend == WebToolBackend.CUSTOM) {
        val raw = settings.customSearch.url.trim()
        if (raw.isEmpty()) throw WebToolsValidationFailure("请填写自定义搜索接口地址")
        runCatching { WebHttpTransport.validateUrl(raw.replace("{{query}}", "eta").replace("{{raw_query}}", "eta").replace("{{count}}", "5")) }
            .getOrElse { throw WebToolsValidationFailure("自定义搜索接口须为合法的 HTTP(S) 地址") }
        runCatching { ConfigurableWebBackends.parseCustomHeaders(settings.customSearch.headersText, credentials.customSearchApiKey) }
            .getOrElse { throw WebToolsValidationFailure("自定义搜索请求头格式无效") }
    }
    if (settings.fetchBackend == WebToolBackend.CUSTOM) {
        val raw = settings.customFetch.url.trim()
        if (raw.isEmpty()) throw WebToolsValidationFailure("请填写自定义读取接口地址")
        runCatching { WebHttpTransport.validateUrl(raw.replace("{{url}}", "https%3A%2F%2Fexample.com").replace("{{raw_url}}", "https://example.com")) }
            .getOrElse { throw WebToolsValidationFailure("自定义读取接口须为合法的 HTTP(S) 地址") }
        runCatching { ConfigurableWebBackends.parseCustomHeaders(settings.customFetch.headersText, credentials.customFetchApiKey) }
            .getOrElse { throw WebToolsValidationFailure("自定义读取请求头格式无效") }
    }
}
