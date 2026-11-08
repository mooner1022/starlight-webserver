package dev.mooner.starlightwebserver.http

/**
 * A request handler written in a script: `(req, res) => ...`.
 *
 * The handler runs on the project's threads. It answers by calling a sending method of `res`, now or later, or by
 * returning the body: text for strings, numbers and booleans, JSON for objects and arrays. In NeonJS it may return a
 * promise of the body. Returning nothing without sending leaves the request open until it is sent.
 *
 * A request not answered within the time limit set in the Web Server plugin gets 504. A handler that throws before
 * sending gets 500, with the error in the project log.
 */
fun interface RouteHandler {

    /**
     * Answers one request.
     *
     * @param req The request.
     * @param res The response to send.
     * @return The body to send, if `res` wasn't used to send one.
     */
    fun handle(req: HttpRequest, res: HttpResponse): Any?
}
