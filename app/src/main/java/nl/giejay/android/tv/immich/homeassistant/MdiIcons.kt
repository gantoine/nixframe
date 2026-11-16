package nl.giejay.android.tv.immich.homeassistant

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import timber.log.Timber

/**
 * Material Design Icons (the icon set Home Assistant uses) drawn from the bundled webfont
 * (assets/mdi, Pictogrammers Free License / Apache 2.0), so "mdi:..." icons configured in HA
 * show up on the frame as-is.
 */
object MdiIcons {
    private var typeface: Typeface? = null
    private var codepoints: Map<String, Int>? = null

    /** Loads the font and name map; cheap after the first call. Safe to call off the main thread. */
    @Synchronized
    fun load(context: Context) {
        if (codepoints != null) return
        try {
            typeface = Typeface.createFromAsset(context.assets, "mdi/materialdesignicons.ttf")
            val map = HashMap<String, Int>(8192)
            context.assets.open("mdi/codepoints.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val space = line.indexOf(' ')
                    if (space > 0) map[line.substring(0, space)] = line.substring(space + 1).toInt(16)
                }
            }
            codepoints = map
        } catch (e: Exception) {
            Timber.e(e, "Could not load Material Design Icons")
            codepoints = emptyMap()
        }
    }

    /** The glyph for an icon name ("lightbulb" or "mdi:lightbulb"), or null if unknown. */
    fun glyph(context: Context, name: String?): String? {
        if (name == null) return null
        load(context)
        val cp = codepoints?.get(name.removePrefix("mdi:").removePrefix("hass:")) ?: return null
        return String(Character.toChars(cp))
    }

    /** A TextView that renders one icon, [sizeDp] square. */
    fun view(context: Context, sizeDp: Float, color: Int): TextView {
        load(context)
        return TextView(context).apply {
            typeface = MdiIcons.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(color)
        }
    }

    fun set(view: TextView, name: String?, fallback: String = "bookmark") {
        view.text = glyph(view.context, name) ?: glyph(view.context, fallback) ?: ""
    }
}
