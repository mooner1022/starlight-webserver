---
title: Web server routes
summary: Answering HTTP requests from a script: the setup event, path patterns, req and res, static files, time limits, error responses, and the access token.
---

The Web Server plugin runs an HTTP server on the device, port 8080 by default, and passes each request to the route a
project registered.

## Registering routes

Allow the event **웹 서버 설정** (`webserver.server.setup`) in the project's events, and write its function. It is
called after every compilation, once the routes of the previous compilation are removed:

```js
export function onWebServerSetup(server) {
    // What a handler returns is the body: objects and arrays as JSON, strings as text.
    server.get("/hello/:name", (req) => ({ hello: req.params.name }));

    server.post("/echo", (req, res) => {
        res.status(201).header("X-Length", req.bodySize).send(req.body);
    });

    server.get("/weather", async (req, res) => {
        const r = await fetch("https://example.com/api");
        res.json(await r.json());
    });

    // The project directory's public folder under /files.
    server.serveStatic("/files", "public");
}
```

- In NeonJS the function must be exported; in Rhino it is a plain function.
- The global `WebServer` is the same object as `server`, for registering from top-level code.
- A `server` kept in a variable can add and remove routes later. After the project is compiled again, an old `server`
  is ignored, with an error in the project log.

## Paths

- `:name` captures one path segment as `req.params.name`; a trailing `*` captures the rest of the path, possibly
  empty, as `req.params["*"]`.
- When several routes match, literal segments win over `:name`, and `:name` over `*`; then a route of the request's
  method wins over `all`.
- GET routes also answer HEAD requests, without a body.
- When another project registered the same method and path first, requests keep going to that one, and an error goes
  to the project log.
- `serveStatic(prefix, directory)` serves files for GET requests; a directory request gets its `index.html`.

## Answering

- A handler answers by returning the body or by calling a sending method of `res`: `send`, `json`, `text`, `html`,
  `file`, `redirect` or `end`. Only the first one counts; after it, sending again or changing the status or headers
  does nothing.
- A handler may answer later, from a callback or a promise. A request that gets no answer within the time limit
  (**요청 › 핸들러 제한 시간**) gets 504.
- A handler that throws before answering gets 500, and the error goes to the project log.
- Handlers run on the project's threads.

## Error responses

The server answers some requests itself, with JSON such as `{"status": 404, "error": "Not Found"}`:

| Status | When |
|---|---|
| 401 | The access token is required and missing or wrong. |
| 404 | No route matches. |
| 405 | A route matches the path but not the method; the `Allow` header lists the methods. |
| 413 | The body is larger than **최대 본문 크기**. |
| 503 | The project that owns the route is off or not compiled. |
| 504 | The handler didn't answer in time. |

## Access and settings

- Requests need no token by default. With **접근 토큰 필수** on, they must send `Authorization: Bearer <token>` or
  `?token=<token>`.
- **접속 허용 범위** chooses all networks (`0.0.0.0`) or this device only (`127.0.0.1`).
- **다른 출처 요청 허용 (CORS)** lets web pages on other sites call the server.
- The plugin's settings (**제어 › 서버 상태**) show the server's addresses and copy them on a tap; scripts get them
  from `server.getUrls()`.
