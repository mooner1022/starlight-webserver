package dev.mooner.starlightwebserver.api

import dev.mooner.starlight.plugincore.api.Api
import dev.mooner.starlight.plugincore.api.ApiObject
import dev.mooner.starlight.plugincore.api.InstanceType
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlightwebserver.WebServerRuntime
import dev.mooner.starlightwebserver.project.ProjectRouteOwner
import dev.mooner.starlightwebserver.route.WebServer

/**
 * The project's router as the global `WebServer`, for registering routes from top-level code.
 *
 * StarLight creates the instance while compiling the project, which starts the router's generation; the setup event
 * fired after the compilation gets the same router. NeonJS doesn't expose plugin APIs yet, so for now only Rhino
 * scripts see this global; the setup event works in both.
 */
class WebServerApi : Api<WebServer>() {

    override val name: String = "WebServer"

    override val objects: List<ApiObject> =
        getApiObjects<WebServer>()

    override val instanceClass: Class<WebServer> =
        WebServer::class.java

    override val instanceType: InstanceType =
        InstanceType.OBJECT

    override fun getInstance(project: Project): Any =
        WebServerRuntime.routers.open(ProjectRouteOwner(project))
}
