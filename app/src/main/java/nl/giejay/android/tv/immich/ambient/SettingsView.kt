package nl.giejay.android.tv.immich.ambient

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import nl.giejay.android.tv.immich.ambient.ui.AmbientFonts
import nl.giejay.android.tv.immich.ambient.ui.Ui
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.DIM
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.SOFT
import nl.giejay.android.tv.immich.ambient.ui.Ui.Companion.WHITE
import nl.giejay.android.tv.immich.ambient.ui.Weight
import nl.giejay.android.tv.immich.shared.prefs.ActionHost
import nl.giejay.android.tv.immich.shared.prefs.PrefScreen
import nl.giejay.android.tv.immich.shared.prefs.SETTINGS_SECTIONS
import nl.giejay.android.tv.immich.shared.prefs.SettingsEffect
import nl.giejay.android.tv.immich.shared.prefs.TextEditor

/**
 * Screen 5 (Settings.dc.html): "Settings", opened from "What to show". Sections on the left, the
 * section's settings on the right as rows with their current value; the focused row's description
 * under them. Focus is a white dot plus brighter, Medium text; nothing is filled. The sections and
 * rows come from the PrefScreen definitions ([SETTINGS_SECTIONS]). All keys arrive through [onKey].
 */
@SuppressLint("ViewConstructor") // built in code only, never inflated
class SettingsView(private val ui: Ui, private val host: Host) : FrameLayout(ui.context) {

    interface Host : ActionHost {
        /** Back from the sections: return to "What to show". */
        fun closeSettings()
    }

    private class Section(val screen: PrefScreen, val rows: List<SettingRow>)

    private class Picker(val row: SettingRow.Choice, val list: OptionList, var focus: OptionFocus)

