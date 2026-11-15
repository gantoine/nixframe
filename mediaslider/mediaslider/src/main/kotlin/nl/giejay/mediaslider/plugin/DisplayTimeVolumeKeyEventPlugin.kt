package nl.giejay.mediaslider.plugin

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.widget.Toast
import nl.giejay.mediaslider.model.SliderItemType

/**
 * D-pad Up/Down while the controller overlay is hidden:
 * - on a video: raise / lower the media volume
 * - on a photo: step the slideshow display time through [INTERVAL_STEPS] (applies immediately)
 *
 * The overlay controls remain reachable with CENTER. When the overlay is visible, Up/Down
 * fall through so they keep moving focus between the overlay's rows.
 */
class DisplayTimeVolumeKeyEventPlugin(private val context: Context) : SliderKeyEventPlugin {

    private var toast: Toast? = null

    override fun onKeyDown(event: KeyEvent, state: SliderKeyEventState): SliderKeyEventResult {
        if (state.isControllerVisible) return SliderKeyEventResult.UNHANDLED
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_UP && event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
            return SliderKeyEventResult.UNHANDLED
        }
        val increase = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        when (state.currentItemType) {
            SliderItemType.VIDEO -> adjustVolume(increase)
            SliderItemType.IMAGE -> adjustDisplayTime(increase, state)
            null -> return SliderKeyEventResult.UNHANDLED
        }
        return SliderKeyEventResult.HANDLED_CONSUME
    }

    private fun adjustVolume(increase: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun adjustDisplayTime(increase: Boolean, state: SliderKeyEventState) {
        val controller = state.controller
        val current = controller.intervalSeconds
        controller.intervalSeconds = if (increase) {
            INTERVAL_STEPS.firstOrNull { it > current } ?: INTERVAL_STEPS.last()
        } else {
            INTERVAL_STEPS.lastOrNull { it < current } ?: INTERVAL_STEPS.first()
        }
        toast?.cancel()
        toast = Toast.makeText(context, "Display time: ${formatSeconds(controller.intervalSeconds)}", Toast.LENGTH_SHORT)
            .also { it.show() }
        // Restart the running timer so the change takes effect on the current image.
        if (state.isSlideshowPlaying) {
            controller.startTimerNextAsset()
        }
    }

    private fun formatSeconds(s: Int): String =
        if (s >= 60 && s % 60 == 0) "${s / 60}m" else "${s}s"

    private companion object {
        /** Preset steps (seconds) the Up/Down buttons snap through. */
        val INTERVAL_STEPS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 60, 120, 300)
    }
}
