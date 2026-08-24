package com.github.primegraph.core.runtime

/**
 * The shape of one outbound HTTP call and of what it returned.
 *
 * Only the value types live here. The transport that reads them — `fetch`, the
 * auth application, the response decode — is per-bundle machinery and stays in
 * the generated package. These three carry no behaviour and every generated
 * package that emits an HTTP step repeated them verbatim, so they are declared
 * once and reached through the core import the emitted files already carry.
 */
public data class HttpAuth(
    public val type: String,
    public val scheme: String? = null,
    public val `in`: String? = null,
    public val name: String? = null,
    public val value: String? = null,
    public val username: String? = null,
    public val password: String? = null,
    public val token: String? = null,
)

/**
 * One outbound call. [body] is already encoded: the call site owns the JSON /
 * UTF-8 encoding, so the source-level expression type decides the Content-Type
 * where it is still known. A null [timeout] means the step declared none.
 */
public data class HttpRequest(
    public val url: String,
    public val method: String,
    public val headers: Map<String, String> = emptyMap(),
    public val query: Map<String, String> = emptyMap(),
    public val body: ByteArray? = null,
    public val auth: HttpAuth? = null,
    public val timeout: Long? = null,
)

/**
 * What one call returned. [body] is the bytes exactly as they arrived — a typed
 * body is what the step's declared response schemas are for. [status] is a
 * `Long` because that is the DSL integer width, so a `switch` on it in the DSL
 * compares against literals of the same type. Header keys are lowercased by the
 * transport, so a lookup needs no case walk.
 */
public data class HttpResponse(
    public val status: Long,
    public val headers: Map<String, String>,
    public val body: ByteArray,
)
