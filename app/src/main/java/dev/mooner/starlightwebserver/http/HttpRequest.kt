package dev.mooner.starlightwebserver.http

import io.ktor.http.ContentType
import io.ktor.http.charset
import io.ktor.http.parseQueryString

/** A request a route handler receives, as `req`. */
class HttpRequest internal constructor(
    /** The HTTP method in upper case, such as `GET`. */
    val method: String,
    /** The path, without the query string, such as `/hello/kim`. */
    val path: String,
    /** The path with its query string, such as `/hello/kim?lang=ko`. */
    val url: String,
    /** The path segments the route captured: `:name` as `name`, and a trailing `*` as `*`. */
    val params: Map<String, String>,
    /** The query parameters, with the first value of each; see [queryAll] for repeated ones. */
    val query: Map<String, String>,
    private val queryValues: Map<String, List<String>>,
    /** The headers, by lower-case name; repeated headers are joined with `, `. */
    val headers: Map<String, String>,
    private val rawBody: ByteArray,
    /** The client's IP address. */
    val ip: String,
    /** When the request arrived, in milliseconds since the Unix epoch. */
    val receivedAt: Long,
) {

    /** The `Content-Type` header, or `null`. */
    val contentType: String?
        get() = headers["content-type"]

    /** The body as text, decoded with the charset of [contentType], or UTF-8. */
    val body: String by lazy {
        val charset = contentType
            ?.let { runCatching { ContentType.parse(it).charset() }.getOrNull() }
            ?: Charsets.UTF_8
        String(rawBody, charset)
    }

    /** Fields of an `application/x-www-form-urlencoded` body. */
    val form: Map<String, String> by lazy {
        val isForm = contentType?.startsWith(ContentType.Application.FormUrlEncoded.toString(), ignoreCase = true) == true
        if (isForm)
            parseQueryString(body).entries().associate { (name, values) -> name to values.first() }
        else
            emptyMap()
    }

    /** The size of the body in bytes. */
    val bodySize: Int
        get() = rawBody.size

    /**
     * A header's value.
     *
     * @param name The header's name, in any case.
     * @return The value, or `null` if the request has no such header.
     */
    fun header(name: String): String? =
        headers[name.lowercase()]

    /**
     * A path segment the route captured, as in [params].
     *
     * @return The value, or `null` if the route has no such param.
     */
    fun param(name: String): String? =
        params[name]

    /**
     * Every value of a query parameter, in order: `?tag=a&tag=b` gives `["a", "b"]`.
     *
     * @return The values; empty if there are none.
     */
    fun queryAll(name: String): List<String> =
        queryValues[name].orEmpty()

    /** A copy of the body's bytes. */
    fun bodyBytes(): ByteArray =
        rawBody.copyOf()

    /** @suppress */
    override fun toString(): String =
        "$method $url"
}
