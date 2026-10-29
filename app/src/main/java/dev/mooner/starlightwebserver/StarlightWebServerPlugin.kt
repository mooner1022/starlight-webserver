package dev.mooner.starlightwebserver

import android.text.InputType
import dev.mooner.configdsl.ConfigStructure
import dev.mooner.configdsl.Icon
import dev.mooner.configdsl.config
import dev.mooner.configdsl.options.button
import dev.mooner.configdsl.options.password
import dev.mooner.configdsl.options.seekbar
import dev.mooner.configdsl.options.spinner
import dev.mooner.configdsl.options.string
import dev.mooner.configdsl.options.toggle
import dev.mooner.starlight.plugincore.Session
import dev.mooner.starlight.plugincore.config.data.ConfigData
import dev.mooner.starlight.plugincore.event.EventHandler
import dev.mooner.starlight.plugincore.event.Events
import dev.mooner.starlight.plugincore.event.on
import dev.mooner.starlight.plugincore.logger.LogData
import dev.mooner.starlight.plugincore.logger.LoggerFactory
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlight.plugincore.project.event.ProjectEventManager
import dev.mooner.starlightwebserver.api.WebServerApi
import dev.mooner.starlightwebserver.event.WebServerSetupEvent
import dev.mooner.starlightwebserver.project.ProjectRouteOwner
import dev.mooner.starlightwebserver.route.WebServer
import dev.mooner.starlightwebserver.server.HttpServerEngine
import dev.mooner.starlightwebserver.server.RequestDispatcher
import dev.mooner.starlightwebserver.server.ServerSettings
import dev.mooner.starlightwebserver.server.ServerState
import dev.mooner.starlightwebserver.utils.ANY_HOST
import dev.mooner.starlightwebserver.utils.LOCALHOST
import dev.mooner.starlightwebserver.utils.copyServerUrl
import dev.mooner.starlightwebserver.utils.sha256Hex
import dev.mooner.starlightwebserver.utils.tr
import dev.mooner.starlightwebserver.widget.WebServerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val LOG = LoggerFactory.logger("WebServerPlugin")

@Suppress("unused")
class StarlightWebServerPlugin : StarlightPlugin() {

