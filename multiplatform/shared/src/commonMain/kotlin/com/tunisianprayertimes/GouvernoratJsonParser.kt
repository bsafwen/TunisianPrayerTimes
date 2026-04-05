package com.tunisianprayertimes

/**
 * Simple JSON parser for gouvernorats.json — avoids platform-specific JSON libraries.
 * Uses Kotlin's regex-based extraction for this specific JSON structure.
 *
 * For a production app, consider kotlinx.serialization instead.
 */
object GouvernoratJsonParser {

    fun parse(jsonContent: String): List<Gouvernorat> {
        // Use a simple approach: split by gouvernorat objects
        val result = mutableListOf<Gouvernorat>()
        val root = parseJsonObject(jsonContent)
        val gouvernoratsArray = root["gouvernorats"] as? List<*> ?: return emptyList()

        for (gObj in gouvernoratsArray) {
            val g = gObj as? Map<*, *> ?: continue
            val delegationsArray = g["delegations"] as? List<*> ?: emptyList<Any>()
            val gName = g["nomAr"] as? String ?: ""
            val delegations = delegationsArray.mapNotNull { dObj ->
                val d = dObj as? Map<*, *> ?: return@mapNotNull null
                Delegation(
                    id = (d["id"] as? Number)?.toInt() ?: return@mapNotNull null,
                    nomFr = d["nomFr"] as? String ?: "",
                    nomAr = d["nomAr"] as? String ?: "",
                    nomEn = d["nomEn"] as? String ?: "",
                    gouvernoratName = gName,
                    lat = (d["lat"] as? Number)?.toDouble() ?: 0.0,
                    lng = (d["lng"] as? Number)?.toDouble() ?: 0.0
                )
            }
            result.add(
                Gouvernorat(
                    id = (g["id"] as? Number)?.toInt() ?: continue,
                    nomFr = g["nomFr"] as? String ?: "",
                    nomAr = g["nomAr"] as? String ?: "",
                    nomEn = g["nomEn"] as? String ?: "",
                    delegations = delegations
                )
            )
        }

        return result
    }
}

// Minimal JSON parser — handles the gouvernorats.json structure
private fun parseJsonObject(json: String): Map<String, Any?> = JsonParser(json.trim()).parseObject()

private class JsonParser(private val json: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        expect('{')
        skipWhitespace()
        if (peek() == '}') { pos++; return map }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            map[key] = parseValue()
            skipWhitespace()
            if (peek() == ',') { pos++; continue }
            break
        }
        expect('}')
        return map
    }

    private fun parseArray(): List<Any?> {
        val list = mutableListOf<Any?>()
        expect('[')
        skipWhitespace()
        if (peek() == ']') { pos++; return list }
        while (true) {
            skipWhitespace()
            list.add(parseValue())
            skipWhitespace()
            if (peek() == ',') { pos++; continue }
            break
        }
        expect(']')
        return list
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        return when (peek()) {
            '"' -> parseString()
            '{' -> parseObject()
            '[' -> parseArray()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < json.length && json[pos] != '"') {
            if (json[pos] == '\\') {
                pos++
                when (json[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        pos++
                        val hex = json.substring(pos, pos + 4)
                        sb.append(hex.toInt(16).toChar())
                        pos += 3 // +1 below
                    }
                    else -> sb.append(json[pos])
                }
            } else {
                sb.append(json[pos])
            }
            pos++
        }
        expect('"')
        return sb.toString()
    }

    private fun parseNumber(): Number {
        val start = pos
        if (json[pos] == '-') pos++
        while (pos < json.length && json[pos].isDigit()) pos++
        var isDouble = false
        if (pos < json.length && json[pos] == '.') {
            isDouble = true
            pos++
            while (pos < json.length && json[pos].isDigit()) pos++
        }
        if (pos < json.length && (json[pos] == 'e' || json[pos] == 'E')) {
            isDouble = true
            pos++
            if (pos < json.length && (json[pos] == '+' || json[pos] == '-')) pos++
            while (pos < json.length && json[pos].isDigit()) pos++
        }
        val numStr = json.substring(start, pos)
        return if (isDouble) numStr.toDouble() else numStr.toLong()
    }

    private fun parseBoolean(): Boolean {
        return if (json.startsWith("true", pos)) {
            pos += 4; true
        } else {
            pos += 5; false
        }
    }

    private fun parseNull(): Any? {
        pos += 4
        return null
    }

    private fun peek(): Char = json[pos]

    private fun expect(c: Char) {
        if (json[pos] != c) error("Expected '$c' at position $pos, got '${json[pos]}'")
        pos++
    }

    private fun skipWhitespace() {
        while (pos < json.length && json[pos].isWhitespace()) pos++
    }
}
