package nl.giejay.android.tv.immich.ambient

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import nl.giejay.android.tv.immich.ambient.ui.Ui
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.DIM
import nl.giejay.android.tv.immich.ambient.ui.Weight
import nl.giejay.android.tv.immich.homeassistant.Notice
import nl.giejay.android.tv.immich.homeassistant.Outside
import nl.giejay.android.tv.immich.homeassistant.RoomRow
import nl.giejay.android.tv.immich.homeassistant.SafetyLine

/**
 * Screen 2 (Glance.dc.html): "glance at the house" over the dimmed photo. Header with the time
 * and status checks, the notifications, then Outside (now, today's high/low, 4 days), Front door
 * and Inside (thermostat + rooms). No lights or media.
 */
@SuppressLint("ViewConstructor") // built in code only, never inflated
class GlanceView(private val ui: Ui) : FrameLayout(ui.context) {

    private val time = ui.text(76f, tracking = -2f, tight = true)
    private val date = ui.text(21f, Weight.MEDIUM, tracking = 3.5f, caps = true)
    private val statuses = ui.horizontal()
    private val notices = ui.vertical()

    private val outsideSection = ui.vertical()
    private val outsideIcon = android.widget.ImageView(ui.context)
    private val outsideTemp = ui.text(116f, tracking = -3f, tight = true)
    private val high = ui.text(20f, Weight.MEDIUM, tracking = 2.5f, caps = true)
    private val low = ui.text(20f, Weight.MEDIUM, DIM, tracking = 2.5f, caps = true)
    private val forecast = ui.horizontal(Gravity.TOP)
    private val forecastBlock = ui.vertical()

    private val doorSection = ui.vertical()
    private val doorRows = ui.vertical()
    private val rooms = ui.vertical()
    private val footer = ui.text(16f, Weight.MEDIUM, DIM, tracking = 3f, caps = true)

    private var shownStatuses: List<SafetyLine> = emptyList()
    private var shownNotices: List<Notice> = emptyList()
    private var shownRooms: List<RoomRow> = emptyList()
    private var shownDoor: List<Pair<String, String>> = emptyList()

    init {
        setBackgroundColor(Ui.SCRIM)
        val page = ui.vertical().apply { setPadding(ui.px(40), ui.px(38), ui.px(40), ui.px(26)) }

        // header: time + date on the left, status checks bottom-right
        val header = ui.horizontal(Gravity.BOTTOM)
        val clock = ui.vertical()
        clock.addView(time, ui.wrap())
        clock.addView(date, ui.wrap().apply { topMargin = ui.px(14) })
        header.addView(clock, ui.weighted())
        header.addView(statuses, ui.wrap())
        page.addView(header, ui.matchWrap())

        page.addView(notices, ui.matchWrap().apply { topMargin = ui.px(28) })

        // two columns: 380px | 64px gap | rest
        val grid = ui.horizontal(Gravity.TOP)
        val left = ui.vertical()

        outsideSection.addView(heading("Outside"), ui.wrap())
        val now = ui.horizontal()
        now.addView(outsideIcon, ui.square(84f))
        now.addView(outsideTemp, ui.wrap().apply { leftMargin = ui.px(18) })
        val hl = ui.vertical()
        hl.addView(high, ui.wrap())
        hl.addView(low, ui.wrap().apply { topMargin = ui.px(6) })
        now.addView(hl, ui.wrap().apply { leftMargin = ui.px(26) })
        outsideSection.addView(now, ui.wrap().apply { topMargin = ui.px(14) })
        forecastBlock.addView(ui.hairline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        forecastBlock.addView(forecast, ui.matchWrap().apply { topMargin = ui.px(16) })
        outsideSection.addView(forecastBlock, ui.matchWrap().apply { topMargin = ui.px(20) })
        left.addView(outsideSection, ui.matchWrap())

        doorSection.addView(heading("Front door"), ui.wrap())
        doorSection.addView(doorRows, ui.matchWrap().apply { topMargin = ui.px(12) })
        left.addView(doorSection, ui.matchWrap().apply { topMargin = ui.px(28) })
        grid.addView(left, LinearLayout.LayoutParams(ui.px(380), ViewGroup.LayoutParams.WRAP_CONTENT))

        val right = ui.vertical()
        right.addView(heading("Inside"), ui.wrap())
        right.addView(rooms, ui.matchWrap().apply { topMargin = ui.px(14) })
        grid.addView(right, ui.weighted().apply { leftMargin = ui.px(64) })
        page.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.px(34) })

