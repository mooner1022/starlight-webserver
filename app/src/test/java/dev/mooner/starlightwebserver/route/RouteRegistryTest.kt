package dev.mooner.starlightwebserver.route

import dev.mooner.starlightwebserver.FakeOwner
import dev.mooner.starlightwebserver.http.RouteHandler
import dev.mooner.starlightwebserver.route.RouteRegistry.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRegistryTest {

    private val owner = FakeOwner("a")
    private val other = FakeOwner("b")
    private val registry = RouteRegistry()

    private fun handler(name: String) = RouteTarget.Handler(RouteHandler { _, _ -> name })

    private fun add(method: String, path: String, owner: RouteOwner = this.owner, name: String = "$method $path") =
        registry.add(owner, 1, method, PathPattern.parse(path), handler(name))

    private fun matched(method: String, path: String): String {
        val resolution = registry.resolve(method, path)
        check(resolution is Resolution.Matched) { "No match for $method $path: $resolution" }
        val target = resolution.route.target as RouteTarget.Handler
        return target.handler.handle(dummyRequest, dummyResponse) as String
    }

    @Test
    fun prefersSpecificPathsRegardlessOfOrder() {
        add("GET", "/users/*")
        add("GET", "/users/:id")
        add("GET", "/users/me")
        assertEquals("GET /users/me", matched("GET", "/users/me"))
        assertEquals("GET /users/:id", matched("GET", "/users/42"))
        assertEquals("GET /users/*", matched("GET", "/users/42/posts"))
    }

    @Test
    fun prefersExactPathOverEmptyWildcard() {
        add("GET", "/a/*")
        add("GET", "/a")
        assertEquals("GET /a", matched("GET", "/a"))
    }

    @Test
    fun prefersExplicitMethodOverAny() {
        add("*", "/x")
        add("POST", "/x")
        assertEquals("POST /x", matched("POST", "/x"))
        assertEquals("* /x", matched("DELETE", "/x"))
    }

    @Test
    fun headFallsBackToGet() {
        add("GET", "/x")
        assertEquals("GET /x", matched("HEAD", "/x"))
    }

    @Test
    fun reportsAllowedMethods() {
        add("GET", "/x")
        add("PUT", "/x")
        val resolution = registry.resolve("POST", "/x")
        assertTrue(resolution is Resolution.MethodNotAllowed)
        assertEquals(setOf("GET", "PUT"), (resolution as Resolution.MethodNotAllowed).allowed)
        assertSame(Resolution.NotFound, registry.resolve("GET", "/y"))
    }

    @Test
    fun replacesOwnRouteAndReportsOtherOwners() {
        assertNull(add("GET", "/x", name = "first"))
        assertNull(add("GET", "/x", name = "second"))
        assertEquals(1, registry.size)
        assertEquals("second", matched("GET", "/x"))

        assertNotNull(add("GET", "/x", owner = other, name = "other"))
        assertEquals("second", matched("GET", "/x"))
    }

    @Test
    fun removesRoutesOfOwner() {
        add("GET", "/x")
        add("GET", "/y")
        add("GET", "/z", owner = other)
        assertEquals(2, registry.removeOwner(owner.id))
        assertEquals(listOf("GET /z"), registry.routesOf(other.id).map(Route::toString))
    }

    private companion object {
        val dummyRequest = dev.mooner.starlightwebserver.http.HttpRequest(
            "GET", "/", "/", emptyMap(), emptyMap(), emptyMap(), emptyMap(), ByteArray(0), "", 0,
        )
        val dummyResponse = dev.mooner.starlightwebserver.http.HttpResponse(
            dev.mooner.starlightwebserver.http.ResponseSink(), java.io.File("."),
        )
    }
}
