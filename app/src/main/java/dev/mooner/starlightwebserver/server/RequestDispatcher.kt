package dev.mooner.starlightwebserver.server

import dev.mooner.starlightwebserver.http.HttpRequest
import dev.mooner.starlightwebserver.http.HttpResponse
import dev.mooner.starlightwebserver.http.ResponseBody
import dev.mooner.starlightwebserver.http.ResponseSink
import dev.mooner.starlightwebserver.http.ScriptValues
import dev.mooner.starlightwebserver.route.PathPattern
import dev.mooner.starlightwebserver.route.Route
import dev.mooner.starlightwebserver.route.RouteRegistry
import dev.mooner.starlightwebserver.route.RouteRegistry.Resolution
import dev.mooner.starlightwebserver.route.RouteTarget
import dev.mooner.starlightwebserver.stats.ServerStats
import dev.mooner.starlightwebserver.utils.sha256Hex
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.defaultForFile
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.util.cio.ChannelIOException
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** Answers a request: CORS, access token, routing, then the matched project's handler or files. */
class RequestDispatcher(
    private val registry: RouteRegistry,
    private val stats: ServerStats,
    private val settings: () -> ServerSettings,
    /** Runs handlers, so a handler that outlives its timeout doesn't hold the request. */
    private val handlerScope: CoroutineScope,
    private val log: (message: String) -> Unit,
) {

    suspend fun dispatch(call: ApplicationCall) {
        val startNanos = System.nanoTime()
        val method = call.request.httpMethod.value
        val path = call.request.path()
        var project: String? = null
        val status = try {
            handle(call, method, path) { project = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isDisconnect(e)) {
                // The client went away, as when a browser stops a download. What was sent is still the answer.
                if (call.response.isCommitted) call.response.status()?.value ?: HttpStatusCode.OK.value else CLIENT_CLOSED_REQUEST
            } else {
                log("Failed to handle $method $path: $e")
                if (call.response.isCommitted) 500 else respondError(call, HttpStatusCode.InternalServerError)
            }
        }
        val durationMillis = (System.nanoTime() - startNanos) / 1_000_000
        stats.record(ServerStats.Record(System.currentTimeMillis(), method, path, status, durationMillis, project))
        if (settings().logRequests)
            log("$method $path -> $status (${durationMillis}ms)" + (project?.let { " [$it]" } ?: ""))
    }

    /** Returns the status sent. */
    private suspend fun handle(call: ApplicationCall, method: String, path: String, onProject: (String) -> Unit): Int {
        val settings = settings()

        if (settings.enableCors) {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
            val requestedMethod = call.request.headers[HttpHeaders.AccessControlRequestMethod]
            if (method == HttpMethod.Options.value && requestedMethod != null) {
                call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT, PATCH, DELETE, OPTIONS")
                call.request.headers[HttpHeaders.AccessControlRequestHeaders]
                    ?.let { call.response.header(HttpHeaders.AccessControlAllowHeaders, it) }
                call.response.header(HttpHeaders.AccessControlMaxAge, CORS_MAX_AGE)
                call.respond(HttpStatusCode.NoContent)
                return HttpStatusCode.NoContent.value
            }
        }

        if (settings.requireToken && !isAuthorized(call, settings.tokenHash)) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            return respondError(call, HttpStatusCode.Unauthorized)
        }

        val match = when (val resolution = registry.resolve(method, path)) {
            is Resolution.Matched -> resolution
            is Resolution.MethodNotAllowed -> {
                val allowed = resolution.allowed.filter { it != Route.ANY_METHOD }
                call.response.header(HttpHeaders.Allow, allowed.joinToString())
                return respondError(call, HttpStatusCode.MethodNotAllowed)
            }
            Resolution.NotFound ->
                return respondError(call, HttpStatusCode.NotFound)
        }
        val owner = match.route.owner
        onProject(owner.name)
        if (!owner.isActive)
            return respondError(call, HttpStatusCode.ServiceUnavailable, "Project '${owner.name}' is not running")

        return when (val target = match.route.target) {
            is RouteTarget.Static ->
                serveFile(call, target.root, match.params[PathPattern.WILDCARD].orEmpty())
            is RouteTarget.Handler ->
                runHandler(call, match, target, settings)
        }
    }

    private suspend fun runHandler(
        call: ApplicationCall,
        match: Resolution.Matched,
        target: RouteTarget.Handler,
        settings: ServerSettings,
    ): Int {
        val owner = match.route.owner
        val body = receiveBody(call, settings.maxBodyBytes)
            ?: return respondError(call, HttpStatusCode.PayloadTooLarge)
        val request = createRequest(call, match.params, body)
        val sink = ResponseSink()
        val response = HttpResponse(sink, owner.directory)

        val timeout = settings.handlerTimeoutMillis
        val deadline = System.currentTimeMillis() + timeout
        val result = handlerScope.async(owner.dispatcher) {
            val result = target.handler.handle(request, response)
            if (sink.isCommitted) null else ScriptValues.await(result, deadline - System.currentTimeMillis())
        }
        val isAnswered = withTimeoutOrNull(timeout) {
            val value = try {
                result.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                owner.logError("[WebServer] Error in handler of ${request.method} ${request.path}: ${e.message ?: e}")
                // What it sent before failing still goes out.
                if (!sink.isCommitted)
                    return@withTimeoutOrNull false
                null
            }
            if (!sink.isCommitted && !ScriptValues.isAbsentResult(value))
                sink.commit(ScriptValues.toBody(value, sink.contentType))
            sink.completion.await()
            true
        }

        if (isAnswered == false) {
            sink.close()
            return respondError(call, HttpStatusCode.InternalServerError)
        }
        if (isAnswered == null) {
            sink.close()
            owner.logError("[WebServer] ${request.method} ${request.path} sent no response in ${timeout}ms")
            return respondError(call, HttpStatusCode.GatewayTimeout)
        }
        return respond(call, sink, owner.directory)
    }

    private suspend fun respond(call: ApplicationCall, sink: ResponseSink, baseDir: File): Int {
        val status = HttpStatusCode.fromValue(sink.status)
        for ((name, value) in sink.headers) {
            if (!HttpHeaders.isUnsafe(name))
                call.response.header(name, value)
        }
        val isHead = call.request.httpMethod == HttpMethod.Head
        when (val body = sink.body) {
            ResponseBody.Empty -> {
                val contentType = sink.contentType?.let(::parseContentType)
                if (contentType == null)
                    call.respond(status)
                else
                    call.respondBytes(ByteArray(0), contentType, status)
            }
            is ResponseBody.Bytes ->
                call.respondBytes(if (isHead) ByteArray(0) else body.bytes, parseContentType(body.contentType), status)
            is ResponseBody.FileBody -> {
                val file = body.file
                if (!file.isFile)
                    return respondError(call, HttpStatusCode.NotFound)
                val contentType = body.contentType?.let(::parseContentType) ?: ContentType.defaultForFile(file)
                call.respond(status, LocalFileContent(file, contentType).headOnly(isHead))
            }
        }
        return status.value
    }

    private suspend fun serveFile(call: ApplicationCall, root: File, relativePath: String): Int {
        val segments = PathPattern.splitPath(relativePath)
        if (segments.any { it == ".." || it.contains('\\') || it.contains('\u0000') })
            return respondError(call, HttpStatusCode.NotFound)
        var file = segments.fold(root, File::resolve).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator))
            return respondError(call, HttpStatusCode.NotFound)
        if (file.isDirectory)
            file = file.resolve(INDEX_FILE)
        if (!file.isFile)
            return respondError(call, HttpStatusCode.NotFound)
        val isHead = call.request.httpMethod == HttpMethod.Head
        call.respond(LocalFileContent(file, ContentType.defaultForFile(file)).headOnly(isHead))
        return HttpStatusCode.OK.value
    }

    /** The body, or null if it is larger than [limit]. */
    private suspend fun receiveBody(call: ApplicationCall, limit: Long): ByteArray? {
        val length = call.request.contentLength()
        if (length != null && length > limit)
            return null
        val bytes = call.receiveChannel().readBuffer(limit + 1).readByteArray()
        return bytes.takeIf { it.size <= limit }
    }

    private fun createRequest(call: ApplicationCall, params: Map<String, String>, body: ByteArray): HttpRequest {
        val query = call.request.queryParameters
        return HttpRequest(
            method = call.request.httpMethod.value,
            path = call.request.path(),
            url = call.request.uri,
            params = params,
            query = query.names().associateWith { query[it].orEmpty() },
            queryValues = query.entries().associate { (name, values) -> name to values },
            headers = call.request.headers.entries().associate { (name, values) -> name.lowercase() to values.joinToString(", ") },
            rawBody = body,
            ip = call.request.local.remoteAddress,
            receivedAt = System.currentTimeMillis(),
        )
    }

    private fun isAuthorized(call: ApplicationCall, tokenHash: String?): Boolean {
        if (tokenHash == null)
            return false
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?: call.request.queryParameters[TOKEN_QUERY]
            ?: return false
        return MessageDigest.isEqual(sha256Hex(token).toByteArray(), tokenHash.toByteArray())
    }

    private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String = status.description): Int {
        val json = buildJsonObject {
            put("status", status.value)
            put("error", message)
        }
        call.respondText(json.toString(), ContentType.Application.Json, status)
        return status.value
    }

    /** Whether [e] means the connection broke while the request was read or the response written. */
    private fun isDisconnect(e: Throwable): Boolean =
        e is ChannelIOException || e is ClosedByteChannelException

    private fun parseContentType(value: String): ContentType =
        try {
            ContentType.parse(value)
        } catch (_: Exception) {
            ContentType.Application.OctetStream
        }

    private fun OutgoingContent.headOnly(isHead: Boolean): OutgoingContent =
        if (!isHead) this else object : OutgoingContent.NoContent() {
            override val contentType: ContentType? = this@headOnly.contentType
            override val contentLength: Long? = this@headOnly.contentLength
        }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        const val TOKEN_QUERY = "token"
        const val INDEX_FILE = "index.html"
        const val CORS_MAX_AGE = "600"
        /** Recorded for a request the client gave up on before the answer: nginx's convention, not an error. */
        const val CLIENT_CLOSED_REQUEST = 499
    }
}
