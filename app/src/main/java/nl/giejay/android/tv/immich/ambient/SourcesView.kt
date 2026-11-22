package nl.giejay.android.tv.immich.ambient

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import nl.giejay.android.tv.immich.ambient.ui.AmbientFonts
import nl.giejay.android.tv.immich.ambient.ui.AmbientText
import nl.giejay.android.tv.immich.ambient.ui.Ui
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.DIM
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.SOFT
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.WHITE
import nl.giejay.android.tv.immich.ambient.ui.Weight
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_ALBUMS
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_PEOPLE
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_PINNED_ASSET
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_SHUFFLE
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_SOURCE
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_STATUS_STRIP
import nl.giejay.android.tv.immich.shared.prefs.GALLERY_QUERY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.StringSetPref

/** Something that can be picked for the slideshow: an album or a person. */
data class Pickable(val id: String, val name: String)

/**
 * Screen 4 (Sources.dc.html): "What to show". Sources on the left, the picked source's albums or
 * people and the slideshow settings on the right. Focus is a white dot plus brighter, bolder text;
 * nothing is filled. All keys arrive through [onKey] from the ambient host.
 */
@SuppressLint("ViewConstructor") // built in code only, never inflated
class SourcesView(private val ui: Ui, private val host: Host) : FrameLayout(ui.context) {

    interface Host {
        /** Loads albums/people; [done] gets null on failure. Called on the main thread. */
        fun loadPicks(source: PhotoSource, done: (List<Pickable>?) -> Unit)
        /** The Immich asset on screen right now (for "One photo"), or null. */
        fun currentAssetId(): String?
        fun openMoreSettings()
        fun close()
        fun onStatusStripChanged()
    }

    private val summary = ui.text(17f, Weight.MEDIUM, DIM, tracking = 3f, caps = true)
    private val nav = ui.vertical()
    private val panelHeading = ui.text(17f, Weight.MEDIUM, DIM, tracking = 3.5f, caps = true)
    private val picksGrid = ui.vertical()
    private val panelNote = ui.text(21f, color = DIM)
    private val settingsGrid = ui.vertical()

    private var navIndex = 0
    private var inPanel = false
    private var panelIndex = 0
    private var firstPickRow = 0
    private var picks: List<Pickable>? = null
    private var picksFor: PhotoSource? = null

    /** True once something the slideshow depends on changed (reload on close). */
    var slideshowChanged = false
        private set

    private val sources = PhotoSource.entries

    init {
        setBackgroundColor(Ui.SCRIM)
        val page = ui.vertical().apply { setPadding(ui.px(40), ui.px(38), ui.px(40), ui.px(26)) }

        val header = ui.horizontal(Gravity.BOTTOM)
        header.addView(ui.text(56f, tracking = -1f).apply { text = "What to show" }, ui.weighted())
        header.addView(summary, ui.wrap().apply { leftMargin = ui.px(18) })
        page.addView(header, ui.matchWrap())

        val body = ui.horizontal(Gravity.TOP)
        val navBox = ui.horizontal(Gravity.TOP)
        navBox.addView(nav, ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT))
        // the nav's right border
        navBox.addView(ui.hairline(), LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(navBox, LinearLayout.LayoutParams(ui.px(300), ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = ui.vertical()
        panel.addView(panelHeading, ui.wrap())
        panel.addView(picksGrid, ui.matchWrap().apply { topMargin = ui.px(14) })
        panel.addView(panelNote, ui.matchWrap().apply { topMargin = ui.px(14) })
        panel.addView(View(ui.context), LinearLayout.LayoutParams(1, 0, 1f))
        panel.addView(ui.text(17f, Weight.MEDIUM, DIM, tracking = 3.5f, caps = true).apply { text = "Slideshow" }, ui.wrap())
        panel.addView(settingsGrid, ui.matchWrap().apply { topMargin = ui.px(14) })
        body.addView(panel, ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = ui.px(40) })
        page.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.px(30) })

        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private val active: PhotoSource get() = PhotoSource.parse(PreferenceManager.get(AMBIENT_SOURCE))
    private val focused: PhotoSource get() = sources[navIndex]