    private val sectionLabel = ui.text(17f, Weight.MEDIUM, DIM, tracking = 3f, caps = true)
    private val nav = ui.vertical()
    private val rowsBox = ui.vertical()
    private val description = TextView(ui.context).apply {
        typeface = AmbientFonts.regular(context)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(20f).toFloat())
        setTextColor(DIM)
        includeFontPadding = false
        // the mockup's line-height 1.35
        setLineSpacing(0f, 0.95f)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }

    private var sections: List<Section> = emptyList()
    private var moves = SettingsNav(emptyList(), VISIBLE_ROWS)
    private var focus = SettingsFocus()
    private var picker: Picker? = null
    private var dialog: Dialog? = null

    /** True once something the slideshow depends on changed (reload when the menus close). */
    var slideshowChanged = false
        private set
    /** True once a Home Assistant setting changed (reconnect when the menus close). */
    var houseChanged = false
        private set

    init {
        setBackgroundColor(Ui.SCRIM)
        val page = ui.vertical().apply { setPadding(ui.px(40), ui.px(38), ui.px(40), ui.px(26)) }

        val header = ui.horizontal(Gravity.BOTTOM)
        header.addView(ui.text(56f, tracking = -1f).apply { text = "Settings" }, ui.weighted())
        header.addView(sectionLabel, ui.wrap().apply { leftMargin = ui.px(18) })
        page.addView(header, ui.matchWrap())

        val body = ui.horizontal(Gravity.TOP)
        val navBox = ui.horizontal(Gravity.TOP)
        navBox.addView(nav, ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT))
        // the nav's right border
        navBox.addView(ui.hairline(), LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(navBox, LinearLayout.LayoutParams(ui.px(300), ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = ui.vertical()
        panel.addView(rowsBox, ui.matchWrap())
        panel.addView(description, ui.matchWrap().apply { topMargin = ui.px(22); leftMargin = ui.px(28) })
        body.addView(panel, ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = ui.px(40) })
        page.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.px(30) })

        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Called each time the screen opens from "What to show": the first section, focus on the list. */
    fun open(section: PrefScreen? = null) {
        dismissDialog()
        sections = SETTINGS_SECTIONS.map { Section(it, SettingsRows.build(context, it)) }.filter { it.rows.isNotEmpty() }
        moves = SettingsNav(sections.map { it.rows.size }, VISIBLE_ROWS)
        focus = moves.start(sections.indexOfFirst { it.screen == section }.coerceAtLeast(0))
        picker = null
        render()
    }

    /** The screen is going away (Back, the doorbell, sign-in): drop the dialog and the open list. */
    fun onHidden() {
        dismissDialog()
        picker = null
    }

    /** The menus applied the changes: start counting afresh. */
    fun clearChanges() {
        slideshowChanged = false
        houseChanged = false
    }

    private fun dismissDialog() {
        dialog?.let { if (it.isShowing) it.dismiss() }
        dialog = null
    }

    // ---- keys -----------------------------------------------------------------------------

    fun onKey(keyCode: Int, repeat: Boolean): Boolean {
        val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        // a held OK or Back must not toggle a setting back and forth or leave two screens
        if (repeat && (ok || keyCode == KeyEvent.KEYCODE_BACK)) return true
        picker?.let { p ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> p.focus = p.list.move(p.focus, -1)
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> p.focus = p.list.move(p.focus, 1)
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> picker = null
                ok -> {
                    p.row.options.getOrNull(p.focus.index)?.let { option ->
                        if (option.value != p.row.current) {
                            p.row.pick(option.value)
                            changed()
                        }
                    }
                    picker = null
                }
                else -> return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
            render()
            return true
        }
        when {
            keyCode == KeyEvent.KEYCODE_BACK -> {
                val to = moves.back(focus)
                if (to == null) {
                    host.closeSettings()
                    return true
                }
                focus = to
            }
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> focus = moves.up(focus)
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> focus = moves.down(focus)
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> focus = moves.left(focus)
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> focus = moves.right(focus)
            ok -> if (focus.inRows) activate() else focus = moves.right(focus)
            else -> return false
        }
        render()
        return true
    }

    private val section: Section? get() = sections.getOrNull(focus.section)
    private val focusedRow: SettingRow? get() = if (focus.inRows) section?.rows?.getOrNull(focus.row) else null

    /** OK on a row. */
    private fun activate() {
        when (val row = focusedRow ?: return) {
            is SettingRow.Toggle -> {
                row.on = !row.on
                changed()
            }
            is SettingRow.Choice -> if (row.cycles) {
                SettingsFormat.next(row.options.map { it.value }, row.current)?.let {
                    row.pick(it)
                    changed()
                }
            } else {
                val list = OptionList(row.options.size, VISIBLE_ROWS)
                picker = Picker(row, list, list.open(row.currentIndex))
            }
            is SettingRow.Text -> when (row.text.editor) {
                TextEditor.DIALOG -> editText(row)
                TextEditor.SIGN_IN -> host.openSignIn()
                TextEditor.NONE -> Unit
            }
            is SettingRow.Action -> row.action.run(host)
        }
    }

    private fun editText(row: SettingRow.Text) {
        dismissDialog()
        val pref = row.text
        val current = row.value
        val clear = when {
            pref.builtIn.isNotBlank() && current.isNotBlank() -> "Use built-in"
            current.isNotBlank() -> "Clear"
            else -> null
        }
        dialog = TextEntryDialog.show(context, pref.title, pref.summary, if (pref.secret) "" else current, pref.secret, clear) { typed ->
            if (typed != row.value) {
                row.value = typed
                changed()
            }
            render()
        }
    }

    private fun changed() {
        when (section?.screen?.effect) {
            SettingsEffect.SLIDESHOW -> slideshowChanged = true
            SettingsEffect.HOME_ASSISTANT -> houseChanged = true
            else -> Unit
        }
    }

    // ---- drawing --------------------------------------------------------------------------

    private fun render() {
        val s = section
        val p = picker
        sectionLabel.text = when {
            p != null -> p.row.name
            s == null -> ""
            else -> SettingsFormat.sectionLabel(s.screen.title, if (focus.inRows) focus.row else null, s.rows.size, VISIBLE_ROWS)
        }
        renderNav()
        if (p != null) renderOptions(p) else renderRows()
    }

    private fun renderNav() {
        nav.removeAllViews()
        sections.forEachIndexed { i, s ->
            val current = i == focus.section
            val focused = current && !focus.inRows && picker == null
            val row = ui.horizontal()
            row.addView(dot(current, if (focused) 1f else 0.4f), ui.square(12f))
            row.addView(ui.text(28f, if (current) Weight.MEDIUM else Weight.REGULAR, if (current) WHITE else DIM).apply { text = s.screen.title },
                ui.weighted().apply { leftMargin = ui.px(16); rightMargin = ui.px(12) })
            nav.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(66)))
        }
    }

    private fun renderRows() {
        rowsBox.removeAllViews()
        val s = section ?: run { setDescription(null); return }
        for (i in focus.firstRow until minOf(s.rows.size, focus.firstRow + VISIBLE_ROWS)) {
            val row = s.rows[i]
            val focused = focus.inRows && i == focus.row
            rowsBox.addView(rowView(row.name, value(row), if (row is SettingRow.Action) "chevron-right" else null, focused), ui.matchWrap())
        }
        setDescription(focusedRow?.let { describe(it) })
    }

    private fun renderOptions(p: Picker) {
        rowsBox.removeAllViews()
        val options = p.row.options
        val current = p.row.current
        for (i in p.focus.first until minOf(options.size, p.focus.first + VISIBLE_ROWS)) {
            val option = options[i]
            rowsBox.addView(rowView(option.label, null, if (option.value == current) "check" else null, i == p.focus.index), ui.matchWrap())
        }
        setDescription(p.row.description)
    }

    private fun setDescription(text: String?) {
        description.text = text.orEmpty()
        description.visibility = if (text.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
    }

    private fun value(row: SettingRow): ValueText? = when (row) {
        is SettingRow.Toggle -> SettingsFormat.onOff(row.on)
        is SettingRow.Choice -> SettingsFormat.choice(row.options.getOrNull(row.currentIndex)?.short ?: row.current)
        is SettingRow.Text -> SettingsFormat.text(row.value, row.text.secret, row.text.builtIn, row.text.blankLabel)
        is SettingRow.Action -> null
    }

    private fun describe(row: SettingRow): String = when (row) {
        is SettingRow.Text -> SettingsFormat.textDescription(row.description, row.value, row.text.secret, row.text.builtIn, row.text.editor)
        else -> row.description
    }

    /** A settings row: focus dot, name, then the value (spaced capitals) or a glyph, over a hairline. */
    private fun rowView(name: String, value: ValueText?, icon: String?, focused: Boolean): View {
        val row = ui.horizontal()
        row.addView(dot(focused, 1f), ui.square(12f))
        row.addView(ui.text(24f, if (focused) Weight.MEDIUM else Weight.REGULAR, if (focused) WHITE else SOFT).apply { text = name },
            ui.weighted().apply { leftMargin = ui.px(16) })
        if (value != null) {
            val v = ui.text(18f, Weight.MEDIUM, if (value.dim) DIM else WHITE, tracking = if (value.caps) 2.5f else 0.5f, caps = value.caps)
            v.text = value.text
            row.addView(v, ui.wrap().apply { leftMargin = ui.px(16) })
        }
        if (icon != null) {
            row.addView(ui.icon(icon, 22f, if (focused) WHITE else DIM), ui.square(22f).apply { leftMargin = ui.px(16) })
        }
        return ui.ruled(row, 60f)
    }

    private fun dot(visible: Boolean, alpha: Float): View = View(ui.context).apply {
        @Suppress("DEPRECATION")
        setBackgroundDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WHITE) })
        this.alpha = if (visible) alpha else 0f
    }

    private companion object {
        /** Rows that fit above the description (the mockup shows seven). */
        const val VISIBLE_ROWS = 7
    }
}
