package nl.giejay.android.tv.immich.ambient

import android.content.Context
import nl.giejay.android.tv.immich.shared.prefs.ActionPref
import nl.giejay.android.tv.immich.shared.prefs.BooleanPref
import nl.giejay.android.tv.immich.shared.prefs.EnumByTitlePref
import nl.giejay.android.tv.immich.shared.prefs.EnumPref
import nl.giejay.android.tv.immich.shared.prefs.IntListPref
import nl.giejay.android.tv.immich.shared.prefs.IntSeekbarPref
import nl.giejay.android.tv.immich.shared.prefs.NumberFormat
import nl.giejay.android.tv.immich.shared.prefs.Pref
import nl.giejay.android.tv.immich.shared.prefs.PrefScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.StringPref
import nl.giejay.android.tv.immich.shared.prefs.StringSetPref
import timber.log.Timber

/** One option of a choice setting: [label] in the list of options, [short] in the row. */
data class SettingOption(val value: String, val label: String, val short: String)

/** A row of the settings screen, made from a [Pref] definition (see [SettingsRows.build]). */
sealed class SettingRow(val pref: Pref<*, *>) {
    val name: String get() = pref.title
    val description: String get() = pref.summary

    class Toggle(pref: BooleanPref) : SettingRow(pref) {
        private val p = pref
        var on: Boolean
            get() = PreferenceManager.get(p)
            set(value) = PreferenceManager.save(p, value)
    }

    class Choice(pref: Pref<*, *>, val options: List<SettingOption>, private val read: () -> String, private val write: (String) -> Unit) : SettingRow(pref) {
        /** The stored value, as an option's [SettingOption.value]. */
        val current: String get() = read()
        val currentIndex: Int get() = options.indexOfFirst { it.value == current }
        fun pick(value: String) = write(value)
        val cycles: Boolean get() = options.size <= SettingsFormat.CYCLE_MAX
    }

    class Text(val text: StringPref) : SettingRow(text) {
        var value: String
            get() = PreferenceManager.get(text)
            set(v) = PreferenceManager.save(text, v.trim())
    }

    class Action(val action: ActionPref) : SettingRow(action)
}

/** Builds the settings screen's rows from the pref definitions in shared/prefs. */
object SettingsRows {

    fun build(context: Context, screen: PrefScreen): List<SettingRow> = screen.prefs.mapNotNull { row(context, it) }

    private fun row(context: Context, pref: Pref<*, *>): SettingRow? = try {
        when (pref) {
            is BooleanPref -> SettingRow.Toggle(pref)
            is StringPref -> SettingRow.Text(pref)
            is ActionPref -> if (pref.available(context)) SettingRow.Action(pref) else null
            is IntListPref -> intList(context, pref)
            is IntSeekbarPref -> percent(pref)
            is EnumPref<*> -> stringChoice(pref, strings(context, pref.valuesResourceId).zip(strings(context, pref.titlesResourceId)))
            is EnumByTitlePref<*> -> stringChoice(pref, pref.getEnumEntries().map { it.toString() to it.getTitle() })
            is StringSetPref -> null // picked on the What to show screen
        }
    } catch (e: RuntimeException) {
        // a bad resource must not take the whole screen down; the row is left out
        Timber.e(e, "Could not build the settings row for %s", pref.key())
        null
    }

    private fun strings(context: Context, id: Int): List<String> = context.resources.getTextArray(id).map { it.toString() }

    private fun intList(context: Context, pref: IntListPref): SettingRow {
        val values = strings(context, pref.valuesResourceId)
        val titles = strings(context, pref.titlesResourceId)
        val options = values.mapIndexed { i, v ->
            val title = titles.getOrNull(i) ?: v
            SettingOption(v, title, SettingsFormat.number(v.toInt(), pref.format, title))
        }
        return SettingRow.Choice(pref, options, { PreferenceManager.get(pref).toString() }, { PreferenceManager.save(pref, it.toInt()) })
    }

    private fun percent(pref: IntSeekbarPref): SettingRow {
        val steps = (0..100 step pref.step).toMutableList()
        val now = PreferenceManager.get(pref)
        if (now !in steps) steps.add(now)
        val options = steps.sorted().map { v ->
            val label = SettingsFormat.number(v, NumberFormat.PERCENT)
            SettingOption(v.toString(), label, label)
        }
        return SettingRow.Choice(pref, options, { PreferenceManager.get(pref).toString() }, { PreferenceManager.save(pref, it.toInt()) })
    }

    /** A choice stored as a string (enum prefs): reads and writes through the pref's own conversions. */
    @Suppress("UNCHECKED_CAST")
    private fun stringChoice(pref: Pref<*, *>, valuesAndTitles: List<Pair<String, String>>): SettingRow {
        val p = pref as Pref<Any, String>
        val options = valuesAndTitles.map { (v, t) -> SettingOption(v, t, SettingsFormat.short(t)) }
        return SettingRow.Choice(pref, options, { p.toPrefValue(PreferenceManager.get(p)) }, { PreferenceManager.save(p, p.fromPrefValue(it)) })
    }
}
