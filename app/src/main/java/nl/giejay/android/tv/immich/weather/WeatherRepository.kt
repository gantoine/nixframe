package nl.giejay.android.tv.immich.weather

import nl.giejay.android.tv.immich.api.util.Ipv4FirstDns
import nl.giejay.android.tv.immich.api.util.UnsafeOkHttpClient
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Fetches weather from Open-Meteo (free, no API key). Geocodes a location string to coordinates,
 * then loads the current conditions + a short daily forecast. HTTPS works on KitKat because
 * Conscrypt is installed as the security provider (see ImmichApplication).
 */
object WeatherRepository {

    // Conscrypt supplies the modern ciphers; trust-all skips KitKat's outdated CA store (the cert
    // chain can't validate against a 2014 root store). Acceptable here: Open-Meteo is public,
    // read-only, no credentials are sent.
    private val client: OkHttpClient by lazy { UnsafeOkHttpClient.unsafeOkHttpClient().dns(Ipv4FirstDns).build() }

    private val forecastService: OpenMeteoForecastService by lazy {
        retrofit("https://api.open-meteo.com/").create(OpenMeteoForecastService::class.java)
    }

    private val currentService: OpenMeteoCurrentService by lazy {
        retrofit("https://api.open-meteo.com/").create(OpenMeteoCurrentService::class.java)
    }

    private val geocodingService: OpenMeteoGeocodingService by lazy {
        retrofit("https://geocoding-api.open-meteo.com/").create(OpenMeteoGeocodingService::class.java)
    }

    private val airQualityService: OpenMeteoAirQualityService by lazy {
        retrofit("https://air-quality-api.open-meteo.com/").create(OpenMeteoAirQualityService::class.java)
    }

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /** Resolves [location] to coordinates and a time zone, or null if not found / offline. */
    suspend fun geocode(location: String): GeoResult? = try {
        geocodingService.search(location).results?.firstOrNull()
    } catch (e: Exception) {
        Timber.w(e, "Could not geocode '$location'")
        null
    }

    private const val CURRENT_TTL_MS = 15 * 60_000L
    private val geocodeMemo = mutableMapOf<String, GeoResult>()
    private var currentCache: Pair<String, CurrentConditions>? = null
    private var currentCachedAt = 0L

    /**
     * Current temperature and condition for [location], cached for 15 minutes so every slideshow
     * (and every slide) shares one small request. Returns the last known value if a refresh fails.
     */
    suspend fun currentConditions(location: String, useFahrenheit: Boolean): CurrentConditions? {
        val key = "$location|$useFahrenheit"
        val now = System.currentTimeMillis()
        synchronized(this) {
            currentCache?.let { (k, v) -> if (k == key && now - currentCachedAt < CURRENT_TTL_MS) return v }
        }
        return try {
            val geo = synchronized(this) { geocodeMemo[location] }
                ?: geocode(location)?.also { synchronized(this) { geocodeMemo[location] = it } }
                ?: return null
            val current = currentService.current(
                geo.latitude, geo.longitude,
                temperatureUnit = if (useFahrenheit) "fahrenheit" else "celsius"
            ).current ?: return null
            val conditions = CurrentConditions(current.temperature.roundToInt(), current.weatherCode, current.isDay != 0)
            synchronized(this) {
                currentCache = key to conditions
                currentCachedAt = now
            }
            conditions
        } catch (e: Exception) {
            Timber.w(e, "Could not load current conditions for '$location'")
            synchronized(this) { currentCache?.takeIf { it.first == key }?.second }
        }
    }

    /** Returns the weather for [location], or null if it can't be resolved/loaded. */
    suspend fun load(location: String, useFahrenheit: Boolean): Weather? {
        try {
            val geo = geocodingService.search(location).results?.firstOrNull() ?: run {
                Timber.w("No geocoding result for '$location'")
                return null
            }
            val tempUnit = if (useFahrenheit) "fahrenheit" else "celsius"
            val windUnit = if (useFahrenheit) "mph" else "km/h"
            val precipUnit = if (useFahrenheit) "in" else "mm"
            val resp = forecastService.forecast(
                geo.latitude, geo.longitude,
                temperatureUnit = tempUnit,
                windSpeedUnit = if (useFahrenheit) "mph" else "kmh",
                precipitationUnit = if (useFahrenheit) "inch" else "mm"
            )
            val current = resp.current ?: return null
            val daily = resp.daily ?: return null

            val days = daily.time.indices.map { i ->
                DailyForecast(
                    date = daily.time[i],
                    code = daily.weatherCode.getOrElse(i) { 0 },
                    high = daily.tempMax.getOrElse(i) { 0.0 }.roundToInt(),
                    low = daily.tempMin.getOrElse(i) { 0.0 }.roundToInt()
                )
            }
            // current.precipitation is only the preceding 15-minute bucket (reads 0 mid-rain); use the
            // current hour's value from the hourly series instead, falling back to current if unavailable.
            val precipitation = run {
                val hourly = resp.hourly
                val hour = current.time?.take(13)
                if (hour != null && hourly?.time != null) {
                    val idx = hourly.time.indexOfFirst { it.take(13) == hour }
                    hourly.precipitation?.getOrNull(idx)
                } else {
                    null
                }
            } ?: current.precipitation

            // Air quality is a separate endpoint and not available everywhere — best effort.
            val aqi = try {
                airQualityService.airQuality(geo.latitude, geo.longitude).current?.usAqi
            } catch (e: Exception) {
                Timber.w(e, "Could not load air quality")
                null
            }
            val name = listOfNotNull(geo.name, geo.admin1, geo.country).distinct().firstOrNull() ?: location
            return Weather(
                locationName = name,
                currentTemp = current.temperature.roundToInt(),
                currentCode = current.weatherCode,
                isDay = current.isDay != 0,
                unitSymbol = if (useFahrenheit) "°F" else "°C",
                days = days,
                humidity = current.humidity,
                precipitation = precipitation,
                precipUnit = precipUnit,
                windSpeed = current.windSpeed?.roundToInt(),
                windUnit = windUnit,
                pressureHpa = current.pressure?.roundToInt(),
                uvIndex = daily.uvIndexMax?.firstOrNull()?.roundToInt(),
                aqi = aqi
            )
        } catch (e: Exception) {
            Timber.e(e, "Could not load weather for '$location'")
            return null
        }
    }
}
