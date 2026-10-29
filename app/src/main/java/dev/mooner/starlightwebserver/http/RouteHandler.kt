package dev.mooner.starlightwebserver.http

/**
 * A request handler written in a script: `(req, res) => ...`.
 *
 * The handler answers by calling a sending method of `res`, now or later, or by returning the body (a promise of it in
 * NeonJS). Returning nothing without sending leaves the request open until it is sent or times out.
 */
fun interface RouteHandler {
    fun handle(req: HttpRequest, res: HttpResponse): Any?
}
