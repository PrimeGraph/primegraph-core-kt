package com.github.primegraph.core.runtime

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A union branch is PLAIN WIRE DATA — the branch value itself, with nothing
 * naming the language that wrote it. Each branch is a one-property carrier, so
 * unwrapping is what the encode does and boxing is what the decode does.
 */
class UnionWireTest {

    @Serializable
    data class Alpha(val a: Int, val note: String? = null)

    @Serializable
    data class Beta(val b: String)

    @Serializable
    data class AlphaCarrier(val value: Alpha)

    @Serializable
    data class BetaCarrier(val value: Beta)

    @Serializable
    data class TextCarrier(val value: String)

    @Test
    fun `unionBranchElement writes the branch value with no carrier around it`() {
        val element = unionBranchElement(
            ktJson,
            AlphaCarrier.serializer(),
            AlphaCarrier(Alpha(a = 7)),
        )

        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(7))), element)
    }

    @Test
    fun `unionBranchOf boxes the wire value back into the carrier`() {
        val element = JsonObject(mapOf("a" to JsonPrimitive(7)))

        val carrier = unionBranchOf(ktJson, AlphaCarrier.serializer(), element)

        assertEquals(AlphaCarrier(Alpha(a = 7)), carrier)
    }

    @Test
    fun `a scalar branch round-trips through the pair`() {
        val original = TextCarrier("plain text")

        val element = unionBranchElement(ktJson, TextCarrier.serializer(), original)

        assertEquals(JsonPrimitive("plain text"), element)
        assertEquals(original, unionBranchOf(ktJson, TextCarrier.serializer(), element))
    }

    private val branches: List<KSerializer<out Any>> =
        listOf(AlphaCarrier.serializer(), BetaCarrier.serializer())

    @Test
    fun `unionBranchOfFirst picks the branch that accepts the value`() {
        val beta = unionBranchOfFirst(
            ktJson,
            JsonObject(mapOf("b" to JsonPrimitive("x"))),
            "Choice",
            branches,
        )

        assertIs<BetaCarrier>(beta)
        assertEquals(Beta("x"), beta.value)
    }

    @Test
    fun `unionBranchOfFirst prefers the earlier branch when both accept`() {
        // Declaration order is the tie-break, so the same JSON decodes to the same
        // branch in every language that compiled the same declaration.
        val ambiguous = JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive("x")))

        val decoded = unionBranchOfFirst(ktJson, ambiguous, "Choice", branches)

        assertIs<AlphaCarrier>(decoded)
        assertEquals(Alpha(a = 1), decoded.value)
    }

    @Test
    fun `unionBranchOfFirst reports a mismatch rather than inventing a branch`() {
        val error = assertFailsWith<SerializationException> {
            unionBranchOfFirst(ktJson, JsonArray(emptyList()), "Choice", branches)
        }

        assertEquals("no declared branch of Choice matched the JSON value", error.message)
    }

    @Test
    fun `unionBranchMismatch names the union it failed on`() {
        val error = assertFailsWith<SerializationException> { unionBranchMismatch("Payload") }

        assertEquals("no declared branch of Payload matched the JSON value", error.message)
    }

    @Test
    fun `unionBranchTag reads a string discriminator and declines anything else`() {
        val tagged = JsonObject(mapOf("kind" to JsonPrimitive("alpha")))

        assertEquals("alpha", unionBranchTag(tagged, "kind"))
        assertNull(unionBranchTag(tagged, "absent"))
        assertNull(unionBranchTag(JsonObject(mapOf("kind" to JsonPrimitive(3))), "kind"))
        assertNull(unionBranchTag(JsonObject(mapOf("kind" to JsonNull)), "kind"))
        assertNull(unionBranchTag(JsonPrimitive("not an object"), "kind"))
    }

    @Test
    fun `a union serializer built on jsonEncoderOf and jsonDecoderOf round-trips through ktJson`() {
        val text = ktJson.encodeToString(HeldUnionSerializer, Held("boxed"))

        assertEquals("\"boxed\"", text)
        assertEquals(Held("boxed"), ktJson.decodeFromString(HeldUnionSerializer, text))
    }

    @Test
    fun `jsonEncoderOf refuses a format that is not JSON`() {
        val error = assertFailsWith<SerializationException> {
            HeldUnionSerializer.serialize(NotJsonEncoder, Held("boxed"))
        }

        assertEquals("Held is a union and travels only as JSON", error.message)
    }

    @Test
    fun `jsonDecoderOf refuses a format that is not JSON`() {
        val error = assertFailsWith<SerializationException> {
            HeldUnionSerializer.deserialize(NotJsonDecoder)
        }

        assertEquals("Held is a union and travels only as JSON", error.message)
    }

    data class Held(val value: String)

    /** Shaped like an emitted union serializer: the wire form is the value itself. */
    private object HeldUnionSerializer : KSerializer<Held> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Held", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Held) {
            jsonEncoderOf(encoder, "Held").encodeJsonElement(JsonPrimitive(value.value))
        }

        override fun deserialize(decoder: Decoder): Held =
            Held(jsonDecoderOf(decoder, "Held").decodeJsonElement().jsonPrimitive.content)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private object NotJsonEncoder : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private object NotJsonDecoder : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            CompositeDecoder.DECODE_DONE
    }
}
