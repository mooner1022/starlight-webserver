package dev.mooner.starlightwebserver.route

import dev.mooner.starlightwebserver.http.RouteHandler
import java.io.File

class Route internal constructor(
    val owner: RouteOwner,
    val generation: Long,
    /** An upper-case HTTP method, or [ANY_METHOD]. */
    val method: String,
    val pattern: PathPattern,
    val target: RouteTarget,
    internal val sequence: Long,
) {

    fun accepts(method: String): Boolean =
        this.method == ANY_METHOD || this.method == method || (method == "HEAD" && this.method == "GET")

    override fun toString(): String =
        "$method $pattern"

    companion object {
        const val ANY_METHOD = "*"
    }
}

sealed interface RouteTarget {

    class Handler(val handler: RouteHandler) : RouteTarget

    /** Files under [root], addressed by the `*` param of the route. */
    class Static(val root: File) : RouteTarget
}
