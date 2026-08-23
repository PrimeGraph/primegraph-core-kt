package com.github.primegraph.core.runtime

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

// The wire rendering of an instant and of a calendar day, named once here so the
// serializers below and the untyped `jsonElementOfAny` bridge share one spelling
// instead of repeating the whole chain at each site.
private val instantWireFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

private fun instantToWireText(value: OffsetDateTime): kotlin.String =
    value.withOffsetSameInstant(ZoneOffset.UTC).format(instantWireFormatter)

private fun calendarDayToWireText(value: OffsetDateTime): kotlin.String =
    value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString()

private fun calendarDayOfWireText(text: kotlin.String): OffsetDateTime =
    LocalDate.parse(text.substringBefore('T')).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()

public object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID =
        java.util.UUID.fromString(decoder.decodeString())
}

// `OffsetDateTime.toString()` drops `:00` seconds and the fractional part when
// they are zero, and writes the offset it carries rather than `Z` — three ways
// to disagree with the one wire format `time.now()` defines. The instant is
// normalised to UTC and written with the fixed three fractional digits.
public object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.OffsetDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OffsetDateTime) {
        encoder.encodeString(instantToWireText(value))
    }

    override fun deserialize(decoder: Decoder): OffsetDateTime =
        java.time.OffsetDateTime.parse(decoder.decodeString())
}

// The reader is wider than the writer: a calendar date has no instant, so a
// Firestore `Timestamp` field reads back as a full UTC instant and only its
// date part is the value. A plain `YYYY-MM-DD` carries no `T` and is
// unaffected, which keeps documents written before dates went native readable.
public object CalendarDaySerializer : KSerializer<OffsetDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.OffsetDateTime.day", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OffsetDateTime) {
        encoder.encodeString(calendarDayToWireText(value))
    }

    override fun deserialize(decoder: Decoder): OffsetDateTime =
        calendarDayOfWireText(decoder.decodeString())
}

public object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal =
        BigDecimal(decoder.decodeString())
}

