package com.github.primegraph.core.runtime

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

@Serializable
private data class Counts(
    val count: Long,
    val small: Int,
    val ratio: Double,
    val maybe: Long? = null,
)

/** OpenAPI `integer` is defined by value: `1.0` and `1e2` are integers, `1.5` is not. */
class IntegralJsonTest {

    private fun number(token: String): JsonPrimitive =
        ktJson.parseToJsonElement(token).jsonPrimitive

    @Test
    fun `a whole number with a fraction or an exponent becomes plain digits`() {
        assertEquals(JsonPrimitive(1L), integralJson(number("1.0")))
        assertEquals(JsonPrimitive(100L), integralJson(number("1e2")))
        assertEquals(JsonPrimitive(100L), integralJson(number("1E+2")))
        assertEquals(JsonPrimitive(1L), integralJson(number("100.0e-2")))
        assertEquals(JsonPrimitive(-25L), integralJson(number("-2.50e1")))
        assertEquals(JsonPrimitive(0L), integralJson(number("0.000")))
    }

    @Test
    fun `the rewrite is exact at the edges of the Long range`() {
        assertEquals(
            JsonPrimitive(Long.MAX_VALUE),
            integralJson(number("9.223372036854775807e18")),
        )
        assertEquals(
            JsonPrimitive(Long.MIN_VALUE),
            integralJson(number("-9223372036854775808.0")),
        )
        // A double round-trip would land on 9007199254740992.
        assertEquals(
            JsonPrimitive(9007199254740993L),
            integralJson(number("9007199254740993.0")),
        )
    }

    @Test
    fun `ordinary integers, fractions, strings, booleans and null are left as they are`() {
        for (token in listOf("7", "-7", "1.5", "1e-1", "2.000001", "\"1.0\"", "true", "null")) {
            val element = ktJson.parseToJsonElement(token)
            assertSame(element, integralJson(element), token)
        }
        assertSame(JsonNull, integralJson(JsonNull))
    }

    @Test
    fun `a whole number no Long holds keeps its spelling`() {
        for (token in listOf("1e19", "-9223372036854775809.0", "1e999999999", "1e9999999999")) {
            val element = number(token)
            assertSame(element, integralJson(element), token)
        }
    }

    @Test
    fun `negative zero keeps its sign for a floating slot`() {
        val rewritten = integralJson(number("-0.0"))

        assertEquals("-0", rewritten.jsonPrimitive.content)
        assertFalse(rewritten.jsonPrimitive.isString)
        assertEquals(
            (-0.0).toRawBits(),
            ktJson.decodeFromJsonElement<Double>(rewritten).toRawBits(),
        )
        assertEquals(0L, ktJson.decodeFromJsonElement<Long>(rewritten))
    }

    @Test
    fun `nested objects and arrays are rewritten and an untouched tree is the same instance`() {
        val tree = ktJson.parseToJsonElement("""{"a":[1.0,{"b":2e1}],"c":"3.0","d":4}""")

        val rewritten = integralJson(tree)

        assertEquals(
            ktJson.parseToJsonElement("""{"a":[1,{"b":20}],"c":"3.0","d":4}"""),
            rewritten,
        )
        val untouched = ktJson.parseToJsonElement("""{"a":[1,{"b":2.5}],"c":"3.0"}""")
        assertSame(untouched, integralJson(untouched))
        val arrayValue = untouched.jsonObject.getValue("a")
        assertSame(arrayValue, (integralJson(untouched) as JsonObject).getValue("a"))
        assertSame(arrayValue, integralJson(arrayValue))
    }

    @Test
    fun `a Long slot refuses a whole number with a point until it is rewritten`() {
        val element = ktJson.parseToJsonElement(
            """{"count":1.0,"small":1e2,"ratio":1.0,"maybe":-3.0e0}""",
        )

        assertFailsWith<SerializationException> {
            ktJson.decodeFromJsonElement<Counts>(element)
        }
        assertEquals(
            Counts(count = 1L, small = 100, ratio = 1.0, maybe = -3L),
            ktJson.decodeFromJsonElement<Counts>(integralJson(element)),
        )
    }

    @Test
    fun `a Double slot reads the rewritten digits as the same value`() {
        val decoded = ktJson.decodeFromJsonElement<Counts>(
            integralJson("""{"count":1,"small":1,"ratio":2.0}"""),
        )

        assertEquals(2.0, decoded.ratio)
        assertNull(decoded.maybe)
    }

    @Test
    fun `a fractional number is still refused by an integer slot`() {
        assertFailsWith<SerializationException> {
            ktJson.decodeFromJsonElement<Counts>(
                integralJson("""{"count":1.5,"small":1,"ratio":1.5}"""),
            )
        }
    }

    @Test
    fun `the text entry point parses with ktJson before rewriting`() {
        assertEquals(
            ktJson.parseToJsonElement("""[1,100,1.5,"1.0"]"""),
            integralJson("""[1.0,1e2,1.5,"1.0"]"""),
        )
    }
}
