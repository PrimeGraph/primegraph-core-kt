package com.github.primegraph.core.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The untyped bridge: an arbitrary value normalised to its JSON wire form. */
class JsonElementOfAnyTest {

    @Test
    fun `null becomes JsonNull and a JsonElement passes through untouched`() {
        assertEquals(JsonNull, jsonElementOfAny(null))

        val already = JsonObject(mapOf("k" to JsonPrimitive("v")))
        assertSame(already, jsonElementOfAny(already))
    }

    @Test
    fun `scalars keep their JSON kinds`() {
        assertEquals(JsonPrimitive("text"), jsonElementOfAny("text"))
        assertEquals(JsonPrimitive(true), jsonElementOfAny(true))
        assertEquals(JsonPrimitive(3), jsonElementOfAny(3))
        assertEquals(JsonPrimitive(3L), jsonElementOfAny(3L))
        assertEquals(JsonPrimitive(3.5), jsonElementOfAny(3.5))
        assertEquals(JsonPrimitive(BigDecimal("1.2500")), jsonElementOfAny(BigDecimal("1.2500")))
    }

    @Test
    fun `a non-finite number becomes null instead of losing the whole document`() {
        // JSON states no NaN and no infinity, and the codec refuses to write one.
        assertEquals(JsonNull, jsonElementOfAny(Double.NaN))
        assertEquals(JsonNull, jsonElementOfAny(Double.POSITIVE_INFINITY))
        assertEquals(JsonNull, jsonElementOfAny(Float.NEGATIVE_INFINITY))
        assertEquals(JsonPrimitive(1.5f), jsonElementOfAny(1.5f))
    }

    @Test
    fun `bytes are base64, matching what ByteArraySerializer writes for a model field`() {
        val bytes = "hello, bytes".toByteArray(Charsets.UTF_8)

        assertEquals(JsonPrimitive("aGVsbG8sIGJ5dGVz"), jsonElementOfAny(bytes))
        assertEquals(
            jsonElementOfAny(bytes),
            ktJson.encodeToJsonElement(ByteArraySerializer, bytes),
        )
    }

    @Test
    fun `an instant takes the same wire form here as in a model field`() {
        val at = OffsetDateTime.parse("2026-08-23T17:04:23.123456789+03:00")

        assertEquals(JsonPrimitive("2026-08-23T14:04:23.123Z"), jsonElementOfAny(at))
        assertEquals(
            jsonElementOfAny(at),
            ktJson.encodeToJsonElement(OffsetDateTimeSerializer, at),
        )
    }

    @Test
    fun `maps and lists are walked and their keys stringified`() {
        val value = mapOf(
            "list" to listOf(1, "two", null),
            3 to "int key",
        )

        assertEquals(
            JsonObject(
                mapOf(
                    "list" to JsonArray(
                        listOf(JsonPrimitive(1), JsonPrimitive("two"), JsonNull),
                    ),
                    "3" to JsonPrimitive("int key"),
                ),
            ),
            jsonElementOfAny(value),
        )
    }

    @Test
    fun `a serializable value keeps the rendering its own serializer states`() {
        val element = jsonElementOfAny(Point(x = 1, y = 2))

        assertEquals(
            JsonObject(mapOf("x" to JsonPrimitive(1), "y" to JsonPrimitive(2))),
            element,
        )
    }

    @Test
    fun `a serializable enum keeps its declared wire name, not the Kotlin variant name`() {
        assertEquals(JsonPrimitive("in-progress"), jsonElementOfAny(Status.IN_PROGRESS))
    }

    @Test
    fun `jsonElementOfSerializable answers null for a value carrying no serializer`() {
        assertNull(jsonElementOfSerializable(Opaque("nothing declared")))
    }

    @Test
    fun `a value carrying no serializer falls back to its text`() {
        assertEquals(JsonPrimitive("Opaque(label=free text)"), jsonElementOfAny(Opaque("free text")))
    }

    @Test
    fun `nested serializable values survive a whole tree walk`() {
        val tree: Map<String, Any?> = mapOf(
            "points" to listOf(Point(1, 2), Point(3, 4)),
            "status" to Status.DONE,
        )

        val element: JsonElement = jsonElementOfAny(tree)

        assertEquals(
            JsonObject(
                mapOf(
                    "points" to JsonArray(
                        listOf(
                            JsonObject(mapOf("x" to JsonPrimitive(1), "y" to JsonPrimitive(2))),
                            JsonObject(mapOf("x" to JsonPrimitive(3), "y" to JsonPrimitive(4))),
                        ),
                    ),
                    "status" to JsonPrimitive("done"),
                ),
            ),
            element,
        )
    }

    @Serializable
    data class Point(val x: Int, val y: Int)

    @Serializable
    enum class Status {
        @kotlinx.serialization.SerialName("in-progress")
        IN_PROGRESS,

        @kotlinx.serialization.SerialName("done")
        DONE,
    }

    data class Opaque(val label: String)
}
