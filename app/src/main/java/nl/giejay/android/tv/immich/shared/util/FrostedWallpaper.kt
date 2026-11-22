package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import timber.log.Timber

/**
 * The Frosted Glass backdrop shared by the Home and Weather tabs, matching the Home Assistant theme
 * of the same name: a random photo from the Immich library, heavily blurred, under a dark scrim (the
 * theme's rgb(2, 6, 11)) so white text stays readable on any photo. Glass panels sit on top.
 */
object FrostedWallpaper {
    const val REFRESH_MS = 15 * 60_000L

    /** Edge rgba(234,235,238,.22) and the panel fill, lighter at the top. */
    const val GLASS_TOP = 0x26FFFFFF
    const val GLASS_BOTTOM = 0x12FFFFFF
    const val EDGE = 0x38EAEBEE

    fun imageView(context: Context) = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    fun scrim(context: Context) = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x5902060B, 0x7302060B, 0x9902060B.toInt()))
    }

    /** Picks a random photo and cross-fades it, blurred, into [target]. Keeps the old one on failure. */
    fun load(fragment: Fragment, target: ImageView) {
        val owner = fragment.viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val url = runCatching {
                ApiClient.getClient(ApiClientConfig.fromPrefs())
                    .listAssets(page = 1, pageCount = 1, random = true, contentType = ContentType.IMAGE)
                    .getOrNull()?.assets?.firstOrNull()?.let { ApiUtil.getThumbnailUrl(it.id, "preview") }
            }.onFailure { Timber.w(it, "Could not pick a wallpaper") }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (fragment.view == null) return@withContext
                Glide.with(fragment)
                    .load(url)
                    .transform(CenterCrop(), BlurBackgroundTransformation(radius = 10, downscale = 6))
                    .transition(DrawableTransitionOptions.withCrossFade(1200))
                    .into(target)
            }
        }
    }
}
