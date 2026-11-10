package io.github.mangi.eta.agent.capability

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports a conservative OpenAPI 3.x JSON subset into flat, model-callable actions.
 * Remote references, cookies, multipart bodies and unresolved server variables are skipped.
 */
internal object OpenApiConnectorParser {
    fun parse(
        specification: String,
        sourceUrl: String,
        requestedName: String,
        connectorId: String,
        authType: String,
        authHeaderName: String,
    ): ApiConnectorImportResult {
        val root = runCatching { JSONObject(specification) }
            .getOrElse {
                throw ApiConnectorException("INVALID_OPENAPI", "OpenAPI 文档不是 JSON object")
            }
        val version = root.optString("openapi")
        if (!version.startsWith("3.")) {
            throw ApiConnectorException("UNSUPPORTED_OPENAPI", "当前仅支持 OpenAPI 3.x JSON 文档")
        }
        val baseUrl = resolveBaseUrl(root, sourceUrl)
        val info = root.optJSONObject("info")
        val parsedName = requestedName.trim().ifBlank {
            info?.optString("title").orEmpty().ifBlank { "外部 API" }
        }.take(80)
        val description = info?.optString("description").orEmpty().lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
            .take(1_000)
        val paths = root.optJSONObject("paths")
            ?: throw ApiConnectorException("INVALID_OPENAPI", "OpenAPI 文档缺少 paths")
        val actions = mutableListOf<ApiConnectorAction>()
        val usedNames = mutableSetOf<String>()
        var skipped = 0
        paths.keys().asSequence().sorted().forEach { rawPath ->
            val pathObject = paths.optJSONObject(rawPath) ?: return@forEach
            val pathParameters = parameterDefinitions(pathObject.optJSONArray("parameters"), root)
            HTTP_METHODS.forEach { method ->
                val operation = pathObject.optJSONObject(method.lowercase(Locale.ROOT))
                    ?: return@forEach
                if (actions.size >= MAX_ACTIONS) {
                    skipped += 1
                    return@forEach
                }
                val parsed = runCatching {
                    parseOperation(
                        root = root,
                        path = rawPath,
                        method = method,
                        operation = operation,
                        pathParameters = pathParameters,
                        nameSeed = uniqueActionName(
                            operation.optString("operationId"),
                            method,
                            rawPath,
                            usedNames,
                        ),
                    )
                }.getOrNull()
                if (parsed == null) skipped += 1 else actions += parsed
            }
        }
        if (actions.isEmpty()) {
            throw ApiConnectorException(
                "NO_SUPPORTED_OPERATIONS",
                "OpenAPI 文档中没有可安全导入的 JSON API 操作",
            )
        }
        val connector = ApiConnectorDefinition(
            id = connectorId,
            name = parsedName,
            description = description,
            baseUrl = baseUrl,
            authType = authType,
            authHeaderName = authHeaderName,
            actions = actions.sortedBy(ApiConnectorAction::name),
            sourceUrl = sourceUrl,
        )
        return ApiConnectorImportResult(connector = connector, skippedOperations = skipped)
    }

