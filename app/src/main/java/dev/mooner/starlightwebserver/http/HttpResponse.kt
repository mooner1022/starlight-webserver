package dev.mooner.starlightwebserver.http

import java.io.File

/**
 * The response to a request, as `res`: `res.status(201).json({ ok: true })`.
 *
 * The first sending method (`send`, `json`, `text`, `html`, `file`, `redirect`, `end`) sends the response. After it,
 * sending again and changing the status or headers are ignored. It may be called from a callback after the handler
 * returned, within the time limit.
 */
class HttpResponse internal constructor(
    private val sink: ResponseSink,
    private val baseDir: File,
) {

    /** Whether the response was sent. */
    val isSent: Boolean
        get() = sink.isCommitted

    /**
     * Sets the status code, 200 by default.
     *
     * @return This response, for chaining.
     * @throws IllegalArgumentException If [code] isn't between 100 and 599.
     */
    fun status(code: Int): HttpResponse =
        apply { sink.setStatus(code) }

    /**
     * Sets a header, replacing an earlier value. Headers the server manages itself, such as `Content-Length`, are
     * dropped when the response is sent.
     *
     * @param value The value, converted to text.
     * @return This response, for chaining.
     */
    fun header(name: String, value: Any?): HttpResponse =
        apply { sink.setHeader(name, ScriptValues.toText(value)) }

    /**
     * Sets the `Content-Type`, which the sending methods otherwise choose.
     *
     * @return This response, for chaining.
     */
    fun type(contentType: String): HttpResponse =
        apply { sink.setContentType(contentType) }

    /**
     * Sends [body]: strings, numbers and booleans as text, byte arrays as bytes, and objects or arrays as JSON.
     * `null` or `undefined` sends no body.
     */
    fun send(body: Any?) {
        sink.commit(ScriptValues.toBody(body, sink.contentType))
    }

    /** Sends [body] as JSON, as `JSON.stringify` writes it, even when it is a string. */
    fun json(body: Any?) {
        sink.commit(ResponseBody.Bytes(ScriptValues.toJson(body).toByteArray(), sink.contentType ?: ScriptValues.JSON))
    }

    /** Sends [text] as `text/plain`, unless [type] set another type. */
    fun text(text: String) {
        sink.commit(ResponseBody.Bytes(text.toByteArray(), sink.contentType ?: ScriptValues.TEXT))
    }

    /** Sends [html] as `text/html`, unless [type] set another type. */
    fun html(html: String) {
        sink.commit(ResponseBody.Bytes(html.toByteArray(), sink.contentType ?: ScriptValues.HTML))
    }

    /**
     * Sends a file, with a type guessed from its name unless [type] set one. A missing file gets 404.
     *
     * @param path The file, relative to the project directory unless absolute.
     */
    fun file(path: String) {
        val file = File(path).let { if (it.isAbsolute) it else baseDir.resolve(path) }
        sink.commit(ResponseBody.FileBody(file, sink.contentType))
    }

    /**
     * Sends a redirect to [url].
     *
     * @param status The status code, 302 by default.
     */
    @JvmOverloads
    fun redirect(url: String, status: Int = 302) {
        sink.setStatus(status)
        sink.setHeader("Location", url)
        sink.commit(ResponseBody.Empty)
    }

    /** Sends the response without a body; the status defaults to 200, so set 204 for "no content". */
    fun end() {
        sink.commit(ResponseBody.Empty)
    }
}
