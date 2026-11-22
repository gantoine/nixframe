package nl.giejay.android.tv.immich.ambient

import android.content.Context
import android.view.KeyEvent
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.view.MediaSliderView

/**
 * The slideshow under the ambient screens. The ambient host owns the remote: this view never sees
 * D-pad keys (its own controller overlay would open on OK/Down), only next/previous calls.
 */
class AmbientSliderView(context: Context) : MediaSliderView(context) {

    val ready: Boolean get() = isConfigReady

    fun next() {
        if (isConfigReady && config.items.isNotEmpty()) controller.skipToNextAndRestartTimer()
    }

    fun previous() {
        if (isConfigReady && config.items.isNotEmpty()) controller.skipToPreviousAndRestartTimer()
    }

    /** The photo on screen (the first of a merged portrait pair), or null before loading. */
    fun current(): SliderItem? = if (isConfigReady && config.items.isNotEmpty()) currentItem().mainItem else null

    // keys reach the slider only if the host lets them through: volume and the like
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK -> false
        else -> super.dispatchKeyEvent(event)
    }
}
