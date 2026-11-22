package nl.giejay.android.tv.immich.homeassistant

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong

/** Number and time formatting for the ambient screens. Pure JVM, unit tested. */
object AmbientFormat {

    /** "57°", "67.5°", "−3°" (typographic minus). Trailing ".0" is dropped. */
    fun temperature(value: Double, decimals: Int): String = number(value, decimals).replace('-', '−') + "°"

    /** Rounded to [decimals] places, trailing zeros trimmed; never "-0". */
    fun number(value: Double, decimals: Int): String {
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = (value * factor).roundToLong() / factor
        val text = if (decimals == 0) rounded.toLong().toString()
        else String.format(Locale.US, "%.${decimals}f", rounded).trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }

    /** "just now", "1 min ago", "12 min ago", "4 h ago", "2 d ago". */
    fun ago(then: Instant, now: Instant): String {
        val s = Duration.between(then, now).seconds
        if (s < 45) return "just now"
        return when {
            s < 90 -> "1 min ago"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86400 -> "${(s + 1800) / 3600} h ago"
            else -> "${(s + 43200) / 86400} d ago"
        }
    }

    /** "Rang just now" / "Rang 3 min ago". */
    fun rang(then: Instant?, now: Instant): String = if (then == null) "Rang" else "Rang " + ago(then, now)

    /** "Office Climate Temperature" minus "temperature" -> "Office Climate". Case-insensitive. */
    fun stripSuffix(name: String, suffix: String): String {
        val trimmed = name.trim()
        return if (trimmed.length > suffix.length && trimmed.endsWith(suffix, ignoreCase = true)) {
            trimmed.dropLast(suffix.length).trimEnd()
        } else trimmed
    }

    /** "Heat to 67° · idle", "Cool to 74° · cooling", "66–74° · idle", "Off". */
    fun thermostatDetail(e: HaEntity): String {
        if (e.isUnavailable) return "Unavailable"
        val target = e.attrDouble("temperature")
        val low = e.attrDouble("target_temp_low")
        val high = e.attrDouble("target_temp_high")
        val mode = when (e.state) {
            "off" -> return "Off"
            "heat" -> target?.let { "Heat to ${temperature(it, 1)}" } ?: "Heat"
            "cool" -> target?.let { "Cool to ${temperature(it, 1)}" } ?: "Cool"
            "heat_cool", "auto" -> if (low != null && high != null) "${number(low, 1)}–${temperature(high, 1)}"
                                   else target?.let { "Auto ${temperature(it, 1)}" } ?: "Auto"
            else -> HaFormat.humanize(e.state)
        }
        val action = e.attrString("hvac_action")?.replace('_', ' ')
        return if (action.isNullOrBlank()) mode else "$mode · $action"
    }

    /** Uppercase as the design sets labels; Locale.US so "i" never becomes a dotted capital. */
    fun caps(text: String): String = text.uppercase(Locale.US)
}
