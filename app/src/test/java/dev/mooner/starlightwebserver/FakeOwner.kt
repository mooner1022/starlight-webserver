package dev.mooner.starlightwebserver

import dev.mooner.starlightwebserver.route.RouteOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Collections

class FakeOwner(
    override val id: String = "project",
    override val name: String = id,
    override val directory: File = File("."),
) : RouteOwner {

    @Volatile
    override var isActive: Boolean = true

    override val dispatcher: CoroutineDispatcher = Dispatchers.IO

    val errors: MutableList<String> = Collections.synchronizedList(arrayListOf())

    override fun logInfo(message: String) {}

    override fun logError(message: String) {
        errors += message
    }
}
