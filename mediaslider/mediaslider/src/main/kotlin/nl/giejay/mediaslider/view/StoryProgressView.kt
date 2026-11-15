package nl.giejay.mediaslider.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max

/**
 * Immich Memories–style segmented progress strip drawn on top of the media.
 * Completed segments are full, the active segment fills [currentProgress] (0–1),
 * upcoming segments stay dim.
 */
class StoryProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var segmentCount = 0
    private var currentIndex = 0
    private var currentProgress = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val gapPx = dp(3f)
    private val barHeightPx = dp(3f)
    private val radiusPx = dp(1.5f)

    fun setSegmentCount(count: Int) {
        segmentCount = count.coerceAtLeast(0)
        currentIndex = currentIndex.coerceIn(0, (segmentCount - 1).coerceAtLeast(0))
        invalidate()
    }

    /** [index] is the active slide; [progress] is 0–1 within that slide. */
    fun setProgress(index: Int, progress: Float) {
        if (segmentCount <= 0) return
        currentIndex = index.coerceIn(0, segmentCount - 1)
        currentProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segmentCount <= 0) return

        val contentW = (width - paddingLeft - paddingRight).toFloat()
        val gaps = gapPx * (segmentCount - 1)
        val segW = max(1f, (contentW - gaps) / segmentCount)
        val y = paddingTop + (height - paddingTop - paddingBottom - barHeightPx) / 2f
        var x = paddingLeft.toFloat()

        // RectF overload: the float-coordinate drawRoundRect is API 21+ (KitKat frames are API 19).
        for (i in 0 until segmentCount) {
            rect.set(x, y, x + segW, y + barHeightPx)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, trackPaint)
            val fillFraction = when {
                i < currentIndex -> 1f
                i == currentIndex -> currentProgress
                else -> 0f
            }
            if (fillFraction > 0f) {
                rect.set(x, y, x + segW * fillFraction, y + barHeightPx)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
            }
            x += segW + gapPx
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
