package io.github.mangi.eta.agent.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenApiConnectorParserTest {
    private val spec = JSONObject()
        .put("openapi", "3.1.0")
        .put(
            "info",
            JSONObject()
                .put("title", "Project API")
                .put("description", "Manage projects"),
        )
        .put("servers", org.json.JSONArray().put(JSONObject().put("url", "https://api.example.com/v1")))
        .put(
            "paths",
            JSONObject()
                .put(
                    "/projects/{projectId}",
                    JSONObject()
                        .put(
                            "parameters",
                            org.json.JSONArray().put(
                                JSONObject()
                                    .put("name", "projectId")
                                    .put("in", "path")
                                    .put("required", true)
                                    .put("schema", JSONObject().put("type", "string")),
                            ),
                        )
                        .put(
                            "get",
                            JSONObject()
                                .put("operationId", "getProject")
                                .put("summary", "Read a project")
                                .put(
                                    "parameters",
                                    org.json.JSONArray().put(
                                        JSONObject()
                                            .put("name", "expand")
                                            .put("in", "query")
                                            .put("schema", JSONObject().put("type", "boolean")),
                                    ),
                                ),
                        )
                        .put(
                            "patch",
                            JSONObject()
                                .put("operationId", "updateProject")
                                .put(
                                    "requestBody",
                                    JSONObject()
                                        .put("required", true)
                                        .put(
                                            "content",
                                            JSONObject().put(
                                                "application/json",
                                                JSONObject().put(
                                                    "schema",
                                                    JSONObject()
                                                        .put("type", "object")
                                                        .put(
                                                            "properties",
                                                            JSONObject().put(
                                                                "name",
                                                                JSONObject().put("type", "string"),
                                                            ),
                                                        )
                                                        .put("required", org.json.JSONArray().put("name")),
                                                ),
                                            ),
                                        ),
                                ),
                        ),
                ),
        )

    @Test
    fun importsSafeOperationsAndKeepsThemDisabledUntilExplicitApproval() {
        val result = OpenApiConnectorParser.parse(
            specification = spec.toString(),
            sourceUrl = "https://api.example.com/openapi.json",
            requestedName = "",
            connectorId = "api-test",
            authType = ApiConnectorAuthType.BEARER,
            authHeaderName = "X-API-Key",
        )

        assertEquals("Project API", result.connector.name)
        assertEquals("https://api.example.com/v1", result.connector.baseUrl)
        assertEquals(2, result.connector.actions.size)
        assertTrue(result.connector.actions.all { !it.enabled })
        val get = result.connector.actions.single { it.name == "get_project" }
        assertEquals(ApiHttpMethod.GET, get.method)
        assertEquals(listOf("projectId"), get.pathParameters)
        assertEquals(listOf("expand"), get.queryParameters)
        assertFalse(get.destructive)
        val patch = result.connector.actions.single { it.name == "update_project" }
        assertEquals(listOf("name"), patch.bodyParameters)
        assertTrue(patch.destructive)
        val schema = JSONObject(patch.inputSchemaJson)
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("name").getString("type"))
        val required = schema.getJSONArray("required")
        assertTrue((0 until required.length()).any { required.getString(it) == "name" })
    }

    @Test
    fun rejectsUnresolvedServerVariablesAndUnsafeBodies() {
        val invalidServer = JSONObject(spec.toString()).put(
            "servers",
            org.json.JSONArray().put(JSONObject().put("url", "https://{tenant}.example.com/api")),
        )
        assertThrows(ApiConnectorException::class.java) {
            OpenApiConnectorParser.parse(
                specification = invalidServer.toString(),
                sourceUrl = "https://api.example.com/openapi.json",
                requestedName = "",
                connectorId = "api-test",
                authType = ApiConnectorAuthType.NONE,
                authHeaderName = "X-API-Key",
            )
        }

        val multipartOnly = JSONObject(spec.toString())
        val path = multipartOnly.getJSONObject("paths").getJSONObject("/projects/{projectId}")
        path.getJSONObject("patch").getJSONObject("requestBody")
            .put("content", JSONObject().put("multipart/form-data", JSONObject().put("schema", JSONObject())))
        val result = OpenApiConnectorParser.parse(
            specification = multipartOnly.toString(),
            sourceUrl = "https://api.example.com/openapi.json",
            requestedName = "",
            connectorId = "api-test",
            authType = ApiConnectorAuthType.NONE,
            authHeaderName = "X-API-Key",
        )
        assertEquals(1, result.connector.actions.size)
        assertEquals(1, result.skippedOperations)
    }

    @Test
    fun rejectsNonOpenApiDocuments() {
        assertThrows(ApiConnectorException::class.java) {
            OpenApiConnectorParser.parse(
                specification = "{\"swagger\":\"2.0\"}",
                sourceUrl = "https://api.example.com/openapi.json",
                requestedName = "",
                connectorId = "api-test",
                authType = ApiConnectorAuthType.NONE,
                authHeaderName = "X-API-Key",
            )
        }
    }
}
