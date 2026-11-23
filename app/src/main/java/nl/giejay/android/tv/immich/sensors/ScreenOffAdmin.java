package nl.giejay.android.tv.immich.sensors;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device admin with only the "force lock" policy. DevicePolicyManager.lockNow() is the one way an
 * app not signed with the platform key can switch the display off at once (PowerManager.goToSleep
 * needs DEVICE_POWER, which is signature-only even for /system apps). The frame has no lock screen,
 * so lockNow() just turns the display off.
 *
 * Activated from the frame; see "Power button" in the README.
 */
public class ScreenOffAdmin extends DeviceAdminReceiver {
}
