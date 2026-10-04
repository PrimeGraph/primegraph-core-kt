package com.github.primegraph.core.runtime

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/*
 * OpenAPI defines `integer` by value: a document may write the whole number 100
 * as `100`, `100.0` or `1e2`, while `1.5` names no integer. kotlinx reads `1e2`
 * into a `Long` but refuses `1.0`, so every typed decode first passes its tree
 * through `integralJson`, which spells each whole number as plain digits.
 *
 * The rewrite needs no knowledge of the target type: a `Double` or `Float` slot
 * reads `1` as the same value it would have read from `1.0`, and a `-0.0` keeps
 * its sign as `-0`. A number that is not whole, or whose value no `Long` holds,
 * keeps its spelling, so a `Long` slot refuses it exactly as before and a
 * `Double` slot reads it unchanged. Strings, booleans and null are never
 * touched. The arithmetic is exact BigDecimal; no value passes through a double.
 */

private val jsonNumberPattern = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")

// A Long has at most 19 integer digits; past that the value cannot fit, and the
// check keeps a numeral like `1e999999999` from being expanded.
private const val LONG_MAX_DIGITS = 19

/**
 * The tree with every whole number written as an integer literal. Elements that
 * need no rewrite are returned as the same instance.
 */
public fun integralJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> integralObject(element)
    is JsonArray -> integralArray(element)
    is JsonPrimitive -> integralPrimitive(element)
}

/** Parses `text` with [ktJson] and answers [integralJson] of the result. */
public fun integralJson(text: kotlin.String): JsonElement =
    integralJson(ktJson.parseToJsonElement(text))

private fun integralObject(element: JsonObject): JsonObject {
    var changed = false
    val entries = element.mapValues { (_, value) ->
        val rewritten = integralJson(value)
        if (rewritten !== value) {
            changed = true
        }
        rewritten
    }
    return if (changed) JsonObject(entries) else element
}

private fun integralArray(element: JsonArray): JsonArray {
    var changed = false
    val items = element.map { value ->
        val rewritten = integralJson(value)
        if (rewritten !== value) {
            changed = true
        }
        rewritten
    }
    return if (changed) JsonArray(items) else element
}

@OptIn(ExperimentalSerializationApi::class)
private fun integralPrimitive(element: JsonPrimitive): JsonPrimitive {
    if (element is JsonNull || element.isString) {
        return element
    }
    val digits = wholeNumberDigits(element.content) ?: return element
    return JsonUnquotedLiteral(digits)
}

/**
 * The plain digits of a JSON number token that carries a fraction or an
 * exponent and is whole within the `Long` range, or null when it is anything
 * else.
 */
private fun wholeNumberDigits(token: kotlin.String): kotlin.String? {
    if (token.none { it == '.' || it == 'e' || it == 'E' } || !jsonNumberPattern.matches(token)) {
        return null
    }
    // An exponent past the Int range is refused by the BigDecimal parser itself.
    val number = token.toBigDecimalOrNull() ?: return null
    if (number.signum() == 0) {
        return if (token.startsWith('-')) "-0" else "0"
    }
    if (number.precision() - number.scale() > LONG_MAX_DIGITS) {
        return null
    }
    // longValueExact throws on a fractional part and on a value past the Long range.
    return kotlin.runCatching { number.longValueExact() }.getOrNull()?.toString()
}
