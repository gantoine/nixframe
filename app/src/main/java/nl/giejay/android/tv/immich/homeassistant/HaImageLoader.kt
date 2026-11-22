package nl.giejay.android.tv.immich.homeassistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Loads dashboard images (camera snapshots, picture cards, album art). Relative paths resolve
 * against Home Assistant; only requests to Home Assistant's own host get the access token.
 * Bitmaps are downsampled to the view's width — the frame has little memory to spare.
 */
class HaImageLoader(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope
) {
    private val haHost = HttpUrl.parse(baseUrl)?.host()

    fun resolve(path: String): String? = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> baseUrl.trimEnd('/') + path
        else -> null
    }

    /** [onResult] (main thread) says whether a picture arrived; not called if superseded. */
    fun load(path: String, into: ImageView, targetWidthPx: Int, circle: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        val url = resolve(path) ?: return
        val httpUrl = HttpUrl.parse(url) ?: return
        into.setTag(TAG_KEY, path)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(httpUrl, targetWidthPx) }
            // the view may have been re-pointed at another image meanwhile
            if (into.getTag(TAG_KEY) != path) return@launch
            if (bitmap == null) {
                onResult?.invoke(false)
                return@launch
            }
            if (circle) {
                into.setImageDrawable(RoundedBitmapDrawableFactory.create(into.resources, bitmap).apply { isCircular = true })
            } else {
                into.setImageBitmap(bitmap)
            }
            onResult?.invoke(true)
        }
    }

    private fun fetch(url: HttpUrl, targetWidthPx: Int): Bitmap? = try {
        val request = Request.Builder().url(url).apply {
            if (url.host() == haHost) header("Authorization", "Bearer $token")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Image %s: HTTP %d", url.encodedPath(), response.code())
                return null
            }
            val bytes = response.body()?.bytes() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (targetWidthPx > 0 && bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not load image %s", url.encodedPath())
        null
    }

    private companion object {
        val TAG_KEY = nl.giejay.android.tv.immich.R.id.ha_image_path
    }
}
