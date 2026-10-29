package dev.mooner.starlightwebserver.route

import dev.mooner.starlightwebserver.FakeOwner
import dev.mooner.starlightwebserver.server.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRoutersTest {

    private val owner = FakeOwner()
    private val registry = RouteRegistry()
    private val routers = ProjectRouters(registry) { ServerInfo(false, 8080, emptyList()) }

    @Test
    fun setupEventGetsTheRouterTheApiOpened() {
        val fromApi = routers.open(owner)
        fromApi.get("/top-level") { _, _ -> "" }
        val fromEvent = routers.claim(owner)
        assertSame(fromApi, fromEvent)
        assertEquals(listOf("GET /top-level"), fromEvent.getRoutes())
    }

    @Test
    fun eachCompilationStartsOver() {
        val first = routers.claim(owner)
        first.get("/old") { _, _ -> "" }
        val second = routers.claim(owner)
        assertNotSame(first, second)
        assertEquals(emptyList<String>(), second.getRoutes())

        // A router kept by the old script scope can't add routes anymore.
        first.get("/stale") { _, _ -> "" }
        assertEquals(0, registry.size)
        assertTrue(owner.errors.single().contains("recompiled"))
    }

    @Test
    fun setUpOncePerGeneration() {
        val router = routers.claim(owner)
        assertTrue(routers.markSetUp(owner.id, router.generation))
        assertFalse(routers.markSetUp(owner.id, router.generation))
        assertFalse(routers.markSetUp(owner.id, router.generation - 1))
    }

    @Test
    fun noSetupWhileCompiling() {
        val router = routers.open(owner)
        assertFalse(routers.markSetUp(owner.id, router.generation))
        routers.claim(owner)
        assertTrue(routers.markSetUp(owner.id, router.generation))
    }

    @Test
    fun closeDropsRoutes() {
        routers.claim(owner).get("/x") { _, _ -> "" }
        routers.close(owner.id)
        assertEquals(0, registry.size)
    }
}
