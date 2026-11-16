package nl.giejay.android.tv.immich.slider

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.WEATHER_LOCATION
import nl.giejay.android.tv.immich.shared.prefs.WEATHER_USE_FAHRENHEIT
import nl.giejay.android.tv.immich.weather.CurrentConditions
import nl.giejay.android.tv.immich.weather.WeatherCode
import nl.giejay.android.tv.immich.weather.WeatherRepository
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPluginContext
import nl.giejay.mediaslider.view.MediaSliderController

/**
 * Bottom-left clock + current temperature over the slideshow (Immich and Gallery). The bottom
 * metadata's left column is free, so this sits where nothing else draws; it steps aside while
 * the playback controller is showing. Temperature refreshes every 10 minutes (the repository
 * caches for 15, so slideshows share one request) and is hidden when no weather location is set.
 */
class ClockWeatherViewPlugin : SliderViewPlugin<Unit?>, SliderControllerPlugin {
    private var root: View? = null
    private var scope: CoroutineScope? = null
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            loadWeather()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun attachView(rootView: ConstraintLayout, state: Unit?) {
        val view = View.inflate(rootView.context, R.layout.slider_clock_weather, null)
        val margin = dp(rootView, 30f)
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            leftMargin = margin
            bottomMargin = margin
        }
        rootView.addView(view, params)
        root = view
    }

    override fun onLoadConfig(context: SliderViewPluginContext, config: MediaSliderConfiguration, state: Unit?) {
        scope = context.ioScope
        handler.post(refresh)
    }

    override fun onControllerVisibilityChanged(
        isVisible: Boolean,
        rootView: View,
        controller: MediaSliderController,
        config: MediaSliderConfiguration
    ) {
        root?.animate()?.alpha(if (isVisible) 0f else 1f)?.setDuration(250)?.start()
    }

    override fun onDestroy(context: SliderViewPluginContext, state: Unit?) {
        handler.removeCallbacks(refresh)
        root?.let { (it.parent as? ConstraintLayout)?.removeView(it) }
        root = null
        scope = null
    }

    override fun onDestroy(controller: MediaSliderController) {
        handler.removeCallbacks(refresh)
    }

    private fun loadWeather() {
        val location = PreferenceManager.get(WEATHER_LOCATION).trim()
        if (location.isEmpty()) {
            bind(null)
            return
        }
        val fahrenheit = PreferenceManager.get(WEATHER_USE_FAHRENHEIT)
        scope?.launch {
            val conditions = WeatherRepository.currentConditions(location, fahrenheit)
            withContext(Dispatchers.Main) { bind(conditions) }
        }
    }

    private fun bind(conditions: CurrentConditions?) {
        val view = root ?: return
        val weather = view.findViewById<View>(R.id.slider_weather)
        if (conditions == null) {
            weather.visibility = View.GONE
            return
        }
        view.findViewById<ImageView>(R.id.slider_weather_icon)
            .setImageResource(WeatherCode.iconRes(conditions.code, conditions.isDay))
        // typographic minus: "−3°" reads better than a hyphen at this size
        view.findViewById<TextView>(R.id.slider_weather_temp).text =
            "${conditions.temperature}°".replace('-', '−')
        weather.visibility = View.VISIBLE
    }

    private fun dp(view: View, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, view.resources.displayMetrics).toInt()

    private companion object {
        const val REFRESH_MS = 10 * 60_000L
    }
}
