package nl.giejay.android.tv.immich.ambient.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One line of text drawn with its own letter spacing ("tracking").
 *
 * The design sets labels in wide-spaced capitals and big numerals with negative spacing, but
 * TextView.setLetterSpacing is API 21+ and the frame runs API 19. Rather than approximate it with
 * inserted hair spaces (whose width depends on the font), every styled line on the ambient
 * screens is drawn by this view, code point by code point, advancing by the glyph's width plus
 * [tracking] pixels. Kerning is lost, which is invisible in spaced capitals and tabular numerals.
 *
 * Too-long text ends in an ellipsis. With [tight], the view is exactly as tall as a capital/digit
 * and the baseline is its bottom edge, which is how the mockups' line-height: 0.8 numerals line
 * up with the labels under them.
 */
class AmbientText(context: Context) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bounds = Rect()

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var caps = false
    var tracking = 0f
    var tight = false
    /** Right-align the text when the view is wider than it. */
    var alignEnd = false

    /** [shadowRadius] > 0 adds the design's soft text shadow (hardware-accelerated text shadows work on API 19). */
    fun style(typeface: Typeface?, sizePx: Float, color: Int, tracking: Float = 0f, caps: Boolean = false, tight: Boolean = false, shadowRadius: Float = 0f): AmbientText {
        paint.typeface = typeface
        paint.textSize = sizePx
        paint.color = color
        this.tracking = tracking
        this.caps = caps
        this.tight = tight
        if (shadowRadius > 0f) paint.setShadowLayer(shadowRadius, 0f, shadowRadius / 8f, 0x80000000.toInt()) else paint.clearShadowLayer()
        requestLayout()
        invalidate()
        return this
    }

    var color: Int
        get() = paint.color
        set(value) {
            if (paint.color == value) return
            paint.color = value
            invalidate()
        }

    var typeface: Typeface?
        get() = paint.typeface
        set(value) {
            if (paint.typeface == value) return
            paint.typeface = value
            requestLayout()
            invalidate()
        }

    private fun shown(): String = if (caps) text.uppercase(Locale.US) else text

    /** Width of [s] with tracking between code points (none after the last one). */
    private fun widthOf(s: String): Float {
        if (s.isEmpty()) return 0f
        if (tracking == 0f) return paint.measureText(s)
        var w = 0f
        var i = 0
        var count = 0
        while (i < s.length) {
            val next = s.offsetByCodePoints(i, 1)
            w += paint.measureText(s, i, next)
            count++
            i = next
        }
        return w + tracking * (count - 1)
    }

    private fun capHeight(): Int {
        paint.getTextBounds("0H", 0, 2, bounds)
        return -bounds.top
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val full = ceil(widthOf(shown())).toInt() + paddingLeft + paddingRight
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val w = when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> full.coerceAtMost(size)
            else -> full
        }
        val fm = paint.fontMetrics
        val h = (if (tight) capHeight() else ceil(fm.descent - fm.ascent).toInt()) + paddingTop + paddingBottom
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        var s = shown()
        if (s.isEmpty()) return
        val avail = (width - paddingLeft - paddingRight).toFloat()
        if (widthOf(s) > avail) s = ellipsized(s, avail)
        val drawn = widthOf(s)
        var x = paddingLeft + if (alignEnd) (avail - drawn).coerceAtLeast(0f) else 0f
        val baseline = if (tight) (height - paddingBottom).toFloat() else paddingTop - paint.fontMetrics.ascent
        if (tracking == 0f) {
            canvas.drawText(s, x, baseline, paint)
            return
        }
        var i = 0
        while (i < s.length) {
            val next = s.offsetByCodePoints(i, 1)
            canvas.drawText(s, i, next, x, baseline, paint)
            x += paint.measureText(s, i, next) + tracking
            i = next
        }
    }

    private fun ellipsized(s: String, avail: Float): String {
        var end = s.length
        while (end > 0) {
            end = s.offsetByCodePoints(end, -1)
            val candidate = s.substring(0, end).trimEnd() + "…"
            if (widthOf(candidate) <= avail) return candidate
        }
        return "…"
    }

    /** Distance from the top of this view to its baseline, for lining views up by baseline. */
    val baselinePx: Int
        get() = if (tight) height - paddingBottom else (paddingTop - paint.fontMetrics.ascent).roundToInt()
}
