package dev.mooner.starlightwebserver.widget

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import dev.mooner.configdsl.Icon
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.widget.WidgetScope
import dev.mooner.starlight.plugincore.widget.widget
import dev.mooner.starlight.plugincore.widget.x
import dev.mooner.starlightwebserver.WebServerRuntime
import dev.mooner.starlightwebserver.server.ServerInfo
import dev.mooner.starlightwebserver.server.ServerState
import dev.mooner.starlightwebserver.stats.ServerStats
import dev.mooner.starlightwebserver.utils.copyServerUrl
import dev.mooner.starlightwebserver.utils.tr
import kotlin.time.Duration.Companion.seconds

const val WIDGET_ID = "webserver_status"

private const val SETTING_CHART = "show_chart"
private const val SETTING_RECENT = "show_recent"

val WebServerWidget = widget(WIDGET_ID) {
    name {
        Locale.ENGLISH { "Web server" }
        Locale.KOREAN  { "웹 서버" }
    }
    span(default = 4 x 2, min = 2 x 1, max = 8 x 4)
    settings {
        toggle(SETTING_CHART, tr("Show requests per minute", "분당 요청 그래프 표시"), default = true)
        toggle(SETTING_RECENT, tr("Show recent requests", "최근 요청 표시"), default = true)
    }

    content {
        val view = WebServerWidgetView(this)
        view.bind(liveData())
        view.root.setOnClickListener { copyServerUrl(it.context) }
        ticker(1.seconds) { view.bind(liveData()) }
        repeatWhile(Lifecycle.State.STARTED) { WebServerRuntime.state.collect { view.bind(liveData()) } }
    }

    preview {
        WebServerWidgetView(this).bind(previewData())
    }
}

private class WidgetData(
    val state: ServerState,
    val info: ServerInfo,
    val stats: ServerStats.Snapshot,
    val routes: Int,
)

private fun liveData(): WidgetData =
    WidgetData(
        state = WebServerRuntime.state.value,
        info = WebServerRuntime.serverInfo(),
        stats = WebServerRuntime.stats.snapshot(),
        routes = WebServerRuntime.registry.size,
    )

private fun previewData(): WidgetData {
    val now = System.currentTimeMillis()
    val perMinute = intArrayOf(2, 4, 3, 6, 9, 5, 4, 8, 12, 7, 5, 3, 6, 10, 14, 9, 6, 4, 7, 11, 8, 5, 9, 13, 10, 6, 8, 12, 9, 7)
    return WidgetData(
        state = ServerState.Running("0.0.0.0", WebServerRuntime.DEFAULT_PORT, now),
        info = ServerInfo(true, WebServerRuntime.DEFAULT_PORT, listOf("http://192.168.0.10:${WebServerRuntime.DEFAULT_PORT}")),
        stats = ServerStats.Snapshot(
            total = perMinute.sum().toLong(),
            errors = 1,
            averageMillis = 12,
            recent = listOf(
                ServerStats.Record(now, "GET", "/api/status", 200, 8, null),
                ServerStats.Record(now, "POST", "/webhook", 201, 21, null),
                ServerStats.Record(now, "GET", "/missing", 404, 1, null),
            ),
            perMinute = perMinute,
        ),
        routes = 3,
    )
}

/** Views of one span: a single line when 1 row high, else a panel like StarLight's own widgets. */
private class WebServerWidgetView(scope: WidgetScope) {

    private val context = scope.context
    private val span = scope.span
    private val isCompact = span.y < 2

    private val dot = context.createDot(sizeDp = 10)
    private val title = context.createText(tr("Web server", "웹 서버"), sizeSp = 16f, color = HostColor.Accent)
    private val summary = context.createText(sizeSp = 13f, color = HostColor.TextSub)

