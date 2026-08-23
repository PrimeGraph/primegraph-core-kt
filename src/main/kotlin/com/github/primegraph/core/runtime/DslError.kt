package com.github.primegraph.core.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Two-class wrapper for typed DSL errors on the JVM. The JVM forbids generic
 * subclasses of `Throwable` ("subclass of 'Throwable' cannot have type
 * parameters"), so the typed payload carrier `DslError<Payload>` is a plain
 * data class and the thrown wrapper `DslThrow` is a non-generic exception
 * that holds the carrier behind a star-projection.
 *
 * A catch handles an error when the ARRIVED payload satisfies the schema the
 * catch declares, never by class identity — an inline payload schema is minted
 * per block, so the same shape raised in one block and caught in another is
 * nominally two types. The wire form cannot be recovered from the erased
 * carrier, so the raise site computes it (where the payload's static type is
 * still known) and hands it to `DslThrow.payloadJson`; the catch site reads it
 * back through `arrivedErrorJson`.
 *
 * This is the reason the shared package exists: while each generated package
 * carried its own copy, `catch (e: DslThrow)` could not match a `DslThrow`
 * thrown by another package's validator, and the exception escaped a catch
 * that reads as if it handles it.
 */
public data class DslError<Payload>(public val code: String, public val payload: Payload)

public class DslThrow(
    public val error: DslError<*>,
    public val payloadJson: JsonElement? = null,
) : Exception(error.code)

/**
 * Projects ANY caught error onto the catch-site payload type `T` with the same
 * cast-with-default rule as a normal variable assignment (never crashes). A DSL
 * `raise` contributes its typed payload; any other error (a network failure, a
 * failing block/extension call, ...) contributes the standard error object
 * `{"code": "INTERNAL_ERROR", "message": ...}`. The contribution is returned
 * when it fits `T` (e.g. the default `{code, message}` errorType), otherwise
 * the default value of `T`.
 */
public data class DslErrorView<Payload>(public val code: String, public val payload: Payload)

// Default text of each error code. A coerced error has no message of its own —
// a foreign SDK's wording never reaches a client — so a text payload slot it
// leaves empty is filled from here, and the error view always carries a code
// AND a message.
public val DSL_ERROR_MESSAGES: Map<String, String> = mapOf(
    "AUTH_REQUIRED" to "Authorization required",
    "VALIDATION_FAILED" to "Request validation failed",
    "INTERNAL_ERROR" to "Internal server error",
    "NOT_FOUND" to "Resource not found",
    "METHOD_NOT_ALLOWED" to "Method not allowed",
    "FORBIDDEN" to "Access forbidden",
    "NO_RESPONSE" to "Block did not produce a response",
    "invalid-argument" to "Invalid argument",
    "failed-precondition" to "Failed precondition",
    "out-of-range" to "Value out of range",
    "unauthenticated" to "Authorization required",
    "permission-denied" to "Access forbidden",
    "not-found" to "Resource not found",
    "already-exists" to "Resource already exists",
    "resource-exhausted" to "Resource exhausted",
    "cancelled" to "Request cancelled",
    "data-loss" to "Data loss",
    "unknown" to "Unknown error",
    "internal" to "Internal server error",
    "unavailable" to "Service unavailable",
    "deadline-exceeded" to "Deadline exceeded",
    "aborted" to "Operation aborted",
    "UNSUPPORTED_MEDIA_TYPE" to "Unsupported media type",
    "UTF8_DECODE_FAILED" to "Input is not valid UTF-8",
    "BASE64_DECODE_FAILED" to "Input is not valid base64",
    "HEX_DECODE_FAILED" to "Input is not valid hex",
    "URL_DECODE_FAILED" to "Input is not valid percent-encoded text",
    "ENUM_VALUE_NOT_A_MEMBER" to "Value is not a member of the enumeration",
    "JSON_PARSE_FAILED" to "Input is not valid JSON",
    "DECIMAL_PARSE_FAILED" to "Input is not a decimal number",
    "DURATION_PARSE_FAILED" to "Input is not a valid duration",
    "TIME_PARSE_FAILED" to "Input does not match the expected time format",
)

public fun defaultErrorMessage(code: String): String =
    DSL_ERROR_MESSAGES[code] ?: "Internal server error"

public fun arrivedErrorCode(e: Throwable): String =
    if (e is DslThrow) e.error.code else "INTERNAL_ERROR"

/**
 * The JSON an arrived error contributes to a catch's schema match. A DSL raise
 * carries the wire form of its own payload. Everything else — a foreign error,
 * or a DslThrow raised where no payload type was in hand — contributes the
 * standard error object, so a catch declaring `{code, message}` receives it and
 * a catch declaring any other shape declines it.
 */
public fun arrivedErrorJson(e: Throwable): JsonElement {
    val carried = if (e is DslThrow) e.payloadJson else null
    if (carried != null) {
        return carried
    }
    if (e !is DslThrow) {
        // A foreign error carries no typed payload, so its own text cannot reach
        // the catch binding. It goes to the log instead of being dropped.
        System.err.println("[dsl] foreign error reached a catch as INTERNAL_ERROR: $e")
    }
    val code = arrivedErrorCode(e)
    return JsonObject(
        mapOf(
            "code" to JsonPrimitive(code),
            "message" to JsonPrimitive(defaultErrorMessage(code)),
        ),
    )
}

public inline fun <reified T> coercedPayload(code: String, defaultPayload: T): T {
    if (defaultPayload is String && defaultPayload.isEmpty()) {
        val filled = defaultErrorMessage(code)
        if (filled is T) {
            return filled
        }
    }
    return defaultPayload
}

// Reached where the declared errorType asserts nothing (an opaque carrier), so
// no schema gate stands in front of the catch and the arrived payload is taken
// as it comes.
public inline fun <reified T> coerceError(e: Throwable, defaultPayload: T): DslErrorView<T> {
    if (e is DslThrow) {
        val p = e.error.payload
        val payload = if (p is T) p else coercedPayload(e.error.code, defaultPayload)
        return DslErrorView(e.error.code, payload)
    }
    // A foreign error carries no typed payload, so its own text would be lost
    // here. It goes to the log instead of the returned view.
    System.err.println("[dsl] error coerced to INTERNAL_ERROR: $e")
    return DslErrorView("INTERNAL_ERROR", coercedPayload("INTERNAL_ERROR", defaultPayload))
}
