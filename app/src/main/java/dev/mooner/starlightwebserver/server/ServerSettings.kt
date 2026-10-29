package dev.mooner.starlightwebserver.server

/** Settings read for each request; changing them needs no restart. */
data class ServerSettings(
    /** SHA-256 hex of the access token, or null to accept requests without one. */
    val tokenHash: String? = null,
    val requireToken: Boolean = false,
    val enableCors: Boolean = false,
    val maxBodyBytes: Long = 1024L * 1024,
    val handlerTimeoutMillis: Long = 15_000,
    val logRequests: Boolean = false,
)
