package dev.mooner.starlightwebserver

import dev.mooner.starlightwebserver.route.ProjectRouters
import dev.mooner.starlightwebserver.route.RouteRegistry
import dev.mooner.starlightwebserver.server.ServerInfo
import dev.mooner.starlightwebserver.server.ServerState
import dev.mooner.starlightwebserver.stats.ServerStats
import dev.mooner.starlightwebserver.utils.serverUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State shared by the plugin, the scripts' routers and the widget. */
object WebServerRuntime {

    val registry = RouteRegistry()

    val stats = ServerStats()

    val routers = ProjectRouters(registry, ::serverInfo)

    private val mState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = mState.asStateFlow()

    /** The port in the config, reported while the server is not running. */
    @Volatile
    var configuredPort: Int = DEFAULT_PORT
        internal set

    internal fun setState(state: ServerState) {
        mState.value = state
    }

    fun serverInfo(): ServerInfo =
        when (val state = state.value) {
            is ServerState.Running -> ServerInfo(true, state.port, urlsOf(state))
            else                   -> ServerInfo(false, configuredPort, emptyList())
        }

    @Volatile
    private var urlCache: Triple<ServerState.Running, Long, List<String>>? = null

    /** Looking up network interfaces takes a while and the widget asks every second, so addresses are kept briefly. */
    private fun urlsOf(state: ServerState.Running): List<String> {
        val now = System.currentTimeMillis()
        urlCache
            ?.takeIf { (cached, time, _) -> cached == state && now - time < URL_CACHE_MILLIS }
            ?.let { (_, _, urls) -> return urls }
        return serverUrls(state.host, state.port).also { urlCache = Triple(state, now, it) }
    }

    const val DEFAULT_PORT = 8080
    private const val URL_CACHE_MILLIS = 10_000L
}
