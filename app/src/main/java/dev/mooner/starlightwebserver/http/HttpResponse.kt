package dev.mooner.starlightwebserver.http

import java.io.File

/**
 * The response of a request, built by scripts: `res.status(201).json({ ok: true })`.
 *
 * The first sending method (send, json, text, html, file, redirect, end) sends the response; later calls are ignored.
 */
class HttpResponse(
    private val sink: ResponseSink,
    private val baseDir: File,
) {

    val isSent: Boolean
        get() = sink.isCommitted

    fun status(code: Int): HttpResponse =
        apply { sink.setStatus(code) }

    fun header(name: String, value: Any?): HttpResponse =
        apply { sink.setHeader(name, ScriptValues.toText(value)) }

    fun type(contentType: String): HttpResponse =
        apply { sink.setContentType(contentType) }

    /** Sends text as is, and objects or arrays as JSON. */
    fun send(body: Any?) {
        sink.commit(ScriptValues.toBody(body, sink.contentType))
    }

    fun json(body: Any?) {
        sink.commit(ResponseBody.Bytes(ScriptValues.toJson(body).toByteArray(), sink.contentType ?: ScriptValues.JSON))
    }

    fun text(text: String) {
        sink.commit(ResponseBody.Bytes(text.toByteArray(), sink.contentType ?: ScriptValues.TEXT))
    }

    fun html(html: String) {
        sink.commit(ResponseBody.Bytes(html.toByteArray(), sink.contentType ?: ScriptValues.HTML))
    }

    /** Sends a file; a relative [path] is resolved against the project directory. */
    fun file(path: String) {
        val file = File(path).let { if (it.isAbsolute) it else baseDir.resolve(path) }
        sink.commit(ResponseBody.FileBody(file, sink.contentType))
    }

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
