package nl.giejay.android.tv.immich.display

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Pure scheduling math for auto-dimming (no Android dependencies, unit tested).
 *
 * "Dayness" is 1 in the day and 0 at night. Around each dusk it fades out over 45 minutes
 * (15 before to 30 after) and around each dawn it fades back in (30 before to 15 after),
 * so the screen dims gradually, as the room darkens.
 */
object DisplaySchedule {
    private const val MINUTE = 60_000L
    const val BEFORE_DAWN = 30 * MINUTE
    const val AFTER_DAWN = 15 * MINUTE
    const val BEFORE_DUSK = 15 * MINUTE
    const val AFTER_DUSK = 30 * MINUTE

    /** Lowest and highest values of Android's screen-brightness setting (the frame floors at 10). */
    const val MIN_SETTING = 10
    const val MAX_SETTING = 255

    /** A daylight period: from [dawn] to [dusk] (epoch millis, dusk after dawn). */
    data class DayWindow(val dawn: Long, val dusk: Long)

    /** 0 (night) to 1 (day) at [now], given the day windows around it. */
    fun dayness(now: Long, windows: List<DayWindow>): Float = windows.maxOfOrNull { w ->
        val rising = (now - (w.dawn - BEFORE_DAWN)).toFloat() / (BEFORE_DAWN + AFTER_DAWN)
        val falling = ((w.dusk + AFTER_DUSK) - now).toFloat() / (BEFORE_DUSK + AFTER_DUSK)
        rising.coerceIn(0f, 1f) * falling.coerceIn(0f, 1f)
    } ?: 0f

    /** Day windows from sunrise to sunset for yesterday, today and tomorrow (local to [tz]). */
    fun sunWindows(now: Long, tz: TimeZone, latitude: Double, longitude: Double): List<DayWindow> =
        (-1..1).mapNotNull { offset ->
            when (val r = SunTimes.compute(atLocalTime(now, tz, offset, 12 * 60), latitude, longitude)) {
                is SunTimes.Result.Times -> DayWindow(r.sunrise, r.sunset)
                // padded past midnight so consecutive all-day windows don't ramp down between them
                SunTimes.Result.AlwaysUp -> DayWindow(
                    atLocalTime(now, tz, offset, 0) - 2 * 60 * MINUTE,
                    atLocalTime(now, tz, offset + 1, 0) + 2 * 60 * MINUTE
                )
                SunTimes.Result.AlwaysDown -> null
            }
        }

    /** Day windows for a fixed night from [nightStartMinute] to [nightEndMinute] (minutes after local midnight). */
    fun fixedWindows(now: Long, tz: TimeZone, nightStartMinute: Int, nightEndMinute: Int): List<DayWindow> =
        (-1..1).map { offset ->
            val dawn = atLocalTime(now, tz, offset, nightEndMinute)
            // a night that starts after its end time-of-day (21:00-07:00) begins the same day;
            // one that starts before it (01:00-06:00) begins after midnight, i.e. the next day
            val duskDay = if (nightStartMinute > nightEndMinute) offset else offset + 1
            DayWindow(dawn, atLocalTime(now, tz, duskDay, nightStartMinute))
        }

    /** Whether [now] falls in the local time range [startMinute, endMinute), which may wrap midnight. */
    fun inRange(now: Long, tz: TimeZone, startMinute: Int, endMinute: Int): Boolean {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMinute <= endMinute) {
            minute in startMinute until endMinute
        } else {
            minute >= startMinute || minute < endMinute
        }
    }

    /**
     * Brightness level between [night] and [day] for a given [dayness]. Levels are percentages of
     * the backlight range (0 = the lowest backlight); negative levels go below the backlight floor
     * with a black shade (-35 = lowest backlight plus a 35% shade).
     */
    fun level(dayness: Float, day: Int, night: Int): Float = night + (day - night) * dayness

    /** Android brightness setting (10-255) for [level]. */
    fun brightnessSetting(level: Float): Int =
        if (level <= 0f) MIN_SETTING
        else (MIN_SETTING + level.coerceAtMost(100f) / 100f * (MAX_SETTING - MIN_SETTING)).roundToInt()

    /** Opacity (0-1) of the black shade for [level]. */
    fun shadeAlpha(level: Float): Float = if (level >= 0f) 0f else (-level / 100f).coerceAtMost(0.9f)

    /** Epoch millis of [minuteOfDay] local time, [dayOffset] days from the local date of [now]. */
    fun atLocalTime(now: Long, tz: TimeZone, dayOffset: Int, minuteOfDay: Int): Long {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        cal.set(Calendar.MINUTE, minuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
