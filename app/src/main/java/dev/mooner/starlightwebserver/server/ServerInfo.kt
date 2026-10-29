package dev.mooner.starlightwebserver.server

/** What scripts and the widget see of the server. */
data class ServerInfo(
    val isRunning: Boolean,
    val port: Int,
    val urls: List<String>,
)
