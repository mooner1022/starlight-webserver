package dev.mooner.starlightwebserver.route

import dev.mooner.starlightwebserver.http.RouteHandler
import dev.mooner.starlightwebserver.server.ServerInfo
import java.io.File

/**
 * The router of one project, given to scripts by the `onWebServerSetup(server)` event and as the `WebServer` API.
 *
 * ```js
 * export function onWebServerSetup(server) {
 *     server.get("/hello/:name", (req, res) => res.json({ hello: req.params.name }));
 * }
 * ```
 */
class WebServer(
    private val owner: RouteOwner,
    private val gen: Long,
    private val registry: RouteRegistry,
    private val routers: ProjectRouters,
    private val serverInfo: () -> ServerInfo,
) {

    /** Identifies the compilation this router belongs to. */
    val generation: Long
        get() = gen

    fun get(path: String, handler: RouteHandler): WebServer =
        route("GET", path, handler)

    fun post(path: String, handler: RouteHandler): WebServer =
        route("POST", path, handler)

    fun put(path: String, handler: RouteHandler): WebServer =
        route("PUT", path, handler)

    fun patch(path: String, handler: RouteHandler): WebServer =
        route("PATCH", path, handler)

    fun delete(path: String, handler: RouteHandler): WebServer =
        route("DELETE", path, handler)

    fun options(path: String, handler: RouteHandler): WebServer =
        route("OPTIONS", path, handler)

    /** Handles every method. */
    fun all(path: String, handler: RouteHandler): WebServer =
        route(Route.ANY_METHOD, path, handler)

    fun route(method: String, path: String, handler: RouteHandler): WebServer =
        add(method, path, RouteTarget.Handler(handler))

    /** Serves files of [directory] (relative to the project directory) under [prefix]. */
    fun serveStatic(prefix: String, directory: String): WebServer {
        val dir = File(directory).let { if (it.isAbsolute) it else owner.directory.resolve(directory) }
        require(dir.isDirectory) { "Not a directory: $dir" }
        val path = prefix.trimEnd('/') + "/" + PathPattern.WILDCARD
        return add("GET", path, RouteTarget.Static(dir.canonicalFile))
    }

    fun remove(method: String, path: String): Boolean =
        isCurrent() && registry.remove(owner.id, normalize(method), PathPattern.parse(path))

    /** Removes every route of this project. */
    fun clear() {
        if (isCurrent())
            registry.removeOwner(owner.id)
    }

    /** Routes of this project, such as `GET /hello/:name`. */
    fun getRoutes(): List<String> =
        registry.routesOf(owner.id).map(Route::toString)

    fun isRunning(): Boolean =
        serverInfo().isRunning

    fun getPort(): Int =
        serverInfo().port

    /** Addresses the server can be reached at, such as `http://192.168.0.2:8080`. */
    fun getUrls(): List<String> =
        serverInfo().urls

    private fun add(method: String, path: String, target: RouteTarget): WebServer {
        if (!isCurrent()) {
            owner.logError("[WebServer] Ignored route $method $path: the script was recompiled since this router was given")
            return this
        }
        val pattern = PathPattern.parse(path)
        val shadowedBy = registry.add(owner, gen, normalize(method), pattern, target)
        if (shadowedBy != null)
            owner.logError("[WebServer] Route $method $path is already taken by project '${shadowedBy.owner.name}'")
        return this
    }

    private fun isCurrent(): Boolean =
        routers.isCurrent(owner.id, gen)

    private fun normalize(method: String): String =
        method.trim().uppercase().also { require(it.isNotEmpty()) { "Empty HTTP method" } }

    override fun toString(): String =
        "WebServer[${owner.name}]"
}
