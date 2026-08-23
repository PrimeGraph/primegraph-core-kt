package com.github.primegraph.core.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DslErrorTest {

    @Test
    fun `DslThrow carries and exposes its typed payload`() {
        val payload = mapOf("projectId" to "p-1")
        val error = DslError(code = "not-found", payload = payload)

        val thrown = DslThrow(error)

        assertSame(error, thrown.error)
        assertEquals("not-found", thrown.error.code)
        assertEquals(payload, thrown.error.payload)
        // The exception message is the code, so a bare stack trace still names it.
        assertEquals("not-found", thrown.message)
        assertNull(thrown.payloadJson)
    }

    @Test
    fun `DslThrow carries the wire form the raise site computed`() {
        val wire = buildJsonObject {
            put("projectId", JsonPrimitive("p-1"))
            put("attempts", JsonPrimitive(3))
        }

        val thrown = DslThrow(DslError(code = "aborted", payload = Unit), wire)

        assertSame(wire, thrown.payloadJson)
    }

    @Test
    fun `a DslThrow thrown by one caller is caught by another as this one class`() {
        // The regression this package exists for: `catch (e: DslThrow)` around a
        // call that crosses a package boundary. One class, so the catch matches.
        val caught = try {
            throwingValidator()
            false
        } catch (e: DslThrow) {
            assertEquals("VALIDATION_FAILED", e.error.code)
            true
        }

        assertTrue(caught)
    }

    private fun throwingValidator(): Nothing =
        throw DslThrow(DslError(code = "VALIDATION_FAILED", payload = "name is required"))

    @Test
    fun `arrivedErrorCode reads a DslThrow code and names everything else INTERNAL_ERROR`() {
        assertEquals(
            "permission-denied",
            arrivedErrorCode(DslThrow(DslError(code = "permission-denied", payload = Unit))),
        )
        assertEquals("INTERNAL_ERROR", arrivedErrorCode(IllegalStateException("socket closed")))
    }

    @Test
    fun `arrivedErrorJson returns the payload a DslThrow carries, untouched`() {
        val wire = buildJsonObject { put("slot", JsonPrimitive("value")) }

        val arrived = arrivedErrorJson(DslThrow(DslError(code = "invalid-argument", payload = Unit), wire))

        assertSame(wire, arrived)
    }

    @Test
    fun `arrivedErrorJson gives a foreign exception the standard error object`() {
        // A foreign exception carries no typed payload, so the contribution is the
        // standard `{code, message}` object and never the caller's own text.
        val arrived = arrivedErrorJson(IllegalArgumentException("connection reset"))

        assertEquals(
            JsonObject(
                mapOf(
                    "code" to JsonPrimitive("INTERNAL_ERROR"),
                    "message" to JsonPrimitive("Internal server error"),
                ),
            ),
            arrived,
        )
    }

    @Test
    fun `arrivedErrorJson gives a payloadless DslThrow the standard object under its own code`() {
        val arrived = arrivedErrorJson(DslThrow(DslError(code = "already-exists", payload = Unit)))

        assertEquals(
            JsonObject(
                mapOf(
                    "code" to JsonPrimitive("already-exists"),
                    "message" to JsonPrimitive("Resource already exists"),
                ),
            ),
            arrived,
        )
    }

    @Test
    fun `defaultErrorMessage answers the table, and INTERNAL_ERROR for an unlisted code`() {
        assertEquals("Input is not valid base64", defaultErrorMessage("BASE64_DECODE_FAILED"))
        assertEquals("Authorization required", defaultErrorMessage("unauthenticated"))
        assertEquals("Internal server error", defaultErrorMessage("SOME_CODE_NOBODY_DECLARED"))
    }

    @Test
    fun `DSL_ERROR_MESSAGES names both code spaces and gives every entry a message`() {
        // The UPPER_SNAKE transport codes and the kebab gRPC/DSL codes are one
        // table, because a catch reads a code without knowing which raised it.
        assertEquals(32, DSL_ERROR_MESSAGES.size)
        assertTrue(DSL_ERROR_MESSAGES.containsKey("NO_RESPONSE"))
        assertTrue(DSL_ERROR_MESSAGES.containsKey("deadline-exceeded"))
        assertTrue(DSL_ERROR_MESSAGES.containsKey("TIME_PARSE_FAILED"))
        for ((code, message) in DSL_ERROR_MESSAGES) {
            assertTrue(message.isNotEmpty(), "$code has no message")
        }
    }

    @Test
    fun `coerceError keeps a payload that fits and substitutes the default otherwise`() {
        val fitting = coerceError(
            DslThrow(DslError(code = "not-found", payload = "no such project")),
            defaultPayload = "",
        )
        assertEquals(DslErrorView("not-found", "no such project"), fitting)

        val mismatched = coerceError(
            DslThrow(DslError(code = "not-found", payload = 42)),
            defaultPayload = "fallback",
        )
        assertEquals(DslErrorView("not-found", "fallback"), mismatched)
    }

    @Test
    fun `coerceError fills an empty text slot from the message table`() {
        val view = coerceError(IllegalStateException("boom"), defaultPayload = "")

        assertEquals(DslErrorView("INTERNAL_ERROR", "Internal server error"), view)
    }

    @Test
    fun `coercedPayload only fills an empty text slot`() {
        assertEquals("Input is not valid hex", coercedPayload("HEX_DECODE_FAILED", ""))
        assertEquals("kept", coercedPayload("HEX_DECODE_FAILED", "kept"))
        assertEquals(0, coercedPayload("HEX_DECODE_FAILED", 0))
    }

    @Test
    fun `a DslThrow is an ordinary exception a broad catch still sees`() {
        assertFailsWith<Exception> {
            throw DslThrow(DslError(code = "internal", payload = Unit))
        }
    }
}
