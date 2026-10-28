/*
 * SPDX-FileCopyrightText: 2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.platform.internal.display;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Range;
import android.util.Slog;
import android.util.SparseArray;

import lineageos.hardware.DisplayMode;
import lineageos.hardware.HSIC;
import lineageos.hardware.LineageHardwareManager;
import lineageos.hardware.LiveDisplayManager;
import lineageos.providers.LineageSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class PictureAdjustmentController extends LiveDisplayFeature {

    private static final String TAG = "LiveDisplay-PAC";

    private final LineageHardwareManager mHardware;
    private final boolean mUsePictureAdjustment;
    private final boolean mHasDisplayModes;

    private final List<Range<Float>> mRanges = new ArrayList<Range<Float>>();

    public PictureAdjustmentController(Context context, Handler handler) {
        super(context, handler);
        mHardware = LineageHardwareManager.getInstance(context);
        mHasDisplayModes = isHardwareSupported(mHardware,
                LineageHardwareManager.FEATURE_DISPLAY_MODES);

        boolean usePA = isHardwareSupported(mHardware,
                LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT);
        if (usePA) {
            List<Range<Float>> r = null;
            try {
                // The HAL can advertise picture adjustment and still reject the range
                // queries (EX_UNSUPPORTED_OPERATION from IPictureAdjustment.getHueRange()).
                // That exception used to escape the constructor and kill system_server
                // during PHASE_BOOT_COMPLETED.
                r = mHardware.getPictureAdjustmentRanges();
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to query picture adjustment ranges, disabling feature", e);
            }
            if (r != null) {
                mRanges.addAll(r);
            }
            if (mRanges.size() < 4) {
                usePA = false;
            } else {
                for (Range<Float> range : mRanges) {
                    if (range == null
                            || (range.getLower() == 0.0f && range.getUpper() == 0.0f)) {
                        usePA = false;
                        break;
                    }
                }
            }
        }
        if (!usePA) {
            mRanges.clear();
        }
        mUsePictureAdjustment = usePA;
    }

    @Override
    public void onStart() {
        if (!mUsePictureAdjustment) {
            return;
        }

        registerSettings(
                LineageSettings.System.getUriFor(LineageSettings.System.DISPLAY_PICTURE_ADJUSTMENT));
    }

    @Override
    protected void onSettingsChanged(Uri uri) {// nothing to do for mode switch
        updatePictureAdjustment();
    }

    @Override
    protected void onUpdate() {
        updatePictureAdjustment();
    }

    private void updatePictureAdjustment() {
        if (mUsePictureAdjustment && isScreenOn()) {
            final HSIC hsic = getPictureAdjustment();
            if (hsic != null) {
                boolean ok;
                try {
                    ok = mHardware.setPictureAdjustment(hsic);
                } catch (RuntimeException e) {
                    Slog.e(TAG, "setPictureAdjustment threw", e);
                    ok = false;
                }
                if (!ok) {
                    Slog.e(TAG, "Failed to set picture adjustment! " + hsic.toString());
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        if (mUsePictureAdjustment) {
            pw.println();
            pw.println("PictureAdjustmentController Configuration:");
            pw.println("  adjustment=" + getPictureAdjustment());
            pw.println("  hueRange=" + getHueRange());
            pw.println("  saturationRange=" + getSaturationRange());
            pw.println("  intensityRange=" + getIntensityRange());
            pw.println("  contrastRange=" + getContrastRange());
            pw.println("  saturationThresholdRange=" + getSaturationThresholdRange());
            pw.println("  defaultAdjustment=" + getDefaultPictureAdjustment());
        }
    }


    @Override
    public boolean getCapabilities(BitSet caps) {
        if (mUsePictureAdjustment) {
            caps.set(LiveDisplayManager.FEATURE_PICTURE_ADJUSTMENT);
        }
        return mUsePictureAdjustment;
    }

    private Range<Float> getRange(int index) {
        return mUsePictureAdjustment && mRanges.size() > index
                ? mRanges.get(index) : Range.create(0.0f, 0.0f);
    }

    Range<Float> getHueRange() {
        return getRange(0);
    }

    Range<Float> getSaturationRange() {
        return getRange(1);
    }

    Range<Float> getIntensityRange() {
        return getRange(2);
    }

    Range<Float> getContrastRange() {
        return getRange(3);
    }

    Range<Float> getSaturationThresholdRange() {
        return getRange(4);
    }

    HSIC getDefaultPictureAdjustment() {
        HSIC hsic = null;
        if (mUsePictureAdjustment) {
            try {
                hsic = mHardware.getDefaultPictureAdjustment();
            } catch (RuntimeException e) {
                Slog.w(TAG, "getDefaultPictureAdjustment failed", e);
            }
        }
        if (hsic == null) {
            hsic = new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }
        return hsic;
    }

    private int getCurrentModeId() {
        if (!mHasDisplayModes) {
            return 0;
        }
        try {
            final DisplayMode mode = mHardware.getCurrentDisplayMode();
            return mode != null ? mode.id : 0;
        } catch (RuntimeException e) {
            Slog.w(TAG, "getCurrentDisplayMode failed", e);
            return 0;
        }
    }

    HSIC getPictureAdjustment() {
        HSIC hsic = null;
        if (mUsePictureAdjustment) {
            hsic = getPAForMode(getCurrentModeId());
        }
        if (hsic == null) {
            hsic = new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }
        return hsic;
    }

    boolean setPictureAdjustment(HSIC hsic) {
        if (mUsePictureAdjustment && hsic != null) {
            setPAForMode(getCurrentModeId(), hsic);
            return true;
        }
        return false;
    }

    // TODO: Expose mode-based settings to upper layers

    private HSIC getPAForMode(int mode) {
        final SparseArray<HSIC> prefs = unpackPreference();
        final HSIC hsic = prefs.get(mode);
        return hsic != null ? hsic : getDefaultPictureAdjustment();
    }

    private void setPAForMode(int mode, HSIC hsic) {
        final SparseArray<HSIC> prefs = unpackPreference();
        prefs.put(mode, hsic);
        packPreference(prefs);
    }

    private SparseArray<HSIC> unpackPreference() {
        final SparseArray<HSIC> ret = new SparseArray<HSIC>();

        String pref = getString(LineageSettings.System.DISPLAY_PICTURE_ADJUSTMENT);
        if (pref != null) {
            String[] byMode = TextUtils.split(pref, ",");
            for (String mode : byMode) {
                String[] modePA = TextUtils.split(mode, ":");
                if (modePA.length != 2) {
                    continue;
                }
                // The setting is user-writable; a malformed value must not throw on the
                // LiveDisplay handler thread (that would crash system_server).
                try {
                    final HSIC hsic = HSIC.unflattenFrom(modePA[1]);
                    if (hsic != null) {
                        ret.put(Integer.parseInt(modePA[0]), hsic);
                    }
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Ignoring malformed picture adjustment entry: " + mode, e);
                }
            }
        }
        return ret;
    }

    private void packPreference(final SparseArray<HSIC> modes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < modes.size(); i++) {
            final HSIC m = modes.valueAt(i);
            if (m == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(modes.keyAt(i)).append(":").append(m.flatten());
        }
        putString(LineageSettings.System.DISPLAY_PICTURE_ADJUSTMENT, sb.toString());
    }

}
