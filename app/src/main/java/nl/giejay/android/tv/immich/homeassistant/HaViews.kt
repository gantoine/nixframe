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

/** Lays children out left to right, wrapping onto new lines (Mushroom chips, badges). */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : android.view.ViewGroup(context) {
    var gravityEnd = false
    var gravityCenter = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: Int.MAX_VALUE
        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            x += child.measuredWidth + hGap
            widest = max(widest, x - hGap)
            lineHeight = max(lineHeight, child.measuredHeight)
        }
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) maxWidth else min(widest, maxWidth)
        setMeasuredDimension(width, y + lineHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        // group children into lines, then place each line according to the gravity
        val lines = mutableListOf<MutableList<View>>(mutableListOf())
        var x = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > width) {
                lines += mutableListOf<View>()
                x = 0
            }
            lines.last() += child
            x += child.measuredWidth + hGap
        }
        var y = 0
        for (line in lines) {
            val lineWidth = line.sumOf { it.measuredWidth } + hGap * (line.size - 1).coerceAtLeast(0)
            var cx = when {
                gravityEnd -> width - lineWidth
                gravityCenter -> (width - lineWidth) / 2
                else -> 0
            }
            val lineHeight = line.maxOfOrNull { it.measuredHeight } ?: 0
            for (child in line) {
                val top = y + (lineHeight - child.measuredHeight) / 2
                child.layout(cx, top, cx + child.measuredWidth, top + child.measuredHeight)
                cx += child.measuredWidth + hGap
            }
            y += lineHeight + vGap
        }
    }
}

/** Bubble Card's pill: a rounded track with an optional fill (slider brightness, "on" switches). */
class BubblePill(context: Context) : android.widget.FrameLayout(context) {
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var fillColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var baseColor: Int = 0x1FFFFFFF
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x17FFFFFF
    }
    private val rect = RectF()
    private val clip = Path()

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = baseColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (fraction > 0f && fillColor != 0) {
            canvas.save()
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            paint.color = fillColor
            canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
            canvas.restore()
        }
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }
}
