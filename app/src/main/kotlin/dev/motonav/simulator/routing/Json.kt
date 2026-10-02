package dev.motonav.simulator.routing

internal sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: Double) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal class StrictJsonParser(private val source: String) {
    private var index = 0

    fun parse(): JsonValue {
        val value = value()
        whitespace()
        require(index == source.length) { "Unexpected trailing JSON" }
        return value
    }

    private fun value(): JsonValue {
        whitespace()
        require(index < source.length) { "Unexpected end of JSON" }
        return when (source[index]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> JsonValue.StringValue(string())
            't' -> literal("true", JsonValue.BooleanValue(true))
            'f' -> literal("false", JsonValue.BooleanValue(false))
            'n' -> literal("null", JsonValue.NullValue)
            '-', in '0'..'9' -> number()
            else -> error("Unexpected JSON token")
        }
    }

    private fun objectValue(): JsonValue.Object {
        index++
        whitespace()
        val values = linkedMapOf<String, JsonValue>()
        if (take('}')) return JsonValue.Object(values)
        while (true) {
            whitespace()
            require(index < source.length && source[index] == '"') { "Expected JSON object key" }
            val key = string()
            require(key !in values) { "Duplicate JSON object key" }
            whitespace()
            require(take(':')) { "Expected colon" }
            values[key] = value()
            whitespace()
            if (take('}')) return JsonValue.Object(values)
            require(take(',')) { "Expected comma" }
        }
    }

    private fun arrayValue(): JsonValue.Array {
        index++
        whitespace()
        val values = mutableListOf<JsonValue>()
        if (take(']')) return JsonValue.Array(values)
        while (true) {
            values += value()
            whitespace()
            if (take(']')) return JsonValue.Array(values)
            require(take(',')) { "Expected comma" }
        }
    }

    private fun string(): String {
        require(take('"'))
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when (character) {
                '"' -> return result.toString()
                '\\' -> {
                    require(index < source.length) { "Truncated JSON escape" }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length) { "Truncated unicode escape" }
                            result.append(source.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
                else -> {
                    require(character.code >= 0x20) { "Control character in JSON string" }
                    result.append(character)
                }
            }
        }
        error("Unterminated JSON string")
    }

    private fun number(): JsonValue.NumberValue {
        val start = index
        if (source[index] == '-') index++
        require(index < source.length)
        if (source[index] == '0') index++ else {
            require(source[index] in '1'..'9')
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && source[index] == '.') {
            index++
            require(index < source.length && source[index].isDigit())
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && source[index] in "eE") {
            index++
            if (index < source.length && source[index] in "+-") index++
            require(index < source.length && source[index].isDigit())
            while (index < source.length && source[index].isDigit()) index++
        }
        val parsed = source.substring(start, index).toDouble()
        require(parsed.isFinite()) { "Non-finite JSON number" }
        return JsonValue.NumberValue(parsed)
    }

    private fun <T : JsonValue> literal(text: String, value: T): T {
        require(source.startsWith(text, index)) { "Invalid JSON literal" }
        index += text.length
        return value
    }

    private fun whitespace() {
        while (index < source.length && source[index] in " \n\r\t") index++
    }

    private fun take(character: Char): Boolean =
        if (index < source.length && source[index] == character) { index++; true } else false
}

internal fun JsonValue.objectOrThrow() = this as? JsonValue.Object ?: error("Expected JSON object")
internal fun JsonValue.arrayOrThrow() = (this as? JsonValue.Array)?.values ?: error("Expected JSON array")
internal fun JsonValue.stringOrThrow() = (this as? JsonValue.StringValue)?.value ?: error("Expected JSON string")
internal fun JsonValue.numberOrThrow() = (this as? JsonValue.NumberValue)?.value ?: error("Expected JSON number")
internal operator fun JsonValue.Object.get(key: String): JsonValue = values[key] ?: error("Missing $key")
internal fun JsonValue.Object.optional(key: String): JsonValue? = values[key]?.takeUnless { it == JsonValue.NullValue }
