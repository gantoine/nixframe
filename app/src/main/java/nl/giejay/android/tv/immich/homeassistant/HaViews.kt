package nl.giejay.android.tv.immich.homeassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Semi-circular gauge (HA's gauge card shape): a faint track with a coloured arc up to [fraction]. */
class GaugeArcView(context: Context) : View(context) {
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var color: Int = HaFormat.COLOR_INACTIVE
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 12 * density
        color = 0x26FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 12 * density
    }
    private val oval = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w / 2 + track.strokeWidth).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = track.strokeWidth
        val size = min(width.toFloat(), (height - stroke) * 2) - stroke
        val left = (width - size) / 2
        oval.set(left, stroke / 2, left + size, stroke / 2 + size)
        canvas.drawArc(oval, 180f, 180f, false, track)
        if (fraction > 0f) {
            arc.color = color
            canvas.drawArc(oval, 180f, max(1f, 180f * fraction), false, arc)
        }
    }
}

/** Ring (light card): track plus an arc from the top, clockwise, for [fraction]. */
class RingView(context: Context) : View(context) {
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var color: Int = HaFormat.COLOR_INACTIVE
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9 * density
        color = 0x26FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 9 * density
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height) - track.strokeWidth
        val left = (width - size) / 2
        val top = (height - size) / 2
        oval.set(left, top, left + size, top + size)
        canvas.drawOval(oval, track)
        if (fraction > 0f) {
            arc.color = color
            canvas.drawArc(oval, -90f, 360f * fraction, false, arc)
        }
    }
}

/**
 * Line chart for history (sensor graph, history-graph): one line per series over a shared time
 * window, the first series with a soft fill. Points are (epochSeconds, value).
 */
class SparklineView(context: Context) : View(context) {
    class Series(val points: List<Pair<Double, Double>>, val color: Int)

    private var series: List<Series> = emptyList()
    private var start = 0.0
    private var end = 1.0
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun setData(series: List<Series>, start: Double, end: Double) {
        this.series = series.filter { it.points.isNotEmpty() }
        this.start = start
        this.end = max(end, start + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (series.isEmpty() || width == 0) return
        val values = series.flatMap { s -> s.points.map { it.second } }
        var lo = values.minOrNull() ?: return
        var hi = values.maxOrNull() ?: return
        if (hi - lo < 1e-6) {
            lo -= 1
            hi += 1
        }
        val pad = 3 * density
        val h = height - 2 * pad
        fun x(t: Double) = ((t - start) / (end - start) * width).toFloat().coerceIn(0f, width.toFloat())
        fun y(v: Double) = (pad + h - (v - lo) / (hi - lo) * h).toFloat()

        series.forEachIndexed { index, s ->
            path.reset()
            // step-wise like HA: hold each value until the next change, and to "now"
            var lastY = y(s.points.first().second)
            path.moveTo(x(s.points.first().first), lastY)
            for (i in 1 until s.points.size) {
                val (t, v) = s.points[i]
                path.lineTo(x(t), lastY)
                lastY = y(v)
                path.lineTo(x(t), lastY)
            }
            path.lineTo(width.toFloat(), lastY)
            if (index == 0) {
                val area = Path(path)
                area.lineTo(width.toFloat(), height.toFloat())
                area.lineTo(x(s.points.first().first), height.toFloat())
                area.close()
                fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                    (s.color and 0x00FFFFFF) or 0x55000000, s.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
                canvas.drawPath(area, fill)
            }
            line.color = s.color
            canvas.drawPath(path, line)
        }
    }
}
