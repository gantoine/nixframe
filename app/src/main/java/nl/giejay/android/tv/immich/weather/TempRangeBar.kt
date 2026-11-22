package nl.giejay.android.tv.immich.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A forecast day's temperature range drawn as a pill on a track spanning the whole forecast's range,
 * colored by temperature (cold blue → mild green → warm yellow → hot red). Optionally marks the
 * current temperature with a dot (used on today's row). Uses only API 1 Canvas calls (KitKat-safe).
 */
class TempRangeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var rangeMin = 0f
    private var rangeMax = 1f
    private var low = 0f
    private var high = 0f
    private var current: Float? = null
    private var fahrenheit = false

    fun set(rangeMin: Int, rangeMax: Int, low: Int, high: Int, current: Int?, fahrenheit: Boolean) {
        this.rangeMin = rangeMin.toFloat()
        this.rangeMax = maxOf(rangeMax, rangeMin + 1).toFloat()
        this.low = low.toFloat()
        this.high = high.toFloat()
        this.current = current?.toFloat()
        this.fahrenheit = fahrenheit
        invalidate()
    }

    private fun xFor(t: Float, left: Float, right: Float): Float {
        val f = ((t - rangeMin) / (rangeMax - rangeMin)).coerceIn(0f, 1f)
        return left + (right - left) * f
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val thickness = 6f * d
        val r = thickness / 2f
        val cy = height / 2f
        // Inset by the dot radius so the current-temp marker never clips at the ends.
        val dotR = 5f * d
        val left = paddingLeft + dotR
        val right = width - paddingRight - dotR

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x24FFFFFF
        rect.set(left - r, cy - r, right + r, cy + r)
        canvas.drawRoundRect(rect, r, r, paint)

        val x0 = xFor(low, left, right)
        val x1 = xFor(high, left, right)
        // Opaque base color: the paint's alpha also modulates the shader, and the track left it at 0x38.
        paint.color = Color.WHITE
        paint.shader = LinearGradient(x0, 0f, maxOf(x1, x0 + 1f), 0f,
            colorFor(low), colorFor(high), Shader.TileMode.CLAMP)
        rect.set(x0 - r, cy - r, x1 + r, cy + r)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null

        current?.let { c ->
            val x = xFor(c, left, right)
            paint.color = 0x66000000
            canvas.drawCircle(x, cy, dotR + 1.5f * d, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, cy, dotR, paint)
        }
    }

    /** Temperature → color, interpolated across Celsius stops (Fahrenheit converted first). */
    private fun colorFor(t: Float): Int {
        val c = if (fahrenheit) (t - 32f) * 5f / 9f else t
        val i = STOPS_C.indexOfFirst { c <= it }
        if (i <= 0) return if (i == 0) STOP_COLORS.first() else STOP_COLORS.last()
        val f = (c - STOPS_C[i - 1]) / (STOPS_C[i] - STOPS_C[i - 1])
        return lerp(STOP_COLORS[i - 1], STOP_COLORS[i], f)
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )

    companion object {
        private val STOPS_C = floatArrayOf(-15f, -2f, 8f, 16f, 24f, 32f)
        private val STOP_COLORS = intArrayOf(
            0xFF7B8CFF.toInt(), // frigid: periwinkle
            0xFF56C2F5.toInt(), // freezing: ice blue
            0xFF6FD6B4.toInt(), // cool: mint
            0xFFC9DE62.toInt(), // mild: lime
            0xFFFFC245.toInt(), // warm: amber
            0xFFFF6A4D.toInt()  // hot: coral red
        )
    }
}