    private fun parseOperation(
        root: JSONObject,
        path: String,
        method: String,
        operation: JSONObject,
        pathParameters: List<ParameterDefinition>,
        nameSeed: String,
    ): ApiConnectorAction? {
        val resolvedPath = path.trim()
        if (!resolvedPath.startsWith('/') || resolvedPath.any(Char::isISOControl) ||
            '?' in resolvedPath || '#' in resolvedPath || ".." in resolvedPath
        ) return null
        val placeholders = PATH_PARAMETER.findAll(resolvedPath)
            .map { it.groupValues[1] }
            .toList()
        if (placeholders.any { !PARAMETER_NAME.matches(it) }) return null
        if (placeholders.distinct().size != placeholders.size) return null
        val operationParameters = parameterDefinitions(operation.optJSONArray("parameters"), root)
        val parameters = (pathParameters + operationParameters)
            .associateBy { it.location + "\u0000" + it.name }
            .values
        val unsupportedParameter = parameters.any { parameter ->
            parameter.location !in setOf("path", "query", "header") ||
                parameter.location == "cookie" ||
                (parameter.location == "header" && !SAFE_REQUEST_HEADER.matches(parameter.name)) ||
                parameter.name.uppercase(Locale.ROOT) in FORBIDDEN_HEADERS
        }
        if (unsupportedParameter) return null
        val pathNames = parameters.filter { it.location == "path" }.map(ParameterDefinition::name)
        if (pathNames.toSet() != placeholders.toSet()) return null

        val properties = JSONObject()
        val required = linkedSetOf<String>()
        parameters.forEach { parameter ->
            properties.put(parameter.name, parameter.schema ?: JSONObject().put("type", "string"))
            if (parameter.required || parameter.location == "path") required += parameter.name
        }
        val bodyParameters = mutableListOf<String>()
        var bodyRequired = false
        operation.optJSONObject("requestBody")?.let { rawBody ->
            val body = resolveReference(rawBody, root)
            if (body == null) return null
            bodyRequired = body.optBoolean("required", false)
            val content = body.optJSONObject("content") ?: return null
            val media = content.optJSONObject("application/json") ?: return null
            val rawSchema = media.optJSONObject("schema") ?: return null
            val schema = resolveSchema(rawSchema, root) ?: return null
            if (schema.optString("type", "object") == "object" || schema.has("properties")) {
                val bodyProperties = schema.optJSONObject("properties") ?: JSONObject()
                val existingProperties = properties.keys().asSequence().toSet()
                bodyProperties.keys().asSequence().toList().forEach { key ->
                    if (key in existingProperties) return null
                    properties.put(key, resolveSchema(bodyProperties.optJSONObject(key), root) ?: JSONObject())
                    bodyParameters += key
                }
                schema.optJSONArray("required").strings().forEach(required::add)
            } else {
                properties.put("body", schema)
                bodyParameters += "body"
                if (bodyRequired) required += "body"
            }
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
        val summary = operation.optString("summary").trim()
        val detail = operation.optString("description").trim()
        val description = summary.ifBlank { detail }
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .take(1_000)
        return ApiConnectorAction(
            name = nameSeed,
            description = description.ifBlank { "$method $resolvedPath" },
            method = method,
            path = resolvedPath,
            inputSchemaJson = schema.toString(),
            pathParameters = pathNames.distinct(),
            queryParameters = parameters.filter { it.location == "query" }.map(ParameterDefinition::name).distinct(),
            headerParameters = parameters.filter { it.location == "header" }.map(ParameterDefinition::name).distinct(),
            bodyParameters = bodyParameters.distinct(),
            requestBodyRequired = bodyRequired,
            enabled = false,
            destructive = method !in setOf(ApiHttpMethod.GET),
        )
    }

    private fun parameterDefinitions(
        values: JSONArray?,
        root: JSONObject,
    ): List<ParameterDefinition> {
        if (values == null) return emptyList()
        val result = mutableListOf<ParameterDefinition>()
        for (index in 0 until values.length()) {
            val raw = values.optJSONObject(index) ?: continue
            val value = resolveReference(raw, root) ?: continue
            val name = value.optString("name").trim()
            val location = value.optString("in").lowercase(Locale.ROOT)
            if (!PARAMETER_NAME.matches(name) || location !in setOf("path", "query", "header", "cookie")) {
                continue
            }
            result += ParameterDefinition(
                name = name,
                location = location,
                required = value.optBoolean("required", false) || location == "path",
                schema = value.optJSONObject("schema")?.let { resolveSchema(it, root) },
            )
        }
        return result
    }

    private fun resolveBaseUrl(root: JSONObject, sourceUrl: String): String {
        val servers = root.optJSONArray("servers")
        val declared = if (servers != null && servers.length() > 0) {
            servers.optJSONObject(0)?.optString("url").orEmpty()
        } else {
            ""
        }
        if ('{' in declared || '}' in declared) {
            throw ApiConnectorException("SERVER_VARIABLES", "OpenAPI 服务地址包含未解析变量")
        }
        val resolved = runCatching {
            if (declared.isBlank()) {
                val source = URI(sourceUrl)
                URI(source.scheme, source.authority, "/", null, null).toASCIIString()
            } else {
                URI(sourceUrl).resolve(declared).toASCIIString()
            }
        }.getOrElse {
            throw ApiConnectorException("INVALID_SERVER_URL", "OpenAPI 服务地址无效")
        }
        val uri = runCatching { URI(resolved) }
            .getOrElse { throw ApiConnectorException("INVALID_SERVER_URL", "OpenAPI 服务地址无效") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in setOf(-1, 443)
        ) {
            throw ApiConnectorException(
                "INVALID_SERVER_URL",
                "OpenAPI 服务地址必须是无凭据、查询参数和片段的 HTTPS 地址",
            )
        }
        return uri.toASCIIString().trimEnd('/')
    }

    private fun resolveReference(value: JSONObject, root: JSONObject): JSONObject? {
        val reference = value.optString("\$ref")
        if (reference.isBlank()) return value
        if (!reference.startsWith("#/")) return null
        var current: Any = root
        reference.removePrefix("#/").split('/').forEach { rawSegment ->
            val segment = rawSegment.replace("~1", "/").replace("~0", "~")
            current = (current as? JSONObject)?.opt(segment) ?: return null
        }
        return current as? JSONObject
    }

    private fun resolveSchema(value: JSONObject?, root: JSONObject): JSONObject? {
        if (value == null) return null
        val resolved = resolveReference(value, root) ?: return null
        return runCatching { JSONObject(resolved.toString()) }.getOrNull()
    }

    private fun uniqueActionName(
        rawOperationId: String,
        method: String,
        path: String,
        used: MutableSet<String>,
    ): String {
        val seed = rawOperationId.trim().ifBlank { "${method}_$path" }
            .replace(Regex("([a-z0-9])([A-Z])"), "${'$'}1_${'$'}2")
        var candidate = seed.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .replace(Regex("_{2,}"), "_")
            .trim('_', '-')
            .take(56)
            .ifBlank { "operation" }
        if (!candidate.first().isLetter()) candidate = "api_$candidate"
        var suffix = 2
        val base = candidate
        while (!used.add(candidate)) {
            candidate = base.take(56 - suffix.toString().length - 1) + "_$suffix"
            suffix += 1
        }
        return candidate
    }

    private data class ParameterDefinition(
        val name: String,
        val location: String,
        val required: Boolean,
        val schema: JSONObject?,
    )

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private val HTTP_METHODS = listOf(
        ApiHttpMethod.GET,
        ApiHttpMethod.POST,
        ApiHttpMethod.PUT,
        ApiHttpMethod.PATCH,
        ApiHttpMethod.DELETE,
    )
    private const val MAX_ACTIONS = 128
    private val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")
    private val PATH_PARAMETER = Regex("\\{([A-Za-z_][A-Za-z0-9_-]{0,63})}")
    private val SAFE_REQUEST_HEADER = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")
    private val FORBIDDEN_HEADERS = setOf(
        "AUTHORIZATION", "HOST", "CONTENT-LENGTH", "TRANSFER-ENCODING",
        "CONNECTION", "COOKIE", "PROXY-AUTHORIZATION",
    )
}
