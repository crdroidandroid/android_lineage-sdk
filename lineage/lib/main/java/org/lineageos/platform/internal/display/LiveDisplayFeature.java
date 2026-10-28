/*
 * SPDX-FileCopyrightText: 2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.platform.internal.display;

import static org.lineageos.platform.internal.display.LiveDisplayService.ALL_CHANGED;
import static org.lineageos.platform.internal.display.LiveDisplayService.DISPLAY_CHANGED;
import static org.lineageos.platform.internal.display.LiveDisplayService.MODE_CHANGED;
import static org.lineageos.platform.internal.display.LiveDisplayService.TWILIGHT_CHANGED;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.os.Handler;
import android.util.Log;
import android.util.Slog;

import lineageos.hardware.LineageHardwareManager;

import org.lineageos.platform.internal.LineageBaseFeature;
import org.lineageos.platform.internal.display.LiveDisplayService.State;
import org.lineageos.platform.internal.display.TwilightTracker.TwilightState;

import java.util.BitSet;

public abstract class LiveDisplayFeature extends LineageBaseFeature {

    protected static final String TAG = "LiveDisplay";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    protected final boolean mNightDisplayAvailable;

    private volatile State mState;

    public LiveDisplayFeature(Context context, Handler handler) {
        super(context, handler);
        mNightDisplayAvailable = ColorDisplayManager.isNightDisplayAvailable(mContext);
    }

    public abstract boolean getCapabilities(final BitSet caps);

    protected abstract void onUpdate();

    void update(final int flags, final State state) {
        mState = state;
        if ((flags & DISPLAY_CHANGED) != 0) {
            onScreenStateChanged();
        }
        if (((flags & TWILIGHT_CHANGED) != 0) && state != null && state.mTwilight != null) {
            onTwilightUpdated();
        }
        if ((flags & MODE_CHANGED) != 0) {
            onUpdate();
        }
        if (flags == ALL_CHANGED) {
            onSettingsChanged(null);
        }
    }

    protected void onScreenStateChanged() { }

    protected void onTwilightUpdated() { }

    protected final boolean isLowPowerMode() {
        final State state = mState;
        return state != null && state.mLowPowerMode;
    }

    protected final int getMode() {
        final State state = mState;
        return state != null ? state.mMode : -1;
    }

    protected final boolean isScreenOn() {
        final State state = mState;
        return state != null && state.mScreenOn;
    }

    protected final TwilightState getTwilight() {
        final State state = mState;
        return state != null ? state.mTwilight : null;
    }

    public final boolean isNight() {
        final TwilightState twilight = getTwilight();
        return twilight != null && twilight.isNight();
    }

    /*
     * HAL access helpers.
     */

    protected static boolean isHardwareSupported(LineageHardwareManager hw, int feature) {
        if (hw == null) {
            return false;
        }
        try {
            return hw.isSupported(feature);
        } catch (RuntimeException e) {
            Slog.w(TAG, "isSupported(" + feature + ") failed, treating as unsupported", e);
            return false;
        }
    }

    protected static boolean setHardwareFeature(LineageHardwareManager hw, int feature,
            boolean enable) {
        try {
            return hw.set(feature, enable);
        } catch (RuntimeException e) {
            Slog.w(TAG, "set(" + feature + ", " + enable + ") failed", e);
            return false;
        }
    }

    protected static boolean getHardwareFeature(LineageHardwareManager hw, int feature) {
        try {
            return hw.get(feature);
        } catch (RuntimeException e) {
            Slog.w(TAG, "get(" + feature + ") failed", e);
            return false;
        }
    }
}
