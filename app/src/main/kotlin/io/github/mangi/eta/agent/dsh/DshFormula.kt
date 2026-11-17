package io.github.mangi.eta.agent.dsh

import net.objecthunter.exp4j.ExpressionBuilder
import net.objecthunter.exp4j.function.Function
import net.objecthunter.exp4j.operator.Operator
import org.json.JSONObject

/** The arithmetic parser is exp4j; this adapter admits only the protocol's bounded vocabulary. */
internal object DshFormula {
    private val token = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)|data(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+\\])+|[A-Za-z_][A-Za-z0-9_]*|[+*/%(),-]")
    private val functions = listOf(
        object : Function("abs", 1) { override fun apply(vararg args: Double) = kotlin.math.abs(args[0]) },
        object : Function("min", 2) { override fun apply(vararg args: Double) = minOf(args[0], args[1]) },
        object : Function("max", 2) { override fun apply(vararg args: Double) = maxOf(args[0], args[1]) },
        object : Function("clamp", 3) { override fun apply(vararg args: Double) = if (args[1] <= args[2]) args[0].coerceIn(args[1], args[2]) else Double.NaN },
    )
    private val remainder = object : Operator("%", 2, true, Operator.PRECEDENCE_MULTIPLICATION) {
        override fun apply(vararg args: Double): Double {
            val divisor = kotlin.math.abs(args[1]); val value = args[0] % divisor
            return if (value < 0) value + divisor else value
        }
    }

    fun evaluate(expression: String, data: Any?): Double? = runCatching {
        require(expression.length in 1..256)
        var offset = 0; var count = 0; var depth = 0
        val output = StringBuilder()
        val variables = linkedMapOf<String, Double>()
        while (offset < expression.length) {
            if (expression[offset].isWhitespace()) { offset++; continue }
            val found = token.matchAt(expression, offset) ?: error("Unsupported expression token")
            require(++count <= 128)
            val value = found.value
            when {
                value == "(" -> { require(++depth <= 16); output.append(value) }
                value == ")" -> { require(--depth >= 0); output.append(value) }
                value.startsWith("data.") || value.startsWith("data[") -> {
                    val number = DshProtocol.binding(JSONObject().put("$", value), data)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)
                    requireNotNull(number)
                    val key = "v${variables.size}"; variables[key] = number; output.append(key)
                }
                value.first().isLetter() -> { require(value in setOf("abs", "min", "max", "clamp")); output.append(value) }
                else -> output.append(value)
            }
            output.append(' '); offset = found.range.last + 1
        }
        require(depth == 0)
        ExpressionBuilder(output.toString()).variables(variables.keys).functions(*functions.toTypedArray()).operator(remainder)
            .implicitMultiplication(false).build().setVariables(variables).evaluate().takeIf(Double::isFinite)
    }.getOrNull()
}
