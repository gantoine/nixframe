package nl.giejay.android.tv.immich.timeline

import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_DPAD_SEEK_IN_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_PAN_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_SHOW_DATE_TOP_LEFT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_SCROLL_PANORAMAS
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType

/**
 * Slider configuration for playing one memory: its bounded asset set from the start, auto-playing,
 * no [nl.giejay.mediaslider.util.LoadMore]. Shared by the Timeline memories row and the Memories page.
 */
fun Memory.toSliderConfiguration(): MediaSliderConfiguration {
    val sliderItems = assets.toSliderItems(
        keepOrder = true,
        // Memory payloads omit EXIF/dimensions; portrait pairing is unreliable and can
        // bind the wrong asset's city/country into the details strip.
        mergePortrait = false
    )
    return MediaSliderConfiguration(
        0,
        PreferenceManager.get(SLIDER_INTERVAL),
        PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
        isVideoSoundEnable = true,
        sliderItems,
        null,
        animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
        maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
        maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
        glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
        enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
        gradiantOverlay = false,
        metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
            .filter { it.type != MetaDataType.MEDIA_COUNT },
        zoomAndScrollPanorama = PreferenceManager.get(SLIDER_ZOOM_SCROLL_PANORAMAS),
        zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
        panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT),
        useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
        dpadSeeksInVideo = PreferenceManager.get(SLIDER_DPAD_SEEK_IN_VIDEO),
        showDateTopLeft = PreferenceManager.get(SLIDER_SHOW_DATE_TOP_LEFT)
    )
}