// Binary content travels base64-encoded: JSON has no octet type, and the
// default array-of-numbers form is neither compact nor what any other target
// reads back.
//
// kotlinx.serialization holds one global registry of descriptor names and
// refuses a custom descriptor that spells a built-in's name — `kotlin.ByteArray`
// is the stdlib serializer's, and claiming it throws while the file's classes
// initialise. The name is kept in a namespace no serialization library owns.
public object ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("primegraph.Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

/**
 * The contextual bindings every `@Contextual` model field resolves against.
 * While each generated package registered its own copy, the encode was decided
 * by whichever package's `ktJson` happened to perform it; one shared module
 * removes that coupling, so the set below is the whole contract.
 */
public val dslSerializersModule: SerializersModule = SerializersModule {
    contextual(UUID::class, UUIDSerializer)
    contextual(OffsetDateTime::class, OffsetDateTimeSerializer)
    contextual(BigDecimal::class, BigDecimalSerializer)
    contextual(ByteArray::class, ByteArraySerializer)
}

// Key presence on the wire follows `required`, not Kotlin nullability.
// `explicitNulls = true` writes `"k": null` for a nullable property, and
// `encodeDefaults = false` drops a property that still holds its declared
// default. Only optional properties carry a `= null` default, so the pair
// yields: required always present (null when nullable), optional omitted
// when unset.
public val ktJson: Json = Json {
    serializersModule = dslSerializersModule
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = true
}

/*
 * What a union puts on the wire, and takes back off it.
 *
 * A union branch is PLAIN WIRE DATA: the branch value itself, carrying nothing
 * that names the language that wrote it. That is what a Go, TypeScript, Python
 * or Swift client writes for the same declaration, and what every validator this
 * compiler emits reads — so the polymorphic envelope kotlinx.serialization
 * writes for a sealed hierarchy (`{"type":…,"value":…}`) belongs on no wire.
 *
 * Each branch is generated as a one-property carrier, so unwrapping is what
 * `serialize` does and boxing is what `deserialize` does; the carrier's own
 * generated serializer decides everything in between, which is how a contextual
 * field type inside a branch keeps rendering the way it does anywhere else.
 */

public fun <T> unionBranchElement(
    json: Json,
    serializer: KSerializer<T>,
    value: T,
): JsonElement = json.encodeToJsonElement(serializer, value).jsonObject.getValue("value")

public fun <T> unionBranchOf(json: Json, serializer: KSerializer<T>, element: JsonElement): T =
    json.decodeFromJsonElement(
        serializer,
        JsonObject(kotlin.collections.mapOf("value" to element)),
    )

/** The first declared branch that accepts the value, in declaration order. */
public fun <T : Any> unionBranchOfFirst(
    json: Json,
    element: JsonElement,
    name: kotlin.String,
    branches: kotlin.collections.List<KSerializer<out T>>,
): T {
    for (branch in branches) {
        val decoded = kotlin.runCatching { unionBranchOf(json, branch, element) }.getOrNull()
        if (decoded != null) {
            return decoded
        }
    }
    unionBranchMismatch(name)
}

/** The discriminator tag a value carries, or null when it carries none. */
public fun unionBranchTag(element: JsonElement, property: kotlin.String): kotlin.String? {
    val tag = (element as? JsonObject)?.get(property)
    return if (tag is JsonPrimitive && tag !is JsonNull && tag.isString) tag.content else null
}

public fun unionBranchMismatch(name: kotlin.String): Nothing =
    throw SerializationException("no declared branch of " + name + " matched the JSON value")

public fun jsonEncoderOf(encoder: Encoder, name: kotlin.String): JsonEncoder =
    encoder as? JsonEncoder
        ?: throw SerializationException(name + " is a union and travels only as JSON")

public fun jsonDecoderOf(decoder: Decoder, name: kotlin.String): JsonDecoder =
    decoder as? JsonDecoder
        ?: throw SerializationException(name + " is a union and travels only as JSON")

// `kotlin.collections.*` is fully qualified throughout because a generated
// model may itself be named `List` or `Map` (an OpenAPI schema can carry those
// names), which would otherwise shadow the stdlib types in this same package.
public fun jsonElementOfAny(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is kotlin.String -> JsonPrimitive(value)
    is kotlin.Boolean -> JsonPrimitive(value)
    is kotlin.Int -> JsonPrimitive(value)
    is kotlin.Long -> JsonPrimitive(value)
    is kotlin.Short -> JsonPrimitive(value)
    is kotlin.Byte -> JsonPrimitive(value)
    // JSON states no NaN and no infinity, and the codec refuses to write one —
    // which would lose the whole document over a single field. It is written as
    // null where it stands instead, as every other target writes it.
    is kotlin.Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is kotlin.Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is BigDecimal -> JsonPrimitive(value)
    is kotlin.ByteArray -> JsonPrimitive(Base64.getEncoder().encodeToString(value))
    is kotlin.Number -> JsonPrimitive(value)
    // `OffsetDateTime.toString()` writes whatever offset the value carries and
    // drops the fractional part when it is zero, so an instant reaching this
    // untyped path would disagree with the one `OffsetDateTimeSerializer`
    // writes for the same value in a model field.
    is OffsetDateTime -> JsonPrimitive(instantToWireText(value))
    is kotlin.collections.Map<*, *> ->
        JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonElementOfAny(v) })
    is kotlin.collections.List<*> -> JsonArray(value.map { jsonElementOfAny(it) })
    else -> jsonElementOfSerializable(value) ?: JsonPrimitive(value.toString())
}

/**
 * Encodes a value through the serializer its own class carries, or answers null
 * when it carries none. The last resort of `jsonElementOfAny`.
 *
 * Reached only once every branch above has declined, so a carrier with a
 * rendering of its own keeps it. A generated model or enum states its wire form
 * through the serializer it was generated with — `toString()` would write the
 * Kotlin property names and variant names instead, which no other target does
 * and no reader can parse back.
 */
@OptIn(InternalSerializationApi::class)
public fun jsonElementOfSerializable(value: Any): JsonElement? = kotlin.runCatching {
    @Suppress("UNCHECKED_CAST")
    val strategy = value::class.serializer() as KSerializer<Any>
    ktJson.encodeToJsonElement(strategy, value)
}.getOrNull()
