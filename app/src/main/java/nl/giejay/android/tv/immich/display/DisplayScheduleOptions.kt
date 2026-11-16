package nl.giejay.android.tv.immich.display

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.EnumWithTitle

private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

/** When the screen dims: follow the sun, or a fixed night in the location's time zone. */
enum class NightSchedule(val startMinute: Int?, val endMinute: Int?) : EnumWithTitle {
    SUNSET_TO_SUNRISE(null, null),
    H20_07(20 * 60, 7 * 60),
    H21_07(21 * 60, 7 * 60),
    H22_07(22 * 60, 7 * 60),
    H23_07(23 * 60, 7 * 60);

    override fun getTitle(): String =
        if (startMinute == null || endMinute == null) {
            ImmichApplication.appContext!!.getString(R.string.display_sunset_to_sunrise)
        } else {
            "${clock(startMinute)} – ${clock(endMinute)}"
        }
}

/** Optional overnight window during which the screen is turned off entirely. */
enum class ScreenOffSchedule(val startMinute: Int?, val endMinute: Int?) : EnumWithTitle {
    NEVER(null, null),
    H22_06(22 * 60, 6 * 60),
    H23_06(23 * 60, 6 * 60),
    H00_06(0, 6 * 60),
    H00_07(0, 7 * 60),
    H01_06(60, 6 * 60);

    override fun getTitle(): String =
        if (startMinute == null || endMinute == null) {
            ImmichApplication.appContext!!.getString(R.string.display_screen_off_never)
        } else {
            "${clock(startMinute)} – ${clock(endMinute)}"
        }
}
