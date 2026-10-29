package dev.mooner.starlightwebserver.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathPatternTest {

    @Test
    fun matchesLiteralsAndParams() {
        val pattern = PathPattern.parse("/users/:id/posts/:post")
        assertEquals(mapOf("id" to "42", "post" to "7"), pattern.match("/users/42/posts/7"))
        assertEquals(mapOf("id" to "42", "post" to "7"), pattern.match("/users/42/posts/7/"))
        assertNull(pattern.match("/users/42/posts"))
        assertNull(pattern.match("/users/42/posts/7/extra"))
        assertNull(pattern.match("/user/42/posts/7"))
    }

    @Test
    fun decodesSegments() {
        assertEquals(mapOf("name" to "a b/c"), PathPattern.parse("/hello/:name").match("/hello/a%20b%2Fc"))
        assertEquals(emptyMap<String, String>(), PathPattern.parse("/caf%C3%A9").match("/café"))
    }

    @Test
    fun wildcardCapturesRest() {
        val pattern = PathPattern.parse("/files/*")
        assertEquals(mapOf("*" to "a/b.txt"), pattern.match("/files/a/b.txt"))
        assertEquals(mapOf("*" to ""), pattern.match("/files"))
        assertNull(pattern.match("/other/a"))
    }

    @Test
    fun rootMatchesOnlyRoot() {
        val pattern = PathPattern.parse("/")
        assertEquals(emptyMap<String, String>(), pattern.match("/"))
        assertNull(pattern.match("/a"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWildcardInTheMiddle() {
        PathPattern.parse("/a/*/b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRelativePath() {
        PathPattern.parse("a/b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicatedParams() {
        PathPattern.parse("/:id/:id")
    }
}
