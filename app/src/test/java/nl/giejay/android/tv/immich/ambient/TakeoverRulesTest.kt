package nl.giejay.android.tv.immich.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class TakeoverRulesTest {
    private val now = Instant.parse("2026-11-22T19:41:30Z")

    @Test
    fun aRingIsANewRecentEventTimestamp() {
        val before = "2026-11-21T23:41:00.000+00:00"
        val ring = "2026-11-22T19:41:25.000+00:00"
        assertThat(TakeoverRules.eventFired(before, ring, now)).isTrue()
        // first state after (re)connecting is history
        assertThat(TakeoverRules.eventFired(null, ring, now)).isFalse()
        // unchanged
        assertThat(TakeoverRules.eventFired(ring, ring, now)).isFalse()
        // a change to an old timestamp (replay after a restart) isn't a ring
        assertThat(TakeoverRules.eventFired(before, "2026-11-22T10:00:00+00:00", now)).isFalse()
        // unknown / unavailable aren't events
        assertThat(TakeoverRules.eventFired(before, "unavailable", now)).isFalse()
    }

    @Test
    fun safetyTripsOnlyOnTheWayToOn() {
        assertThat(TakeoverRules.safetyTripped("off", "on")).isTrue()
        assertThat(TakeoverRules.safetyTripped("unavailable", "on")).isTrue()
        assertThat(TakeoverRules.safetyTripped(null, "on")).isFalse()
        assertThat(TakeoverRules.safetyTripped("on", "on")).isFalse()
        assertThat(TakeoverRules.safetyTripped("on", "off")).isFalse()
    }

    @Test
    fun aPrintFinishesFromPrintingToFinished() {
        assertThat(TakeoverRules.printFinished("running", "finish")).isTrue()
        assertThat(TakeoverRules.printFinished("Printing", "Completed")).isTrue()
        assertThat(TakeoverRules.printFinished("idle", "finish")).isFalse()
        assertThat(TakeoverRules.printFinished(null, "finish")).isFalse()
        assertThat(TakeoverRules.printFinished("running", "failed")).isFalse()
    }

    @Test
    fun quietHoursComeFromTheEntityWhenOneIsSetElseTheScreenOffWindow() {
        assertThat(TakeoverRules.isQuiet("on", entityConfigured = true, displayQuietHours = false)).isTrue()
        assertThat(TakeoverRules.isQuiet("off", entityConfigured = true, displayQuietHours = true)).isFalse()
        assertThat(TakeoverRules.isQuiet("unavailable", entityConfigured = true, displayQuietHours = true)).isFalse()
        assertThat(TakeoverRules.isQuiet(null, entityConfigured = false, displayQuietHours = true)).isTrue()
        assertThat(TakeoverRules.isQuiet(null, entityConfigured = false, displayQuietHours = false)).isFalse()
    }

    @Test
    fun whatMayInterruptWhat() {
        Screen.entries.forEach { screen ->
            Takeover.entries.forEach { t -> assertThat(TakeoverRules.shouldOpen(t, screen, quiet = true)).isFalse() }
            assertThat(TakeoverRules.shouldOpen(Takeover.DOORBELL, screen, quiet = false)).isTrue()
        }
        assertThat(TakeoverRules.shouldOpen(Takeover.SAFETY, Screen.SOURCES, quiet = false)).isTrue()
        assertThat(TakeoverRules.shouldOpen(Takeover.SAFETY, Screen.DOORBELL, quiet = false)).isFalse()
        assertThat(TakeoverRules.shouldOpen(Takeover.PRINT_FINISHED, Screen.PHOTOS, quiet = false)).isTrue()
        assertThat(TakeoverRules.shouldOpen(Takeover.PRINT_FINISHED, Screen.SOURCES, quiet = false)).isFalse()
        assertThat(TakeoverRules.shouldOpen(Takeover.PRINT_FINISHED, Screen.GLANCE, quiet = false)).isFalse()
    }

    @Test
    fun timers() {
        assertThat(TakeoverRules.glanceCloseAt(1_000, safetyAlarmActive = false, reason = null)).isEqualTo(31_000)
        assertThat(TakeoverRules.glanceCloseAt(1_000, safetyAlarmActive = true, reason = null)).isEqualTo(31_000)
        assertThat(TakeoverRules.glanceCloseAt(1_000, safetyAlarmActive = true, reason = Takeover.SAFETY)).isNull()
        assertThat(TakeoverRules.glanceCloseAt(1_000, safetyAlarmActive = false, reason = Takeover.SAFETY)).isEqualTo(31_000)
        assertThat(TakeoverRules.doorbellCloseAt(5_000)).isEqualTo(125_000)
        assertThat(TakeoverRules.secondsLeft(31_000, 1_000)).isEqualTo(30)
        assertThat(TakeoverRules.secondsLeft(31_000, 30_500)).isEqualTo(1)
        assertThat(TakeoverRules.secondsLeft(31_000, 40_000)).isEqualTo(0)
    }
}
