package nl.giejay.android.tv.immich.ambient

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import nl.giejay.android.tv.immich.ambient.ui.Ui
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.DIM
import nl.giejay.android.tv.immich.ambient.ui.Weight

/**
 * Screen 3 (Doorbell.dc.html): takes over when the doorbell rings. Title and "rang just now",
 * the clock, the doorbell camera, then who/what was seen and who is home.
 *
 * The camera area shows the camera entity's snapshot (refreshed by the host). A Nest doorbell is
 * WebRTC-only, so Home Assistant answers snapshots with a placeholder; see [cameraImage].
 */
@SuppressLint("ViewConstructor") // built in code only, never inflated
class DoorbellView(private val ui: Ui) : FrameLayout(ui.context) {

    private val time = ui.text(76f, tracking = -2f, tight = true)
    private val title = ui.text(44f, tracking = -0.5f)
    private val rang = ui.text(20f, tracking = 2.5f, caps = true)

    /** The host loads the camera snapshot into this. */
    val cameraImage = ImageView(ui.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val placeholder = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val placeholderText = ui.text(18f, Weight.MEDIUM, DIM, tracking = 3f, caps = true)
    private val caption = ui.horizontal()
    private val captionText = ui.text(18f, Weight.MEDIUM, tracking = 3f, caps = true, shadow = true)

    private val seenIcon = ui.icon("walk", 32f)
    private val seenTitle = ui.text(18f, Weight.MEDIUM, tracking = 2.5f, caps = true)
    private val seenWhen = ui.text(19f, color = DIM)
    private val seenColumn = ui.horizontal()
    private val homeTitle = ui.text(18f, Weight.MEDIUM, tracking = 2.5f, caps = true)
    private val homeNames = ui.text(19f, color = DIM)
    private val homeColumn = ui.horizontal()

    init {
        setBackgroundColor(Ui.SCRIM)
        val page = ui.vertical().apply { setPadding(ui.px(40), ui.px(38), ui.px(40), ui.px(26)) }

        val header = ui.horizontal(Gravity.BOTTOM)
        val left = ui.vertical()
        title.text = "Someone's at the front door"
        left.addView(title, ui.wrap())
        val rangRow = ui.horizontal()
        rangRow.addView(ui.icon("bell-outline", 26f), ui.square(26f))
        rangRow.addView(rang, ui.wrap().apply { leftMargin = ui.px(10) })
        left.addView(rangRow, ui.wrap().apply { topMargin = ui.px(12) })
        header.addView(left, ui.weighted())
        header.addView(time, ui.wrap())
        page.addView(header, ui.matchWrap())

        val figure = ui.frame().apply { setBackgroundColor(0xFF171B22.toInt()) }
        placeholder.addView(ui.icon("video-outline", 72f, DIM), ui.square(72f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        placeholder.addView(placeholderText, ui.wrap().apply { topMargin = ui.px(14); gravity = Gravity.CENTER_HORIZONTAL })
        figure.addView(placeholder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        figure.addView(cameraImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // the only colour in the design: the live dot
        caption.addView(View(ui.context).apply {
            @Suppress("DEPRECATION")
            setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.LIVE_RED) })
        }, ui.square(12f))
        caption.addView(captionText, ui.wrap().apply { leftMargin = ui.px(10) })
        figure.addView(caption, ui.pinned(Gravity.TOP or Gravity.START, left = 22f, top = 20f))
        page.addView(figure, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.px(24) })

        val info = ui.horizontal()
        info.addView(column(seenColumn, seenIcon, seenTitle, seenWhen), ui.weighted())
        info.addView(column(homeColumn, ui.icon("account-multiple-outline", 32f), homeTitle, homeNames), ui.weighted().apply { leftMargin = ui.px(24) })
        // the mockup's third column ("Living room paused / Chime played on both Sonos") has no data source
        info.addView(View(ui.context), ui.weighted().apply { leftMargin = ui.px(24) })
        page.addView(info, ui.matchWrap().apply { topMargin = ui.px(24) })

        page.addView(ui.text(16f, Weight.MEDIUM, DIM, tracking = 3f, caps = true).apply {
            text = "Back to photos 2 min after the last motion, or press Back"
        }, ui.wrap().apply { topMargin = ui.px(24); gravity = Gravity.CENTER_HORIZONTAL })
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showSnapshot(false, null)
    }

    private fun column(row: LinearLayout, icon: View, top: View, bottom: View): LinearLayout {
        row.addView(icon, ui.square(32f))
        val texts = ui.vertical()
        texts.addView(top, ui.wrap())
        texts.addView(bottom, ui.wrap().apply { topMargin = ui.px(2) })
        row.addView(texts, ui.weighted().apply { leftMargin = ui.px(14) })
        return row
    }

    fun bindClock(timeText: String) {
        time.text = timeText
    }

    /** [rangText] e.g. "Rang just now"; [seen] = ("Person seen", "1 min ago"); [home] = ("Both home", names). */
    fun bind(rangText: String, cameraName: String?, seen: Pair<String, String>?, home: Pair<String, String>?) {
        rang.text = rangText
        captionText.text = listOfNotNull("Live", cameraName).joinToString(" · ")
        seenColumn.visibility = if (seen == null) View.INVISIBLE else View.VISIBLE
        seen?.let { (what, ago) ->
            seenTitle.text = what
            seenWhen.text = ago
            ui.setIcon(seenIcon, if (what.startsWith("Person")) "walk" else "motion-sensor")
        }
        homeColumn.visibility = if (home == null) View.INVISIBLE else View.VISIBLE
        home?.let { (label, names) ->
            homeTitle.text = label
            homeNames.text = names
        }
    }

    /** Shows the snapshot once one has loaded, else an icon and why there is no picture. */
    fun showSnapshot(loaded: Boolean, whyNot: String?) {
        cameraImage.visibility = if (loaded) View.VISIBLE else View.INVISIBLE
        caption.visibility = if (loaded) View.VISIBLE else View.GONE
        placeholder.visibility = if (loaded) View.GONE else View.VISIBLE
        placeholderText.text = whyNot ?: "No camera picture"
    }
}
