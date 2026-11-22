package nl.giejay.android.tv.immich.weather

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.WEATHER_ANIMATED_BACKGROUND
import nl.giejay.android.tv.immich.shared.prefs.WEATHER_LOCATION
import nl.giejay.android.tv.immich.shared.prefs.WEATHER_USE_FAHRENHEIT
import nl.giejay.android.tv.immich.shared.util.FrostedWallpaper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    // Required so this fragment can be hosted as a page row inside HomeFragment's BrowseSupportFragment.
    private val mainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    override fun getMainFragmentAdapter() = mainFragmentAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val wallpaperTick = object : Runnable {
        override fun run() {
            view?.findViewById<ImageView>(R.id.weather_wallpaper)?.let { FrostedWallpaper.load(this@WeatherFragment, it) }
            handler.postDelayed(this, FrostedWallpaper.REFRESH_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(wallpaperTick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(wallpaperTick)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_weather, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainFragmentAdapter.fragmentHost?.notifyDataReady(mainFragmentAdapter)
        val status = view.findViewById<TextView>(R.id.weather_status)
        val content = view.findViewById<View>(R.id.weather_content)

        val location = PreferenceManager.get(WEATHER_LOCATION).trim()
        if (location.isBlank()) {
            content.visibility = View.GONE
            status.visibility = View.VISIBLE
            status.text = getString(R.string.weather_set_location)
            return
        }

        content.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = getString(R.string.weather_loading)

        val fahrenheit = PreferenceManager.get(WEATHER_USE_FAHRENHEIT)
        viewLifecycleOwner.lifecycleScope.launch {
            val weather = WeatherRepository.load(location, fahrenheit)
            if (!isAdded) return@launch
            if (weather == null) {
                status.visibility = View.VISIBLE
                status.text = getString(R.string.weather_error)
                content.visibility = View.GONE
            } else {
                status.visibility = View.GONE
                content.visibility = View.VISIBLE
                bind(view, weather)
            }
        }
    }

    private fun bind(view: View, weather: Weather) {
        val scene = WeatherCode.scene(weather.currentCode, weather.isDay)
        view.background = backgroundFor(scene)

        val animation = view.findViewById<WeatherAnimationView>(R.id.weather_animation)
        animation.setScene(
            if (PreferenceManager.get(WEATHER_ANIMATED_BACKGROUND)) scene
            else WeatherAnimationView.Scene.NONE
        )

        view.findViewById<TextView>(R.id.weather_location).text = weather.locationName
        view.findViewById<TextView>(R.id.weather_date).text =
            SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        // The unit is implied by the settings; the hero reads cleaner as just "5°".
        view.findViewById<TextView>(R.id.current_temp).text = "${weather.currentTemp}°"
        view.findViewById<TextView>(R.id.current_condition).text = WeatherCode.description(weather.currentCode)
        view.findViewById<ImageView>(R.id.current_icon)
            .setImageDrawable(AppCompatResources.getDrawable(requireContext(), WeatherCode.iconRes(weather.currentCode, weather.isDay)))
        weather.days.firstOrNull()?.let { today ->
            view.findViewById<TextView>(R.id.current_hilo).text = "H ${today.high}°   L ${today.low}°"
        }

        val inflater = LayoutInflater.from(requireContext())
        val gap = (12 * resources.displayMetrics.density).toInt()

        val rows = listOf(
            view.findViewById<LinearLayout>(R.id.stats_row_top),
            view.findViewById<LinearLayout>(R.id.stats_row_bottom)
        )
        rows.forEach { it.removeAllViews() }
        var statCount = 0
        fun addStat(iconRes: Int, value: String?, label: String, level: String? = null, levelColor: Int = 0) {
            if (value == null) return
            val row = rows[statCount / 2]
            val item = inflater.inflate(R.layout.weather_stat, row, false)
            item.findViewById<ImageView>(R.id.stat_icon).apply {
                setImageDrawable(AppCompatResources.getDrawable(requireContext(), iconRes))
                setColorFilter(Color.WHITE)
            }
            item.findViewById<TextView>(R.id.stat_value).text = value
            item.findViewById<TextView>(R.id.stat_label).text = label
            if (level != null) {
                item.findViewById<TextView>(R.id.stat_level).apply {
                    text = level
                    setTextColor(levelColor)
                    visibility = View.VISIBLE
                }
            }
            if (row.childCount > 0) (item.layoutParams as LinearLayout.LayoutParams).leftMargin = gap
            row.addView(item)
            statCount++
        }
        addStat(R.drawable.ic_stat_drop, weather.humidity?.let { "$it%" }, getString(R.string.weather_humidity))
        addStat(R.drawable.ic_stat_umbrella, weather.precipitation?.let { "$it ${weather.precipUnit}" },
            getString(R.string.weather_precipitation))
        weather.uvIndex?.let { uv ->
            val (level, color) = uvLevel(uv)
            addStat(R.drawable.ic_weather_sun, "$uv", getString(R.string.weather_uv), level, color)
        }
        weather.aqi?.let { aqi ->
            val (level, color) = aqiLevel(aqi)
            addStat(R.drawable.ic_stat_wind, "$aqi", getString(R.string.weather_air_quality), level, color)
        }
        rows[1].visibility = if (statCount > 2) View.VISIBLE else View.GONE

        view.findViewById<TextView>(R.id.forecast_header).text =
            getString(R.string.weather_forecast_header, weather.days.size)
        val fahrenheit = weather.unitSymbol == "°F"
        val weekMin = weather.days.minOfOrNull { it.low } ?: 0
        val weekMax = weather.days.maxOfOrNull { it.high } ?: 0
        val list = view.findViewById<LinearLayout>(R.id.forecast_row)
        list.removeAllViews()
        weather.days.forEachIndexed { index, day ->
            if (index > 0) {
                list.addView(View(requireContext()).apply { setBackgroundColor(0x1FFFFFFF) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
            val item = inflater.inflate(R.layout.weather_day_item, list, false)
            item.findViewById<TextView>(R.id.day_name).apply {
                text = if (index == 0) getString(R.string.weather_today) else dayName(day.date)
                if (index == 0) setTypeface(typeface, Typeface.BOLD)
            }
            item.findViewById<ImageView>(R.id.day_icon)
                .setImageDrawable(AppCompatResources.getDrawable(requireContext(), WeatherCode.iconRes(day.code)))
            item.findViewById<TextView>(R.id.day_low).text = "${day.low}°"
            item.findViewById<TextView>(R.id.day_high).text = "${day.high}°"
            item.findViewById<TempRangeBar>(R.id.day_bar).set(
                weekMin, weekMax, day.low, day.high,
                if (index == 0) weather.currentTemp else null, fahrenheit
            )
            list.addView(item)
        }
    }

    /**
     * A vertical three-stop sky gradient tinted to the current conditions — lighter where the light
     * comes from (top), deepening toward the bottom so the panels and text keep their contrast.
     */
    private fun backgroundFor(scene: WeatherAnimationView.Scene): GradientDrawable {
        val colors = when (scene) {
            WeatherAnimationView.Scene.CLEAR -> intArrayOf(0xFF2F72C2.toInt(), 0xFF275C9E.toInt(), 0xFF16305A.toInt())
            WeatherAnimationView.Scene.PARTLY -> intArrayOf(0xFF4F80B6.toInt(), 0xFF3A6597.toInt(), 0xFF1C3253.toInt())
            WeatherAnimationView.Scene.CLEAR_NIGHT -> intArrayOf(0xFF0B1734.toInt(), 0xFF12224A.toInt(), 0xFF090F22.toInt())
            WeatherAnimationView.Scene.CLOUDS -> intArrayOf(0xFF5D6E82.toInt(), 0xFF46546A.toInt(), 0xFF242C3A.toInt())
            WeatherAnimationView.Scene.RAIN -> intArrayOf(0xFF33404F.toInt(), 0xFF263240.toInt(), 0xFF121922.toInt())
            WeatherAnimationView.Scene.SNOW -> intArrayOf(0xFF46566B.toInt(), 0xFF364558.toInt(), 0xFF1B2433.toInt())
            WeatherAnimationView.Scene.FOG -> intArrayOf(0xFF59616B.toInt(), 0xFF464D56.toInt(), 0xFF23282F.toInt())
            WeatherAnimationView.Scene.THUNDER -> intArrayOf(0xFF1D2230.toInt(), 0xFF161A25.toInt(), 0xFF0B0E14.toInt())
            else -> intArrayOf(0xFF14161A.toInt(), 0xFF1C1F24.toInt(), 0xFF24272E.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }

    /** UV index -> (label, color) on the WHO scale. */
    private fun uvLevel(uv: Int): Pair<String, Int> = when {
        uv <= 2 -> "Low" to 0xFF66BB6A.toInt()
        uv <= 5 -> "Moderate" to 0xFFFFCA28.toInt()
        uv <= 7 -> "High" to 0xFFFFA726.toInt()
        uv <= 10 -> "Very high" to 0xFFEF5350.toInt()
        else -> "Extreme" to 0xFFAB47BC.toInt()
    }

    /** US AQI -> (label, color). */
    private fun aqiLevel(aqi: Int): Pair<String, Int> = when {
        aqi <= 50 -> "Good" to 0xFF66BB6A.toInt()
        aqi <= 100 -> "Moderate" to 0xFFFFCA28.toInt()
        aqi <= 150 -> "Poor" to 0xFFFFA726.toInt()
        aqi <= 200 -> "Unhealthy" to 0xFFEF5350.toInt()
        aqi <= 300 -> "Very poor" to 0xFFAB47BC.toInt()
        else -> "Hazardous" to 0xFF8D6E63.toInt()
    }

    private fun dayName(isoDate: String): String = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)
        if (date != null) SimpleDateFormat("EEE", Locale.getDefault()).format(date) else isoDate
    } catch (e: Exception) {
        isoDate
    }
}