        page.addView(footer, ui.wrap().apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun heading(text: String) = ui.text(17f, Weight.MEDIUM, DIM, tracking = 3.5f, caps = true).apply { this.text = text }

    fun bindClock(timeText: String, dateText: String) {
        time.text = timeText
        date.text = dateText
    }

    fun bindFooter(text: String) {
        footer.text = text
    }

    fun bind(statusLines: List<SafetyLine>, items: List<Notice>, outside: Outside?, door: List<Pair<String, String>>, roomRows: List<RoomRow>) {
        if (statusLines != shownStatuses) {
            shownStatuses = statusLines
            statuses.removeAllViews()
            statusLines.forEachIndexed { i, line ->
                val item = ui.horizontal()
                item.addView(ui.icon(if (line.ok) "check" else "alert-outline", 20f), ui.square(20f))
                item.addView(ui.text(17f, Weight.MEDIUM, tracking = 2.5f, caps = true).apply { text = line.label },
                    ui.wrap().apply { leftMargin = ui.px(8) })
                statuses.addView(item, ui.wrap().apply { if (i > 0) leftMargin = ui.px(24) })
            }
        }
        if (items != shownNotices) {
            shownNotices = items
            notices.removeAllViews()
            items.take(PhotoOverlay.MAX_NOTICES).forEachIndexed { i, n ->
                notices.addView(PhotoOverlay.noticeRow(ui, n, shadow = false), ui.wrap().apply { if (i > 0) topMargin = ui.px(8) })
            }
            notices.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }

        outsideSection.visibility = if (outside == null) View.GONE else View.VISIBLE
        outside?.let { o ->
            outsideIcon.setImageResource(nl.giejay.android.tv.immich.ambient.ui.WeatherArt.drawable(o.icon))
            outsideTemp.text = o.temperature
            high.text = o.high?.let { "H $it" }.orEmpty()
            low.text = o.low?.let { "L $it" }.orEmpty()
            forecast.removeAllViews()
            o.days.forEach { d ->
                val col = ui.vertical()
                col.addView(ui.text(16f, Weight.MEDIUM, DIM, tracking = 3f, caps = true).apply { text = d.day }, ui.wrap())
                col.addView(ui.text(32f).apply { text = d.high }, ui.wrap().apply { topMargin = ui.px(2) })
                d.low?.let { col.addView(ui.text(20f, color = DIM).apply { text = it }, ui.wrap().apply { topMargin = ui.px(2) }) }
                forecast.addView(col, ui.weighted().apply { rightMargin = ui.px(8) })
            }
            forecastBlock.visibility = if (o.days.isEmpty()) View.GONE else View.VISIBLE
        }

        if (door != shownDoor) {
            shownDoor = door
            doorRows.removeAllViews()
            door.forEach { (label, ago) ->
                val row = ui.horizontal()
                row.addView(ui.text(23f).apply { text = label }, ui.weighted())
                row.addView(ui.text(20f, color = DIM).apply { text = ago }, ui.wrap())
                doorRows.addView(ui.ruled(row, 52f), ui.matchWrap())
            }
        }
        doorSection.visibility = if (door.isEmpty()) View.GONE else View.VISIBLE

        if (roomRows != shownRooms) {
            shownRooms = roomRows
            rooms.removeAllViews()
            roomRows.forEach { r ->
                val row = ui.horizontal()
                row.addView(ui.text(25f).apply { text = r.name }, ui.weighted())
                row.addView(ui.text(19f, color = DIM).apply { text = r.detail }, ui.wrap().apply { leftMargin = ui.px(16) })
                row.addView(ui.text(38f, tracking = -0.5f).apply { text = r.temperature; alignEnd = true },
                    LinearLayout.LayoutParams(ui.px(108), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(16) })
                rooms.addView(ui.ruled(row, 72f), ui.matchWrap())
            }
        }
    }
}
