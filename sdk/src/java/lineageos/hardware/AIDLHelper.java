/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package lineageos.hardware;

import android.util.Log;
import android.util.Range;

import java.util.ArrayList;

/**
 * Converts between vendor AIDL HAL types and SDK types.
 */
class AIDLHelper {
    private static final String TAG = "LineageHardwareManager";

    static DisplayMode[] fromAIDLModes(vendor.lineage.livedisplay.DisplayMode[] modes) {
        if (modes == null) {
            return null;
        }
        final ArrayList<DisplayMode> r = new ArrayList<>(modes.length);
        for (vendor.lineage.livedisplay.DisplayMode m : modes) {
            if (m != null) {
                r.add(new DisplayMode(m.id, m.name));
            }
        }
        return r.toArray(new DisplayMode[0]);
    }

    static DisplayMode fromAIDLMode(vendor.lineage.livedisplay.DisplayMode mode) {
        return mode != null ? new DisplayMode(mode.id, mode.name) : null;
    }

    static HSIC fromAIDLHSIC(vendor.lineage.livedisplay.HSIC hsic) {
        if (hsic == null) {
            return null;
        }
        return new HSIC(hsic.hue, hsic.saturation, hsic.intensity,
                hsic.contrast, hsic.saturationThreshold);
    }

    static vendor.lineage.livedisplay.HSIC toAIDLHSIC(HSIC hsic) {
        vendor.lineage.livedisplay.HSIC h = new vendor.lineage.livedisplay.HSIC();
        h.hue = hsic.getHue();
        h.saturation = hsic.getSaturation();
        h.intensity = hsic.getIntensity();
        h.contrast = hsic.getContrast();
        h.saturationThreshold = hsic.getSaturationThreshold();
        return h;
    }

    static Range<Integer> fromAIDLRange(vendor.lineage.livedisplay.Range range) {
        if (range == null) {
            return null;
        }
        if (range.min > range.max) {
            Log.w(TAG, "HAL returned inverted range [" + range.min + ", " + range.max + "]");
            return null;
        }
        return new Range<Integer>(range.min, range.max);
    }

    static Range<Float> fromAIDLRange(vendor.lineage.livedisplay.FloatRange range) {
        if (range == null) {
            return null;
        }
        if (Float.isNaN(range.min) || Float.isNaN(range.max) || range.min > range.max) {
            Log.w(TAG, "HAL returned invalid range [" + range.min + ", " + range.max + "]");
            return null;
        }
        return new Range<Float>(range.min, range.max);
    }

    static TouchscreenGesture[] fromAIDLGestures(
            vendor.lineage.touch.Gesture[] gestures) {
        if (gestures == null) {
            return null;
        }
        final ArrayList<TouchscreenGesture> r = new ArrayList<>(gestures.length);
        for (vendor.lineage.touch.Gesture g : gestures) {
            if (g != null) {
                r.add(new TouchscreenGesture(g.id, g.name, g.keycode));
            }
        }
        return r.toArray(new TouchscreenGesture[0]);
    }

    static vendor.lineage.touch.Gesture toAIDLGesture(TouchscreenGesture gesture) {
        vendor.lineage.touch.Gesture g = new vendor.lineage.touch.Gesture();
        g.id = gesture.id;
        g.name = gesture.name;
        g.keycode = gesture.keycode;
        return g;
    }
}