    // The server is started and stopped on this thread only: binding is network I/O, which Android forbids on the
    // main thread (config buttons are clicked there).
    private val serverExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StarlightWebServer-Server").apply {
            isDaemon = true
            contextClassLoader = StarlightWebServerPlugin::class.java.classLoader
        }
    }

    /** Lives from onEnable to onDisable; runs event subscriptions and request handlers. */
    private var pluginScope: CoroutineScope? = null

    /** Accessed on [serverExecutor] only. */
    private var engine: HttpServerEngine? = null

    @Volatile
    private var settings = ServerSettings()
    @Volatile
    private var host: String = ANY_HOST
    @Volatile
    private var port: Int = WebServerRuntime.DEFAULT_PORT
    private var autoStart = true

    private val isRunning: Boolean
        get() = WebServerRuntime.state.value is ServerState.Running

    override fun onEnable() {
        updateConfig(getPluginConfig())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pluginScope = scope

        registerProjectEvents {
            category("server") {
                add<WebServerSetupEvent>()
            }
        }
        addApi(WebServerApi())
        try {
            addWidget(WebServerWidget)
        } catch (e: LinkageError) {
            LOG.warn {
                tr("Widget is not supported by this StarLight version: $e", "이 StarLight 버전에서는 위젯을 지원하지 않아요: $e")
            }
        }

        EventHandler.on<Events.Project.Compile>(scope) { setUpProject(project) }
        EventHandler.on<Events.Project.InfoUpdate>(scope) { onProjectInfoUpdated(project) }
        EventHandler.on<Events.Project.Delete>(scope) { WebServerRuntime.routers.close(projectId.toString()) }
        // Projects compiled before this plugin was enabled.
        for (project in Session.projectManager.getProjects())
            if (project.isCompiled && WebServerRuntime.routers.current(project.info.id.toString()) == null)
                setUpProject(project)

        if (autoStart)
            serverExecutor.execute(::startServer)
    }

    override fun onDisable() {
        pluginScope?.cancel()
        pluginScope = null
        serverExecutor.execute(::stopServer)
    }

    override fun onConfigUpdated(config: ConfigData, updated: Map<String, Set<String>>) {
        super.onConfigUpdated(config, updated)
        val oldHost = host
        val oldPort = port
        updateConfig(config)
        if ((host != oldHost || port != oldPort) && isRunning) {
            LOG.info { tr("Restarting the server on $host:$port", "$host:$port 에서 서버를 다시 시작해요.") }
            serverExecutor.execute(::restartServer)
        }
    }

    private fun updateConfig(config: ConfigData) {
        with(config.category(CATEGORY_GENERAL)) {
            port = getString(CONF_PORT)?.toIntOrNull()?.takeIf(::isValidPort) ?: WebServerRuntime.DEFAULT_PORT
            host = if (getInt(CONF_BIND, BIND_ALL) == BIND_LOCAL) LOCALHOST else ANY_HOST
            autoStart = getBoolean(CONF_AUTO_START, true)
        }
        WebServerRuntime.configuredPort = port

        val security = config.category(CATEGORY_SECURITY)
        val request = config.category(CATEGORY_REQUEST)
        settings = ServerSettings(
            tokenHash = security.getString(CONF_TOKEN)?.takeUnless { it.isBlank() || it == EMPTY_TOKEN_HASH },
            requireToken = security.getBoolean(CONF_REQUIRE_TOKEN, false),
            enableCors = security.getBoolean(CONF_CORS, false),
            maxBodyBytes = BODY_LIMITS.getOrElse(request.getInt(CONF_MAX_BODY, DEF_BODY_LIMIT)) { BODY_LIMITS[DEF_BODY_LIMIT] },
            handlerTimeoutMillis = request.getInt(CONF_TIMEOUT, DEF_TIMEOUT_SECONDS) * 1000L,
            logRequests = config.category(CATEGORY_LOGGING).getBoolean(CONF_LOG_REQUESTS, false),
        )
    }

    // ------------------------------------------------------------------ projects

    /** Starts a router generation for a compiled [project] and calls its setup function with it. */
    private fun setUpProject(project: Project) {
        val router = WebServerRuntime.routers.claim(ProjectRouteOwner(project))
        callSetup(project, router)
    }

    /** Runs the setup of a project whose setup event was enabled after it was compiled. */
    private fun onProjectInfoUpdated(project: Project) {
        if (!project.isCompiled)
            return
        val router = WebServerRuntime.routers.current(project.info.id.toString()) ?: return
        callSetup(project, router)
    }

    private fun callSetup(project: Project, router: WebServer) {
        val eventId = ProjectEventManager.validateAndGetEventID(WebServerSetupEvent::class) ?: return
        if (!project.isCompiled || !project.isEventCallAllowed(eventId))
            return
        if (!WebServerRuntime.routers.markSetUp(project.info.id.toString(), router.generation))
            return
        project.callFunction(WebServerSetupEvent.FUNCTION_NAME, arrayOf(router)) { e ->
            LOG.error {
                tr(
                    "Error while setting up routes of project ${project.info.name}: ${e.message}",
                    "프로젝트 ${project.info.name}의 라우트 설정 중 오류 발생: ${e.message}",
                )
            }
        }
    }

    // ------------------------------------------------------------------ server

    /** Runs on [serverExecutor]. */
    private fun startServer() {
        if (engine != null)
            return
        val scope = pluginScope ?: return
        if (settings.requireToken && settings.tokenHash == null)
            LOG.warn {
                tr(
                    "Access token is required but not set: every request will be rejected.",
                    "접근 토큰이 필수로 설정되었지만 토큰이 없어요. 모든 요청이 거부돼요.",
                )
            }
        val host = host
        val port = port
        WebServerRuntime.setState(ServerState.Starting)
        val dispatcher = RequestDispatcher(
            registry = WebServerRuntime.registry,
            stats = WebServerRuntime.stats,
            settings = { settings },
            handlerScope = scope,
            log = { message -> LOG.info { message } },
        )
        val engine = HttpServerEngine(dispatcher)
        try {
            engine.start(host, port)
        } catch (e: Throwable) {
            WebServerRuntime.setState(ServerState.Failed(e.message ?: e.toString()))
            LOG.error {
                tr("Failed to start the web server on $host:$port: $e", "$host:$port 에서 웹 서버를 시작하지 못했어요: $e")
            }
            return
        }
        this.engine = engine
        WebServerRuntime.setState(ServerState.Running(host, port, System.currentTimeMillis()))
        val urls = WebServerRuntime.serverInfo().urls.joinToString()
        LOG.info { tr("Web server started: $urls", "웹 서버 시작됨: $urls") }
    }

    /** Runs on [serverExecutor]. */
    private fun stopServer() {
        val engine = engine ?: return
        this.engine = null
        try {
            engine.stop()
        } catch (e: Exception) {
            LOG.error { tr("Error while stopping the web server: $e", "웹 서버 종료 중 오류 발생: $e") }
        }
        WebServerRuntime.setState(ServerState.Stopped)
        LOG.info { tr("Web server stopped", "웹 서버가 종료되었어요.") }
    }

    private fun restartServer() {
        stopServer()
        startServer()
    }

    // ------------------------------------------------------------------ config

    override fun getConfigStructure(): ConfigStructure = config {
        category {
            id = CATEGORY_GENERAL
            title = tr("General", "일반")
            textColor = color { COLOR_TITLE }
            items {
                string {
                    id = CONF_PORT
                    title = tr("Port", "포트")
                    hint = WebServerRuntime.DEFAULT_PORT.toString()
                    defaultValue = WebServerRuntime.DEFAULT_PORT.toString()
                    inputType = InputType.TYPE_CLASS_NUMBER
                    require = { value ->
                        if (value.toIntOrNull()?.let(::isValidPort) == true) null
                        else tr("Enter a port between $MIN_PORT and $MAX_PORT", "$MIN_PORT~$MAX_PORT 사이의 포트를 입력하세요")
                    }
                    icon = Icon.LINK
                    iconTintColor = color { COLOR_ICON }
                }
                spinner {
                    id = CONF_BIND
                    title = tr("Accept connections from", "접속 허용 범위")
                    items = listOf(
                        tr("All networks ($ANY_HOST)", "모든 네트워크 ($ANY_HOST)"),
                        tr("This device only ($LOCALHOST)", "이 기기에서만 ($LOCALHOST)"),
                    )
                    defaultIndex = BIND_ALL
                    icon = Icon.CLOUD
                    iconTintColor = color { COLOR_ICON }
                }
                toggle {
                    id = CONF_AUTO_START
                    title = tr("Start automatically", "자동 시작")
                    description = tr("Starts the server when StarLight starts.", "StarLight 시작 시 서버를 함께 시작합니다.")
                    defaultValue = true
                    icon = Icon.POWER
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_CONTROL
            title = tr("Control", "제어")
            textColor = color { COLOR_SUB_TITLE }
            items {
                button {
                    val state = WebServerRuntime.state.value
                    id = "state_indicator"
                    title = when (state) {
                        is ServerState.Running -> tr("Running on ${WebServerRuntime.serverInfo().urls.joinToString()}", "작동 중: ${WebServerRuntime.serverInfo().urls.joinToString()}")
                        ServerState.Starting   -> tr("Starting...", "시작 중...")
                        ServerState.Stopped    -> tr("Stopped", "중지됨")
                        is ServerState.Failed  -> tr("Failed: ${state.reason}", "오류: ${state.reason}")
                    }
                    description = if (state is ServerState.Running) tr("Tap to copy the address.", "눌러서 주소를 복사합니다.") else null
                    icon = Icon.POWER
                    iconTintColor = color {
                        when (state) {
                            is ServerState.Running -> "#4ADE80"
                            ServerState.Starting   -> "#F2B05E"
                            else                   -> "#FF6188"
                        }
                    }
                    setOnClickListener { view -> copyServerUrl(view.context) }
                }
                button {
                    val action = if (isRunning) tr("Stop", "중지") else tr("Start", "시작")
                    id = "toggle_server"
                    title = tr("$action server", "서버 $action")
                    icon = Icon.POWER
                    iconTintColor = color { "#FF6188" }
                    setOnClickListener { _ ->
                        LOG.info(LogData.FLAG_NOTIFY) { tr("$action server...", "서버 $action 중...") }
                        serverExecutor.execute {
                            if (engine != null) stopServer() else startServer()
                        }
                    }
                }
                button {
                    id = "restart_server"
                    title = tr("Restart server", "서버 재시작")
                    description = tr("Routes registered by projects are kept.", "프로젝트가 등록한 라우트는 유지됩니다.")
                    icon = Icon.REFRESH
                    iconTintColor = color { "#FF6188" }
                    setOnClickListener { _ ->
                        LOG.info(LogData.FLAG_NOTIFY) { tr("Restarting server...", "서버 재시작 중...") }
                        serverExecutor.execute(::restartServer)
                    }
                }
            }
        }
        category {
            id = CATEGORY_SECURITY
            title = tr("Security", "보안")
            textColor = color { COLOR_SUB_TITLE }
            items {
                toggle {
                    id = CONF_REQUIRE_TOKEN
                    title = tr("Require access token", "접근 토큰 필수")
                    description = tr(
                        "Requests must send the token as 'Authorization: Bearer <token>' or '?token=<token>'.",
                        "요청에 'Authorization: Bearer <토큰>' 헤더나 '?token=<토큰>' 파라미터가 있어야 합니다.",
                    )
                    defaultValue = false
                    icon = Icon.LOCK
                    iconTintColor = color { COLOR_ICON }
                }
                password {
                    id = CONF_TOKEN
                    title = tr("Access token", "접근 토큰")
                    description = tr("Only a hash of the token is stored.", "토큰은 해시로만 저장됩니다.")
                    hashCode = ::sha256Hex
                    require = { value -> if (value.length >= MIN_TOKEN_LENGTH) null else tr("At least $MIN_TOKEN_LENGTH characters", "${MIN_TOKEN_LENGTH}자 이상 입력하세요") }
                    icon = Icon.KEY
                    iconTintColor = color { COLOR_ICON }
                    dependency = CONF_REQUIRE_TOKEN
                }
                toggle {
                    id = CONF_CORS
                    title = tr("Allow cross-origin requests (CORS)", "다른 출처 요청 허용 (CORS)")
                    description = tr("Lets web pages on other sites call this server.", "다른 사이트의 웹 페이지에서 이 서버를 호출할 수 있게 합니다.")
                    defaultValue = false
                    icon = Icon.SHARE
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_REQUEST
            title = tr("Requests", "요청")
            textColor = color { COLOR_SUB_TITLE }
            items {
                spinner {
                    id = CONF_MAX_BODY
                    title = tr("Max body size", "최대 본문 크기")
                    items = BODY_LIMIT_LABELS
                    defaultIndex = DEF_BODY_LIMIT
                    icon = Icon.FILE
                    iconTintColor = color { COLOR_ICON }
                }
                seekbar {
                    id = CONF_TIMEOUT
                    title = tr("Handler timeout (seconds)", "핸들러 제한 시간 (초)")
                    description = tr("Requests not answered in time get 504.", "시간 내에 응답하지 않은 요청은 504로 응답합니다.")
                    min = 1
                    max = 120
                    defaultValue = DEF_TIMEOUT_SECONDS
                    icon = Icon.TIMER
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_LOGGING
            title = tr("Logging", "로그")
            textColor = color { COLOR_SUB_TITLE }
            items {
                toggle {
                    id = CONF_LOG_REQUESTS
                    title = tr("Log requests", "요청 로그 표시")
                    description = tr("Logs every request with its status and duration.", "모든 요청을 상태 코드, 처리 시간과 함께 로그에 남깁니다.")
                    defaultValue = false
                    icon = Icon.LIST_BULLETED
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
    }

    private companion object {
        const val CATEGORY_GENERAL  = "general"
        const val CATEGORY_CONTROL  = "control"
        const val CATEGORY_SECURITY = "security"
        const val CATEGORY_REQUEST  = "request"
        const val CATEGORY_LOGGING  = "logging"

        const val CONF_PORT          = "port"
        const val CONF_BIND          = "bind_address"
        const val CONF_AUTO_START    = "auto_start"
        const val CONF_REQUIRE_TOKEN = "require_token"
        const val CONF_TOKEN         = "access_token"
        const val CONF_CORS          = "enable_cors"
        const val CONF_MAX_BODY      = "max_body_size"
        const val CONF_TIMEOUT       = "handler_timeout"
        const val CONF_LOG_REQUESTS  = "log_requests"

        const val BIND_ALL   = 0
        const val BIND_LOCAL = 1

        // Ports below 1024 need root on Android.
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MIN_TOKEN_LENGTH = 8
        const val DEF_TIMEOUT_SECONDS = 15

        val BODY_LIMITS = longArrayOf(64L shl 10, 256L shl 10, 1L shl 20, 4L shl 20, 16L shl 20)
        val BODY_LIMIT_LABELS = listOf("64KB", "256KB", "1MB", "4MB", "16MB")
        const val DEF_BODY_LIMIT = 2

        val EMPTY_TOKEN_HASH = sha256Hex("")

        const val COLOR_TITLE     = "#78DCE8"
        const val COLOR_SUB_TITLE = "#5AA9C4"
        const val COLOR_ICON      = "#78DCE8"

        fun isValidPort(port: Int): Boolean =
            port in MIN_PORT..MAX_PORT
    }
}
