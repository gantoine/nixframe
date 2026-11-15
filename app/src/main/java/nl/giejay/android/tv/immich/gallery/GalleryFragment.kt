package nl.giejay.android.tv.immich.gallery

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.GALLERY_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.GALLERY_QUERY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_PAN_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_EFFECT
import nl.giejay.android.tv.immich.slider.ImmichMediaSliderArgs
import nl.giejay.mediaslider.adapter.AlignOption
import nl.giejay.mediaslider.adapter.MetaDataSliderItem
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.model.StaticMetaDataProvider
import nl.giejay.mediaslider.util.LoadMoreResult
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import timber.log.Timber

/**
 * Sidebar page that shows photos from Unsplash: a full-bleed preview that cross-fades through the
 * current batch, showing each photo's title and artist, and a Play button that starts the regular
 * slideshow (autoplaying, with the same controls as Immich photos) at the photo on screen.
 */
class GalleryFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    // Required so this fragment can be hosted as a page row inside HomeFragment's BrowseSupportFragment.
    private val mainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    override fun getMainFragmentAdapter() = mainFragmentAdapter

    private var photos: List<GalleryPhoto> = emptyList()
    private var heroIndex = 0
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    /** Whether [front] (rather than [back]) is the image currently visible. */
    private var frontShowing = false

    private val rotateHero = object : Runnable {
        override fun run() {
            if (photos.size > 1) showHero((heroIndex + 1) % photos.size)
            view?.postDelayed(this, HERO_ROTATE_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_gallery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainFragmentAdapter.fragmentHost?.notifyDataReady(mainFragmentAdapter)
        front = view.findViewById(R.id.gallery_hero_front)
        back = view.findViewById(R.id.gallery_hero_back)
        front.alpha = 0f
        view.findViewById<View>(R.id.gallery_play).setOnClickListener { play() }

        // Small caps label above the title: the search term, or nothing when searching everything.
        val query = PreferenceManager.get(GALLERY_QUERY).trim()
        view.findViewById<TextView>(R.id.gallery_label).apply {
            text = query
            visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        }

        showStatus(getString(R.string.gallery_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = GalleryRepository.loadBatch()
            if (!isAdded) return@launch
            when (result) {
                is GalleryResult.Photos -> if (result.photos.isEmpty()) {
                    showStatus(getString(R.string.gallery_error))
                } else {
                    photos = result.photos
                    view.findViewById<View>(R.id.gallery_status).visibility = View.GONE
                    view.findViewById<View>(R.id.gallery_content).visibility = View.VISIBLE
                    showHero(0)
                    restartRotation()
                }
                GalleryResult.NoKey -> showStatus(getString(R.string.gallery_no_key))
                GalleryResult.BadKey -> showStatus(getString(R.string.gallery_bad_key))
                GalleryResult.RateLimited -> showStatus(getString(R.string.gallery_rate_limited))
                GalleryResult.Error -> showStatus(getString(R.string.gallery_error))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        restartRotation()
    }

    override fun onPause() {
        super.onPause()
        view?.removeCallbacks(rotateHero)
    }

    private fun restartRotation() {
        val v = view ?: return
        v.removeCallbacks(rotateHero)
        if (photos.size > 1) v.postDelayed(rotateHero, HERO_ROTATE_MS)
    }

    private fun showStatus(text: String) {
        val v = view ?: return
        v.findViewById<TextView>(R.id.gallery_status).apply {
            this.text = text
            visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.gallery_content).visibility = View.GONE
    }

    /**
     * Cross-fades to photo [index] with two image views at fixed depth ([front] above [back]):
     * whichever one is currently hidden gets the new photo, then [front] fades in (or out, to reveal
     * [back]). Glide therefore only ever replaces the bitmap of a view that isn't on screen.
     */
    private fun showHero(index: Int) {
        val photo = photos.getOrNull(index) ?: return
        val v = view ?: return
        heroIndex = index
        val target = if (frontShowing) back else front
        Glide.with(this)
            .load(photo.imageUrl)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    Timber.w(e, "Could not load gallery photo ${photo.id}")
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    if (!isAdded || heroIndex != index) return false
                    v.findViewById<TextView>(R.id.gallery_title).text = photo.title
                    v.findViewById<TextView>(R.id.gallery_artist).apply {
                        text = photo.artist.orEmpty()
                        visibility = if (photo.artist == null) View.GONE else View.VISIBLE
                    }
                    frontShowing = !frontShowing
                    front.animate().alpha(if (frontShowing) 1f else 0f).setDuration(CROSSFADE_MS).start()
                    return false
                }
            })
            .into(target)
        // Warm the cache for the next photo so the following cross-fade starts promptly.
        photos.getOrNull((index + 1) % photos.size)?.let { Glide.with(this).load(it.imageUrl).preload() }
    }

    private fun play() {
        if (photos.isEmpty()) return
        val config = MediaSliderConfiguration(
            startPosition = heroIndex,
            interval = PreferenceManager.get(GALLERY_INTERVAL),
            isOnlyUseThumbnails = false,
            isVideoSoundEnable = false,
            items = photos.map { it.toSliderItem() },
            loadMore = {
                when (val more = GalleryRepository.loadBatch()) {
                    is GalleryResult.Photos -> LoadMoreResult(more.photos.map { it.toSliderItem() }, more.photos.isNotEmpty())
                    else -> LoadMoreResult(emptyList(), false)
                }
            },
            animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
            maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
            maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
            glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
            gradiantOverlay = true,
            enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
            // Credit: the work's title, then its artist, bottom right.
            metaDataConfig = listOf(
                MetaDataSliderItem(MetaDataType.DESCRIPTION, AlignOption.RIGHT, fontSize = 22),
                MetaDataSliderItem(MetaDataType.ARTIST, AlignOption.RIGHT, fontSize = 16)
            ),
            zoomAndScrollPanorama = false,
            zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
            panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT)
        )
        ViewModelProvider(requireActivity())[MediaSliderViewModel::class.java].configuration = config
        findNavController().navigate(
            R.id.action_to_photo_slider,
            ImmichMediaSliderArgs(timelineView = false, allowFavorite = false, autoPlay = true).toBundle()
        )
    }

    private fun GalleryPhoto.toSliderItem() = SliderItemViewHolder(
        SliderItem(
            id = "unsplash-$id",
            url = imageUrl,
            type = SliderItemType.IMAGE,
            orientation = 1,
            metaData = mapOf(
                MetaDataType.DESCRIPTION to StaticMetaDataProvider(title),
                MetaDataType.ARTIST to StaticMetaDataProvider(artist)
            ),
            thumbnailUrl = thumbnailUrl,
            isPanorama = false
        )
    )

    companion object {
        private const val HERO_ROTATE_MS = 12_000L
        private const val CROSSFADE_MS = 1_200L
    }
}
