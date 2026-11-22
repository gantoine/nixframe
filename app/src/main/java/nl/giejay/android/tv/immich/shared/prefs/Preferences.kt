package nl.giejay.android.tv.immich.shared.prefs;

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import nl.giejay.android.tv.immich.BuildConfig
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.display.DisplayController
import nl.giejay.android.tv.immich.display.NightSchedule
import nl.giejay.android.tv.immich.display.ScreenOffSchedule
import nl.giejay.android.tv.immich.security.ApiKeyCipher
import nl.giejay.mediaslider.transformations.GlideTransformations
import timber.log.Timber
import java.util.Collections.emptySet

// general
data object DISABLE_SSL_VERIFICATION : BooleanPref(false,
    ImmichApplication.appContext!!.getString(R.string.disable_ssl),
    ImmichApplication.appContext!!.getString(R.string.disable_ssl_text)) {
    override fun key() = "disableSSLVerification"
}

data object API_KEY : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.api_key_text),
    ImmichApplication.appContext!!.getString(R.string.api_key_desc)) {
    override fun key() = "apiKey"
    override val secret = true
    override val editor = TextEditor.SIGN_IN

    // Encrypted at rest via ApiKeyCipher; fromPrefValue also needs the override since
    // PreferenceManager's eager subscription reads raw values through it, bypassing getValue().
    // Legacy-plaintext migration happens once in PreferenceManager.init(), not here - this stays
    // a pure decrypt so it's safe to call from a reactive callback.
    override fun getValue(sharedPreferences: SharedPreferences): String =
        fromPrefValue(sharedPreferences.getString(key(), defaultValue) ?: defaultValue)

    override fun fromPrefValue(prefValue: String): String = ApiKeyCipher.decryptOrAdoptLegacy(prefValue)

    override fun save(sharedPreferences: SharedPreferences, value: String) {
        super.save(sharedPreferences, toPrefValue(value))
    }

    override fun toPrefValue(value: String): String = ApiKeyCipher.encrypt(value)
}

data object HOST_NAME : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.server_url_text),
    ImmichApplication.appContext!!.getString(R.string.server_url_desc)) {
    override fun key() = "hostName"
    override val editor = TextEditor.SIGN_IN

    override fun save(sharedPreferences: SharedPreferences, value: String) {
        super.save(sharedPreferences, value.replace(Regex("/$"), ""))
    }
}

// wakelock (Nixplay frame motion sensor): value in minutes, 0 = always on
data object WAKE_LOCK_MINUTES : IntListPref(15,
    ImmichApplication.appContext!!.getString(R.string.wake_lock),
    ImmichApplication.appContext!!.getString(R.string.wake_lock_desc),
    R.array.wake_lock_minutes_titles,
    R.array.wake_lock_minutes_values,
    NumberFormat.MINUTES)

data object SCREENSAVER_ANIMATE_ASSET_SLIDE : BooleanPref(true,
    ImmichApplication.appContext!!.getString(R.string.slide_new_asset_in),
    ImmichApplication.appContext!!.getString(R.string.slide_new_asset_in_desc))

data object SLIDER_ANIMATION_SPEED : IntListPref(0,
    ImmichApplication.appContext!!.getString(R.string.slide_animation_speed_ms),
    ImmichApplication.appContext!!.getString(R.string.slide_animation_speed_desc),
    R.array.animation_speed_ms,
    R.array.animation_speed_ms,
    NumberFormat.MILLIS)

data object SLIDER_ONLY_USE_THUMBNAILS : BooleanPref(true,
    ImmichApplication.appContext!!.getString(R.string.use_hd_thumbnails),
    ImmichApplication.appContext!!.getString(R.string.use_hd_thumbnails_text))

data object SLIDER_MERGE_PORTRAIT_PHOTOS : BooleanPref(true,
    ImmichApplication.appContext!!.getString(R.string.merge_portrait_photos),
    ImmichApplication.appContext!!.getString(R.string.merge_portrait_photos_desc))

data object SLIDER_MAX_CUT_OFF_WIDTH : IntSeekbarPref(20,
    ImmichApplication.appContext!!.getString(R.string.safe_center_crop_max_cutoff_width),
    ImmichApplication.appContext!!.getString(R.string.safe_center_crop_max_cutoff_width_desc))

