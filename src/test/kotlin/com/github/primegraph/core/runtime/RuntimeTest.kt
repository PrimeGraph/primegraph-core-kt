package com.github.primegraph.core.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RuntimeTest {

    private val bytes = "hello, bytes".toByteArray(Charsets.UTF_8)

    @Test
    fun `Runtime File compares and hashes by content, not by array identity`() {
        val one = Runtime.File(name = "a.txt", mimeType = "text/plain", data = bytes)
        val other = Runtime.File(
            name = "a.txt",
            mimeType = "text/plain",
            data = "hello, bytes".toByteArray(Charsets.UTF_8),
        )

        assertEquals(one, other)
        assertEquals(one.hashCode(), other.hashCode())
        assertEquals(one, one)
    }

    @Test
    fun `Runtime File tells apart a different name, type or payload`() {
        val base = Runtime.File(name = "a.txt", mimeType = "text/plain", data = bytes)

        assertNotEquals(base, base.copy(name = "b.txt"))
        assertNotEquals(base, base.copy(mimeType = "application/json"))
        assertNotEquals(base, base.copy(data = ByteArray(0)))
        assertNotEquals<Any?>(base, "a.txt")
    }

    @Test
    fun `Runtime File serializes with its bytes base64 through the codec`() {
        // A model field may declare `format: file`, so the carrier travels inside a
        // serializable model — its bytes the way every other ByteArray travels.
        val file = Runtime.File(name = "a.txt", mimeType = "text/plain", data = bytes)

        val element = ktJson.encodeToJsonElement(Runtime.File.serializer(), file)

        assertEquals(
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("a.txt"),
                    "mimeType" to JsonPrimitive("text/plain"),
                    "data" to JsonPrimitive("aGVsbG8sIGJ5dGVz"),
                ),
            ),
            element,
        )

        val back = ktJson.decodeFromJsonElement(Runtime.File.serializer(), element)
        assertEquals(file, back)
        assertContentEquals(bytes, back.data)
    }

    @Test
    fun `Runtime File survives being a field of a generated-shaped model`() {
        val upload = Upload(field = "avatar", file = Runtime.File("a.png", "image/png", bytes))

        val back = ktJson.decodeFromString<Upload>(ktJson.encodeToString(upload))

        assertEquals(upload, back)
    }

    @Test
    fun `formField builds a value part and leaves every file slot empty`() {
        val part = Runtime.formField("name", "value")

        assertEquals("name", part.name)
        assertEquals("value", part.value)
        assertEquals("", part.filename)
        assertEquals("", part.contentType)
        assertTrue(part.data.isEmpty())
        assertEquals(false, part.isFile)
    }

    @Test
    fun `FormPart compares and hashes by content`() {
        val one = Runtime.FormPart(
            name = "file",
            filename = "a.txt",
            contentType = "text/plain",
            data = bytes,
            isFile = true,
        )
        val other = Runtime.FormPart(
            name = "file",
            filename = "a.txt",
            contentType = "text/plain",
            data = "hello, bytes".toByteArray(Charsets.UTF_8),
            isFile = true,
        )

        assertEquals(one, other)
        assertEquals(one.hashCode(), other.hashCode())
        assertNotEquals(one, other.copy(isFile = false))
        assertNotEquals(one, other.copy(data = ByteArray(1)))
        assertNotEquals<Any?>(one, "file")
    }

    @Serializable
    data class Upload(val field: String, val file: Runtime.File)
}
