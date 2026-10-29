package dev.mooner.starlightwebserver.http

import io.ktor.http.ContentType
import io.ktor.http.charset
import io.ktor.http.parseQueryString

/** A request as scripts see it. Header names are lower-case. */
class HttpRequest(
    val method: String,
    val path: String,
    /** The path with its query string. */
    val url: String,
    val params: Map<String, String>,
    val query: Map<String, String>,
    private val queryValues: Map<String, List<String>>,
    val headers: Map<String, String>,
    private val rawBody: ByteArray,
    val ip: String,
    val receivedAt: Long,
) {

    val contentType: String?
        get() = headers["content-type"]

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

    val bodySize: Int
        get() = rawBody.size

    fun header(name: String): String? =
        headers[name.lowercase()]

    fun param(name: String): String? =
        params[name]

    /** Every value of a repeated query parameter. */
    fun queryAll(name: String): List<String> =
        queryValues[name].orEmpty()

    fun bodyBytes(): ByteArray =
        rawBody.copyOf()

    override fun toString(): String =
        "$method $url"
}
