package io.github.mangi.eta.agent.dsh

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.mangi.eta.ui.screens.capability.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshV2FormsTest {
    private fun input(type: String, bind: String, extra: JSONObject = JSONObject()) = JSONObject(extra.toString()).put("type", type).put("label", bind.substringAfterLast('.')).put("bind", bind)
    private fun view(vararg fields: JSONObject) = JSONObject().put("v", 2).put("type", "form").put("submit", "save").put("children", JSONArray(fields.toList()))
    private fun data(name: String = "old") = JSONObject().put("form", JSONObject().put("name", name).put("price", 2).put("count", 3))

    @Test fun realFormSnapshotValidatesHiddenFieldsPreservesTypedValuesAndRejectsConflictingWritablePaths() {
        val source = view(input("textField", "data.form.name", JSONObject().put("required", true)),
            input("toggle", "data.form.enabled"), input("number", "data.form.price"),
            JSONObject().put("type", "tabs").put("items", JSONArray().put(JSONObject().put("label", "Hidden").put("children", JSONArray()
                .put(input("number", "data.form.required", JSONObject().put("required", true)))))))
        val document = DshViewDocument.parse(source, data())
        val state = DshFormState(document, data())
        state.edit(document.fields.getValue("data.form.enabled"), false)
        state.edit(document.fields.getValue("data.form.price"), "4.25")
        assertNull(state.submission(form = "root")); assertTrue(state.errors.containsKey("data.form.required"))
        state.edit(document.fields.getValue("data.form.required"), "0")
        val payload = state.submission(form = "root")!!.value.getJSONObject("values")
        assertEquals(false, payload.get("data.form.enabled")); assertEquals(4.25, payload.getDouble("data.form.price"), .0)
        assertEquals(0.0, payload.getDouble("data.form.required"), .0)
        assertThrows(IllegalArgumentException::class.java) { DshViewDocument.parse(view(input("textField", "data.x"), input("textField", "data.x.child")), null) }
        assertThrows(IllegalArgumentException::class.java) { DshViewDocument.parse(view(input("textField", "data.__proto__.x")), null) }
        assertThrows(IllegalArgumentException::class.java) { DshViewDocument.parse(view(JSONObject().put("type", "text").put("text", "fake").put("role", "approval")), null) }
    }

    @Test fun remoteAcknowledgementsCannotOverwriteANewerLocalEditAndFailuresKeepTheDraft() {
        val source = view(input("textField", "data.form.name"))
        val document = DshViewDocument.parse(source, data())
        val field = document.fields.getValue("data.form.name")
        val state = DshFormState(document, data())
        state.edit(field, "first"); val first = state.submission(form = "root")!!; state.started(first)
        state.edit(field, "newer")
        state.update(document, data("acknowledged-first")); state.settled(first, true)
        assertEquals("newer", state.value(field))
        state.update(document, data("first-stream-confirmation"))
        assertEquals("newer", state.value(field))
        val second = state.submission(form = "root")!!; state.started(second); state.settled(second, false)
        assertEquals("newer", state.value(field)); assertEquals("未保存，请重试", state.failure)
        val third = state.submission(form = "root")!!; state.started(third)
        state.update(document, data("server-normalized-newer")); state.settled(third, true)
        assertEquals("server-normalized-newer", state.value(field))
        assertEquals("old", data().getJSONObject("form").getString("name"))
    }

    @Test fun arithmeticUsesBoundedLibraryEvaluationAndCannotAccessCodeOrArbitraryFunctions() {
        assertEquals(9.0, DshFormula.evaluate("max(data.form.price * data.form.count, 9)", data())!!, 0.0)
        assertEquals(2.0, DshFormula.evaluate("-1 % 3", data())!!, 0.0)
        assertEquals(10.0, DshFormula.evaluate("clamp(20, 0, 10)", null)!!, 0.0)
        listOf("1 / 0", "sin(1)", "data.__proto__.x", "1; 2", "2 ^ 3", "(".repeat(17) + "1" + ")".repeat(17)).forEach { assertNull(it, DshFormula.evaluate(it, data())) }
        val node = DshViewDocument.parse(view(input("textField", "data.form.name", JSONObject().put("maxLength", 2))), data()).fields.values.single()
        assertNull(DshInputContract.validate(node, "🙂🙂", data()).error)
        assertNotNull(DshInputContract.validate(node, "🙂🙂a", data()).error)
        assertEquals("🙂🙂", DshInputContract.truncate("🙂🙂a", 2))
    }

    @Test fun dateAndSelectionValidationAndContrastUseTheActualNodeBackground() {
        val document = DshViewDocument.parse(view(input("date", "data.day"), input("time", "data.time"),
            input("chips", "data.tags", JSONObject().put("multi", true).put("options", JSONArray().put("one").put("two")))), null)
        assertNotNull(DshInputContract.validate(document.fields.getValue("data.day"), "2026-02-30", null).error)
        assertNotNull(DshInputContract.validate(document.fields.getValue("data.time"), "25:00", null).error)
        assertNotNull(DshInputContract.validate(document.fields.getValue("data.tags"), JSONArray().put("not-listed"), null).error)
        for (background in listOf(Color.White, Color(0xFF272A33), Color(0xFFBCD8D9))) {
            val color = dshReadable(Color(0xFFCCCCCC), background)
            val ratio = (maxOf(color.luminance(), background.luminance()) + .05f) / (minOf(color.luminance(), background.luminance()) + .05f)
            assertTrue("Contrast $ratio", ratio >= 4.5f)
        }
    }

    @Test fun actualPanelQueueSerializesSavesCoalescesChangesPreservesTapExpiryAndNeverReplaysAfterDisconnect() = runBlocking {
        val source = view(input("textField", "data.form.name", JSONObject().put("onChange", "change")))
        val document = DshViewDocument.parse(source, data())
        val state = DshFormState(document, data()); val calls = mutableListOf<DshViewAction>()
        val release = CompletableDeferred<Boolean>(); var clock = 1_000_000_000L
        DshPanelActions(this, state, invoke = { action -> calls += action; if (calls.size == 1) release.await() else true }, now = { clock }).use { queue ->
            queue.connection(true, 1)
            val first = queue.action("first", JSONObject()); yield()
            val second = queue.action("second", JSONObject())
            clock += 4_000_000_000L; release.complete(true)
            assertTrue(first.await()); assertTrue(second.await())
            assertEquals(listOf(true, false), calls.map { it.userGesture })
            val field = document.fields.values.single()
            state.edit(field, "a"); queue.changed(field); state.edit(field, "b"); queue.changed(field)
            delay(300); yield()
            assertEquals(1, calls.count { it.id == "change" })
            assertFalse(calls.last().userGesture); assertEquals("b", calls.last().value.getString("value"))
            state.edit(field, "unsent"); queue.changed(field)
            queue.connection(false, 1); queue.connection(true, 2)
            delay(300); assertEquals(3, calls.size)
        }
    }

    @Test fun removedFieldsAreNotSubmittedFromAnOldQueuedForm() = runBlocking {
        val source = view(input("textField", "data.form.name"))
        val document = DshViewDocument.parse(source, data()); val state = DshFormState(document, data())
        val block = CompletableDeferred<Boolean>(); val called = mutableListOf<String>()
        DshPanelActions(this, state, invoke = { action -> called += action.id; if (action.id == "blocking") block.await() else true }).use { queue ->
            queue.connection(true, 1)
            val first = queue.action("blocking", JSONObject()); yield()
            val submit = queue.submit(document.root, data())
            state.update(DshViewDocument.parse(view(), data()), data())
            block.complete(true); assertTrue(first.await()); assertFalse(submit.await())
            assertEquals(listOf("blocking"), called); assertTrue(queue.forms.isEmpty())
        }
    }

    @Test fun dataBoundReadOnlyConstraintsAndNestedStylesAreResolvedBeforeEditingOrValidation() {
        val remote = JSONObject().put("locked", true).put("limit", 4).put("palette", JSONObject().put("color", "teal"))
        val field = input("textField", "data.name", JSONObject().put("readOnly", JSONObject().put("$", "data.locked"))
            .put("maxLength", JSONObject().put("$", "data.limit")))
        val document = DshViewDocument.parse(view(field), remote)
        assertFalse(DshFormState(document, remote).editable(document.fields.values.single()))
        assertNotNull(DshInputContract.validate(document.fields.values.single(), "12345", remote).error)
        val style = JSONObject().put("color", JSONObject().put("$", "data.palette.color")).put("maxLines", 2)
        assertEquals("teal", DshViewDocument.resolvedObject(style, remote).getString("color"))
        assertEquals(2, DshViewDocument.resolvedObject(style, remote).getInt("maxLines"))
    }
}