    private var address: TextView? = null
    private var requests: TextView? = null
    private var routes: TextView? = null
    private var average: TextView? = null
    private var chart: ActivityBarView? = null
    private val recentRows: MutableList<Pair<TextView, TextView>> = arrayListOf()
    private var lastRecentVersion = -1L

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val horizontal = context.hostDimen("card_radius", 14)
        setPadding(horizontal, if (isCompact) 0 else context.dp(12), horizontal, if (isCompact) 0 else context.dp(12))
        gravity = if (isCompact) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        setRipple()
    }

    init {
        scope.container.removeAllViews()
        scope.container.addView(root, MATCH_PARENT, MATCH_PARENT)

        root.addView(createHeader())
        if (!isCompact)
            root.addView(createContent(scope))
    }

    private fun createHeader(): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(28)
            if (isCompact) {
                addView(dot)
                title.isVisible = span.x >= 3
            } else {
                addView(context.createIcon(Icon.LINK.drawableRes, HostColor.Accent, sizeDp = 20))
            }
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(if (isCompact) 8 else 6) })
            addView(summary, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(8) })
            if (!isCompact)
                addView(dot, LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginStart = context.dp(6) })
        }

    private fun createContent(scope: WidgetScope): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(6) }
        }
        var capacity = rowCapacity()
        val showChart = span.y >= 3 && scope.settings.boolean(SETTING_CHART)
        val showRecent = span.y >= 3 && scope.settings.boolean(SETTING_RECENT)
        if (showChart)
            capacity -= CHART_ROWS

        val infoRows = if (showRecent) capacity.coerceAtMost(2) else capacity.coerceAtMost(4)
        fun row(title: String): TextView =
            context.createRow(title).also { (row, _) -> content.addView(row) }.second
        if (infoRows >= 1) address = row(tr("Address", "주소"))
        if (infoRows >= 2) requests = row(tr("Requests", "요청"))
        if (infoRows >= 3) routes = row(tr("Routes", "라우트"))
        if (infoRows >= 4) average = row(tr("Avg. time", "평균 처리 시간"))

        if (showChart) {
            chart = ActivityBarView(context).also {
                content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(CHART_ROWS * ROW_HEIGHT_DP - 8)).apply {
                    topMargin = context.dp(4)
                    bottomMargin = context.dp(4)
                })
            }
        }
        if (showRecent) {
            repeat((capacity - infoRows).coerceAtLeast(0)) {
                val (row, status) = context.createRow("")
                content.addView(row)
                recentRows += (row.getChildAt(0) as TextView) to status
            }
        }
        return content
    }

    fun bind(data: WidgetData) {
        val (stateText, stateColor) = data.state.describe()
        dot.setDotColor(context.hostColor(stateColor))
        val hostPort = data.info.urls.firstOrNull()?.removePrefix("http://")
        summary.text = when {
            !isCompact || hostPort == null -> stateText
            span.x >= 4                    -> hostPort
            else                           -> ":${data.info.port}"
        }
        summary.setTextColor(context.hostColor(if (isCompact && hostPort != null) HostColor.Text else stateColor))

        address?.text = hostPort ?: ":${data.info.port}"
        requests?.text = if (data.stats.errors > 0)
            tr("${data.stats.total} (${data.stats.errors} errors)", "${data.stats.total} (오류 ${data.stats.errors})")
        else
            data.stats.total.toString()
        routes?.text = data.routes.toString()
        average?.text = "${data.stats.averageMillis}ms"
        chart?.setValues(data.stats.perMinute)
        bindRecent(data.stats)
    }

    private fun bindRecent(stats: ServerStats.Snapshot) {
        if (recentRows.isEmpty() || stats.total == lastRecentVersion)
            return
        lastRecentVersion = stats.total
        for ((i, row) in recentRows.withIndex()) {
            val (request, status) = row
            val record = stats.recent.getOrNull(i)
            when {
                record != null -> {
                    request.text = "${record.method} ${record.path}"
                    status.text = "${record.status} · ${record.durationMillis}ms"
                    status.setTextColor(context.hostColor(record.statusColor()))
                }
                i == 0 -> {
                    request.text = tr("No requests yet", "아직 요청이 없어요")
                    status.text = ""
                }
                else -> {
                    request.text = ""
                    status.text = ""
                }
            }
        }
    }

    /** Rows of [ROW_HEIGHT_DP] that fit below the header. */
    private fun rowCapacity(): Int {
        val cellHeight = context.hostDimen("widget_cell_height", 54)
        val gap = context.hostDimen("widget_spacing", 10)
        val contentHeight = span.y * cellHeight + (span.y - 1) * gap - context.dp(PANEL_CHROME_DP)
        return (contentHeight / context.dp(ROW_HEIGHT_DP)).coerceAtLeast(1)
    }

    private fun ServerState.describe(): Pair<String, HostColor> =
        when (this) {
            is ServerState.Running -> tr("Running", "작동 중") to HostColor.Ok
            ServerState.Starting   -> tr("Starting", "시작 중") to HostColor.Warning
            ServerState.Stopped    -> tr("Stopped", "중지됨") to HostColor.TextSub
            is ServerState.Failed  -> tr("Error", "오류") to HostColor.Error
        }

    private fun ServerStats.Record.statusColor(): HostColor =
        when {
            status >= 500 -> HostColor.Error
            status >= 400 -> HostColor.Warning
            else          -> HostColor.Ok
        }

    private companion object {
        const val CHART_ROWS = 2
        // Vertical padding, header and the gap below it.
        const val PANEL_CHROME_DP = 58
    }
}
