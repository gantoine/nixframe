package nl.giejay.android.tv.immich.ambient

import nl.giejay.android.tv.immich.shared.prefs.NumberFormat
import nl.giejay.android.tv.immich.shared.prefs.TextEditor

/**
 * Where the focus is on the settings screen: a section in the left column, or (with [inRows]) one
 * of that section's rows; [firstRow] is the first row drawn when the section has more rows than fit.
 */
data class SettingsFocus(val section: Int = 0, val inRows: Boolean = false, val row: Int = 0, val firstRow: Int = 0)

/**
 * D-pad movement on the settings screen. [rowCounts] is the number of rows of each section,
 * [visibleRows] how many rows fit. Pure, unit tested.
 *
 *  - Up/Down move within the column that has the focus; moving to another section starts it at its
 *    first row. Long sections scroll by one row.
 *  - Right (or OK on a section) moves into the rows; Left back to the sections, on the same section.
 *  - Back from the rows goes to the sections; Back from the sections leaves the screen (null).
 */
class SettingsNav(private val rowCounts: List<Int>, private val visibleRows: Int) {

    fun start(section: Int = 0): SettingsFocus = SettingsFocus(section.coerceIn(0, maxOf(0, rowCounts.size - 1)))

    private fun rows(f: SettingsFocus) = rowCounts.getOrElse(f.section) { 0 }

    fun up(f: SettingsFocus): SettingsFocus = if (f.inRows) moveRow(f, -1) else moveSection(f, -1)

    fun down(f: SettingsFocus): SettingsFocus = if (f.inRows) moveRow(f, 1) else moveSection(f, 1)

    fun right(f: SettingsFocus): SettingsFocus = if (!f.inRows && rows(f) > 0) f.copy(inRows = true) else f

    fun left(f: SettingsFocus): SettingsFocus = if (f.inRows) f.copy(inRows = false) else f

    /** Null means "leave the settings screen". */
    fun back(f: SettingsFocus): SettingsFocus? = if (f.inRows) f.copy(inRows = false) else null

    private fun moveSection(f: SettingsFocus, delta: Int): SettingsFocus {
        val to = (f.section + delta).coerceIn(0, maxOf(0, rowCounts.size - 1))
        return if (to == f.section) f else SettingsFocus(to)
    }

    private fun moveRow(f: SettingsFocus, delta: Int): SettingsFocus {
        val n = rows(f)
        if (n == 0) return f
        val to = (f.row + delta).coerceIn(0, n - 1)
        return f.copy(row = to, firstRow = PanelGrid.scrollRow(f.firstRow, to, visibleRows, n))
    }
}

/** Focus in a setting's list of options: [index] is the focused option, [first] the first one drawn. */
data class OptionFocus(val index: Int, val first: Int)

/** Up/Down through a list of [count] options of which [visible] fit. Pure, unit tested. */
class OptionList(private val count: Int, private val visible: Int) {
    /** Opens on [selected] (the current value; -1 when it is none of the options), scrolled into view. */
    fun open(selected: Int): OptionFocus {
        val i = selected.coerceIn(0, maxOf(0, count - 1))
        return OptionFocus(i, PanelGrid.scrollRow(0, i, visible, count))
    }

    fun move(f: OptionFocus, delta: Int): OptionFocus {
        if (count == 0) return f
        val to = (f.index + delta).coerceIn(0, count - 1)
        return OptionFocus(to, PanelGrid.scrollRow(f.first, to, visible, count))
    }
}

/** A value as drawn at the right of a settings row. [dim] for off/unset; [caps] for spaced capitals. */
data class ValueText(val text: String, val dim: Boolean = false, val caps: Boolean = true)

/** How the settings screen writes values. Pure, unit tested. */
object SettingsFormat {
    /** Stands for a secret that is set; the same length whatever the secret, so nothing leaks. */
    const val MASK = "••••••••"
    const val BUILT_IN = "Built-in"
    /** Free text longer than this is cut with an ellipsis in the row (the description has all of it). */
    const val MAX_TEXT = 28

