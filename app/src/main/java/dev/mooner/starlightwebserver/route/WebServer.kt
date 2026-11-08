package dev.mooner.starlightwebserver.route

import dev.mooner.starlightwebserver.http.RouteHandler
import dev.mooner.starlightwebserver.server.ServerInfo
import java.io.File

/**
 * The project's routes on the Web Server plugin's HTTP server; the global `WebServer`, and the argument of
 * `onWebServerSetup(server)`.
 *
 * ```js
 * export function onWebServerSetup(server) {
 *     server.get("/hello/:name", (req, res) => res.json({ hello: req.params.name }));
 * }
 * ```
 *
 * A path is matched segment by segment: `:name` captures one segment into `req.params.name`, and a trailing `*`
 * captures the rest of the path, possibly empty, into `req.params["*"]`. When several routes match, literal segments
 * win over `:name`, and `:name` over `*`; then a route of the request's method wins over [all].
 *
 * If another project registered the same method and path first, requests keep going to that one, and an error goes to
 * the project log. This one is kept and takes over once the other is removed.
 *
 * An instance belongs to one compilation of the project. Once the project is compiled again, changes made through an
 * older instance are ignored, with an error in the project log.
 *
 * @see guide:routes
 */
class WebServer internal constructor(
    private val owner: RouteOwner,
    private val gen: Long,
    private val registry: RouteRegistry,
    private val routers: ProjectRouters,
    private val serverInfo: () -> ServerInfo,
) {

    /**
     * Identifies the compilation this router belongs to.
     *
     * @suppress
     */
    val generation: Long
        get() = gen

    /**
     * Handles GET requests to [path]; HEAD requests get the same response without a body.
     *
     * @param path A path such as `/hello/:name`; see the class for its syntax.
     * @param handler `(req, res) => ...`.
     * @return This router, for chaining.
     */
    fun get(path: String, handler: RouteHandler): WebServer =
        route("GET", path, handler)

    /**
     * Handles POST requests to [path].
     *
     * @return This router, for chaining.
     */
    fun post(path: String, handler: RouteHandler): WebServer =
        route("POST", path, handler)

    /**
     * Handles PUT requests to [path].
     *
     * @return This router, for chaining.
     */
    fun put(path: String, handler: RouteHandler): WebServer =
        route("PUT", path, handler)

    /**
     * Handles PATCH requests to [path].
     *
     * @return This router, for chaining.
     */
    fun patch(path: String, handler: RouteHandler): WebServer =
        route("PATCH", path, handler)

    /**
     * Handles DELETE requests to [path].
     *
     * @return This router, for chaining.
     */
    fun delete(path: String, handler: RouteHandler): WebServer =
        route("DELETE", path, handler)

    /**
     * Handles OPTIONS requests to [path].
     *
     * @return This router, for chaining.
     */
    fun options(path: String, handler: RouteHandler): WebServer =
        route("OPTIONS", path, handler)

    /**
     * Handles requests of every method to [path].
     *
     * @return This router, for chaining.
     */
    fun all(path: String, handler: RouteHandler): WebServer =
        route(Route.ANY_METHOD, path, handler)

    /**
     * Handles requests of [method] to [path].
     *
     * @param method An HTTP method such as `GET`, in any case.
     * @return This router, for chaining.
     * @throws IllegalArgumentException If [method] is blank.
     */
    fun route(method: String, path: String, handler: RouteHandler): WebServer =
        add(method, path, RouteTarget.Handler(handler))

    /**
     * Serves the files of [directory] for GET requests under [prefix]: `serveStatic("/static", "public")` answers
     * `/static/app.js` with `public/app.js`.
     *
     * @param prefix The path the files appear under.
     * @param directory The directory, relative to the project directory unless absolute.
     * @return This router, for chaining.
     * @throws IllegalArgumentException If [directory] isn't a directory.
     */
    fun serveStatic(prefix: String, directory: String): WebServer {
        val dir = File(directory).let { if (it.isAbsolute) it else owner.directory.resolve(directory) }
        require(dir.isDirectory) { "Not a directory: $dir" }
        val path = prefix.trimEnd('/') + "/" + PathPattern.WILDCARD
        return add("GET", path, RouteTarget.Static(dir.canonicalFile))
    }

    /**
     * Removes one of this project's routes, given as it was registered (`"GET", "/hello/:name"`).
     *
     * @return Whether there was such a route.
     */
    fun remove(method: String, path: String): Boolean =
        isCurrent() && registry.remove(owner.id, normalize(method), PathPattern.parse(path))

    /** Removes every route of this project. */
    fun clear() {
        if (isCurrent())
            registry.removeOwner(owner.id)
    }

    /** The routes of this project, such as `GET /hello/:name`. */
    fun getRoutes(): List<String> =
        registry.routesOf(owner.id).map(Route::toString)

    /** Whether the Web Server plugin's server is running, so the routes answer requests now. */
    fun isRunning(): Boolean =
        serverInfo().isRunning

    /** The port the server listens on, or is set to listen on while it is stopped. */
    fun getPort(): Int =
        serverInfo().port

    /** The addresses the server can be reached at, such as `http://192.168.0.2:8080`; empty while it is stopped. */
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

    /** @suppress */
    override fun toString(): String =
        "WebServer[${owner.name}]"
}
