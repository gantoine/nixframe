package nl.giejay.android.tv.immich.display

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise/sunset for a coordinate, computed offline (the same formulas as suncalc / NOAA's
 * simplified sunrise equation; accurate to a minute or two at non-polar latitudes).
 */
object SunTimes {

    sealed class Result {
        /** Epoch millis of sunrise and sunset. */
        data class Times(val sunrise: Long, val sunset: Long) : Result()
        /** Midnight sun: the sun stays above the horizon all day. */
        object AlwaysUp : Result()
        /** Polar night: the sun never rises. */
        object AlwaysDown : Result()
    }

    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val J0 = 0.0009
    private val OBLIQUITY = RAD * 23.4397
    /** Sun's center 0.833° below the horizon: refraction plus the solar disc's radius. */
    private val HORIZON = RAD * -0.833

    /** Sun times for the solar day closest to [dateMs] (pass local noon to get that calendar day). */
    fun compute(dateMs: Long, latitude: Double, longitude: Double): Result {
        val lw = RAD * -longitude
        val phi = RAD * latitude
        val d = dateMs / DAY_MS - 0.5 + J1970 - J2000
        val n = Math.round(d - J0 - lw / (2 * PI)).toDouble()
        val ds = J0 + lw / (2 * PI) + n

        val m = RAD * (357.5291 + 0.98560028 * ds)
        val center = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + center + RAD * 102.9372 + PI
        val dec = asin(sin(OBLIQUITY) * sin(l))
        val jNoon = J2000 + ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)

        val cosW = (sin(HORIZON) - sin(phi) * sin(dec)) / (cos(phi) * cos(dec))
        if (cosW > 1) return Result.AlwaysDown
        if (cosW < -1) return Result.AlwaysUp
        val w = acos(cosW)
        val jSet = J2000 + J0 + (w + lw) / (2 * PI) + n + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
        val jRise = jNoon - (jSet - jNoon)
        return Result.Times(fromJulian(jRise), fromJulian(jSet))
    }

    private fun fromJulian(j: Double): Long = ((j + 0.5 - J1970) * DAY_MS).roundToLong()
}
