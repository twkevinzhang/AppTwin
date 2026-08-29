package org.apptwin.archive

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface JsonValue {
    data class Object(val fields: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class LongValue(val value: Long) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal object StrictJson {
    fun parse(bytes: ByteArray): JsonValue {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: Exception) {
            throw SpaceArchiveException("Archive JSON is not valid UTF-8", failure)
        }
        return Parser(text).parse()
    }

    fun encode(value: JsonValue): ByteArray = buildString { appendValue(value) }
        .toByteArray(StandardCharsets.UTF_8)

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.Object -> {
                append('{')
                value.fields.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendString(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is JsonValue.Array -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is JsonValue.StringValue -> appendString(value.value)
            is JsonValue.LongValue -> append(value.value)
            is JsonValue.BooleanValue -> append(value.value)
            JsonValue.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val input: String) {
        private var offset = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            if (offset != input.length) fail("Trailing JSON data")
            return value
        }

        private fun readValue(): JsonValue {
            if (offset >= input.length) fail("Unexpected end of JSON")
            return when (input[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.StringValue(readString())
                't' -> readLiteral("true", JsonValue.BooleanValue(true))
                'f' -> readLiteral("false", JsonValue.BooleanValue(false))
                'n' -> readLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> readLong()
                else -> fail("Unexpected JSON token")
            }
        }

        private fun readObject(): JsonValue.Object {
            offset++
            skipWhitespace()
            val fields = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.Object(fields)
            while (true) {
                if (offset >= input.length || input[offset] != '"') fail("Object key is missing")
                val key = readString()
                if (fields.containsKey(key)) fail("Duplicate JSON key: $key")
                skipWhitespace()
                requireCharacter(':')
                skipWhitespace()
                fields[key] = readValue()
                skipWhitespace()
                if (consume('}')) return JsonValue.Object(fields)
                requireCharacter(',')
                skipWhitespace()
            }
        }

        private fun readArray(): JsonValue.Array {
            offset++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.Array(values)
            while (true) {
                values += readValue()
                skipWhitespace()
                if (consume(']')) return JsonValue.Array(values)
                requireCharacter(',')
                skipWhitespace()
            }
        }

        private fun readString(): String {
            requireCharacter('"')
            val result = StringBuilder()
            while (offset < input.length) {
                val character = input[offset++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> result.append(readEscape())
                    character.code < 0x20 -> fail("Unescaped control character in JSON string")
                    character.isHighSurrogate() -> {
                        if (offset >= input.length || !input[offset].isLowSurrogate()) {
                            fail("Unpaired high surrogate in JSON string")
                        }
                        result.append(character)
                        result.append(input[offset++])
                    }
                    character.isLowSurrogate() -> fail("Unpaired low surrogate in JSON string")
                    else -> result.append(character)
                }
            }
            fail("Unterminated JSON string")
        }

        private fun readEscape(): Char {
            if (offset >= input.length) fail("Unterminated JSON escape")
            return when (val escaped = input[offset++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> fail("Invalid JSON escape")
            }
        }

        private fun readUnicodeEscape(): Char {
            if (offset + 4 > input.length) fail("Incomplete Unicode escape")
            val digits = input.substring(offset, offset + 4)
            offset += 4
            return digits.toIntOrNull(16)?.toChar() ?: fail("Invalid Unicode escape")
        }

        private fun readLong(): JsonValue.LongValue {
            val start = offset
            if (consume('-') && offset >= input.length) fail("Invalid JSON number")
            if (consume('0')) {
                if (offset < input.length && input[offset].isDigit()) fail("Leading zero in JSON number")
            } else {
                if (offset >= input.length || input[offset] !in '1'..'9') fail("Invalid JSON number")
                while (offset < input.length && input[offset].isDigit()) offset++
            }
            if (offset < input.length && input[offset] in listOf('.', 'e', 'E')) {
                fail("Archive JSON only permits integer numbers")
            }
            val number = input.substring(start, offset).toLongOrNull()
                ?: fail("JSON integer is out of range")
            return JsonValue.LongValue(number)
        }

        private fun <T : JsonValue> readLiteral(text: String, value: T): T {
            if (!input.startsWith(text, offset)) fail("Invalid JSON literal")
            offset += text.length
            return value
        }

        private fun skipWhitespace() {
            while (offset < input.length && input[offset] in listOf(' ', '\t', '\r', '\n')) offset++
        }

        private fun consume(character: Char): Boolean {
            if (offset < input.length && input[offset] == character) {
                offset++
                return true
            }
            return false
        }

        private fun requireCharacter(character: Char) {
            if (!consume(character)) fail("Expected '$character'")
        }

        private fun fail(message: String): Nothing =
            throw SpaceArchiveException("$message at JSON offset $offset")
    }
}

internal fun JsonValue.requireObject(label: String): Map<String, JsonValue> =
    (this as? JsonValue.Object)?.fields ?: throw SpaceArchiveException("$label must be an object")

internal fun JsonValue.requireArray(label: String): List<JsonValue> =
    (this as? JsonValue.Array)?.values ?: throw SpaceArchiveException("$label must be an array")

internal fun JsonValue.requireString(label: String): String =
    (this as? JsonValue.StringValue)?.value ?: throw SpaceArchiveException("$label must be a string")

internal fun JsonValue.requireLong(label: String): Long =
    (this as? JsonValue.LongValue)?.value ?: throw SpaceArchiveException("$label must be an integer")

internal fun JsonValue.requireBoolean(label: String): Boolean =
    (this as? JsonValue.BooleanValue)?.value ?: throw SpaceArchiveException("$label must be a boolean")

internal fun Map<String, JsonValue>.requireExactKeys(label: String, vararg keys: String) {
    val expected = keys.toSet()
    if (this.keys != expected) {
        val missing = expected - this.keys
        val unexpected = this.keys - expected
        throw SpaceArchiveException("$label fields are invalid; missing=$missing unexpected=$unexpected")
    }
}