data object SLIDER_MAX_CUT_OFF_HEIGHT : IntSeekbarPref(20,
    ImmichApplication.appContext!!.getString(R.string.safe_center_crop_max_cutoff_height),
    ImmichApplication.appContext!!.getString(R.string.safe_center_crop_max_cutoff_height_desc))

data object SLIDER_GLIDE_TRANSFORMATION : EnumPref<GlideTransformations>(GlideTransformations.CENTER_INSIDE,
    ImmichApplication.appContext!!.getString(R.string.photo_transformation),
    ImmichApplication.appContext!!.getString(R.string.photo_transformation_desc),
    R.array.glide_transformation_labels,
    R.array.glide_transformation_keys) {
    override fun fromPrefValue(prefValue: String): GlideTransformations {
        return GlideTransformations.valueOfSafe(prefValue, defaultValue)
    }
}

data object SLIDER_ZOOM_SCROLL_PANORAMAS : BooleanPref(false,
    ImmichApplication.appContext!!.getString(R.string.zoom_scroll_panoramas),
    ImmichApplication.appContext!!.getString(R.string.zoom_scroll_panoramas_desc))

data object SLIDER_ZOOM_EFFECT : IntSeekbarPref(50,
    ImmichApplication.appContext!!.getString(R.string.zoom_effect_percent),
    ImmichApplication.appContext!!.getString(R.string.zoom_effect_percent_desc))

data object SLIDER_PAN_EFFECT : IntSeekbarPref(50,
    ImmichApplication.appContext!!.getString(R.string.pan_effect_percent),
    ImmichApplication.appContext!!.getString(R.string.pan_effect_percent_desc))

data object SLIDER_FORCE_ORIGINAL_VIDEO : BooleanPref(false,
    ImmichApplication.appContext!!.getString(R.string.force_original_video),
    ImmichApplication.appContext!!.getString(R.string.force_original_video_desc))

data object SLIDER_LOAD_EDITED_PHOTO : BooleanPref(false,
    ImmichApplication.appContext!!.getString(R.string.load_edited_photo),
    ImmichApplication.appContext!!.getString(R.string.load_edited_photo_desc))

// other
data object DEBUG_MODE : BooleanPref(false,
    ImmichApplication.appContext!!.getString(R.string.enable_debug_mode),
    ImmichApplication.appContext!!.getString(R.string.enable_debug_mode_desc))

/** A random ID made on first start (upstream used it for Crashlytics). Shown, not changed. */
data object USER_ID : StringPref("", ImmichApplication.appContext!!.getString(R.string.user_id),
    ImmichApplication.appContext!!.getString(R.string.user_id_desc)) {
    override fun key() = "user_id"
    override val editor = TextEditor.NONE
}

// gallery (Unsplash)
/** Unsplash access key; encrypted at rest like [API_KEY]. Blank = use the build-time key, if any. */
data object GALLERY_UNSPLASH_KEY : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.gallery_unsplash_key),
    ImmichApplication.appContext!!.getString(R.string.gallery_unsplash_key_desc)) {
    override fun key() = "galleryUnsplashKey"
    override val secret = true
    override val builtIn: String get() = BuildConfig.UNSPLASH_ACCESS_KEY.trim()
    override fun getValue(sharedPreferences: SharedPreferences): String =
        fromPrefValue(sharedPreferences.getString(key(), defaultValue) ?: defaultValue)
    override fun fromPrefValue(prefValue: String): String = ApiKeyCipher.decryptOrAdoptLegacy(prefValue)
    override fun save(sharedPreferences: SharedPreferences, value: String) {
        super.save(sharedPreferences, toPrefValue(value))
    }
    override fun toPrefValue(value: String): String = ApiKeyCipher.encrypt(value)
}
data object GALLERY_QUERY : StringPref("nature",
    ImmichApplication.appContext!!.getString(R.string.gallery_query),
    ImmichApplication.appContext!!.getString(R.string.gallery_query_desc)) {
    override fun key() = "galleryQuery"
    override val blankLabel = "Anything"
}
// Home Assistant (glance, doorbell, status strip). Blank = the build-time default from local.properties, if any.
data object HA_URL : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.ha_url),
    ImmichApplication.appContext!!.getString(R.string.ha_url_desc)) {
    override fun key() = "haUrl"
    override val builtIn: String get() = BuildConfig.HA_URL.trim()
}
/** Long-lived access token; encrypted at rest like [API_KEY]. */
data object HA_TOKEN : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.ha_token),
    ImmichApplication.appContext!!.getString(R.string.ha_token_desc)) {
    override fun key() = "haToken"
    override val secret = true
    override val builtIn: String get() = BuildConfig.HA_TOKEN.trim()
    override fun getValue(sharedPreferences: SharedPreferences): String =
        fromPrefValue(sharedPreferences.getString(key(), defaultValue) ?: defaultValue)
    override fun fromPrefValue(prefValue: String): String = ApiKeyCipher.decryptOrAdoptLegacy(prefValue)
    override fun save(sharedPreferences: SharedPreferences, value: String) {
        super.save(sharedPreferences, toPrefValue(value))
    }
    override fun toPrefValue(value: String): String = ApiKeyCipher.encrypt(value)
}
data object HA_DASHBOARD : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.ha_dashboard),
    ImmichApplication.appContext!!.getString(R.string.ha_dashboard_desc)) {
    override fun key() = "haDashboard"
    override val builtIn: String get() = BuildConfig.HA_DASHBOARD.trim()
    override val blankLabel = "Default"
}

