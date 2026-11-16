package nl.giejay.android.tv.immich.display

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.shared.prefs.DISPLAY_AUTO_DIM
import nl.giejay.android.tv.immich.shared.prefs.DISPLAY_DAY_BRIGHTNESS
import nl.giejay.android.tv.immich.shared.prefs.DISPLAY_GEO_CACHE
import nl.giejay.android.tv.immich.shared.prefs.DISPLAY_NIGHT_BRIGHTNESS
import nl.giejay.android.tv.immich.shared.prefs.DISPLAY_NIGHT_SCHEDULE
import nl.giejay.android.tv.immich.shared.prefs.DISPLAY_SCREEN_OFF
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.WEATHER_LOCATION
import nl.giejay.android.tv.immich.weather.WeatherRepository
import timber.log.Timber
import java.util.TimeZone
import kotlin.math.abs

/**
 * Dims the screen after dark and (optionally) keeps it off overnight.
 *
 * The frame has no ambient-light sensor, so brightness follows sunset/sunrise at the weather
 * location (or a fixed schedule). The backlight is set through Android's brightness setting; below
 * its floor a black overlay window ("shade") dims further. Times use the location's time zone,
 * not the device's (the frame's clock is often left on GMT).
 *
 * Re-evaluated every 30 seconds and whenever a display/weather setting changes.
 */
object DisplayController {
    private const val TICK_MS = 30_000L
    private const val PREVIEW_MS = 15_000L
    private const val GEOCODE_RETRY_MS = 15 * 60_000L

    /** Fallback night when sunset/sunrise can't be computed (no weather location set). */
    private val FALLBACK_NIGHT = NightSchedule.H21_07

    /** True during the overnight screen-off window; polled by ActivitySensor. */
    @Volatile
    private var quietHours = false

    @JvmStatic
    fun isQuietHours(): Boolean = quietHours

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var shade: View? = null
    private var lastSetting = -1
    private var lastShade = -1f
    private var managing = false
    private var previewUntil = 0L

    private var resolvingFor: String? = null
    private var lastGeocodeAttempt = 0L

    private val tick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            update()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val previewEnd = Runnable { update() }

