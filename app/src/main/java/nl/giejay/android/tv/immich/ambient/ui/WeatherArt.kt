package nl.giejay.android.tv.immich.ambient.ui

import nl.giejay.android.tv.immich.R

/**
 * The colour weather pictures (ic_weather_*) for a weather icon name as HaFormat.weatherIcon
 * gives it ("weather-partly-cloudy"). Home Assistant has no night variant of partly cloudy, so
 * that one is always the sun behind a cloud.
 */
object WeatherArt {
    fun drawable(icon: String?): Int = when (icon) {
        "weather-sunny" -> R.drawable.ic_weather_sun
        "weather-night" -> R.drawable.ic_weather_moon
        "weather-partly-cloudy" -> R.drawable.ic_weather_partly
        "weather-rainy", "weather-pouring", "weather-hail", "weather-snowy-rainy" -> R.drawable.ic_weather_rain
        "weather-snowy" -> R.drawable.ic_weather_snow
        "weather-lightning", "weather-lightning-rainy" -> R.drawable.ic_weather_thunder
        else -> R.drawable.ic_weather_cloud
    }
}