// display (auto-dim + overnight screen-off), see display.DisplayController
/** Where sunset and sunrise are computed for. Keeps the old weather-location key. */
data object DISPLAY_LOCATION : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.display_location),
    ImmichApplication.appContext!!.getString(R.string.display_location_desc)) {
    override fun key() = "weatherLocation"
}
data object DISPLAY_AUTO_DIM : BooleanPref(true,
    ImmichApplication.appContext!!.getString(R.string.display_auto_dim),
    ImmichApplication.appContext!!.getString(R.string.display_auto_dim_desc)) {
    override fun key() = "displayAutoDim"
}
data object DISPLAY_NIGHT_SCHEDULE : EnumByTitlePref<NightSchedule>(NightSchedule.SUNSET_TO_SUNRISE,
    ImmichApplication.appContext!!.getString(R.string.display_night_schedule),
    ImmichApplication.appContext!!.getString(R.string.display_night_schedule_desc)) {
    override fun key() = "displayNightSchedule"
    override fun fromPrefValue(prefValue: String): NightSchedule =
        NightSchedule.entries.firstOrNull { it.name == prefValue } ?: defaultValue
    override fun getEnumEntries(): Array<NightSchedule> = NightSchedule.entries.toTypedArray()
}
data object DISPLAY_DAY_BRIGHTNESS : IntListPref(90,
    ImmichApplication.appContext!!.getString(R.string.display_day_brightness),
    ImmichApplication.appContext!!.getString(R.string.display_day_brightness_desc),
    R.array.display_day_brightness_titles,
    R.array.display_day_brightness_values) {
    override fun key() = "displayDayBrightness"
}
data object DISPLAY_NIGHT_BRIGHTNESS : IntListPref(-35,
    ImmichApplication.appContext!!.getString(R.string.display_night_brightness),
    ImmichApplication.appContext!!.getString(R.string.display_night_brightness_desc),
    R.array.display_night_brightness_titles,
    R.array.display_night_brightness_values) {
    override fun key() = "displayNightBrightness"
}
data object DISPLAY_PREVIEW_NIGHT : ActionPref("displayPreviewNight",
    ImmichApplication.appContext!!.getString(R.string.display_preview_night),
    ImmichApplication.appContext!!.getString(R.string.display_preview_night_desc),
    { DisplayController.previewNight() })
data object DISPLAY_SCREEN_OFF : EnumByTitlePref<ScreenOffSchedule>(ScreenOffSchedule.NEVER,
    ImmichApplication.appContext!!.getString(R.string.display_screen_off),
    ImmichApplication.appContext!!.getString(R.string.display_screen_off_desc)) {
    override fun key() = "displayScreenOff"
    override fun fromPrefValue(prefValue: String): ScreenOffSchedule =
        ScreenOffSchedule.entries.firstOrNull { it.name == prefValue } ?: defaultValue
    override fun getEnumEntries(): Array<ScreenOffSchedule> = ScreenOffSchedule.entries.toTypedArray()
}
// the display location resolved to "location\tlatitude\tlongitude\ttimezone" for sun times
data object DISPLAY_GEO_CACHE : StringPref("", "", "") {
    override fun key() = "displayGeoCache"
}

