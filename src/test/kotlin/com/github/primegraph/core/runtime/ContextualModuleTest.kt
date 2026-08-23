package com.github.primegraph.core.runtime

import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The coupling this package removes. A generated model declares its JDK-typed
 * fields `@Contextual`, and a `@Contextual` field resolves against whichever
 * `Json`'s `serializersModule` performs the encode. While every generated
 * package carried its own `ktJson`, a model encoded by one package's codec and
 * decoded by another's agreed only as long as the two registered the same set.
 * One module, one answer.
 */
class ContextualModuleTest {

    @Serializable
    data class ContextualModel(
        @Contextual val id: UUID,
        @Contextual val at: OffsetDateTime,
        @Contextual val amount: BigDecimal,
        @Contextual val blob: ByteArray,
        @Serializable(with = CalendarDaySerializer::class) val day: OffsetDateTime,
    )

    private val model = ContextualModel(
        id = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6"),
        at = OffsetDateTime.parse("2026-08-23T17:04:23.123456789+03:00"),
        amount = BigDecimal("123.4500"),
        blob = "hello, bytes".toByteArray(Charsets.UTF_8),
        day = OffsetDateTime.parse("2026-08-23T22:30:00-05:00"),
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `dslSerializersModule binds each contextual type to exactly one serializer`() {
        assertSame(UUIDSerializer, dslSerializersModule.getContextual(UUID::class))
        assertSame(
            OffsetDateTimeSerializer,
            dslSerializersModule.getContextual(OffsetDateTime::class),
        )
        assertSame(BigDecimalSerializer, dslSerializersModule.getContextual(BigDecimal::class))
        assertSame(ByteArraySerializer, dslSerializersModule.getContextual(ByteArray::class))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the calendar-day rendering is named per property, not bound contextually`() {
        // Both an instant and a calendar day are carried by OffsetDateTime. The
        // contextual binding is the instant; a day says so on the property, so a
        // contextual binding for LocalDate would be a second answer for one type.
        assertNull(dslSerializersModule.getContextual(LocalDate::class))
        assertSame(
            OffsetDateTimeSerializer,
            dslSerializersModule.getContextual(OffsetDateTime::class),
        )
    }

    @Test
    fun `ktJson resolves every contextual field of a model`() {
        val text = ktJson.encodeToString(model)

        assertEquals(
            """{"id":"f81d4fae-7dec-11d0-a765-00a0c91e6bf6",""" +
                """"at":"2026-08-23T14:04:23.123Z",""" +
                """"amount":"123.4500",""" +
                """"blob":"aGVsbG8sIGJ5dGVz",""" +
                """"day":"2026-08-24"}""",
            text,
        )
    }

    @Test
    fun `a model round-trips through ktJson with every contextual field intact`() {
        val back = ktJson.decodeFromString<ContextualModel>(ktJson.encodeToString(model))

        assertEquals(model.id, back.id)
        // The wire format states three fractional digits, so an instant carrying
        // more comes back truncated to milliseconds — the same in every target.
        assertEquals(
            model.at.toInstant().truncatedTo(ChronoUnit.MILLIS),
            back.at.toInstant(),
        )
        assertEquals(model.amount, back.amount)
        assertEquals(4, back.amount.scale())
        assertContentEquals(model.blob, back.blob)
        assertEquals(OffsetDateTime.parse("2026-08-24T00:00:00Z"), back.day)
    }

    @Test
    fun `a Json without the module cannot encode the same model`() {
        // Pins that the registration is load-bearing rather than incidental: drop
        // the module and the very same model stops encoding.
        assertFailsWith<SerializationException> {
            Json.encodeToString(model)
        }
    }

    @Test
    fun `a Json carrying the module encodes identically to ktJson`() {
        // A consumer that builds its own codec gets the same wire form as long as
        // it installs this module — the whole point of exporting it.
        val other = Json { serializersModule = dslSerializersModule }

        assertEquals(ktJson.encodeToString(model), other.encodeToString(model))
    }

    @Test
    fun `ktJson omits a property still holding its default and writes an explicit null`() {
        assertEquals("""{"required":null}""", ktJson.encodeToString(Defaults(required = null)))
        assertEquals(
            """{"required":"set","optional":"also set"}""",
            ktJson.encodeToString(Defaults(required = "set", optional = "also set")),
        )
    }

    @Test
    fun `ktJson ignores a key the model does not declare`() {
        val back = ktJson.decodeFromString<Defaults>("""{"required":"x","stranger":1}""")

        assertEquals("x", back.required)
        assertNull(back.optional)
    }

    @Serializable
    data class Defaults(val required: String?, val optional: String? = null)
}
