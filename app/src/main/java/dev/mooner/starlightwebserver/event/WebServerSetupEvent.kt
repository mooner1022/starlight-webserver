package dev.mooner.starlightwebserver.event

import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate
import dev.mooner.starlightwebserver.route.WebServer

/**
 * The project was compiled: register the routes the web server answers for it.
 *
 * Called once after each compilation. A project that is already compiled gets it once when the Web Server plugin
 * starts, or when the project starts receiving this event. The routes of the previous compilation are removed before
 * it is called.
 *
 * ```js
 * export function onWebServerSetup(server) {
 *     server.get("/hello/:name", (req, res) => res.json({ hello: req.params.name }));
 * }
 * ```
 *
 * In NeonJS the function must be exported.
 *
 * @param server The project's routes, valid until the next compilation.
 * @see guide:routes
 */
class WebServerSetupEvent : ProjectEvent() {

    override val functionName: String = FUNCTION_NAME

    override val id: String = "setup"

    override val name: String = translate {
        Locale.ENGLISH { "Web server setup" }
        Locale.KOREAN  { "웹 서버 설정" }
    }

    override val description: String = translate {
        Locale.ENGLISH { "After the project is compiled, to register the routes of the web server" }
        Locale.KOREAN  { "프로젝트가 컴파일된 뒤, 웹 서버의 라우트를 등록할 때" }
    }

    override val functionComment: String = translate {
        Locale.ENGLISH {
            """
                |Called after the project is compiled. Register routes on the server:
                |server.get("/hello/:name", (req, res) => res.json({ hello: req.params.name }));
                |Routes of the last compilation are removed before this is called.
                |In NeonJS, the function must be exported.
            """.trimMargin()
        }
        Locale.KOREAN {
            """
                |프로젝트가 컴파일된 후 호출됩니다. server에 라우트를 등록하세요:
                |server.get("/hello/:name", (req, res) => res.json({ hello: req.params.name }));
                |이 함수가 호출되기 전 이전 컴파일에서 등록한 라우트는 모두 제거됩니다.
                |NeonJS에서는 함수를 export 해야 합니다.
            """.trimMargin()
        }
    }

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf("server" typedAs WebServer::class)

    companion object {
        const val FUNCTION_NAME = "onWebServerSetup"
    }
}
