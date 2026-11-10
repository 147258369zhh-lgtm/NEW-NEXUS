package io.github.mangi.eta.fork.a2a

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2AOutputSanitizerTest {
    @Test
    fun deniedDsmlInvokeIsReplacedWithExplicitPermissionError() {
        val raw = """<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name="terminal">bad</｜｜DSML｜｜ invoke></｜｜DSML｜｜ calls>"""

        val cleaned = A2AOutputSanitizer.sanitize(raw, A2AToolAccess.readOnly("peer"))

        assertFalse(cleaned.contains("DSML"))
        assertFalse(cleaned.contains("invoke"))
        assertTrue(cleaned.contains("terminal"))
        assertTrue(cleaned.contains("未授权"))
    }

    @Test
    fun fullWidthDsmlFromProviderNeverLeaksProtocolFragments() {
        val raw = """<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name="get_current_context">\</｜｜DSML｜｜ invoke>\</｜｜DSML｜｜ calls>"""

        val cleaned = A2AOutputSanitizer.sanitize(raw, A2AToolAccess.readOnly("peer"))

        assertFalse(cleaned.contains("DSML"))
        assertFalse(cleaned.contains("invoke"))
        assertFalse(cleaned.contains("calls"))
        assertTrue(cleaned.contains("未执行任何操作"))
    }

    @Test
    fun ordinaryNaturalLanguageIsUnchanged() {
        val answer = "当前电量约为 80%，Wi-Fi 已连接。"
        org.junit.Assert.assertEquals(answer, A2AOutputSanitizer.sanitize(answer, A2AToolAccess.readOnly("peer")))
    }
}
