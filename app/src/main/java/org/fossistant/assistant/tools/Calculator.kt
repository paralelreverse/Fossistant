package org.fossistant.assistant.tools

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * The `calculate` tool: evaluates a math expression so the model never does arithmetic in its head.
 *
 * Supports + - * / ^ (or **), parentheses, implicit multiplication (`2pi`, `3(4+5)`), factorials (`5!`), modulo
 * (`10 % 3`, `10 mod 3`), percentages (`15% of 80`, `100 + 10%` = 110 like a pocket calculator), degrees
 * (`sin(30deg)`, `30°`), thousands separators (`1,234.5`), the constants pi, e and tau, and the functions in
 * [FUNCTIONS]. Plain arithmetic is exact (BigDecimal), so big products and money sums don't pick up floating-point
 * errors; sqrt, logs and trigonometry use doubles.
 */
object Calculator {

    class CalculationException(message: String) : IllegalArgumentException(message)

    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)
    private const val MAX_EXACT_EXPONENT = 9999
    private const val MAX_FACTORIAL = 1000

    private val CONSTANTS = mapOf(
        "pi" to BigDecimal("3.141592653589793238462643383279503"),
        "tau" to BigDecimal("6.283185307179586476925286766559006"),
        "e" to BigDecimal("2.718281828459045235360287471352662")
    )

    /** Function names the parser accepts. */
    val FUNCTIONS = setOf(
        "sqrt", "cbrt", "abs", "ln", "log", "log10", "log2", "exp", "sin", "cos", "tan", "asin", "acos", "atan",
        "atan2", "sinh", "cosh", "tanh", "floor", "ceil", "round", "min", "max", "pow", "hypot", "fact", "factorial",
        "mod", "sum", "avg", "mean"
    )

    /** Spoken or typographic forms rewritten to the parser's syntax. */
    private val REWRITES = listOf(
        Regex("[×✕✖·∙]") to "*",
        Regex("[÷∕]") to "/",
        Regex("[−–—]") to "-",
        Regex("\\*\\*") to "^",
        Regex("π") to "pi",
        Regex("√") to " sqrt ",
        Regex("²") to "^2",
        Regex("³") to "^3",
        Regex("[$€£¥₹]") to "",
        Regex("\\bsquare root of\\b") to " sqrt ",
        Regex("\\bcube root of\\b") to " cbrt ",
        Regex("\\bto the power of\\b") to "^",
        Regex("\\bsquared\\b") to "^2",
        Regex("\\bcubed\\b") to "^3",
        Regex("\\bmultiplied by\\b") to "*",
        Regex("\\bdivided by\\b") to "/",
        Regex("\\btimes\\b") to "*",
        Regex("\\bplus\\b") to "+",
        Regex("\\bminus\\b") to "-",
        Regex("\\bpercent\\b") to "%",
        // "10 mod 3"; mod(10, 3) stays a function call.
        Regex("\\s+mod\\s+") to " % ",
        Regex("\\bof\\b") to "*",
        // "3 x 4": an x between numbers or brackets is a multiplication sign.
        Regex("(?<=[0-9).])\\s*x\\s*(?=[0-9(.])") to "*"
    )

    /** Evaluates [expression]; throws [CalculationException] with a short reason when it can't. */
    fun evaluate(expression: String): BigDecimal {
        val text = normalize(expression)
        if (text.isBlank()) throw CalculationException("The expression is empty")
        return Parser(text).parse()
    }

    /** The expression as the parser reads it (also shown back to the model). */
    fun normalize(expression: String): String {
        var text = expression.lowercase().trim().trimEnd('=', '?', '.', ' ')
        for ((pattern, replacement) in REWRITES) text = pattern.replace(text, replacement)
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Shows [value] the way a calculator would: whole numbers up to 30 digits in full, everything else rounded to 15
     * significant digits, in scientific notation ("1.5e-9") when very large or very small.
     */
    fun format(value: BigDecimal): String {
        val v = value.stripTrailingZeros()
        if (v.signum() == 0) return "0"
        if (v.scale() <= 0 && v.precision() - v.scale() <= 30) return v.toPlainString()
        val rounded = v.round(MathContext(15, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val magnitude = rounded.precision() - rounded.scale() - 1
        if (magnitude in -7..20) return rounded.toPlainString()
        val mantissa = rounded.movePointLeft(magnitude).stripTrailingZeros().toPlainString()
        return "${mantissa}e$magnitude"
    }

    /** One operand: its value, and whether it was written as a bare percentage ("10%"). */
    private data class Operand(val value: BigDecimal, val isPercent: Boolean = false)

    private class Parser(private val text: String) {
        private var pos = 0

        /** Inside a function's parentheses a comma separates arguments instead of grouping thousands. */
        private var inArguments = false

        fun parse(): BigDecimal {
            val value = expression()
            skipSpaces()
            if (pos < text.length) throw CalculationException("Unexpected '${text[pos]}' at position ${pos + 1}")
            return value
        }

        private fun expression(): BigDecimal {
            var left = term().value
            while (true) {
                skipSpaces()
                val op = peek()
                if (op != '+' && op != '-') return left
                pos++
                val right = term()
                // "100 + 10%" adds ten percent of 100.
                val amount = if (right.isPercent) left.multiply(right.value, MC) else right.value
                left = if (op == '+') left.add(amount, MC) else left.subtract(amount, MC)
            }
        }

        private fun term(): Operand {
            var left = unary()
            while (true) {
                skipSpaces()
                val c = peek() ?: return left
                left = when {
                    c == '*' -> { pos++; Operand(left.value.multiply(unary().value, MC)) }
                    c == '/' -> { pos++; Operand(divide(left.value, unary().value)) }
                    c == '%' -> { pos++; Operand(remainder(left.value, unary().value)) }
                    startsOperand(c) -> Operand(left.value.multiply(unary().value, MC))
                    else -> return left
                }
            }
        }

        private fun unary(): Operand {
            skipSpaces()
            return when (peek()) {
                '-' -> { pos++; unary().let { Operand(it.value.negate(), it.isPercent) } }
                '+' -> { pos++; unary() }
                else -> power()
            }
        }

        private fun power(): Operand {
            val base = postfix()
            skipSpaces()
            if (peek() != '^') return base
            pos++
            return Operand(pow(base.value, unary().value))
        }

        private fun postfix(): Operand {
            var value = primary()
            var percent = false
            while (true) {
                skipSpaces()
                when {
                    peek() == '!' -> { pos++; value = factorial(value) }
                    peek() == '%' && !operandAfterPercent() -> { pos++; value = value.divide(HUNDRED, MC); percent = true }
                    peek() == '°' -> { pos++; value = degreesToRadians(value) }
                    text.startsWith("degrees", pos) -> { pos += 7; value = degreesToRadians(value) }
                    text.startsWith("deg", pos) -> { pos += 3; value = degreesToRadians(value) }
                    else -> return Operand(value, percent)
                }
            }
        }

        private fun primary(): BigDecimal {
            skipSpaces()
            val c = peek() ?: throw CalculationException("The expression ends too early")
            return when {
                c.isDigit() || c == '.' -> number()
                c == '(' || c == '[' -> {
                    pos++
                    val saved = inArguments
                    inArguments = false
                    val value = expression()
                    inArguments = saved
                    skipSpaces()
                    if (peek() != ')' && peek() != ']') throw CalculationException("Missing ')'")
                    pos++
                    value
                }
                c.isLetter() -> identifier()
                else -> throw CalculationException("Unexpected '$c' at position ${pos + 1}")
            }
        }

        private fun number(): BigDecimal {
            val start = pos
            val digits = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos]
                when {
                    ch.isDigit() || ch == '.' -> { digits.append(ch); pos++ }
                    ch == ',' && !inArguments && '.' !in digits && isThousandsGroup(pos) -> pos++
                    (ch == 'e') && exponentAt(pos) -> {
                        digits.append('e')
                        pos++
                        if (text[pos] == '+' || text[pos] == '-') digits.append(text[pos++])
                        while (pos < text.length && text[pos].isDigit()) digits.append(text[pos++])
                    }
                    else -> break
                }
            }
            return digits.toString().toBigDecimalOrNull()
                ?: throw CalculationException("'${text.substring(start, pos)}' isn't a number")
        }

        private fun identifier(): BigDecimal {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            val name = text.substring(start, pos)
            CONSTANTS[name]?.let { return it }
            if (name !in FUNCTIONS) throw CalculationException("Unknown name '$name'")
            skipSpaces()
            val args = if (peek() == '(') {
                pos++
                val saved = inArguments
                inArguments = true
                val list = mutableListOf(expression())
                while (true) {
                    skipSpaces()
                    if (peek() != ',' && peek() != ';') break
                    pos++
                    list.add(expression())
                }
                inArguments = saved
                skipSpaces()
                if (peek() != ')') throw CalculationException("Missing ')' after $name(")
                pos++
                list
            } else {
                // "sqrt 16", "sin 30deg"
                listOf(power().value)
            }
            return applyFunction(name, args)
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipSpaces() {
            while (pos < text.length && text[pos] == ' ') pos++
        }

        private fun startsOperand(c: Char): Boolean = c.isDigit() || c == '.' || c == '(' || c == '[' || c.isLetter()

        /** After '%': another operand means modulo ("10 % 3"), anything else a percentage ("50% * 80"). */
        private fun operandAfterPercent(): Boolean {
            var i = pos + 1
            while (i < text.length && text[i] == ' ') i++
            val next = text.getOrNull(i) ?: return false
            return next.isDigit() || next == '.' || next == '(' || next == '[' || next.isLetter()
        }

        /** ",234" followed by a non-digit: a thousands separator. */
        private fun isThousandsGroup(at: Int): Boolean =
            (1..3).all { text.getOrNull(at + it)?.isDigit() == true } && text.getOrNull(at + 4)?.isDigit() != true

        private fun exponentAt(at: Int): Boolean {
            val next = text.getOrNull(at + 1) ?: return false
            if (next.isDigit()) return true
            return (next == '+' || next == '-') && text.getOrNull(at + 2)?.isDigit() == true
        }
    }

    private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
        if (b.signum() == 0) throw CalculationException("Division by zero")
        return a.divide(b, MC)
    }

    private fun remainder(a: BigDecimal, b: BigDecimal): BigDecimal {
        if (b.signum() == 0) throw CalculationException("Division by zero")
        return a.remainder(b, MC)
    }

    private fun pow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
        val whole = exponent.stripTrailingZeros().let { if (it.scale() <= 0) runCatching { it.intValueExact() }.getOrNull() else null }
        if (whole != null && kotlin.math.abs(whole) <= MAX_EXACT_EXPONENT) {
            if (base.signum() == 0 && whole < 0) throw CalculationException("Division by zero")
            return base.pow(whole, MC)
        }
        return fromDouble(Math.pow(base.toDouble(), exponent.toDouble()), "pow")
    }

    private fun factorial(value: BigDecimal): BigDecimal {
        val n = value.stripTrailingZeros().let { if (it.scale() <= 0) runCatching { it.intValueExact() }.getOrNull() else null }
        if (n == null || n < 0) throw CalculationException("Factorial needs a whole number of 0 or more")
        if (n > MAX_FACTORIAL) throw CalculationException("Factorial of more than $MAX_FACTORIAL is too large")
        var result = BigInteger.ONE
        for (i in 2..n) result = result.multiply(BigInteger.valueOf(i.toLong()))
        return BigDecimal(result)
    }

    private fun degreesToRadians(value: BigDecimal): BigDecimal =
        value.multiply(CONSTANTS.getValue("pi"), MC).divide(BigDecimal(180), MC)

    private fun applyFunction(name: String, args: List<BigDecimal>): BigDecimal {
        fun arity(count: Int) {
            if (args.size != count) throw CalculationException("$name takes $count value${if (count == 1) "" else "s"}")
        }
        fun d(index: Int = 0) = args[index].toDouble()
        return when (name) {
            "abs" -> { arity(1); args[0].abs() }
            "floor" -> { arity(1); args[0].setScale(0, RoundingMode.FLOOR) }
            "ceil" -> { arity(1); args[0].setScale(0, RoundingMode.CEILING) }
            "round" -> {
                if (args.size !in 1..2) throw CalculationException("round takes a value and optionally the number of decimals")
                args[0].setScale(args.getOrNull(1)?.toInt() ?: 0, RoundingMode.HALF_UP)
            }
            "min" -> args.minOrNull() ?: throw CalculationException("min needs values")
            "max" -> args.maxOrNull() ?: throw CalculationException("max needs values")
            "sum" -> args.fold(BigDecimal.ZERO) { acc, v -> acc.add(v, MC) }
            "avg", "mean" -> {
                if (args.isEmpty()) throw CalculationException("$name needs values")
                args.fold(BigDecimal.ZERO) { acc, v -> acc.add(v, MC) }.divide(BigDecimal(args.size), MC)
            }
            "mod" -> { arity(2); remainder(args[0], args[1]) }
            "pow" -> { arity(2); pow(args[0], args[1]) }
            "fact", "factorial" -> { arity(1); factorial(args[0]) }
            "sqrt" -> {
                arity(1)
                if (args[0].signum() < 0) throw CalculationException("No real square root of a negative number")
                exactRoot(args[0], 2) ?: fromDouble(Math.sqrt(d()), name)
            }
            "cbrt" -> { arity(1); exactRoot(args[0], 3) ?: fromDouble(Math.cbrt(d()), name) }
            "ln" -> { arity(1); positive(args[0], name); fromDouble(Math.log(d()), name) }
            "log" -> {
                positive(args[0], name)
                when (args.size) {
                    1 -> fromDouble(Math.log10(d()), name)
                    2 -> { positive(args[1], name); fromDouble(Math.log(d()) / Math.log(d(1)), name) }
                    else -> throw CalculationException("log takes a value and optionally a base")
                }
            }
            "log10" -> { arity(1); positive(args[0], name); fromDouble(Math.log10(d()), name) }
            "log2" -> { arity(1); positive(args[0], name); fromDouble(Math.log(d()) / Math.log(2.0), name) }
            "exp" -> { arity(1); fromDouble(Math.exp(d()), name) }
            "sin" -> { arity(1); trig(Math.sin(d())) }
            "cos" -> { arity(1); trig(Math.cos(d())) }
            "tan" -> { arity(1); trig(Math.tan(d())) }
            "asin" -> { arity(1); fromDouble(Math.asin(d()), name) }
            "acos" -> { arity(1); fromDouble(Math.acos(d()), name) }
            "atan" -> { arity(1); fromDouble(Math.atan(d()), name) }
            "atan2" -> { arity(2); fromDouble(Math.atan2(d(), d(1)), name) }
            "sinh" -> { arity(1); fromDouble(Math.sinh(d()), name) }
            "cosh" -> { arity(1); fromDouble(Math.cosh(d()), name) }
            "tanh" -> { arity(1); fromDouble(Math.tanh(d()), name) }
            "hypot" -> { arity(2); fromDouble(Math.hypot(d(), d(1)), name) }
            else -> throw CalculationException("Unknown function '$name'")
        }
    }

    private fun positive(value: BigDecimal, name: String) {
        if (value.signum() <= 0) throw CalculationException("$name needs a positive number")
    }

    /** The exact root when [value] is a perfect square / cube of a whole number (sqrt(144) = 12, not 12.0000...1). */
    private fun exactRoot(value: BigDecimal, degree: Int): BigDecimal? {
        val whole = value.stripTrailingZeros().takeIf { it.scale() <= 0 }?.toBigInteger() ?: return null
        val guess = Math.round(Math.pow(whole.abs().toDouble(), 1.0 / degree)).toBigInteger()
        return listOf(guess.subtract(BigInteger.ONE), guess, guess.add(BigInteger.ONE))
            .firstOrNull { it.signum() >= 0 && it.pow(degree) == whole.abs() }
            ?.let { BigDecimal(if (whole.signum() < 0) it.negate() else it) }
    }

    /** sin(pi) comes out as 1.2e-16 in doubles: snap such leftovers to 0. */
    private fun trig(value: Double): BigDecimal = fromDouble(if (kotlin.math.abs(value) < 1e-14) 0.0 else value, "trig")

    private fun fromDouble(value: Double, name: String): BigDecimal {
        if (value.isNaN()) throw CalculationException("$name is undefined for this value")
        if (value.isInfinite()) throw CalculationException("The result of $name is too large")
        return BigDecimal(value.toString()).round(MathContext(15, RoundingMode.HALF_EVEN))
    }
}
