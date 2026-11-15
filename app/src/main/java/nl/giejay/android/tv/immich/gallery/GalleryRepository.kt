package nl.giejay.android.tv.immich.gallery

import nl.giejay.android.tv.immich.BuildConfig
import nl.giejay.android.tv.immich.api.util.PublicHttpClient
import nl.giejay.android.tv.immich.shared.prefs.GALLERY_QUERY
import nl.giejay.android.tv.immich.shared.prefs.GALLERY_UNSPLASH_KEY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.Locale

/** A photo ready for the Gallery page and slideshow. */
data class GalleryPhoto(
    val id: String,
    val title: String,
    val artist: String?,
    /** Sized for the frame's screen. */
    val imageUrl: String,
    val thumbnailUrl: String
)

sealed class GalleryResult {
    data class Photos(val photos: List<GalleryPhoto>) : GalleryResult()
    data object NoKey : GalleryResult()
    data object BadKey : GalleryResult()
    data object RateLimited : GalleryResult()
    data object Error : GalleryResult()
}

/**
 * Loads batches of random landscape photos from Unsplash for the Gallery tab.
 *
 * One API call returns 30 photos; image downloads (images.unsplash.com) don't count toward the
 * rate limit, so even the 50 requests/hour "demo" tier lasts a long time at slideshow speed.
 * Images are hot-linked from the URLs the API returns (as Unsplash requires), resized on their CDN.
 */
object GalleryRepository {

    private const val BATCH_SIZE = 30
    private const val IMAGE_WIDTH = 1280
    private const val THUMB_WIDTH = 400
    private const val MAX_TITLE_LENGTH = 70

    private val service: UnsplashService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.unsplash.com/")
            // The access key travels over this trust-all client (KitKat can't validate Unsplash's
            // certificate chain). Accepted: it's a free, rate-limited key for public photos.
            .client(PublicHttpClient.client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(UnsplashService::class.java)
    }

    /** The key from Settings, else the one baked in at build time (local.properties). */
    fun accessKey(): String =
        PreferenceManager.get(GALLERY_UNSPLASH_KEY).trim().ifBlank { BuildConfig.UNSPLASH_ACCESS_KEY.trim() }

    suspend fun loadBatch(): GalleryResult {
        val key = accessKey()
        if (key.isBlank()) return GalleryResult.NoKey
        return try {
            val query = PreferenceManager.get(GALLERY_QUERY).trim().ifBlank { null }
            val photos = service.randomPhotos("Client-ID $key", count = BATCH_SIZE, query = query)
            GalleryResult.Photos(photos.map { it.toGalleryPhoto() })
        } catch (e: HttpException) {
            Timber.w(e, "Unsplash request failed: ${e.code()}")
            when (e.code()) {
                401 -> GalleryResult.BadKey
                403, 429 -> GalleryResult.RateLimited
                else -> GalleryResult.Error
            }
        } catch (e: Exception) {
            Timber.w(e, "Unsplash request failed")
            GalleryResult.Error
        }
    }

    private fun UnsplashPhoto.toGalleryPhoto() = GalleryPhoto(
        id = id,
        title = titleFor(this),
        artist = user?.name?.trim()?.ifBlank { null },
        imageUrl = sized(urls.raw, IMAGE_WIDTH, 80),
        thumbnailUrl = sized(urls.raw, THUMB_WIDTH, 70)
    )

    /** Unsplash's documented resize parameters on the raw URL (keeps its ixid tracking param). */
    private fun sized(raw: String, width: Int, quality: Int): String {
        val sep = if (raw.contains('?')) '&' else '?'
        return "$raw${sep}w=$width&q=$quality&fm=jpg&fit=max"
    }

    /**
     * A short, human title: the photographer's own description when it's a tidy one-liner,
     * otherwise Unsplash's generated alt text ("a lake surrounded by mountains" → capitalised),
     * otherwise the location name.
     */
    internal fun titleFor(photo: UnsplashPhoto): String {
        val description = photo.description?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_LENGTH && !it.contains('#') && !it.contains("http") }
        val candidate = description
            ?: photo.altDescription?.trim()?.takeIf { it.isNotEmpty() }
            ?: photo.location?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Untitled"
        val capitalised = candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        return if (capitalised.length > MAX_TITLE_LENGTH) capitalised.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…" else capitalised
    }
}
