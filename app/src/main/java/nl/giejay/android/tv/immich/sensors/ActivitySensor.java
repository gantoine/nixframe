package nl.giejay.android.tv.immich.sensors;

import android.content.Context;

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
    }

    /** Overnight screen-off window currently active (see DisplayController). */
    private boolean quietActive = false;
    /** The user woke the screen during quiet hours; honoured until it next goes to sleep. */
    private boolean quietOverride = false;

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
            Thread.currentThread().setPriority(10);
            instance.checkIfActivityDetected(sensorServiceCallback);
        } catch (SecurityException e2) {
            Timber.e(e2.toString());
        }
    }

    public void powerButtonPressed() {
        if(instance.isSleepModeActive()) {
            quietOverride = quietActive;
            instance.resetMotionSensor();
            powerUtils.wakeUp();
        } else {
            quietOverride = false;
            powerUtils.goToSleep();
        }
    }

    public void resetMotionSensor() {
        instance.resetMotionSensor();
    }
}
