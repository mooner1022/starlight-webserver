package dev.mooner.starlightwebserver.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Bars of requests per minute, oldest on the left. */
internal class ActivityBarView(context: Context) : View(context) {

    private var values: IntArray = IntArray(0)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.hostColor(HostColor.Accent)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.hostColor(HostColor.TextSub)
        alpha = 60
    }
    private val rect = RectF()
    private val gap = context.dp(2).toFloat()
    private val minBarHeight = context.dp(2).toFloat()
    private val radius = context.dp(2).toFloat()

    fun setValues(values: IntArray) {
        if (values.contentEquals(this.values))
            return
        this.values = values.copyOf()
        contentDescription = "${values.sum()} requests in ${values.size} minutes"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty())
            return
        val max = values.max().coerceAtLeast(1)
        val barWidth = (width - gap * (values.size - 1)) / values.size
        for ((i, value) in values.withIndex()) {
            val left = i * (barWidth + gap)
            val barHeight = if (value == 0) minBarHeight else (height * value / max.toFloat()).coerceAtLeast(minBarHeight)
            rect.set(left, height - barHeight, left + barWidth, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, if (value == 0) emptyPaint else barPaint)
        }
    }
}
