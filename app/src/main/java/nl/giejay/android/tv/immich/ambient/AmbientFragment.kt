package nl.giejay.android.tv.immich.ambient

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import arrow.core.Either
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.ambient.ui.Ui
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.display.DisplayController
import nl.giejay.android.tv.immich.gallery.GalleryPhoto
import nl.giejay.android.tv.immich.gallery.GalleryRepository
import nl.giejay.android.tv.immich.gallery.GalleryResult
import nl.giejay.android.tv.immich.homeassistant.AmbientFormat
import nl.giejay.android.tv.immich.homeassistant.HaImageLoader
import nl.giejay.android.tv.immich.homeassistant.House
import nl.giejay.android.tv.immich.homeassistant.HouseFeed
import nl.giejay.android.tv.immich.homeassistant.HaFormat
import nl.giejay.android.tv.immich.homeassistant.MdiIcons
import nl.giejay.android.tv.immich.homeassistant.Notice
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_ALBUMS
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_INCLUDE_VIDEOS
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_PEOPLE
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_PINNED_ASSET
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_SHUFFLE
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_SOURCE
import nl.giejay.android.tv.immich.shared.prefs.AMBIENT_STATUS_STRIP
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.EXCLUDE_ASSETS_IN_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.HA_PRINT_STATUS_ENTITY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.ServerPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MERGE_PORTRAIT_PHOTOS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_PAN_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_SCROLL_PANORAMAS
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.model.StaticMetaDataProvider
import nl.giejay.mediaslider.util.LoadMore
import nl.giejay.mediaslider.util.LoadMoreResult
import timber.log.Timber
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The whole app once signed in: one ambient experience instead of a sidebar of tabs.
 *
 *  1. Photos (default): full-bleed slideshow with the status strip ([PhotoOverlay]).
 *  2. Glance ([GlanceView]): OK or Up, 30 s, then back to the photos.
 *  3. Doorbell ([DoorbellView]): opens by itself on a ring, closes 2 min after the last motion.
 *  4. What to show ([SourcesView]): Back from the photos.
 *  5. Settings ([SettingsView]): "More settings" on What to show; Back returns there.
 *
 * The remote is handled here ([handleKey], called by MainActivity before any view sees the key),
 * so the slideshow's own controller overlay never opens.
 */
class AmbientFragment : Fragment(), HouseFeed.Listener, SourcesView.Host, SettingsView.Host {

    private lateinit var ui: Ui
    private lateinit var sliderBox: FrameLayout
    private lateinit var photo: PhotoOverlay
    private lateinit var glance: GlanceView
    private lateinit var doorbell: DoorbellView
    private lateinit var sources: SourcesView
    private lateinit var settings: SettingsView
    private var slider: AmbientSliderView? = null
    private var loadJob: Job? = null

    private var screen = Screen.PHOTOS
    private var feed: HouseFeed? = null
    private var images: HaImageLoader? = null

