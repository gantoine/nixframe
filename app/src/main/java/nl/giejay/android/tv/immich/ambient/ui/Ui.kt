package nl.giejay.android.tv.immich.ambient.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import nl.giejay.android.tv.immich.homeassistant.MdiIcons
import timber.log.Timber
import kotlin.math.roundToInt

/** Jost (SIL OFL, bundled in assets/fonts): Regular for numerals and body, Medium for labels. */
object AmbientFonts {
    @Volatile private var regular: Typeface? = null
    @Volatile private var medium: Typeface? = null

    fun regular(context: Context): Typeface = regular ?: load(context, "fonts/Jost-Regular.ttf").also { regular = it }
    fun medium(context: Context): Typeface = medium ?: load(context, "fonts/Jost-Medium.ttf").also { medium = it }

    private fun load(context: Context, path: String): Typeface = try {
        Typeface.createFromAsset(context.applicationContext.assets, path)
    } catch (e: RuntimeException) {
        Timber.e(e, "Could not load %s", path)
        Typeface.DEFAULT
    }
}

enum class Weight { REGULAR, MEDIUM }

/**
 * Builds the ambient screens in the mockups' units: the designs are 1024×768 pixels, so every
 * size is given in design pixels and scaled by [u] = screen width / 1024. On the frame u is 1.
 */
class Ui(val context: Context, val u: Float) {

    fun px(v: Float): Int = (v * u).roundToInt()
    fun px(v: Int): Int = px(v.toFloat())

    fun text(
        size: Float,
        weight: Weight = Weight.REGULAR,
        color: Int = WHITE,
        tracking: Float = 0f,
        caps: Boolean = false,
        tight: Boolean = false,
        shadow: Boolean = false
    ): AmbientText = AmbientText(context).style(
        if (weight == Weight.MEDIUM) AmbientFonts.medium(context) else AmbientFonts.regular(context),
        size * u, color, tracking * u, caps, tight, if (shadow) 6f * u else 0f
    )

    /** An MDI glyph, [size] design pixels square, plain white (no circle or box behind it). */
    fun icon(name: String?, size: Float, color: Int = WHITE, shadow: Boolean = false, fallback: String = "alert-circle-outline"): TextView =
        TextView(context).apply {
            typeface = MdiIcons.typeface(context)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(color)
            if (shadow) setShadowLayer(4f * u, 0f, 1f * u, 0x73000000)
            MdiIcons.set(this, name, fallback)
        }

    fun setIcon(view: TextView, name: String?, fallback: String = "alert-circle-outline") = MdiIcons.set(view, name, fallback)

    /** A 1px rule in 20% white: the only lines the design uses (row separators). */
    fun hairline(): View = View(context).apply { setBackgroundColor(HAIRLINE) }

    /** A vertical gradient (scrim), top to bottom through [colors]. */
    fun gradient(vararg colors: Int): View = View(context).apply {
        @Suppress("DEPRECATION")
        setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors))
    }

    fun vertical(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    fun horizontal(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        clipChildren = false
        clipToPadding = false
    }

    fun frame(): FrameLayout = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }

    fun wrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun square(size: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(px(size), px(size))
    fun weighted(weight: Float = 1f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, height, weight)

    /** A FrameLayout child pinned by [gravity] with margins in design pixels. */
    fun pinned(gravity: Int, left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f,
               width: Int = ViewGroup.LayoutParams.WRAP_CONTENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        FrameLayout.LayoutParams(width, height, gravity).apply {
            leftMargin = px(left); topMargin = px(top); rightMargin = px(right); bottomMargin = px(bottom)
        }

    fun LinearLayout.LayoutParams.margins(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f) = apply {
        leftMargin = px(left); topMargin = px(top); rightMargin = px(right); bottomMargin = px(bottom)
    }

    /** A 1px hairline above [content], as the mockups' border-top rows. */
    fun ruled(content: View, height: Float): LinearLayout = vertical().apply {
        addView(hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(height) - 1))
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        /** rgba(255,255,255,.66): secondary text. */
        const val DIM = 0xA8FFFFFF.toInt()
        /** rgba(255,255,255,.72): unfocused source names. */
        const val SOFT = 0xB8FFFFFF.toInt()
        const val HAIRLINE = 0x33FFFFFF
        /** rgba(8,10,14,.84): the scrim behind the glance, doorbell and sources screens. */
        const val SCRIM = 0xD6080A0E.toInt()
        /** The one colour in the design: the camera's live dot. */
        const val LIVE_RED = 0xFFFF6B5E.toInt()
    }
}
