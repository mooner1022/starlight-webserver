package dev.mooner.starlightwebserver.route

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Routes of every project. Lookups don't lock; changes are serialized. */
class RouteRegistry {

    private val routes: MutableList<Route> = CopyOnWriteArrayList()
    private val sequence = AtomicLong()

    val size: Int
        get() = routes.size

    /** Adds a route, replacing the owner's route of the same method and path. Returns the route of another owner it is shadowed by. */
    @Synchronized
    fun add(owner: RouteOwner, generation: Long, method: String, pattern: PathPattern, target: RouteTarget): Route? {
        routes.removeAll { it.owner.id == owner.id && it.method == method && it.pattern == pattern }
        val shadowedBy = routes.firstOrNull { it.owner.id != owner.id && it.method == method && it.pattern == pattern }
        routes += Route(owner, generation, method, pattern, target, sequence.incrementAndGet())
        return shadowedBy
    }

    @Synchronized
    fun remove(ownerId: String, method: String, pattern: PathPattern): Boolean =
        routes.removeAll { it.owner.id == ownerId && it.method == method && it.pattern == pattern }

    @Synchronized
    fun removeOwner(ownerId: String): Int {
        val owned = routes.filter { it.owner.id == ownerId }
        routes.removeAll(owned)
        return owned.size
    }

    fun routesOf(ownerId: String): List<Route> =
        routes.filter { it.owner.id == ownerId }

    fun resolve(method: String, path: String): Resolution {
        var best: Route? = null
        var bestParams: Map<String, String>? = null
        val allowed: MutableSet<String> = linkedSetOf()
        for (route in routes) {
            val params = route.pattern.match(path) ?: continue
            if (!route.accepts(method)) {
                allowed += route.method
                continue
            }
            if (best == null || route isPreferredTo best) {
                best = route
                bestParams = params
            }
        }
        return when {
            best != null       -> Resolution.Matched(best, bestParams!!)
            allowed.isNotEmpty() -> Resolution.MethodNotAllowed(allowed)
            else               -> Resolution.NotFound
        }
    }

    /** More specific paths win, then explicit methods over [Route.ANY_METHOD], then the earlier registration. */
    private infix fun Route.isPreferredTo(other: Route): Boolean {
        for ((a, b) in pattern.specificity.zip(other.pattern.specificity)) {
            if (a != b)
                return a < b
        }
        // Both matched the same path, so the longer one ends with a wildcard that matched nothing.
        if (pattern.specificity.size != other.pattern.specificity.size)
            return pattern.specificity.size < other.pattern.specificity.size
        val isAny = method == Route.ANY_METHOD
        if (isAny != (other.method == Route.ANY_METHOD))
            return !isAny
        return sequence < other.sequence
    }

    sealed interface Resolution {
        class Matched(val route: Route, val params: Map<String, String>) : Resolution
        class MethodNotAllowed(val allowed: Set<String>) : Resolution
        data object NotFound : Resolution
    }
}