    private val handler = Handler(Looper.getMainLooper())
    private var glanceOpenedAt = 0L
    private var glanceReason: Takeover? = null
    private var safetyWasActive = false
    private var doorbellOpenedAt = 0L
    private var lastDoorActivity = 0L
    private var lastCameraRefresh = 0L
    private var infoUntil = 0L
    private var printNotice: Notice? = null
    private var printNoticeUntil = 0L
    private var ticks = 0
    /** Sign-in was opened from the settings: come back to them rather than to the photos. */
    private var returnToSettings = false

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        ui = Ui(context, resources.displayMetrics.widthPixels / 1024f)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        sliderBox = FrameLayout(context)
        root.addView(sliderBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        photo = PhotoOverlay(ui)
        glance = GlanceView(ui)
        doorbell = DoorbellView(ui)
        sources = SourcesView(ui, this)
        settings = SettingsView(ui, this)
        listOf(photo, glance, doorbell, sources, settings).forEach {
            root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        photo.stripVisible = PreferenceManager.get(AMBIENT_STATUS_STRIP)
        if (returnToSettings) {
            returnToSettings = false
            sources.open()
            settings.open(ServerPrefScreen)
            show(Screen.SETTINGS)
        } else {
            show(Screen.PHOTOS)
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Timber.i("Loading ambient screen")
        // warm the icon font off the main thread
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { MdiIcons.load(requireContext().applicationContext) }
        loadSlideshow()
    }

    override fun onStart() {
        super.onStart()
        startHouse()
        onTick()
        handler.postDelayed(tick, 1000L)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        stopHouse()
        super.onStop()
    }

    private fun startHouse() {
        feed = HouseFeed(requireContext(), this).also { f ->
            f.start()
            f.client?.let { images = HaImageLoader(it, f.baseUrl, f.token, viewLifecycleOwner.lifecycleScope) }
        }
    }

    private fun stopHouse() {
        feed?.stop()
        feed = null
        images = null
    }

    // The screen sleeping only pauses the slideshow; tearing it down here would leave it frozen.
    override fun onPause() {
        slider?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.PHOTOS) slider?.onResume()
    }

    override fun onDestroyView() {
        settings.onHidden()
        loadJob?.cancel()
        slider?.onDestroy()
        slider = null
        super.onDestroyView()
    }

    // ---- remote ---------------------------------------------------------------------------

    /** Called by MainActivity for every key while this screen is resumed. True = consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val settingsKey = code == KeyEvent.KEYCODE_SETTINGS || event.scanCode == KEY_SETUP_SCANCODE
        if (code !in OWNED_KEYS && !settingsKey) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val repeat = event.repeatCount > 0
        when (screen) {
            Screen.PHOTOS -> when {
                settingsKey || code == KeyEvent.KEYCODE_BACK -> if (!repeat) openSources()
                code == KeyEvent.KEYCODE_DPAD_LEFT -> { photo.hideInfo(); slider?.previous() }
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> { photo.hideInfo(); slider?.next() }
                code == KeyEvent.KEYCODE_DPAD_DOWN -> if (!repeat) togglePhotoInfo()
                else -> if (!repeat) openGlance(null) // OK or Up
            }
            Screen.GLANCE -> when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_UP -> if (!repeat) showPhotos()
                else -> if (settingsKey && !repeat) openSources()
            }
            Screen.DOORBELL -> if (!repeat && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) showPhotos()
            Screen.SOURCES -> if (settingsKey) { if (!repeat) close() } else sources.onKey(code)
            Screen.SETTINGS -> if (settingsKey) { if (!repeat) close() } else settings.onKey(code, repeat)
        }
        return true
    }

    // ---- screens --------------------------------------------------------------------------

    private fun show(next: Screen) {
        // leaving the menus (What to show and Settings; with Back, or because the doorbell took
        // over) applies their changes
        val leavingMenus = screen in MENUS && next !in MENUS
        val reload = leavingMenus && (sources.slideshowChanged || settings.slideshowChanged)
        val reconnect = leavingMenus && settings.houseChanged
        if (leavingMenus) {
            settings.onHidden()
            settings.clearChanges()
        }
        screen = next
        glance.visibility = if (next == Screen.GLANCE) View.VISIBLE else View.GONE
        doorbell.visibility = if (next == Screen.DOORBELL) View.VISIBLE else View.GONE
        sources.visibility = if (next == Screen.SOURCES) View.VISIBLE else View.GONE
        settings.visibility = if (next == Screen.SETTINGS) View.VISIBLE else View.GONE
        photo.visibility = if (next == Screen.PHOTOS) View.VISIBLE else View.GONE
        // the slideshow waits behind the other screens and resumes on return
        if (next == Screen.PHOTOS) slider?.onResume() else slider?.onPause()
        bindAll()
        // countdowns and the clock right away, not at the next tick
        if (this::ui.isInitialized && isAdded) onTick()
        if (leavingMenus) onStatusStripChanged()
        if (reconnect && isStarted()) {
            Timber.i("Home Assistant settings changed, reconnecting")
            stopHouse()
            startHouse()
            bindAll()
        }
        if (reload) loadSlideshow()
    }

    private fun isStarted() = isAdded && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    private fun showPhotos() {
        glanceReason = null
        show(Screen.PHOTOS)
    }

    private fun openGlance(reason: Takeover?) {
        glanceOpenedAt = System.currentTimeMillis()
        glanceReason = reason
        safetyWasActive = safetyActive()
        show(Screen.GLANCE)
    }

    private fun openDoorbell() {
        doorbellOpenedAt = System.currentTimeMillis()
        lastCameraRefresh = 0L
        doorbell.showSnapshot(false, "Loading the camera…")
        show(Screen.DOORBELL) // its first tick loads the camera picture
    }

    private fun openSources() {
        photo.hideInfo()
        sources.open()
        show(Screen.SOURCES)
    }

    private fun togglePhotoInfo() {
        if (photo.infoShowing) {
            photo.hideInfo()
            return
        }
        val item = slider?.current() ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val art = item.id.startsWith(ART_PREFIX)
            val first = if (art) item.get(MetaDataType.DESCRIPTION) else item.get(MetaDataType.DATE)
            val place = if (art) item.get(MetaDataType.ARTIST)?.let { "Photo by $it" }
                        else listOfNotNull(item.get(MetaDataType.CITY), item.get(MetaDataType.COUNTRY)).joinToString(", ").ifBlank { null }
            val people = if (art) null else item.get(MetaDataType.PEOPLE)
            withContext(Dispatchers.Main) {
                if (screen != Screen.PHOTOS) return@withContext
                photo.showInfo(first, place, people)
                infoUntil = System.currentTimeMillis() + INFO_MS
            }
        }
    }

    // ---- clock and timers -----------------------------------------------------------------

    private fun onTick() {
        if (!isAdded) return
        val now = System.currentTimeMillis()
        val date = Date(now)
        val timeText = SimpleDateFormat(if (DateFormat.is24HourFormat(requireContext())) "H:mm" else "h:mm", Locale.US).format(date)
        val dateText = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(date)
        photo.bindClock(timeText, dateText)
        glance.bindClock(timeText, dateText)
        doorbell.bindClock(timeText)

        if (photo.infoShowing && now > infoUntil) photo.hideInfo()
        if (printNotice != null && now > printNoticeUntil) printNotice = null

        when (screen) {
            Screen.GLANCE -> {
                val active = safetyActive()
                if (safetyWasActive && !active) glanceOpenedAt = now // the alarm cleared: count down from now
                safetyWasActive = active
                val closeAt = TakeoverRules.glanceCloseAt(glanceOpenedAt, active, glanceReason)
                if (closeAt != null && now >= closeAt) {
                    showPhotos()
                    return
                }
                glance.bindFooter(closeAt?.let { "Back to photos in ${TakeoverRules.secondsLeft(it, now)} s" } ?: "Press Back to return to the photos")
            }
            Screen.DOORBELL -> {
                if (now >= TakeoverRules.doorbellCloseAt(maxOf(lastDoorActivity, doorbellOpenedAt))) {
                    showPhotos()
                    return
                }
                if (now - lastCameraRefresh >= CAMERA_REFRESH_MS) refreshCamera()
            }
            else -> Unit
        }
        // relative times ("4 min ago") move on even when nothing in the house changes
        if (++ticks % 15 == 0) bindAll()
    }

    private fun safetyActive(): Boolean {
        val house = feed?.house() ?: return false
        return (house.smokeAlarms + house.leakSensors).any { it.second.state == "on" }
    }

    // ---- house ----------------------------------------------------------------------------

    override fun onHouseChanged() {
        if (isAdded) bindAll()
    }

    override fun onDoorActivity() {
        lastDoorActivity = System.currentTimeMillis()
    }

    override fun onTakeover(takeover: Takeover) {
        val f = feed ?: return
        if (takeover == Takeover.PRINT_FINISHED) rememberPrint(f)
        val quiet = TakeoverRules.isQuiet(f.quietState(), f.quietConfigured, DisplayController.isQuietHours())
        if (!TakeoverRules.shouldOpen(takeover, screen, quiet)) {
            Timber.i("Not opening %s over %s (quiet hours: %s)", takeover, screen, quiet)
            return
        }
        Timber.i("Opening %s", takeover)
        ImmichApplication.activitySensor?.wakeForTakeover()
        when (takeover) {
            Takeover.DOORBELL -> openDoorbell()
            Takeover.SAFETY, Takeover.PRINT_FINISHED -> openGlance(takeover)
        }
    }

    private fun rememberPrint(f: HouseFeed) {
        val e = f.entity(PreferenceManager.get(HA_PRINT_STATUS_ENTITY).trim()) ?: return
        val printer = AmbientFormat.stripSuffix(AmbientFormat.stripSuffix(e.friendlyName, "print status"), "status")
        printNotice = Notice("print", HaFormat.iconName(e), "$printer · print finished")
        printNoticeUntil = System.currentTimeMillis() + PRINT_NOTICE_MS
    }

    private fun bindAll() {
        if (!this::ui.isInitialized) return
        val f = feed
        val house = f?.house() ?: House(emptyList(), emptyMap())
        val now = Instant.now()
        val notices = buildList {
            printNotice?.let { add(it) }
            addAll(house.notices(f?.persistent?.values.orEmpty()))
            // a connection problem is worth a quiet line, but only when Home Assistant is set up
            f?.problem?.takeIf { f.configured }?.let { add(Notice("problem", "home-alert-outline", it)) }
        }
        // Who is home is left to the glance screen; the photo screen only carries the indoor temperature.
        val indoorLine = house.indoorTemperature()?.let { "$it inside" }
        photo.bindHouse(house.outsideNow(), indoorLine)
        val presence = house.presence()
        if (screen == Screen.GLANCE) {
            glance.bind(house.statusLines(), notices, house.outside(f?.forecast, LocalDate.now(), ZoneId.systemDefault()),
                house.frontDoor(now), house.rooms())
        }
        if (screen == Screen.DOORBELL) {
            val motion = house.motion
            val seen = motion?.let { m ->
                val person = m.attrString("event_type")?.contains("person") == true
                (if (person) "Person seen" else "Motion") to (House.lastFired(m)?.let { AmbientFormat.ago(it, now) } ?: "—")
            }
            val camera = house.doorbellCamera()
            doorbell.bind(
                AmbientFormat.rang(house.doorbell?.let { House.lastFired(it) }, now),
                camera?.let { AmbientFormat.stripSuffix(it.friendlyName, "doorbell") },
                seen,
                presence?.let { it.label to it.names }
            )
        }
    }

    /**
     * The doorbell camera's snapshot (its entity_picture, a camera_proxy URL), refreshed every few
     * seconds while the doorbell view is open. Not a live stream: KitKat has no WebRTC, and a Nest
     * doorbell is WebRTC-only, so Home Assistant answers with a placeholder picture for it.
     */
    private fun refreshCamera() {
        lastCameraRefresh = System.currentTimeMillis()
        val camera = feed?.house()?.doorbellCamera()
        val picture = camera?.attrString("entity_picture")
        val loader = images
        if (camera == null || picture == null || loader == null) {
            doorbell.showSnapshot(false, if (camera == null) "No doorbell camera found" else "Camera picture unavailable")
            return
        }
        val bust = (if (picture.contains('?')) "&" else "?") + "_t=" + System.currentTimeMillis()
        loader.load(picture + bust, doorbell.cameraImage, resources.displayMetrics.widthPixels) { ok ->
            if (ok) doorbell.showSnapshot(true, null)
            else if (doorbell.cameraImage.drawable == null) doorbell.showSnapshot(false, "Camera picture unavailable")
        }
    }

    // ---- slideshow ------------------------------------------------------------------------

    private fun sourceSettings() = SourceSettings(
        source = PhotoSource.parse(PreferenceManager.get(AMBIENT_SOURCE)),
        albums = PreferenceManager.get(AMBIENT_ALBUMS).toList(),
        people = PreferenceManager.get(AMBIENT_PEOPLE).toList(),
        pinnedAsset = PreferenceManager.get(AMBIENT_PINNED_ASSET),
        shuffle = PreferenceManager.get(AMBIENT_SHUFFLE),
        includeVideos = PreferenceManager.get(AMBIENT_INCLUDE_VIDEOS),
        excludedAlbums = PreferenceManager.get(EXCLUDE_ASSETS_IN_ALBUM)
    )

    private fun loadSlideshow() {
        loadJob?.cancel()
        photo.showMessage(null)
        val settings = sourceSettings()
        loadJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                if (settings.source == PhotoSource.ART) artSlides() else immichSlides(settings)
            } catch (e: Exception) {
                Timber.e(e, "Could not load the slideshow")
                Either.Left(PhotoSourceLoader.COULD_NOT_LOAD)
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    { message ->
                        replaceSlider(null, null)
                        photo.showMessage(message)
                    },
                    { (items, more) -> replaceSlider(items, more) }
                )
            }
        }
    }

    private suspend fun immichSlides(settings: SourceSettings): Either<String, Pair<List<SliderItemViewHolder>, LoadMore?>> {
        val merge = PreferenceManager.get(SLIDER_MERGE_PORTRAIT_PHOTOS)
        val keepOrder = !settings.shuffle
        return PhotoSourceLoader(ApiClient.getClient(ApiClientConfig.fromPrefs()), settings).load().map { batch ->
            val more: LoadMore? = batch.more?.let { fetch ->
                suspend {
                    fetch().fold(
                        { LoadMoreResult(emptyList(), false) },
                        { LoadMoreResult(it.assets.toSliderItems(keepOrder, merge), it.canLoadMore) }
                    )
                }
            }
            batch.assets.toSliderItems(keepOrder, merge) to more
        }
    }

    private suspend fun artSlides(): Either<String, Pair<List<SliderItemViewHolder>, LoadMore?>> =
        when (val batch = GalleryRepository.loadBatch()) {
            is GalleryResult.Photos -> if (batch.photos.isEmpty()) Either.Left(PhotoSourceLoader.NOTHING_FOUND) else Either.Right(
                batch.photos.map { it.toSlide() } to suspend {
                    when (val more = GalleryRepository.loadBatch()) {
                        is GalleryResult.Photos -> LoadMoreResult(more.photos.map { it.toSlide() }, more.photos.isNotEmpty())
                        else -> LoadMoreResult(emptyList(), false)
                    }
                }
            )
            GalleryResult.NoKey -> Either.Left("Add an Unsplash access key in More settings → Art gallery")
            GalleryResult.BadKey -> Either.Left("Unsplash rejected the access key")
            GalleryResult.RateLimited -> Either.Left("Unsplash is rate limiting; try again later")
            GalleryResult.Error -> Either.Left("Couldn't reach Unsplash")
        }

    private fun GalleryPhoto.toSlide() = SliderItemViewHolder(
        SliderItem(
            id = ART_PREFIX + id,
            url = imageUrl,
            type = SliderItemType.IMAGE,
            orientation = 1,
            metaData = mapOf(
                MetaDataType.DESCRIPTION to StaticMetaDataProvider(title),
                MetaDataType.ARTIST to StaticMetaDataProvider(artist)
            ),
            // The slider shows thumbnailUrl instead of url when "Use high resolution thumbnails" is
            // on (the default). For Immich that's the ~1440px preview; Unsplash has no such middle
            // size, so hand it the screen-sized image rather than a small one that gets upscaled.
            thumbnailUrl = imageUrl,
            isPanorama = false
        )
    )

    private fun replaceSlider(items: List<SliderItemViewHolder>?, more: LoadMore?) {
        slider?.let {
            it.onDestroy()
            sliderBox.removeView(it)
        }
        slider = null
        if (items.isNullOrEmpty()) return
        val view = AmbientSliderView(requireContext())
        view.setDefaultExoFactory(
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("x-api-key" to PreferenceManager.get(API_KEY)))
        )
        sliderBox.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.loadMediaSliderView(
            MediaSliderConfiguration(
                startPosition = 0,
                interval = PreferenceManager.get(AMBIENT_INTERVAL),
                isOnlyUseThumbnails = PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                isVideoSoundEnable = false,
                items = items,
                loadMore = more,
                animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
                maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
                maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
                glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
                // no metadata, date or gradient from the slider: the status strip replaces them
                gradiantOverlay = false,
                enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
                metaDataConfig = emptyList(),
                zoomAndScrollPanorama = PreferenceManager.get(SLIDER_ZOOM_SCROLL_PANORAMAS),
                zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
                panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT),
                useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO)
            )
        )
        view.toggleSlideshow(false)
        if (screen != Screen.PHOTOS || !isResumed) view.onPause()
    }

    // ---- SourcesView.Host -----------------------------------------------------------------

    override fun loadPicks(source: PhotoSource, done: (List<Pickable>?) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val client = ApiClient.getClient(ApiClientConfig.fromPrefs())
            val result: List<Pickable>? = when (source) {
                PhotoSource.ALBUMS -> client.listAlbums().fold({ null }, { albums ->
                    albums.sortedByDescending { it.updatedAt }.map { Pickable(it.id, it.albumName) }
                })
                PhotoSource.PEOPLE -> client.listPeople().fold({ null }, { people -> people.map { Pickable(it.id.toString(), it.name.orEmpty()) } })
                else -> emptyList()
            }
            withContext(Dispatchers.Main) { done(result) }
        }
    }

    override fun currentAssetId(): String? = slider?.current()?.id?.takeUnless { it.startsWith(ART_PREFIX) }

    override fun openMoreSettings() {
        settings.open()
        show(Screen.SETTINGS)
    }

    override fun close() = showPhotos()

    // ---- SettingsView.Host ----------------------------------------------------------------

    override val actionContext: Context get() = requireContext()

    override fun closeSettings() {
        sources.refresh()
        show(Screen.SOURCES)
    }

    override fun openSignIn() {
        // the slideshow and Home Assistant restart with this screen's view when sign-in returns
        returnToSettings = true
        settings.onHidden()
        findNavController().navigate(R.id.action_global_signInFragment)
    }

    override fun onStatusStripChanged() {
        photo.stripVisible = PreferenceManager.get(AMBIENT_STATUS_STRIP)
    }

    private companion object {
        /** The frame remote's Settings button: scancode 141 (KEY_SETUP), unmapped in its keylayout. */
        const val KEY_SETUP_SCANCODE = 141
        const val ART_PREFIX = "unsplash-"
        const val INFO_MS = 12_000L
        const val CAMERA_REFRESH_MS = 5_000L
        const val PRINT_NOTICE_MS = 60 * 60_000L
        val MENUS = setOf(Screen.SOURCES, Screen.SETTINGS)
        val OWNED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK
        )
    }
}
