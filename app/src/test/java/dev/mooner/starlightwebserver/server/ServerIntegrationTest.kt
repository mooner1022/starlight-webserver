package dev.mooner.starlightwebserver.server

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.starlightwebserver.FakeOwner
import dev.mooner.starlightwebserver.route.ProjectRouters
import dev.mooner.starlightwebserver.route.RouteRegistry
import dev.mooner.starlightwebserver.stats.ServerStats
import dev.mooner.starlightwebserver.utils.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** Requests over HTTP to handlers written in NeonJS, set up the way StarLight sets up its engine. */
class ServerIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var owner: FakeOwner
    private val registry = RouteRegistry()
    private val stats = ServerStats()
    private val port = ServerSocket(0).use { it.localPort }
    private val routers = ProjectRouters(registry) { ServerInfo(true, port, listOf("http://127.0.0.1:$port")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logged = CopyOnWriteArrayList<String>()

    @Volatile
    private var settings = ServerSettings(handlerTimeoutMillis = 2_000)

    private lateinit var engine: HttpServerEngine
    private lateinit var neon: NeonContext

    @Before
    fun setUp() {
        val projectDir = folder.newFolder("project")
        projectDir.resolve("public").mkdirs()
        projectDir.resolve("public/hello.txt").writeText("static file")
        projectDir.resolve("public/index.html").writeText("<h1>index</h1>")
        projectDir.resolve("secret.txt").writeText("secret")
        owner = FakeOwner(directory = projectDir)

        engine = HttpServerEngine(RequestDispatcher(registry, stats, { settings }, scope) { logged += it })
        engine.start("127.0.0.1", port)

        neon = NeonEngine.builder()
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build())
            .hostAccess(
                HostAccess.builder(HostAccess.Level.ALL)
                    .defaultDenyList(false)
                    .allowLookup { true }
                    .build()
            )
            .webGlobals(true)
            .build()
            .newContext()
        neon["server"] = routers.claim(owner)
        neon.eval(SCRIPT, "test.js")
    }

    @After
    fun tearDown() {
        engine.stop()
        scope.cancel()
        neon.close()
    }

    @Test
    fun clientsLeavingAreNotErrors() {
        // A browser stops a download.
        owner.directory.resolve("public/big.bin").writeBytes(ByteArray(8_000_000))
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply { write("GET /static/big.bin HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()); flush() }
            socket.getInputStream().readNBytes(100)
            socket.setSoLinger(true, 0)
        }
        // A client drops the connection while sending.
        Socket("127.0.0.1", port).use { socket ->
            val head = "POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\nContent-Length: 100000\r\n\r\nhalf"
            socket.getOutputStream().apply { write(head.toByteArray()); flush() }
            Thread.sleep(200)
            socket.setSoLinger(true, 0)
        }
        Thread.sleep(500)

        assertEquals(emptyList<String>(), logged)
        val snapshot = stats.snapshot()
        assertEquals(0L, snapshot.errors)
        assertEquals(listOf(499, 200), snapshot.recent.map { it.status })
    }

    @Test
    fun readsRequestAndSendsJson() {
        val response = send("GET", "/hello/world?x=1&x=2", headers = mapOf("X-Test" to "t"))
        assertEquals(200, response.status)
        assertTrue(response.header("Content-Type")!!.startsWith("application/json"))
        assertJson("""{"hello":"world","q":"1","all":["1","2"],"method":"GET","header":"t"}""", response.body)
    }

    @Test
    fun returnedValuesBecomeTheBody() {
        assertJson("""{"ok":true,"list":[1,2.5,"a"],"nested":{"n":null}}""", send("GET", "/object").body)
        val text = send("GET", "/text")
        assertEquals("plain", text.body)
        assertTrue(text.header("Content-Type")!!.startsWith("text/plain"))
        // JSON.stringify of the script's own engine, so Dates come out as in JS.
        assertJson("""{"d":"1970-01-01T00:00:00.000Z"}""", send("GET", "/date").body)
    }

    @Test
    fun echoesBodyAndForm() {
        val echo = send("POST", "/echo", body = "안녕 body")
        assertEquals(201, echo.status)
        assertEquals("안녕 body", echo.body)
        assertEquals("안녕 body".toByteArray().size.toString(), echo.header("X-Len")!!)

        val form = send("POST", "/form", body = "name=a+b&x=1", headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        assertJson("""{"name":"a b"}""", form.body)
    }

    @Test
    fun asyncHandlers() {
        val sent = send("GET", "/async")
        assertEquals(202, sent.status)
        assertEquals("later", sent.body)
        assertJson("""{"a":1}""", send("GET", "/async-return").body)
    }

    @Test
    fun failuresAndTimeouts() {
        assertEquals(500, send("GET", "/throw").status)
        assertTrue(owner.errors.any { "boom" in it })

        settings = settings.copy(handlerTimeoutMillis = 300)
        assertEquals(504, send("GET", "/never").status)
    }

    @Test
    fun routingErrors() {
        assertEquals(404, send("GET", "/nothing").status)
        val notAllowed = send("DELETE", "/text")
        assertEquals(405, notAllowed.status)
        assertEquals("GET", notAllowed.header("Allow")!!)

        owner.isActive = false
        assertEquals(503, send("GET", "/text").status)
    }

    @Test
    fun redirectsAndHead() {
        val redirect = send("GET", "/redirect")
        assertEquals(302, redirect.status)
        assertEquals("/text", redirect.header("Location")!!)

        val head = send("HEAD", "/text")
        assertEquals(200, head.status)
        assertEquals("", head.body)
    }

    @Test
    fun servesStaticFilesInsideTheDirectoryOnly() {
        assertEquals("static file", send("GET", "/static/hello.txt").body)
        assertEquals("<h1>index</h1>", send("GET", "/static/").body)
        assertEquals(404, send("GET", "/static/missing.txt").status)
        assertEquals(404, send("GET", "/static/%2E%2E/secret.txt").status)
        assertEquals(404, send("GET", "/static/..%2Fsecret.txt").status)
    }

    @Test
    fun accessToken() {
        settings = settings.copy(requireToken = true, tokenHash = sha256Hex("s3cret-token"))
        assertEquals(401, send("GET", "/text").status)
        assertEquals(401, send("GET", "/text", headers = mapOf("Authorization" to "Bearer wrong")).status)
        assertEquals(200, send("GET", "/text", headers = mapOf("Authorization" to "Bearer s3cret-token")).status)
        assertEquals(200, send("GET", "/text?token=s3cret-token").status)
    }

    @Test
    fun corsPreflight() {
        settings = settings.copy(enableCors = true)
        val response = send("OPTIONS", "/text", headers = mapOf(
            "Origin" to "https://example.com",
            "Access-Control-Request-Method" to "GET",
        ))
        assertEquals(204, response.status)
        assertEquals("*", response.header("Access-Control-Allow-Origin")!!)
    }

    @Test
    fun bodyLimit() {
        settings = settings.copy(maxBodyBytes = 8)
        assertEquals(413, send("POST", "/echo", body = "123456789").status)
        assertEquals(201, send("POST", "/echo", body = "12345678").status)
    }

    @Test
    fun portInUseFailsToStart() {
        val second = HttpServerEngine(RequestDispatcher(registry, stats, { settings }, scope) { logged += it })
        assertThrows(Exception::class.java) { second.start("127.0.0.1", port) }
        assertFalse(second.isRunning)
        // The running server is unaffected.
        assertEquals(200, send("GET", "/text").status)
    }

    @Test
    fun recordsStats() {
        send("GET", "/text")
        send("GET", "/nothing")
        val snapshot = stats.snapshot()
        assertEquals(2, snapshot.total)
        assertEquals(listOf(404, 200), snapshot.recent.map { it.status })
        assertEquals(2, snapshot.perMinute.last())
    }

    private class Response(val status: Int, private val headers: Map<String, List<String>>, val body: String) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { String(it.readBytes()) }.orEmpty()
            return Response(status, connection.headerFields.filterKeys { it != null }, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun assertJson(expected: String, actual: String) =
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))

    private companion object {
        val SCRIPT = """
            server.get("/hello/:name", (req, res) => res.json({
                hello: req.params.name,
                q: req.query.x,
                all: req.queryAll("x"),
                method: req.method,
                header: req.header("X-Test"),
            }));
            server.get("/object", () => ({ ok: true, list: [1, 2.5, "a"], nested: { n: null } }));
            server.get("/text", () => "plain");
            server.get("/date", () => ({ d: new Date(0) }));
            server.post("/echo", (req, res) => res.status(201).header("X-Len", req.bodySize).send(req.body));
            server.post("/form", (req) => ({ name: req.form.name }));
            server.get("/async", async (req, res) => {
                await new Promise(resolve => setTimeout(resolve, 30));
                res.status(202).send("later");
            });
            server.get("/async-return", async () => { await null; return { a: 1 }; });
            server.get("/throw", () => { throw new Error("boom"); });
            server.get("/never", () => {});
            server.get("/redirect", (req, res) => res.redirect("/text"));
            server.serveStatic("/static", "public");
        """.trimIndent()
    }
}
