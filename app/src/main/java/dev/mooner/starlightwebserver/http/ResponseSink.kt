package dev.mooner.starlightwebserver.http

import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** Response state behind [HttpResponse], kept out of what scripts can reach. */
class ResponseSink {

    @Volatile
    var status: Int = 200
        private set

    private val mHeaders: MutableMap<String, String> = linkedMapOf()
    val headers: Map<String, String>
        @Synchronized get() = LinkedHashMap(mHeaders)

    @Volatile
    var contentType: String? = null
        private set

    @Volatile
    var body: ResponseBody = ResponseBody.Empty
        private set

    val completion = CompletableDeferred<Unit>()

    /** Whether the response was sent; later changes are ignored. */
    val isCommitted: Boolean
        get() = completion.isCompleted

    @Synchronized
    fun setStatus(code: Int) {
        require(code in 100..599) { "Invalid HTTP status code: $code" }
        if (!isCommitted)
            status = code
    }

    @Synchronized
    fun setHeader(name: String, value: String) {
        if (isCommitted)
            return
        if (name.equals(CONTENT_TYPE, ignoreCase = true))
            contentType = value
        else
            mHeaders[name] = value
    }

    @Synchronized
    fun setContentType(value: String) {
        if (!isCommitted)
            contentType = value
    }

    /** Sends [body]. Returns false if the response was already sent. */
    @Synchronized
    fun commit(body: ResponseBody): Boolean {
        if (isCommitted)
            return false
        this.body = body
        return completion.complete(Unit)
    }

    /** Ends the response without sending anything, as when the request timed out. */
    fun close() {
        completion.complete(Unit)
    }

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
    }
}

sealed interface ResponseBody {

    data object Empty : ResponseBody

    class Bytes(val bytes: ByteArray, val contentType: String) : ResponseBody

    class FileBody(val file: File, val contentType: String?) : ResponseBody
}
