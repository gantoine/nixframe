package nl.giejay.android.tv.immich.gallery

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Query

/** Unsplash API (https://unsplash.com/documentation). Only the random-photo endpoint is used. */
interface UnsplashService {
    @Headers("Accept-Version: v1")
    @GET("photos/random")
    suspend fun randomPhotos(
        @Header("Authorization") authorization: String,
        /** Max 30 per request; with count set the response is always an array. */
        @Query("count") count: Int = 30,
        @Query("orientation") orientation: String = "landscape",
        /** Only photos under the free Unsplash License (excludes paid Unsplash+ photos). */
        @Query("license") license: String = "free",
        /** Search term; null = any photo. Can't be combined with topics/collections. */
        @Query("query") query: String?,
        @Query("content_filter") contentFilter: String = "high"
    ): List<UnsplashPhoto>
}

data class UnsplashPhoto(
    val id: String,
    val width: Int,
    val height: Int,
    val description: String?,
    @SerializedName("alt_description") val altDescription: String?,
    val urls: UnsplashUrls,
    val user: UnsplashUser?,
    val location: UnsplashLocation?
)

data class UnsplashUrls(val raw: String)

data class UnsplashUser(val name: String?)

data class UnsplashLocation(val name: String?)
