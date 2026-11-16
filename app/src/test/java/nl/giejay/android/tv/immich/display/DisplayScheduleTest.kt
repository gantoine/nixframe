package nl.giejay.android.tv.immich.display

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

class DisplayScheduleTest {
    private val ottawa = TimeZone.getTimeZone("America/Toronto")
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(tz: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    private fun assertNear(actual: Long, expected: Long, toleranceMin: Int = 5) {
        assertThat(abs(actual - expected) / 60_000L).isAtMost(toleranceMin.toLong())
    }

    @Test
    fun sunTimesOttawaNovember() {
        // NOAA solar calculator: 16 Nov 2026, Ottawa sunrise 07:03, sunset 16:31 (EST)
        val r = SunTimes.compute(at(ottawa, 2026, 11, 16, 12), 45.42, -75.70) as SunTimes.Result.Times
        assertNear(r.sunrise, at(ottawa, 2026, 11, 16, 7, 3))
        assertNear(r.sunset, at(ottawa, 2026, 11, 16, 16, 31))
    }

    @Test
    fun sunTimesOttawaSummerSolstice() {
        // NOAA: 05:14 and 20:54 EDT
        val r = SunTimes.compute(at(ottawa, 2026, 6, 21, 12), 45.42, -75.70) as SunTimes.Result.Times
        assertNear(r.sunrise, at(ottawa, 2026, 6, 21, 5, 14))
        assertNear(r.sunset, at(ottawa, 2026, 6, 21, 20, 54))
    }

    @Test
    fun polarDayAndNight() {
        val tromso = TimeZone.getTimeZone("Europe/Oslo")
        assertThat(SunTimes.compute(at(tromso, 2026, 12, 21, 12), 69.65, 18.96)).isEqualTo(SunTimes.Result.AlwaysDown)
        assertThat(SunTimes.compute(at(tromso, 2026, 6, 21, 12), 69.65, 18.96)).isEqualTo(SunTimes.Result.AlwaysUp)
    }

    @Test
    fun sunWindowsDimAfterSunsetAndBrightenAtSunrise() {
        fun dayness(h: Int, m: Int = 0) = DisplaySchedule.dayness(
            at(ottawa, 2026, 11, 16, h, m),
            DisplaySchedule.sunWindows(at(ottawa, 2026, 11, 16, h, m), ottawa, 45.42, -75.70)
        )
        assertThat(dayness(12)).isEqualTo(1f)
        assertThat(dayness(16, 0)).isEqualTo(1f)       // before the ramp (sunset ~16:31)
        assertThat(dayness(16, 30)).isWithin(0.2f).of(0.7f) // mid-fade
        assertThat(dayness(17, 15)).isEqualTo(0f)      // fully dimmed
        assertThat(dayness(2)).isEqualTo(0f)
        assertThat(dayness(6, 50)).isWithin(0.2f).of(0.5f) // brightening (sunrise ~07:03)
        assertThat(dayness(7, 30)).isEqualTo(1f)
    }

    @Test
    fun sunWindowsUseLocationTimeZoneNotDeviceClock() {
        // 22:15 UTC is 17:15 in Ottawa: already dark there, though mid-afternoon by a GMT clock's sun
        val now = at(utc, 2026, 11, 16, 22, 15)
        assertThat(DisplaySchedule.dayness(now, DisplaySchedule.sunWindows(now, ottawa, 45.42, -75.70))).isEqualTo(0f)
        // 16:00 UTC is 11:00 in Ottawa: full day
        val noon = at(utc, 2026, 11, 16, 16)
        assertThat(DisplaySchedule.dayness(noon, DisplaySchedule.sunWindows(noon, ottawa, 45.42, -75.70))).isEqualTo(1f)
    }

    @Test
    fun fixedScheduleWrapsMidnight() {
        fun dayness(h: Int, m: Int = 0): Float {
            val now = at(ottawa, 2026, 11, 16, h, m)
            return DisplaySchedule.dayness(now, DisplaySchedule.fixedWindows(now, ottawa, 21 * 60, 7 * 60))
        }
        assertThat(dayness(12)).isEqualTo(1f)
        assertThat(dayness(20, 40)).isEqualTo(1f)
        assertThat(dayness(21, 30)).isEqualTo(0f)
        assertThat(dayness(23, 59)).isEqualTo(0f)
        assertThat(dayness(3)).isEqualTo(0f)
        assertThat(dayness(7, 15)).isEqualTo(1f)
    }

    @Test
    fun fixedScheduleAfterMidnight() {
        val now = at(ottawa, 2026, 11, 16, 3)
        assertThat(DisplaySchedule.dayness(now, DisplaySchedule.fixedWindows(now, ottawa, 60, 6 * 60))).isEqualTo(0f)
        val evening = at(ottawa, 2026, 11, 16, 23)
        assertThat(DisplaySchedule.dayness(evening, DisplaySchedule.fixedWindows(evening, ottawa, 60, 6 * 60))).isEqualTo(1f)
    }

    @Test
    fun quietHoursRange() {
        assertThat(DisplaySchedule.inRange(at(ottawa, 2026, 11, 16, 23, 30), ottawa, 22 * 60, 6 * 60)).isTrue()
        assertThat(DisplaySchedule.inRange(at(ottawa, 2026, 11, 16, 5, 59), ottawa, 22 * 60, 6 * 60)).isTrue()
        assertThat(DisplaySchedule.inRange(at(ottawa, 2026, 11, 16, 6, 0), ottawa, 22 * 60, 6 * 60)).isFalse()
        assertThat(DisplaySchedule.inRange(at(ottawa, 2026, 11, 16, 12), ottawa, 0, 6 * 60)).isFalse()
        assertThat(DisplaySchedule.inRange(at(ottawa, 2026, 11, 16, 0, 0), ottawa, 0, 6 * 60)).isTrue()
    }

    @Test
    fun brightnessLevels() {
        assertThat(DisplaySchedule.brightnessSetting(100f)).isEqualTo(255)
        assertThat(DisplaySchedule.brightnessSetting(90f)).isEqualTo(231)
        assertThat(DisplaySchedule.brightnessSetting(0f)).isEqualTo(10)
        assertThat(DisplaySchedule.brightnessSetting(-35f)).isEqualTo(10)
        assertThat(DisplaySchedule.shadeAlpha(10f)).isEqualTo(0f)
        assertThat(DisplaySchedule.shadeAlpha(-35f)).isWithin(0.001f).of(0.35f)
        // halfway through the fade from 90 down to -35
        assertThat(DisplaySchedule.level(0.5f, 90, -35)).isWithin(0.001f).of(27.5f)
    }
}
