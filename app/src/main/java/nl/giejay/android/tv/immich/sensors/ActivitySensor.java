package nl.giejay.android.tv.immich.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.kitesystems.nix.frame.MotionSensor;

import java.io.File;

import nl.giejay.android.tv.immich.display.DisplayController;

import timber.log.Timber;

public class ActivitySensor {
    private final SensorPoller instance;
    private final PowerUtils powerUtils;

    public ActivitySensor(Context context) {
        powerUtils = new PowerUtils(context);
        if(new File("/etc/nix.model").exists()) {
            instance = new SensorPoller(new MotionSensor());
        } else {
            instance = new SensorPoller(() -> true);
        }
        // The remote's power key is WAKE_DROPPED: with the display off, Android wakes it itself and
        // never delivers the key. Catch that here, so a manual "off" ends and the wake lock returns.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (manualOff) {
                    Timber.i("Display woken after the power button turned it off");
                    manualOff = false;
                    quietOverride = quietActive;
                    instance.resetMotionSensor();
                    powerUtils.wakeUp();
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** Overnight screen-off window currently active (see DisplayController). */
    private volatile boolean quietActive = false;
    /** The user woke the screen during quiet hours; honoured until it next goes to sleep. */
    private volatile boolean quietOverride = false;
    /**
     * The power button turned the display off. Motion doesn't wake it (whoever pressed the button
     * is standing right in front of the sensor); the power button, a takeover or the end of the
     * night does.
     */
    private volatile boolean manualOff = false;

    SensorServiceCallback sensorServiceCallback = new SensorServiceCallback() {
        @Override
        public void sleep() {
            quietOverride = false;
            powerUtils.goToSleep();
        }

        @Override
        public void wakeUp() {
            instance.resetMotionSensor();
            powerUtils.wakeUp();
        }
    };

    public void checkSensors() {
        try {
            boolean quiet = DisplayController.isQuietHours();
            if (quiet != quietActive) {
                quietActive = quiet;
                quietOverride = false;
                if (!quiet) {
                    manualOff = false;
                    // the night is over: wake back into whatever was showing (e.g. the slideshow)
                    Timber.i("Overnight screen-off ended, waking the display");
                    sensorServiceCallback.wakeUp();
                    return;
                }
                Timber.i("Overnight screen-off started");
            }
            if (quiet && !quietOverride) {
                // motion doesn't wake the screen overnight; the power button still does
                powerUtils.goToSleep();
                SensorPoller.sleepModeActive = true;
                return;
            }
            if (manualOff) {
                return;
            }
            Thread.currentThread().setPriority(10);
            instance.checkIfActivityDetected(sensorServiceCallback);
        } catch (SecurityException e2) {
            Timber.e(e2.toString());
        }
    }

    /** The power key went down while the app could see it, i.e. with the display on. */
    private volatile boolean powerKeyDown = false;

    public void powerKeyDown() {
        powerKeyDown = true;
    }

    /**
     * Acts on the release of a press that started with the display on. With the display off, the
     * press itself is swallowed by Android (it wakes the screen) and only the release arrives here:
     * that one is ignored, or it would turn the display straight back off.
     */
    public void powerKeyUp() {
        if (!powerKeyDown) return;
        powerKeyDown = false;
        powerButtonPressed();
    }

    /**
     * Toggles the display. Decided by whether it's actually lit, not by the motion poller's sleep
     * flag: a button "off" used to only release the wake lock, so the screen stayed on for another
     * minute and the next press released it again instead of waking.
     */
    private void powerButtonPressed() {
        if (powerUtils.isScreenOn()) {
            Timber.i("Power button: display off");
            manualOff = true;
            quietOverride = false;
            powerUtils.screenOffNow();
        } else {
            manualOff = false;
            quietOverride = quietActive;
            instance.resetMotionSensor();
            powerUtils.wakeUp();
        }
    }

    /** Something worth seeing happened (doorbell, alarm): wake the screen as motion would. */
    public void wakeForTakeover() {
        manualOff = false;
        sensorServiceCallback.wakeUp();
    }

    public void resetMotionSensor() {
        instance.resetMotionSensor();
    }
}
