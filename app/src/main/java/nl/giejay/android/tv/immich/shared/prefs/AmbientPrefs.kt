package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R

// Ambient mode: what the photo screen shows. Chosen on the "What to show" screen.

/** [nl.giejay.android.tv.immich.ambient.PhotoSource] name. */
data object AMBIENT_SOURCE : StringPref("ALBUMS", "", "") {
    override fun key() = "ambientSource"
}

/** Albums in the slideshow. Keeps the old screensaver key so earlier picks carry over. */
data object AMBIENT_ALBUMS : StringSetPref(emptySet(), "", "") {
    override fun key() = "screensaver_albums"
}

/** People whose photos are shown (Immich person IDs). Keeps the old screensaver key. */
data object AMBIENT_PEOPLE : StringSetPref(emptySet(), "", "") {
    override fun key() = "screensaver_people"
}

/** The Immich asset pinned by the "One photo" source. */
data object AMBIENT_PINNED_ASSET : StringPref("", "", "") {
    override fun key() = "ambientPinnedAsset"
}

data object AMBIENT_INTERVAL : IntListPref(30,
    ImmichApplication.appContext!!.getString(R.string.ambient_interval),
    ImmichApplication.appContext!!.getString(R.string.ambient_interval_desc),
    R.array.interval_titles,
    R.array.interval_values,
    NumberFormat.SECONDS) {
    override fun key() = "ambientInterval"
}

data object AMBIENT_SHUFFLE : BooleanPref(true,
    ImmichApplication.appContext!!.getString(R.string.ambient_shuffle),
    ImmichApplication.appContext!!.getString(R.string.ambient_shuffle_desc)) {
    override fun key() = "ambientShuffle"
}

/** Time, weather and notifications over the photos (screen 1). */
data object AMBIENT_STATUS_STRIP : BooleanPref(true,
    ImmichApplication.appContext!!.getString(R.string.ambient_status_strip),
    ImmichApplication.appContext!!.getString(R.string.ambient_status_strip_desc)) {
    override fun key() = "ambientStatusStrip"
}

data object AMBIENT_INCLUDE_VIDEOS : BooleanPref(false,
    ImmichApplication.appContext!!.getString(R.string.include_videos),
    ImmichApplication.appContext!!.getString(R.string.ambient_include_videos_desc)) {
    override fun key() = "ambientIncludeVideos"
}

/** Optional: an entity that is "on" during quiet hours (no automatic takeovers then). */
data object HA_QUIET_HOURS_ENTITY : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.ha_quiet_hours_entity),
    ImmichApplication.appContext!!.getString(R.string.ha_quiet_hours_entity_desc)) {
    override fun key() = "haQuietHoursEntity"
}

/** Optional: a 3D printer's print status sensor; a finished print opens the glance view. */
data object HA_PRINT_STATUS_ENTITY : StringPref("",
    ImmichApplication.appContext!!.getString(R.string.ha_print_status_entity),
    ImmichApplication.appContext!!.getString(R.string.ha_print_status_entity_desc)) {
    override fun key() = "haPrintStatusEntity"
}
