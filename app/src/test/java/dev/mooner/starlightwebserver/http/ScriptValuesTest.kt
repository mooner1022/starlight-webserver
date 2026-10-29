package dev.mooner.starlightwebserver.http

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Values as Rhino hands them over: JS objects are Maps, arrays are Lists, numbers are Doubles. */
class ScriptValuesTest {

    @Test
    fun mapsAndListsBecomeJson() {
        val value = mapOf("n" to 1.0, "f" to 2.5, "list" to listOf("a", 3.0, null), "nested" to mapOf("ok" to true))
        assertEquals(
            Json.parseToJsonElement("""{"n":1,"f":2.5,"list":["a",3,null],"nested":{"ok":true}}"""),
            Json.parseToJsonElement(ScriptValues.toJson(value)),
        )
    }

    @Test
    fun textBodies() {
        val body = ScriptValues.toBody(42.0, null) as ResponseBody.Bytes
        assertEquals("42", String(body.bytes))
        assertTrue(body.contentType.startsWith("text/plain"))
        assertEquals("<p>", String((ScriptValues.toBody("<p>", "text/html") as ResponseBody.Bytes).bytes))
    }

    @Test
    fun undefinedFromRhinoIsNoResult() {
        assertTrue(ScriptValues.isAbsentResult("undefined"))
        assertTrue(ScriptValues.isAbsentResult(null))
        assertTrue(ScriptValues.toBody(null, null) === ResponseBody.Empty)
    }
}
