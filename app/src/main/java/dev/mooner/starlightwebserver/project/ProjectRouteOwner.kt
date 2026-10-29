package dev.mooner.starlightwebserver.project

import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlightwebserver.route.RouteOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.coroutines.ContinuationInterceptor

class ProjectRouteOwner(
    private val project: Project,
) : RouteOwner {

    override val id: String =
        project.info.id.toString()

    override val name: String
        get() = project.info.name

    override val isActive: Boolean
        get() = project.isCompiled && project.info.isEnabled

    override val directory: File
        get() = project.directory

    // Handlers run on the project's own threads, as its events do.
    override val dispatcher: CoroutineDispatcher
        get() = project.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.IO

    override fun logInfo(message: String) =
        project.logger.info(message)

    override fun logError(message: String) =
        project.logger.error(message)
}
