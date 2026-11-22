package nl.giejay.android.tv.immich.ambient

import nl.giejay.android.tv.immich.homeassistant.HaFormat
import java.time.Duration
import java.time.Instant

/** The ambient screens. */
enum class Screen { PHOTOS, GLANCE, DOORBELL, SOURCES, SETTINGS }

/** Why a screen opened on its own. */
enum class Takeover { DOORBELL, SAFETY, PRINT_FINISHED }

/**
 * When the frame opens a screen by itself and when it goes back to the photos. Pure, unit tested;
 * [AmbientFragment] feeds it entity state changes and the clock.
 */
object TakeoverRules {
    /** Glance opened with OK/Up (or a finished print) returns to the photos after this. */
    const val GLANCE_MS = 30_000L

    /** The doorbell view returns to the photos this long after the last ring or motion. */
    const val DOORBELL_IDLE_MS = 2 * 60_000L

    /** An event older than this when it arrives is a replay (reconnect), not a ring. */
    private val EVENT_MAX_AGE: Duration = Duration.ofSeconds(90)

    private val PRINT_ACTIVE = setOf("running", "printing", "prepare", "slicing", "pause", "paused")
    private val PRINT_DONE = setOf("finish", "finished", "complete", "completed")

    /**
     * An `event.*` entity fired: its state (the event's timestamp) changed to a recent time.
     * The first state seen ([old] null) is history, not news.
     */
    fun eventFired(old: String?, new: String, now: Instant): Boolean {
        if (old == null || old == new) return false
        val at = HaFormat.parseTimestamp(new) ?: return false
        return Duration.between(at, now).abs() <= EVENT_MAX_AGE
    }

    /** A smoke, CO, gas or leak sensor went from clear to tripped. */
    fun safetyTripped(old: String?, new: String): Boolean = old != null && old != "on" && new == "on"

    /** A print status sensor went from printing to finished. */
    fun printFinished(old: String?, new: String): Boolean =
        old != null && old.lowercase() in PRINT_ACTIVE && new.lowercase() in PRINT_DONE

    /**
     * Quiet hours: the configured Home Assistant entity when there is one (on = quiet; an
     * unavailable entity counts as not quiet), else the frame's own overnight screen-off window.
     */
    fun isQuiet(quietEntityState: String?, entityConfigured: Boolean, displayQuietHours: Boolean): Boolean =
        if (entityConfigured) quietEntityState == "on" else displayQuietHours

    /**
     * Whether [takeover] may replace [current]. Quiet hours suppress every automatic opening.
     * The doorbell interrupts anything; a safety alert interrupts anything but the doorbell; a
     * finished print only interrupts the photos.
     */
    fun shouldOpen(takeover: Takeover, current: Screen, quiet: Boolean): Boolean {
        if (quiet) return false
        return when (takeover) {
            Takeover.DOORBELL -> true
            Takeover.SAFETY -> current != Screen.DOORBELL
            Takeover.PRINT_FINISHED -> current == Screen.PHOTOS
        }
    }

    /**
     * When an open glance returns to the photos, or null to stay: a safety alert keeps it up
     * while the alarm lasts, then counts down like any other glance.
     */
    fun glanceCloseAt(openedAt: Long, safetyAlarmActive: Boolean, reason: Takeover?): Long? =
        if (reason == Takeover.SAFETY && safetyAlarmActive) null else openedAt + GLANCE_MS

    /** When the doorbell view returns to the photos, counted from the last ring or motion. */
    fun doorbellCloseAt(lastActivity: Long): Long = lastActivity + DOORBELL_IDLE_MS

    /** Whole seconds left until [closeAt], for "Back to photos in 30 s". Never negative. */
    fun secondsLeft(closeAt: Long, now: Long): Long = ((closeAt - now + 999) / 1000).coerceAtLeast(0)
}
