package nl.giejay.android.tv.immich.sensors;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;

import timber.log.Timber;

public class PowerUtils {
    private final PowerManager.WakeLock wakeLock;
    private final PowerManager powerManager;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName admin;

    public PowerUtils(Context context) {
        // Use the String-based overload: getSystemService(Class) is API 23+ and crashes on
        // API 19 (KitKat) with NoSuchMethodError.
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(context, ScreenOffAdmin.class);
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Immich:PowerUtils");
        wakeUp();
    }

    /** Lets the display go dark once Android's screen-off timeout (60 s on the frame) runs out. */
    public void goToSleep() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /**
     * Turns the display off now, if [ScreenOffAdmin] is active; otherwise falls back to
     * [goToSleep] and the screen-off timeout.
     */
    public void screenOffNow() {
        goToSleep();
        if (devicePolicyManager != null && devicePolicyManager.isAdminActive(admin)) {
            try {
                devicePolicyManager.lockNow();
            } catch (SecurityException e) {
                Timber.w(e, "lockNow refused");
            }
        } else {
            Timber.w("ScreenOffAdmin isn't active: the screen turns off after the screen-off timeout");
        }
    }

    @SuppressWarnings("deprecation") // isInteractive() is API 20+
    public boolean isScreenOn() {
        return powerManager.isScreenOn();
    }

    public void wakeUp() {
        if(!wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
        }
    }
}
