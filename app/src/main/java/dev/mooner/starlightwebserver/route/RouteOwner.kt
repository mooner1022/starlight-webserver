package dev.mooner.starlightwebserver.route

import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/** The project a route belongs to, as the server sees it. */
interface RouteOwner {

    val id: String

    val name: String

    /** Whether requests may be handled now; inactive owners are answered with 503. */
    val isActive: Boolean

    /** Base of relative paths given by the owner's scripts. */
    val directory: File

    /** Where the owner's handlers run. */
    val dispatcher: CoroutineDispatcher

    fun logInfo(message: String)

    fun logError(message: String)
}