    // held strongly: SharedPreferences only keeps weak references to listeners
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("display") || key == WEATHER_LOCATION.key())) {
            handler.post(tick)
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        PreferenceManager.sharedPreference.registerOnSharedPreferenceChangeListener(prefListener)
        handler.post(tick)
    }

    /** Shows the night brightness for a few seconds, so it can be tuned during the day. */
    fun previewNight() {
        previewUntil = System.currentTimeMillis() + PREVIEW_MS
        handler.post(tick)
        // not `tick` itself: each tick run cancels its own pending posts
        handler.removeCallbacks(previewEnd)
        handler.postDelayed(previewEnd, PREVIEW_MS + 100)
    }

    private fun update() {
        try {
            val now = System.currentTimeMillis()
            val geo = location(now)
            val tz = geo?.timeZone ?: TimeZone.getDefault()

            val off = PreferenceManager.get(DISPLAY_SCREEN_OFF)
            val quiet = off.startMinute != null && off.endMinute != null &&
                DisplaySchedule.inRange(now, tz, off.startMinute, off.endMinute)
            quietHours = quiet

            val day = PreferenceManager.get(DISPLAY_DAY_BRIGHTNESS)
            val previewing = now < previewUntil
            if (!PreferenceManager.get(DISPLAY_AUTO_DIM) && !previewing) {
                if (managing) {
                    // auto-dim was just turned off: leave the screen at the day brightness
                    apply(day.toFloat())
                    managing = false
                }
                return
            }
            managing = true

            val dayness = if (previewing) 0f else dayness(now, tz, geo)
            val level = DisplaySchedule.level(dayness, day, PreferenceManager.get(DISPLAY_NIGHT_BRIGHTNESS))
            apply(level)
        } catch (e: Exception) {
            Timber.e(e, "Could not update display brightness")
        }
    }

    private fun dayness(now: Long, tz: TimeZone, geo: Geo?): Float {
        val schedule = PreferenceManager.get(DISPLAY_NIGHT_SCHEDULE)
        val windows = if (schedule == NightSchedule.SUNSET_TO_SUNRISE && geo != null) {
            DisplaySchedule.sunWindows(now, tz, geo.latitude, geo.longitude)
        } else {
            val fixed = if (schedule == NightSchedule.SUNSET_TO_SUNRISE) FALLBACK_NIGHT else schedule
            DisplaySchedule.fixedWindows(now, tz, fixed.startMinute!!, fixed.endMinute!!)
        }
        return DisplaySchedule.dayness(now, windows)
    }

    private fun apply(level: Float) {
        val setting = DisplaySchedule.brightnessSetting(level)
        if (setting != lastSetting) {
            if (writeBrightness(setting)) {
                Timber.i("Display brightness %d (level %.1f)", setting, level)
                lastSetting = setting
            }
        }
        val alpha = DisplaySchedule.shadeAlpha(level)
        if (abs(alpha - lastShade) > 0.001f) {
            setShade(alpha)
            lastShade = alpha
        }
    }

    private fun writeBrightness(setting: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(appContext)) return false
        return try {
            val resolver = appContext.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, setting)
        } catch (e: SecurityException) {
            Timber.w(e, "Not allowed to change the screen brightness")
            false
        }
    }

    /** Full-screen black overlay above every window (including dialogs); removed when unused. */
    @Suppress("DEPRECATION")
    private fun setShade(alpha: Float) {
        val color = Color.argb((alpha * 255).toInt(), 0, 0, 0)
        shade?.let {
            if (alpha <= 0f) {
                removeShade()
            } else {
                it.setBackgroundColor(color)
            }
            return
        }
        if (alpha <= 0f) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val view = View(appContext).apply { setBackgroundColor(color) }
        try {
            windowManager().addView(view, params)
            shade = view
        } catch (e: Exception) {
            Timber.w(e, "Could not add the dimming overlay")
        }
    }

    private fun removeShade() {
        shade?.let {
            try {
                windowManager().removeView(it)
            } catch (e: Exception) {
                Timber.w(e, "Could not remove the dimming overlay")
            }
        }
        shade = null
    }

    private fun windowManager() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private data class Geo(val location: String, val latitude: Double, val longitude: Double, val timeZone: TimeZone?) {
        fun serialize() = listOf(location, latitude, longitude, timeZone?.id ?: "").joinToString("\t")

        companion object {
            fun parse(raw: String): Geo? {
                val parts = raw.split("\t")
                if (parts.size != 4) return null
                val lat = parts[1].toDoubleOrNull() ?: return null
                val lon = parts[2].toDoubleOrNull() ?: return null
                return Geo(parts[0], lat, lon, parts[3].takeIf { it.isNotEmpty() }?.let(TimeZone::getTimeZone))
            }
        }
    }

    /** The weather location's coordinates and time zone, resolved once in the background and cached. */
    private fun location(now: Long): Geo? {
        val location = PreferenceManager.get(WEATHER_LOCATION).trim()
        if (location.isEmpty()) return null
        val cached = Geo.parse(PreferenceManager.get(DISPLAY_GEO_CACHE))
        if (cached?.location == location) return cached

        if (resolvingFor != location || now - lastGeocodeAttempt > GEOCODE_RETRY_MS) {
            resolvingFor = location
            lastGeocodeAttempt = now
            scope.launch {
                val result = WeatherRepository.geocode(location) ?: return@launch
                val geo = Geo(location, result.latitude, result.longitude, result.timezone?.let(TimeZone::getTimeZone))
                Timber.i("Display schedule location: %s (%s)", geo.location, geo.timeZone?.id)
                handler.post { PreferenceManager.save(DISPLAY_GEO_CACHE, geo.serialize()) }
            }
        }
        // until the new location resolves, the previous one is a better guess than the device clock
        return cached
    }
}
