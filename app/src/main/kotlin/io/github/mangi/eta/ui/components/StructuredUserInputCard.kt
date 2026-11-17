package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import io.github.mangi.eta.agent.runtime.RunInputForms
import io.github.zhh_lgtm.nexus.kernel.api.UserInputPart
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Answers are kept under stable IDs; labels are never used as identifiers or approval evidence. */
@Composable
internal fun StructuredUserInputCard(part: UserInputPart, onSubmit: (String) -> Unit, enabled: Boolean = true) {
    var selected by rememberSaveable(part.requestId) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var texts by rememberSaveable(part.requestId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var others by rememberSaveable(part.requestId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember(part.requestId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        part.questions.forEachIndexed { index, question ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = if (part.questions.size > 1) "${index + 1}. ${question.title}" else question.title,
                    style = MiuixTheme.textStyles.body1)
                if (question.kind == "multiple") Text(text = "可多选",
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                question.options.forEach { option ->
                    val checked = option.id in selected[question.id].orEmpty()
                    Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                        .semantics { this.selected = checked; role = if (question.kind == "multiple") Role.Checkbox else Role.RadioButton }
                        .squircleSurface(if (checked) MiuixTheme.colorScheme.primary.copy(alpha = .10f)
                            else MiuixTheme.colorScheme.surfaceContainerHigh, 14.dp)
                        .clickable(enabled = enabled) {
                            val current = selected[question.id].orEmpty()
                            val next = if (checked) current - option.id else if (question.kind == "single") setOf(option.id)
                                else if (current.size < question.maxSelections) current + option.id else current
                            selected = selected + (question.id to next)
                            error = null
                        }.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(imageVector = if (question.kind == "multiple") {
                            if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank
                        } else if (checked) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option.label, style = MiuixTheme.textStyles.body2)
                            if (option.description.isNotBlank()) Text(option.description, style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                if (question.kind == "text") TextField(value = texts[question.id].orEmpty(),
                    onValueChange = { texts = texts + (question.id to it.take(question.maxLength)) },
                    enabled = enabled, label = "填写回答", modifier = Modifier.fillMaxWidth())
                else if (question.allowOther) TextField(value = others[question.id].orEmpty(),
                    onValueChange = { others = others + (question.id to it.take(question.maxLength)) },
                    enabled = enabled, label = "其他 / 补充", modifier = Modifier.fillMaxWidth())
            }
        }
        error?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error) }
        TextButton(text = "提交回答", enabled = enabled, onClick = {
            val answers = JSONArray()
            part.questions.forEach { question ->
                answers.put(JSONObject().put("id", question.id)
                    .put("selected_ids", JSONArray(question.options.filter { it.id in selected[question.id].orEmpty() }.map { it.id }))
                    .put("text", texts[question.id].orEmpty()).put("other_text", others[question.id].orEmpty()))
            }
            val raw = JSONObject().put("answers", answers).toString()
            if (runCatching { RunInputForms.answer(part.questions, raw) }.isSuccess) onSubmit(raw)
            else error = "请完成必填项，并核对选择数量"
        })
    }
}