    private val OFF_WORDS = setOf("off", "never", "not set")

    /** Settings with this many options or fewer change in place on OK; more open a list. */
    const val CYCLE_MAX = 3

    fun onOff(on: Boolean): ValueText = ValueText(if (on) "On" else "Off", dim = !on)

    /** A choice's value: dimmed when it means "off". */
    fun choice(label: String): ValueText = ValueText(label, dim = label.lowercase() in OFF_WORDS)

    /** "Center inside (keep the whole photo)" → "Center inside": the part a row has room for. */
    fun short(label: String): String = label.substringBefore(" (").trim().ifEmpty { label.trim() }

    /** A number as a row shows it; [title] is the option's own title, for [NumberFormat.TITLE]. */
    fun number(value: Int, format: NumberFormat, title: String? = null): String = when (format) {
        NumberFormat.TITLE -> title?.let { short(it) } ?: value.toString()
        NumberFormat.SECONDS -> when {
            value < 60 -> "$value s"
            value % 60 == 0 -> "${value / 60} min"
            else -> "$value s"
        }
        NumberFormat.MILLIS -> "$value ms"
        NumberFormat.MINUTES -> if (value <= 0) "Never" else "$value min"
        NumberFormat.PERCENT -> "$value%"
    }

    /** The next option after [current] for settings that cycle, wrapping round; the first when [current] is none. */
    fun <T> next(options: List<T>, current: T): T? {
        if (options.isEmpty()) return null
        val i = options.indexOf(current)
        return options[(i + 1) % options.size]
    }

    fun truncate(s: String, max: Int = MAX_TEXT): String =
        if (s.length <= max) s else s.substring(0, maxOf(1, max - 1)).trimEnd() + "…"

    /**
     * A free-text setting's value: secrets are masked, a blank one says when a built-in value
     * stands in for it, else [blankLabel] (dimmed). Text is shown as typed (no capitals: entity
     * ids, paths and keys are case-sensitive), cut to [MAX_TEXT].
     */
    fun text(value: String, secret: Boolean, builtIn: String, blankLabel: String): ValueText {
        val v = value.trim()
        return when {
            v.isNotEmpty() && secret -> ValueText(MASK, caps = false)
            v.isNotEmpty() -> ValueText(truncate(v), caps = false)
            builtIn.isNotBlank() -> ValueText(BUILT_IN)
            else -> ValueText(blankLabel, dim = true)
        }
    }

    /**
     * The paragraph under the rows for a free-text setting: its description, then what it is
     * using right now when the row can't say it all (a built-in value, a value too long for the
     * row, a read-only value). Never repeats a secret.
     */
    fun textDescription(summary: String, value: String, secret: Boolean, builtIn: String, editor: TextEditor): String {
        val v = value.trim()
        val extra = when {
            v.isEmpty() && builtIn.isNotBlank() -> if (secret) "Using the built-in one." else "Using the built-in $builtIn."
            secret -> null
            v.isNotEmpty() && (editor == TextEditor.NONE || v.length > MAX_TEXT) -> v
            else -> null
        }
        return join(summary, extra)
    }

    /** Joins two sentences, adding the full stop the first may lack. */
    fun join(first: String, second: String?): String {
        val a = first.trim()
        if (second.isNullOrBlank()) return a
        if (a.isEmpty()) return second
        val stop = if (a.endsWith(".") || a.endsWith("!") || a.endsWith("?") || a.endsWith(":")) "" else "."
        return "$a$stop $second"
    }

    /** "Slideshow · 3 of 17" when the section has more rows than fit, else the section's name. */
    fun sectionLabel(title: String, row: Int?, rows: Int, visible: Int): String =
        if (row == null || rows <= visible) title else "$title · ${row + 1} of $rows"
}
