package nl.giejay.android.tv.immich.ambient

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import nl.giejay.android.tv.immich.ambient.ui.Ui
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.WHITE
import nl.giejay.android.tv.immich.ambient.ui.Weight
import nl.giejay.android.tv.immich.homeassistant.Notice

/**
 * Screen 1 (Main.dc.html): the status drawn over the full-bleed photo. Only the time, the date,
 * the outdoor temperature with a condition icon, and the indoor temperature. Notifications
 * are deliberately not shown here; they live on the glance screen. Everything is white over a
 * dark gradient; nothing is boxed.
 *
 * Down swaps the time and date for the photo's own date, place and people for a while.
 */
@SuppressLint("ViewConstructor") // built in code only, never inflated
class PhotoOverlay(private val ui: Ui) : FrameLayout(ui.context) {

    private val strip = ui.frame()
    private val time = ui.text(136f, tracking = -3f, tight = true, shadow = true)
    private val date = ui.text(21f, Weight.MEDIUM, tracking = 3.5f, caps = true, shadow = true)
    private val clockColumn = ui.vertical()
    private val weatherIcon = android.widget.ImageView(ui.context)
    // Below zero the sign is a short hyphen in its own view, raised to the middle of the digits:
    // the typographic minus (U+2212) is as wide as a digit and looks too long at this size.
    private val sign = ui.text(88f, tight = true, shadow = true).apply {
        text = "-"
        translationY = -ui.px(7).toFloat()
        visibility = View.GONE
    }
    private val outdoor = ui.text(88f, tracking = -2f, tight = true, shadow = true)
    private val degree = ui.text(88f, tight = true, shadow = true).apply { text = "°" }
    private val weatherRow = ui.horizontal()
    private val presence = ui.text(21f, Weight.MEDIUM, tracking = 3.5f, caps = true, shadow = true).apply { alignEnd = true }

    private val infoColumn = ui.vertical()
    private val infoDate = ui.text(56f, tracking = -1f, shadow = true)
    private val infoPlace = ui.text(21f, Weight.MEDIUM, tracking = 3.5f, caps = true, shadow = true)
    private val infoPeople = ui.text(21f, Weight.MEDIUM, Ui.DIM, tracking = 3.5f, caps = true, shadow = true)

    private val message = ui.text(21f, Weight.MEDIUM, tracking = 3.5f, caps = true, shadow = true)

    init {
        clipChildren = false
        // bottom gradient (400px): transparent -> .50 at the middle -> .88
        strip.addView(ui.gradient(0x00080A0E, 0x80080A0E.toInt(), 0xE0080A0E.toInt()),
            ui.pinned(Gravity.BOTTOM, width = ViewGroup.LayoutParams.MATCH_PARENT, height = ui.px(400)))

        clockColumn.addView(time, ui.wrap())
        clockColumn.addView(date, ui.wrap().apply { topMargin = ui.px(14) })
        strip.addView(clockColumn, ui.pinned(Gravity.BOTTOM or Gravity.START, left = 40f, bottom = 34f))

        infoColumn.addView(infoDate, ui.wrap())
        infoColumn.addView(infoPlace, ui.wrap().apply { topMargin = ui.px(14) })
        infoColumn.addView(infoPeople, ui.wrap().apply { topMargin = ui.px(8) })
        infoColumn.visibility = View.GONE
        strip.addView(infoColumn, ui.pinned(Gravity.BOTTOM or Gravity.START, left = 40f, bottom = 34f, right = 420f))

        val right = ui.vertical().apply { gravity = Gravity.END }
        // The condition picture, then the temperature; the degree sign ends at the 40px right margin.
        weatherRow.addView(weatherIcon, ui.square(84f).apply { rightMargin = ui.px(18) })
        weatherRow.addView(sign, ui.wrap().apply { rightMargin = ui.px(2) })
        weatherRow.addView(outdoor, ui.wrap())
        weatherRow.addView(degree, ui.wrap())
        right.addView(weatherRow, ui.wrap().apply { gravity = Gravity.END })
        right.addView(presence, ui.wrap().apply { topMargin = ui.px(14); gravity = Gravity.END })
        strip.addView(right, ui.pinned(Gravity.BOTTOM or Gravity.END, right = 40f, bottom = 34f))

        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        message.visibility = View.GONE
        addView(message, ui.pinned(Gravity.CENTER))
    }

    var stripVisible: Boolean
        get() = strip.visibility == View.VISIBLE
        set(value) { strip.visibility = if (value) View.VISIBLE else View.GONE }

    fun bindClock(timeText: String, dateText: String) {
        time.text = timeText
        date.text = dateText
    }

    /** [outside] = (MDI icon, "57°"); [presenceLine] = "67.5° inside". */
    fun bindHouse(outside: Pair<String, String>?, presenceLine: String?) {
        weatherRow.visibility = if (outside == null) View.GONE else View.VISIBLE
        outside?.let { (icon, temp) ->
            weatherIcon.setImageResource(nl.giejay.android.tv.immich.ambient.ui.WeatherArt.drawable(icon))
            val number = temp.removeSuffix("°")
            val negative = number.startsWith("−") || number.startsWith("-")
            sign.visibility = if (negative) View.VISIBLE else View.GONE
            outdoor.text = if (negative) number.substring(1) else number
        }
        presence.text = presenceLine.orEmpty()
        presence.visibility = if (presenceLine.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    val infoShowing: Boolean get() = infoColumn.visibility == View.VISIBLE

    fun showInfo(dateText: String?, place: String?, people: String?) {
        infoDate.text = dateText ?: "Unknown date"
        infoPlace.text = place.orEmpty()
        infoPlace.visibility = if (place.isNullOrBlank()) View.GONE else View.VISIBLE
        infoPeople.text = people.orEmpty()
        infoPeople.visibility = if (people.isNullOrBlank()) View.GONE else View.VISIBLE
        infoColumn.visibility = View.VISIBLE
        clockColumn.visibility = View.INVISIBLE
    }

    fun hideInfo() {
        infoColumn.visibility = View.GONE
        clockColumn.visibility = View.VISIBLE
    }

    /** A centred line when the slideshow has nothing to show (and why). */
    fun showMessage(text: String?) {
        message.text = text.orEmpty()
        message.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        const val MAX_NOTICES = 3

        /** A notification line: a plain ~26px icon and a regular-weight spaced uppercase label. */
        fun noticeRow(ui: Ui, n: Notice, shadow: Boolean): LinearLayout = ui.horizontal().apply {
            addView(ui.icon(n.icon, 26f, WHITE, shadow), ui.square(26f))
            addView(ui.text(20f, tracking = 2.5f, caps = true, shadow = shadow).apply { text = n.label },
                ui.wrap().apply { leftMargin = ui.px(10) })
            minimumHeight = ui.px(28)
        }
    }
}
