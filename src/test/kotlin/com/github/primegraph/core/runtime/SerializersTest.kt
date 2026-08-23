package com.github.primegraph.core.runtime

import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerializersTest {

    @Test
    fun `UUIDSerializer round-trips through ktJson as a lowercase string`() {
        val value = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6")

        val text = ktJson.encodeToString(UUIDSerializer, value)

        assertEquals("\"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\"", text)
        assertEquals(value, ktJson.decodeFromString(UUIDSerializer, text))
    }

    @Test
    fun `UUIDSerializer descriptor names the JDK type, which the firestore writer dispatches on`() {
        assertEquals("java.util.UUID", UUIDSerializer.descriptor.serialName)
        assertEquals("java.time.OffsetDateTime", OffsetDateTimeSerializer.descriptor.serialName)
        assertEquals("java.time.OffsetDateTime.day", CalendarDaySerializer.descriptor.serialName)
        assertEquals("java.math.BigDecimal", BigDecimalSerializer.descriptor.serialName)
        // Not `kotlin.ByteArray`: kotlinx refuses a custom descriptor that spells a
        // built-in serializer's name, and the throw would take the module down.
        assertEquals("primegraph.Base64Bytes", ByteArraySerializer.descriptor.serialName)
    }

    @Test
    fun `OffsetDateTimeSerializer normalises to UTC with exactly three fractional digits`() {
        val value = OffsetDateTime.parse("2026-08-23T17:04:23.123456789+03:00")

        val text = ktJson.encodeToString(OffsetDateTimeSerializer, value)

        assertEquals("\"2026-08-23T14:04:23.123Z\"", text)

        val back = ktJson.decodeFromString(OffsetDateTimeSerializer, text)
        assertEquals(value.toInstant().epochSecond, back.toInstant().epochSecond)
        assertEquals(ZoneOffset.UTC, back.offset)
    }

    @Test
    fun `OffsetDateTimeSerializer writes the zero fractional part rather than dropping it`() {
        // `OffsetDateTime.toString()` would write `2026-01-02T03:04:05Z`, which the
        // other targets do not produce for the same value.
        val value = OffsetDateTime.parse("2026-01-02T03:04:05Z")

        assertEquals(
            "\"2026-01-02T03:04:05.000Z\"",
            ktJson.encodeToString(OffsetDateTimeSerializer, value),
        )
    }

    @Test
    fun `CalendarDaySerializer writes the UTC date and reads a bare date back at UTC midnight`() {
        val value = OffsetDateTime.parse("2026-08-23T22:30:00-05:00")

        val text = ktJson.encodeToString(CalendarDaySerializer, value)

        // 22:30 on the 23rd at -05:00 is 03:30 on the 24th at UTC.
        assertEquals("\"2026-08-24\"", text)

        val back = ktJson.decodeFromString(CalendarDaySerializer, text)
        assertEquals(OffsetDateTime.parse("2026-08-24T00:00:00Z"), back)
    }

    @Test
    fun `CalendarDaySerializer reads a full instant back as its date part`() {
        // A Firestore Timestamp field reads back as a full UTC instant; only the
        // date part of it is the value.
        val back = ktJson.decodeFromString(CalendarDaySerializer, "\"2026-08-24T03:30:00Z\"")

        assertEquals(OffsetDateTime.parse("2026-08-24T00:00:00Z"), back)
    }

    @Test
    fun `BigDecimalSerializer keeps precision a Double would destroy`() {
        val value = BigDecimal("0.1000000000000000055511151231257827021181583404541015625")

        val text = ktJson.encodeToString(BigDecimalSerializer, value)

        assertEquals("\"0.1000000000000000055511151231257827021181583404541015625\"", text)
        val back = ktJson.decodeFromString(BigDecimalSerializer, text)
        assertEquals(value, back)
        assertEquals(0, value.compareTo(back))
    }

    @Test
    fun `BigDecimalSerializer keeps the scale, so trailing zeros survive the round-trip`() {
        val value = BigDecimal("123.4500")

        val text = ktJson.encodeToString(BigDecimalSerializer, value)

        assertEquals("\"123.4500\"", text)
        val back = ktJson.decodeFromString(BigDecimalSerializer, text)
        assertEquals(4, back.scale())
        assertEquals(value, back)
    }

    @Test
    fun `BigDecimalSerializer writes plain notation, never scientific`() {
        // `toString()` would write `1E+9`, which no other target reads back.
        assertEquals(
            "\"1000000000\"",
            ktJson.encodeToString(BigDecimalSerializer, BigDecimal("1E+9")),
        )
        assertEquals(
            "\"0.0000001\"",
            ktJson.encodeToString(BigDecimalSerializer, BigDecimal("1E-7")),
        )
    }

    @Test
    fun `ByteArraySerializer round-trips as standard base64`() {
        val value = "hello, bytes".toByteArray(Charsets.UTF_8)

        val text = ktJson.encodeToString(ByteArraySerializer, value)

        assertEquals("\"aGVsbG8sIGJ5dGVz\"", text)
        assertContentEquals(value, ktJson.decodeFromString(ByteArraySerializer, text))
    }

    @Test
    fun `ByteArraySerializer round-trips arbitrary binary, padding included`() {
        val value = ByteArray(256) { it.toByte() }

        val text = ktJson.encodeToString(ByteArraySerializer, value)

        assertEquals(
            JsonPrimitive(Base64.getEncoder().encodeToString(value)),
            ktJson.parseToJsonElement(text),
        )
        assertContentEquals(value, ktJson.decodeFromString(ByteArraySerializer, text))
    }

    @Test
    fun `ByteArraySerializer round-trips an empty array`() {
        val text = ktJson.encodeToString(ByteArraySerializer, ByteArray(0))

        assertEquals("\"\"", text)
        assertTrue(ktJson.decodeFromString(ByteArraySerializer, text).isEmpty())
    }
}
