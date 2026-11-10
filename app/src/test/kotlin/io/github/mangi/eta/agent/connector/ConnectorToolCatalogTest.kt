package io.github.mangi.eta.agent.connector

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorToolCatalogTest {
    @Test
    fun connectorToolIsModelVisibleAndRequiresProtocolAndAction() {
        val tools = JSONArray()
        ConnectorToolCatalog.appendTo(tools)

        val tool = tools.getJSONObject(0)
            .getJSONObject("function")
        assertEquals("connector_call", tool.getString("name"))
        assertTrue(tool.getJSONObject("parameters").getJSONArray("required").toString().contains("action"))
        assertTrue(tool.getJSONObject("parameters").getJSONObject("properties").has("connection_id"))
    }
}
