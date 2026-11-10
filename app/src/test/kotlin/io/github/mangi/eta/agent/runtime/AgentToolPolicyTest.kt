package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPolicyTest {
    @Test fun intersectionsKeepOnlyNarrowerWildcardCapabilities() {
        val parent = AgentToolPolicy(listOf("mcp_read*", "read_file"), listOf("mcp_read_secrets"))
        val child = AgentToolPolicy(listOf("*"), listOf("write_file"))
        val combined = child.intersect(parent)
        assertTrue(combined.isAllowed("mcp_read_calendar")); assertTrue(combined.isAllowed("read_file"))
        assertFalse(combined.isAllowed("mcp_read_secrets")); assertFalse(combined.isAllowed("mcp_write"))
        assertFalse(combined.isAllowed("terminal"))
        assertEquals(listOf("mcp_read*"), parent.intersect(AgentToolPolicy(listOf("mcp_*"))).allowedTools)
        assertEquals(emptyList<String>(), parent.intersect(AgentToolPolicy(emptyList())).allowedTools)
    }

    @Test fun `active skill policies combine capabilities without widening the parent boundary`() {
        val firstSkill = AgentToolPolicy(listOf("read_file", "skills_read"))
        val secondSkill = AgentToolPolicy(listOf("web_search", "skills_read"))
        val combined = firstSkill.union(secondSkill)

        assertTrue(combined.isAllowed("read_file"))
        assertTrue(combined.isAllowed("web_search"))
        assertTrue(combined.isAllowed("skills_read"))
        assertFalse(combined.isAllowed("terminal_exec"))

        val parent = AgentToolPolicy(listOf("read_file", "web_search"), listOf("web_search"))
        val effective = combined.intersect(parent)
        assertTrue(effective.isAllowed("read_file"))
        assertFalse(effective.isAllowed("web_search"))
        assertFalse(effective.isAllowed("terminal_exec"))
    }
    @Test
    fun `empty allowlist denies every tool`() {
        val policy = AgentToolPolicy(allowedTools = emptyList())

        assertFalse(policy.isAllowed("read_file"))
        assertEquals(0, policy.filterSchemas(schemas("read_file", "write_file")).length())
    }

    @Test
    fun `allowlist is narrowed by denylist`() {
        val policy = AgentToolPolicy(
            allowedTools = listOf("read_file", "web_search"),
            disallowedTools = listOf("web_search"),
        )

        assertTrue(policy.isAllowed("read_file"))
        assertFalse(policy.isAllowed("web_search"))
        assertFalse(policy.isAllowed("write_file"))
    }

    @Test
    fun `unrestricted still honors denylist and filters schemas`() {
        val policy = AgentToolPolicy(disallowedTools = listOf("write_file"))
        val filtered = policy.filterSchemas(schemas("read_file", "write_file"))

        assertTrue(policy.isAllowed("read_file"))
        assertFalse(policy.isAllowed("write_file"))
        assertEquals(1, filtered.length())
        assertEquals("read_file", filtered.getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun `prefix allowlist supports dynamic mcp tools while denylist still wins`() {
        val policy = AgentToolPolicy(
            allowedTools = listOf("read_file", "mcp_*"),
            disallowedTools = listOf("mcp_dangerous"),
        )

        assertTrue(policy.isAllowed("read_file"))
        assertTrue(policy.isAllowed("mcp_calendar"))
        assertFalse(policy.isAllowed("mcp_dangerous"))
        assertFalse(policy.isAllowed("write_file"))
    }

    private fun schemas(vararg names: String): JSONArray = JSONArray().apply {
        names.forEach { name ->
            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name)))
        }
    }
}