/** Albums whose photos never show. Set before the redesign; there is no picker for it any more. */
data object EXCLUDE_ASSETS_IN_ALBUM : StringSetPref(emptySet(), "", "")

/** Opens Android's own settings app (hidden where there is none). */
data object ANDROID_SETTINGS : ActionPref("androidSettings",
    ImmichApplication.appContext!!.getString(R.string.android_settings_open),
    ImmichApplication.appContext!!.getString(R.string.android_settings_desc),
    { host ->
        try {
            host.actionContext.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Timber.e(e, "No system settings app available")
        }
    },
    { context -> Intent(Settings.ACTION_SETTINGS).resolveActivity(context.packageManager) != null })

// The settings screen's sections, in order (ambient.SettingsView draws them from these).
data object ServerPrefScreen : PrefScreen(ImmichApplication.appContext!!.getString(R.string.server), "server",
    listOf(PrefCategory("", listOf(HOST_NAME, API_KEY))))

data object SlideshowPrefScreen : PrefScreen(ImmichApplication.appContext!!.getString(R.string.slideshow_settings), "slideshow",
    listOf(
        PrefCategory("", listOf(
            AMBIENT_INTERVAL,
            AMBIENT_SHUFFLE,
            AMBIENT_STATUS_STRIP,
            AMBIENT_INCLUDE_VIDEOS,
            SLIDER_ANIMATION_SPEED,
            SCREENSAVER_ANIMATE_ASSET_SLIDE,
            SLIDER_MERGE_PORTRAIT_PHOTOS,
            SLIDER_ONLY_USE_THUMBNAILS,
            SLIDER_LOAD_EDITED_PHOTO,
            SLIDER_FORCE_ORIGINAL_VIDEO,
            SLIDER_GLIDE_TRANSFORMATION,
            SLIDER_ZOOM_SCROLL_PANORAMAS,
            SLIDER_ZOOM_EFFECT,
            SLIDER_PAN_EFFECT,
            SLIDER_MAX_CUT_OFF_WIDTH,
            SLIDER_MAX_CUT_OFF_HEIGHT,
            WAKE_LOCK_MINUTES
        ))
    ),
    SettingsEffect.SLIDESHOW
)

data object DisplayPrefScreen : PrefScreen(ImmichApplication.appContext!!.getString(R.string.display_brightness), "display_brightness",
    listOf(PrefCategory("", listOf(DISPLAY_AUTO_DIM, DISPLAY_LOCATION, DISPLAY_NIGHT_SCHEDULE, DISPLAY_DAY_BRIGHTNESS,
        DISPLAY_NIGHT_BRIGHTNESS, DISPLAY_PREVIEW_NIGHT, DISPLAY_SCREEN_OFF))))

data object HomeAssistantPrefScreen : PrefScreen(ImmichApplication.appContext!!.getString(R.string.home_assistant), "home_assistant",
    listOf(PrefCategory("", listOf(HA_URL, HA_DASHBOARD, HA_TOKEN, HA_QUIET_HOURS_ENTITY, HA_PRINT_STATUS_ENTITY))),
    SettingsEffect.HOME_ASSISTANT)

data object GalleryPrefScreen : PrefScreen(ImmichApplication.appContext!!.getString(R.string.gallery), "gallery",
    listOf(PrefCategory("", listOf(GALLERY_QUERY, GALLERY_UNSPLASH_KEY))),
    SettingsEffect.SLIDESHOW)

data object DebugPrefScreen : PrefScreen(ImmichApplication.appContext!!.getString(R.string.debug), "debug",
    listOf(PrefCategory("", listOf(DEBUG_MODE, USER_ID))))

data object AndroidPrefScreen : PrefScreen(ImmichApplication.appContext!!.getString(R.string.android_settings), "android",
    listOf(PrefCategory("", listOf(ANDROID_SETTINGS))))

/** The settings screen's sections, top to bottom. */
val SETTINGS_SECTIONS: List<PrefScreen> get() = listOf(
    ServerPrefScreen, SlideshowPrefScreen, DisplayPrefScreen, HomeAssistantPrefScreen, GalleryPrefScreen, DebugPrefScreen, AndroidPrefScreen
)
