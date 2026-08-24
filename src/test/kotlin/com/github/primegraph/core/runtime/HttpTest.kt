package com.github.primegraph.core.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTest {

    // The emitted call sites build these with named arguments and spell every
    // parameter literally, so the names and their defaults are the contract. A
    // step that declares only a URL and a method must compile to exactly this.
    @Test
    fun `a request declaring only a url and a method carries nothing else`() {
        val req = HttpRequest(url = "https://api.example.com/things", method = "GET")

        assertEquals("https://api.example.com/things", req.url)
        assertEquals("GET", req.method)
        assertTrue(req.headers.isEmpty())
        assertTrue(req.query.isEmpty())
        assertNull(req.body)
        assertNull(req.auth)
        assertNull(req.timeout)
    }

    // A declared zero timeout has to stay distinguishable from an absent one:
    // the transport applies only a timeout the step named.
    @Test
    fun `a declared zero timeout is not an absent timeout`() {
        val declared = HttpRequest(url = "u", method = "GET", timeout = 0L)

        assertEquals(0L, declared.timeout)
        assertNull(declared.copy(timeout = null).timeout)
    }

    // `in` is a Kotlin keyword, so the field is spelled with backticks at every
    // emitted `apiKey` call site. Its name is the contract, not the escaping.
    @Test
    fun `an apiKey credential names the location it is sent in`() {
        val auth = HttpAuth(type = "apiKey", `in` = "header", name = "X-Key", value = "secret")

        assertEquals("apiKey", auth.type)
        assertEquals("header", auth.`in`)
        assertEquals("X-Key", auth.name)
        assertEquals("secret", auth.value)
        assertNull(auth.scheme)
        assertNull(auth.token)
    }

    @Test
    fun `a credential declaring only a type leaves every secret slot empty`() {
        val auth = HttpAuth(type = "http")

        assertNull(auth.scheme)
        assertNull(auth.`in`)
        assertNull(auth.name)
        assertNull(auth.value)
        assertNull(auth.username)
        assertNull(auth.password)
        assertNull(auth.token)
    }

    // The status is a Long because that is the DSL integer width: a DSL
    // `switch` on the status compares it against literals of the same type.
    @Test
    fun `a response carries the status, the lowercased headers and the raw bytes`() {
        val resp = HttpResponse(
            status = 201L,
            headers = mapOf("content-type" to "application/json"),
            body = """{"ok":true}""".toByteArray(Charsets.UTF_8),
        )

        assertEquals(201L, resp.status)
        assertEquals("application/json", resp.headers["content-type"])
        assertContentEquals("""{"ok":true}""".toByteArray(Charsets.UTF_8), resp.body)
    }
}
