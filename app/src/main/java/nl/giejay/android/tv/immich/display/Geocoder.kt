package nl.giejay.android.tv.immich.display

import nl.giejay.android.tv.immich.api.util.Ipv4FirstDns
import nl.giejay.android.tv.immich.api.util.UnsafeOkHttpClient
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import timber.log.Timber

data class GeocodingResponse(val results: List<GeoResult>?)

data class GeoResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** IANA time zone, e.g. "America/Toronto". */
    val timezone: String? = null
)

interface OpenMeteoGeocodingService {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 1,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json"
    ): GeocodingResponse
}

/**
 * Resolves the display location (Settings → Display) to coordinates and a time zone with
 * Open-Meteo's free geocoder, for sunset/sunrise dimming. This used to live with the Weather tab;
 * weather itself now comes from Home Assistant.
 */
object Geocoder {
    // Conscrypt supplies the modern ciphers; trust-all skips KitKat's outdated CA store (the cert
    // chain can't validate against a 2014 root store). Acceptable here: the geocoder is public,
    // read-only, and no credentials are sent.
    private val client: OkHttpClient by lazy { UnsafeOkHttpClient.unsafeOkHttpClient().dns(Ipv4FirstDns).build() }

    private val service: OpenMeteoGeocodingService by lazy {
        Retrofit.Builder()
            .baseUrl("https://geocoding-api.open-meteo.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoGeocodingService::class.java)
    }

    /** Resolves [location] to coordinates and a time zone, or null if not found / offline. */
    suspend fun geocode(location: String): GeoResult? = try {
        service.search(location).results?.firstOrNull()
    } catch (e: Exception) {
        Timber.w(e, "Could not geocode '$location'")
        null
    }
}