    /** Called each time the screen opens: focus the source on screen. */
    fun open() {
        slideshowChanged = false
        navIndex = sources.indexOf(active).coerceAtLeast(0)
        inPanel = false
        panelIndex = 0
        firstPickRow = 0
        loadPicksFor(focused)
        render()
    }

    /** Back from Settings: show what may have changed there (the art gallery's topic), keeping the focus. */
    fun refresh() = render()

    // ---- keys -----------------------------------------------------------------------------

    fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> host.close()
            KeyEvent.KEYCODE_DPAD_UP -> if (inPanel) movePanel { grid().up(it) } else moveNav(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (inPanel) movePanel { grid().down(it) } else moveNav(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (inPanel) {
                val to = grid().left(panelIndex)
                if (to == null) inPanel = false else panelIndex = to
                render()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (inPanel) movePanel { grid().right(it) } else {
                grid().first()?.let {
                    inPanel = true
                    panelIndex = it
                    firstPickRow = 0
                    render()
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (inPanel) pickPanel() else pickSource()
            else -> return false
        }
        return true
    }

    private fun moveNav(delta: Int) {
        val to = (navIndex + delta).coerceIn(0, sources.size - 1)
        if (to == navIndex) return
        navIndex = to
        firstPickRow = 0
        loadPicksFor(focused)
        render()
    }

    private fun movePanel(step: (Int) -> Int) {
        panelIndex = step(panelIndex)
        render()
    }

    private fun pickSource() {
        val source = focused
        if (source == PhotoSource.ONE_PHOTO) {
            val asset = host.currentAssetId()
            if (asset != null) PreferenceManager.save(AMBIENT_PINNED_ASSET, asset)
        }
        if (source != active || source == PhotoSource.ONE_PHOTO) {
            PreferenceManager.save(AMBIENT_SOURCE, source.name)
            slideshowChanged = true
        }
        render()
    }

    private fun pickPanel() {
        val pickCount = shownPicks().size
        if (panelIndex < pickCount) {
            val item = shownPicks()[panelIndex]
            val pref = picksPref(focused) ?: return
            val set = PreferenceManager.get(pref)
            PreferenceManager.save(pref, if (item.id in set) set - item.id else set + item.id)
            // picking albums for the Albums source also makes it the one on screen
            if (active != focused) PreferenceManager.save(AMBIENT_SOURCE, focused.name)
            slideshowChanged = true
        } else {
            when (SETTINGS[panelIndex - pickCount]) {
                Setting.INTERVAL -> {
                    PreferenceManager.save(AMBIENT_INTERVAL, IntervalChoices.next(PreferenceManager.get(AMBIENT_INTERVAL)))
                    slideshowChanged = true
                }
                Setting.SHUFFLE -> {
                    PreferenceManager.save(AMBIENT_SHUFFLE, !PreferenceManager.get(AMBIENT_SHUFFLE))
                    slideshowChanged = true
                }
                Setting.STRIP -> {
                    PreferenceManager.save(AMBIENT_STATUS_STRIP, !PreferenceManager.get(AMBIENT_STATUS_STRIP))
                    host.onStatusStripChanged()
                }
                Setting.MORE -> host.openMoreSettings()
            }
        }
        render()
    }

    // ---- data -----------------------------------------------------------------------------

    private fun picksPref(source: PhotoSource): StringSetPref? = when (source) {
        PhotoSource.ALBUMS -> AMBIENT_ALBUMS
        PhotoSource.PEOPLE -> AMBIENT_PEOPLE
        else -> null
    }

    private fun loadPicksFor(source: PhotoSource) {
        if (picksPref(source) == null) {
            picks = emptyList()
            picksFor = source
            return
        }
        if (picksFor == source && picks != null) return
        picksFor = source
        picks = null
        host.loadPicks(source) { result ->
            if (picksFor != source) return@loadPicks
            // picked ones first so they show without scrolling; the order then stays put while toggling
            val chosen = PreferenceManager.get(picksPref(source)!!)
            picks = result.orEmpty().sortedByDescending { it.id in chosen }
            loadFailed = result == null
            render()
        }
    }

    private var loadFailed = false

    /** The picks for the focused source (empty while loading or for sources without picks). */
    private fun shownPicks(): List<Pickable> {
        if (picksPref(focused) == null || picksFor != focused) return emptyList()
        return picks.orEmpty()
    }

    private fun grid() = PanelGrid(shownPicks().size, SETTINGS.size)

    // ---- drawing --------------------------------------------------------------------------

    private fun render() {
        summary.text = summaryText()
        renderNav()
        renderPanel()
    }

    private fun summaryText(): String {
        val order = if (PreferenceManager.get(AMBIENT_SHUFFLE)) "shuffled" else "in order"
        return when (active) {
            PhotoSource.ALBUMS -> PreferenceManager.get(AMBIENT_ALBUMS).size.let { n -> "Showing $n album${if (n == 1) "" else "s"}, $order" }
            PhotoSource.PEOPLE -> PreferenceManager.get(AMBIENT_PEOPLE).size.let { n -> "Showing ${if (n == 1) "1 person" else "$n people"}, $order" }
            PhotoSource.MEMORIES -> "Showing memories, $order"
            PhotoSource.ONE_PHOTO -> "Showing one photo"
            PhotoSource.ART -> "Showing the art gallery"
        }
    }

    private fun hint(source: PhotoSource): String {
        val base = when (source) {
            PhotoSource.ALBUMS -> PreferenceManager.get(AMBIENT_ALBUMS).size.let { if (it == 0) source.hint else "$it picked" }
            PhotoSource.PEOPLE -> PreferenceManager.get(AMBIENT_PEOPLE).size.let { if (it == 0) source.hint else "$it picked" }
            PhotoSource.ONE_PHOTO -> if (PreferenceManager.get(AMBIENT_PINNED_ASSET).isBlank()) source.hint else "A pinned photo"
            else -> source.hint
        }
        return if (source == active) "On screen · $base" else base
    }

    private fun renderNav() {
        nav.removeAllViews()
        sources.forEachIndexed { i, source ->
            val isFocused = i == navIndex && !inPanel
            val isCurrent = i == navIndex
            val row = ui.horizontal()
            row.addView(dot(isFocused || (isCurrent && inPanel), if (isFocused) 1f else 0.4f), ui.square(12f))
            val texts = ui.vertical()
            texts.addView(ui.text(28f, if (isFocused) Weight.MEDIUM else Weight.REGULAR, if (isFocused) WHITE else SOFT).apply { text = source.title }, ui.wrap())
            texts.addView(ui.text(18f, color = DIM).apply { text = hint(source) }, ui.wrap().apply { topMargin = ui.px(2) })
            row.addView(texts, ui.weighted().apply { leftMargin = ui.px(16); rightMargin = ui.px(12) })
            nav.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(84)))
        }
    }

    private fun renderPanel() {
        val source = focused
        val list = shownPicks()
        panelHeading.text = when (source) {
            PhotoSource.ALBUMS -> "Albums in the slideshow"
            PhotoSource.PEOPLE -> "People in the slideshow"
            PhotoSource.MEMORIES -> "Memories"
            PhotoSource.ONE_PHOTO -> "One photo"
            PhotoSource.ART -> "Art gallery"
        }
        panelNote.text = when {
            picksPref(source) != null && picks == null -> "Loading…"
            picksPref(source) != null && loadFailed -> "Couldn't load them from Immich"
            picksPref(source) != null && list.isEmpty() -> "Nothing to pick in Immich yet"
            source == PhotoSource.MEMORIES -> "Photos from this day in past years, from Immich's memories."
            source == PhotoSource.ONE_PHOTO -> "OK on One photo pins the photo that was on screen."
            source == PhotoSource.ART -> "Photos from Unsplash on \"${PreferenceManager.get(GALLERY_QUERY)}\". Change the topic in More settings."
            else -> ""
        }
        panelNote.visibility = if (panelNote.text.isEmpty()) View.GONE else View.VISIBLE

        picksGrid.removeAllViews()
        val pref = picksPref(source)
        if (pref != null && list.isNotEmpty()) {
            val chosen = PreferenceManager.get(pref)
            val totalRows = (list.size + 1) / 2
            val focusRow = if (inPanel && panelIndex < list.size) panelIndex / 2 else firstPickRow
            firstPickRow = PanelGrid.scrollRow(firstPickRow, focusRow, PICK_ROWS, totalRows)
            for (r in firstPickRow until minOf(totalRows, firstPickRow + PICK_ROWS)) {
                val row = ui.horizontal()
                for (c in 0..1) {
                    val i = r * 2 + c
                    val cell = if (i < list.size) pickCell(list[i], list[i].id in chosen, inPanel && panelIndex == i) else View(ui.context)
                    row.addView(cell, ui.weighted(1f, ui.px(60)).apply { if (c == 1) leftMargin = ui.px(36) })
                }
                picksGrid.addView(row, ui.matchWrap())
            }
            if (totalRows > PICK_ROWS) {
                picksGrid.addView(ui.text(16f, Weight.MEDIUM, DIM, tracking = 3f, caps = true).apply {
                    text = "${chosen.size} of ${list.size} picked · rows ${firstPickRow + 1}–${minOf(totalRows, firstPickRow + PICK_ROWS)} of $totalRows"
                }, ui.wrap().apply { topMargin = ui.px(8) })
            }
        }

        settingsGrid.removeAllViews()
        val offset = list.size
        SETTINGS.chunked(2).forEachIndexed { r, pair ->
            val row = ui.horizontal()
            pair.forEachIndexed { c, setting ->
                val i = offset + r * 2 + c
                row.addView(settingCell(setting, inPanel && panelIndex == i), ui.weighted(1f, ui.px(56)).apply { if (c == 1) leftMargin = ui.px(36) })
            }
            settingsGrid.addView(row, ui.matchWrap())
        }
    }

    private fun dot(visible: Boolean, alpha: Float): View = View(ui.context).apply {
        @Suppress("DEPRECATION")
        setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WHITE) })
        this.alpha = if (visible) alpha else 0f
    }

    /** A checkbox row: focus dot, check glyph, name (picked = medium white, else regular dim). */
    private fun pickCell(item: Pickable, on: Boolean, focused: Boolean): View {
        val row = ui.horizontal()
        row.addView(dot(focused, 1f), ui.square(8f))
        row.addView(ui.icon(if (on) "checkbox-marked" else "checkbox-blank-outline", 24f, if (on || focused) WHITE else DIM),
            ui.square(24f).apply { leftMargin = ui.px(8) })
        val name = ui.text(22f, if (on || focused) Weight.MEDIUM else Weight.REGULAR, if (on || focused) WHITE else DIM).apply { text = item.name }
        row.addView(name, ui.weighted().apply { leftMargin = ui.px(14) })
        return ui.ruled(row, 60f)
    }

    private fun settingCell(setting: Setting, focused: Boolean): View {
        val row = ui.horizontal()
        row.addView(dot(focused, 1f), ui.square(8f))
        row.addView(ui.text(21f, if (focused) Weight.MEDIUM else Weight.REGULAR, if (focused) WHITE else DIM).apply { text = setting.label }, ui.weighted().apply { leftMargin = ui.px(10) })
        when (setting) {
            Setting.MORE -> row.addView(ui.icon("chevron-right", 22f), ui.square(22f))
            else -> row.addView(valueText(setting), ui.wrap())
        }
        return ui.ruled(row, 56f)
    }

    private fun valueText(setting: Setting): AmbientText = ui.text(21f).apply {
        text = when (setting) {
            Setting.INTERVAL -> IntervalChoices.label(PreferenceManager.get(AMBIENT_INTERVAL))
            Setting.SHUFFLE -> if (PreferenceManager.get(AMBIENT_SHUFFLE)) "On" else "Off"
            Setting.STRIP -> if (PreferenceManager.get(AMBIENT_STATUS_STRIP)) "Bottom" else "Off"
            Setting.MORE -> ""
        }
        typeface = AmbientFonts.regular(context)
    }

    private enum class Setting(val label: String) {
        INTERVAL("New photo every"), SHUFFLE("Shuffle"), STRIP("Status strip"), MORE("More settings")
    }

    private companion object {
        val SETTINGS = Setting.entries
        const val PICK_ROWS = 6
    }
}
