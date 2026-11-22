package nl.giejay.android.tv.immich.ambient

/**
 * D-pad movement in the right-hand panel of "What to show": two 2-column grids stacked, the
 * picks (albums or people, [picks] items) above the slideshow settings ([settings] items).
 * Positions are indexes into picks followed by settings. Pure, unit tested.
 */
class PanelGrid(val picks: Int, val settings: Int) {
    val size: Int get() = picks + settings

    private fun section(i: Int) = if (i < picks) 0 else 1
    private fun start(section: Int) = if (section == 0) 0 else picks
    private fun count(section: Int) = if (section == 0) picks else settings

    /** The first position, or null when the panel has nothing to focus. */
    fun first(): Int? = if (size == 0) null else 0

    fun down(i: Int): Int {
        val s = section(i)
        val j = i - start(s)
        if (j + 2 < count(s)) return i + 2
        // the last row may hold a single item: step onto it rather than skipping the section
        if (j % 2 == 1 && j + 1 < count(s)) return i + 1
        if (s == 0 && settings > 0) return picks + minOf(j % 2, settings - 1)
        return i
    }

    fun up(i: Int): Int {
        val s = section(i)
        val j = i - start(s)
        if (j - 2 >= 0) return i - 2
        if (s == 1 && picks > 0) {
            val lastRow = (picks - 1) / 2
            return minOf(lastRow * 2 + j % 2, picks - 1)
        }
        return i
    }

    /** Null means "leave the panel" (back to the source list). */
    fun left(i: Int): Int? {
        val j = i - start(section(i))
        return if (j % 2 == 1) i - 1 else null
    }

    fun right(i: Int): Int {
        val s = section(i)
        val j = i - start(s)
        return if (j % 2 == 0 && j + 1 < count(s)) i + 1 else i
    }

    /** First visible row of the picks grid so that [focusRow] shows within [visibleRows] rows. */
    companion object {
        fun scrollRow(firstRow: Int, focusRow: Int, visibleRows: Int, totalRows: Int): Int {
            var first = firstRow
            if (focusRow < first) first = focusRow
            if (focusRow >= first + visibleRows) first = focusRow - visibleRows + 1
            return first.coerceIn(0, maxOf(0, totalRows - visibleRows))
        }
    }
}

/** Cycles the "New photo every" setting through a few sensible intervals (seconds). */
object IntervalChoices {
    val SECONDS = listOf(10, 20, 30, 60, 180, 300, 600)

    fun next(current: Int): Int = SECONDS.firstOrNull { it > current } ?: SECONDS.first()

    fun label(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}
