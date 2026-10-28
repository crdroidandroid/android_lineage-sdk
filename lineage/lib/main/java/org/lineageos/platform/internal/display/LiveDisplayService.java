/*
 * SPDX-FileCopyrightText: 2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.platform.internal.display;

import static lineageos.hardware.LiveDisplayManager.MODE_FIRST;
import static lineageos.hardware.LiveDisplayManager.MODE_LAST;
import static lineageos.hardware.LiveDisplayManager.MODE_OFF;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.UserHandle;
import android.util.Range;
import android.util.Slog;
import android.view.Display;

import com.android.server.LocalServices;
import com.android.server.ServiceThread;

import lineageos.app.LineageContextConstants;
import lineageos.hardware.HSIC;
import lineageos.hardware.ILiveDisplayService;
import lineageos.hardware.LiveDisplayConfig;
import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.LineageSystemService;
import org.lineageos.platform.internal.common.UserContentObserver;
import org.lineageos.platform.internal.display.TwilightTracker.TwilightListener;
import org.lineageos.platform.internal.display.TwilightTracker.TwilightState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * LiveDisplay is an advanced set of features for improving
 * display quality under various ambient conditions.
 *
 * The service is constructed with a set of LiveDisplayFeatures
 * which provide capabilities such as outdoor mode, night mode,
 * and calibration. It interacts with LineageHardwareService to relay
 * changes down to the lower layers.
 */
public class LiveDisplayService extends LineageSystemService {

    private static final String TAG = "LiveDisplay";

    private final Context mContext;
    private final Handler mHandler;
    private final ServiceThread mHandlerThread;

    private DisplayManager mDisplayManager;
    private ModeObserver mModeObserver;
    private final TwilightTracker mTwilightTracker;

    private boolean mAwaitingNudge = true;
    private boolean mSunset = false;

    private volatile List<LiveDisplayFeature> mFeatures = Collections.emptyList();

    private ColorTemperatureController mCTC;
    private DisplayHardwareController mDHC;
    private OutdoorModeController mOMC;
    private PictureAdjustmentController mPAC;

    private volatile LiveDisplayConfig mConfig;

    private volatile boolean mReady = false;

    static int MODE_CHANGED = 1;
    static int DISPLAY_CHANGED = 2;
    static int TWILIGHT_CHANGED = 4;
    static int ALL_CHANGED = 255;

    // PowerManager ServiceType to use when we're only
    // interested in gleaning global battery saver state.
    private static final int SERVICE_TYPE_DUMMY = ServiceType.LOCATION;

    static class State {
        public volatile boolean mLowPowerMode = false;
        public volatile boolean mScreenOn = false;
        public volatile int mMode = -1;
        public volatile TwilightState mTwilight = null;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "[mLowPowerMode=%b, mScreenOn=%b, mMode=%d, mTwilight=%s",
                    mLowPowerMode, mScreenOn, mMode,
                    (mTwilight == null ? "NULL" : mTwilight.toString()));
        }
    }

    private final State mState = new State();

    public LiveDisplayService(Context context) {
        super(context);

        mContext = context;

        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DEFAULT, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mTwilightTracker = new TwilightTracker(context);
    }

    @Override
    public String getFeatureDeclaration() {
        return LineageContextConstants.Features.LIVEDISPLAY;
    }

    @Override
    public boolean isCoreService() {
        return false;
    }

    @Override
    public void onStart() {
        publishBinderService(LineageContextConstants.LINEAGE_LIVEDISPLAY_SERVICE, mBinder);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            try {
                initialize();
            } catch (RuntimeException e) {
                // A misbehaving display HAL (e.g. EX_UNSUPPORTED_OPERATION from
                // IPictureAdjustment) must never take system_server down with it.
                Slog.e(TAG, "Failed to initialize LiveDisplay, disabling it", e);
                mReady = false;
                mFeatures = Collections.emptyList();
                mConfig = createDisabledConfig();
            }

            try {
                Intent intent = new Intent(lineageos.content.Intent.ACTION_INITIALIZE_LIVEDISPLAY);
                intent.setPackage("com.android.systemui");
                mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failed to send LiveDisplay init broadcast", e);
            }
        }
    }

    private void initialize() {
        mAwaitingNudge = getSunsetCounter() < 1;

        mDHC = new DisplayHardwareController(mContext, mHandler);
        mCTC = new ColorTemperatureController(mContext, mHandler, mDHC);
        mOMC = new OutdoorModeController(mContext, mHandler);
        mPAC = new PictureAdjustmentController(mContext, mHandler);

        final List<LiveDisplayFeature> candidates = new ArrayList<>(4);
        candidates.add(mDHC);
        candidates.add(mCTC);
        candidates.add(mOMC);
        candidates.add(mPAC);

        // Get capabilities, throw out any unused features
        final BitSet capabilities = new BitSet();
        final List<LiveDisplayFeature> features = new ArrayList<>(candidates.size());
        for (LiveDisplayFeature feature : candidates) {
            if (feature.getCapabilities(capabilities)) {
                features.add(feature);
            }
        }

        // static config
        int defaultMode = mContext.getResources().getInteger(
                org.lineageos.platform.internal.R.integer.config_defaultLiveDisplayMode);

        mConfig = new LiveDisplayConfig(capabilities, defaultMode,
                mCTC.getDefaultDayTemperature(), mCTC.getDefaultNightTemperature(),
                mOMC.getDefaultAutoOutdoorMode(), mDHC.getDefaultAutoContrast(),
                mDHC.getDefaultCABC(), mDHC.getDefaultColorEnhancement(),
                mCTC.getColorTemperatureRange(), mCTC.getColorBalanceRange(),
                mPAC.getHueRange(), mPAC.getSaturationRange(),
                mPAC.getIntensityRange(), mPAC.getContrastRange(),
                mPAC.getSaturationThresholdRange());

        // listeners
        mDisplayManager = (DisplayManager) getContext().getSystemService(
                Context.DISPLAY_SERVICE);
        // Deliver on our own looper: the boot phase may run on a thread without a Looper
        // (parallel boot phases), and all State mutation then happens on one thread.
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        mState.mScreenOn = isScreenOn();

        PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        if (pmi != null) {
            pmi.registerLowPowerModeObserver(mLowPowerModeListener);
            // ServiceType does not matter when retrieving global saver mode.
            mState.mLowPowerMode =
                    pmi.getLowPowerState(SERVICE_TYPE_DUMMY).globalBatterySaverEnabled;
        }

        mTwilightTracker.registerListener(mTwilightListener, mHandler);
        mState.mTwilight = mTwilightTracker.getCurrentState();

        if (mConfig.hasModeSupport()) {
            mModeObserver = new ModeObserver(mHandler);
            mState.mMode = mModeObserver.getMode();
        }

        // start and update all features
        for (int i = 0; i < features.size(); i++) {
            features.get(i).start();
        }

        mFeatures = Collections.unmodifiableList(features);
        mReady = true;

        updateFeatures(ALL_CHANGED);
    }

    private static LiveDisplayConfig createDisabledConfig() {
        try {
            final Range<Integer> zeroInt = Range.create(0, 0);
            final Range<Float> zeroFloat = Range.create(0.0f, 0.0f);
            return new LiveDisplayConfig(new BitSet(), MODE_OFF, 0, 0,
                    false, false, false, false,
                    zeroInt, zeroInt,
                    zeroFloat, zeroFloat, zeroFloat, zeroFloat, zeroFloat);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to create fallback LiveDisplay config", e);
            return null;
        }
    }

    private void updateFeatures(final int flags) {
        mHandler.post(() -> {
            final List<LiveDisplayFeature> features = mFeatures;
            for (int i = 0; i < features.size(); i++) {
                final LiveDisplayFeature feature = features.get(i);
                try {
                    feature.update(flags, mState);
                } catch (RuntimeException e) {
                    // An uncaught exception on this ServiceThread would kill system_server.
                    Slog.e(TAG, "Failed to update " + feature.getClass().getSimpleName()
                            + " (flags=" + flags + ")", e);
                }
            }
        });
    }

    private void enforceManagePermission() {
        mContext.enforceCallingOrSelfPermission(
                lineageos.platform.Manifest.permission.MANAGE_LIVEDISPLAY, null);
    }

    private final IBinder mBinder = new ILiveDisplayService.Stub() {

        @Override
        public LiveDisplayConfig getConfig() {
            return mConfig;
        }

        @Override
        public int getMode() {
            final LiveDisplayConfig config = mConfig;
            if (mReady && config != null && config.hasModeSupport() && mModeObserver != null) {
                return mModeObserver.getMode();
            } else {
                return MODE_OFF;
            }
        }

        @Override
        public boolean setMode(int mode) {
            enforceManagePermission();
            final LiveDisplayConfig config = mConfig;
            if (!mReady || config == null || !config.hasModeSupport()
                    || mModeObserver == null) {
                return false;
            }
            return mModeObserver.setMode(mode);
        }

        @Override
        public float[] getColorAdjustment() {
            return mReady ? mDHC.getColorAdjustment() : new float[] { 1.0f, 1.0f, 1.0f };
        }

        @Override
        public boolean setColorAdjustment(float[] adj) {
            enforceManagePermission();
            return mReady && mDHC.setColorAdjustment(adj);
        }

        @Override
        public boolean isAutoContrastEnabled() {
            return mReady && mDHC.isAutoContrastEnabled();
        }

        @Override
        public  boolean setAutoContrastEnabled(boolean enabled) {
            enforceManagePermission();
            return mReady && mDHC.setAutoContrastEnabled(enabled);
        }

        @Override
        public boolean isCABCEnabled() {
            return mReady && mDHC.isCABCEnabled();
        }

        @Override
        public boolean setCABCEnabled(boolean enabled) {
            enforceManagePermission();
            return mReady && mDHC.setCABCEnabled(enabled);
        }

        @Override
        public boolean isColorEnhancementEnabled() {
            return mReady && mDHC.isColorEnhancementEnabled();
        }

        @Override
        public boolean setColorEnhancementEnabled(boolean enabled) {
            enforceManagePermission();
            return mReady && mDHC.setColorEnhancementEnabled(enabled);
        }

        @Override
        public boolean isAutomaticOutdoorModeEnabled() {
            return mReady && mOMC.isAutomaticOutdoorModeEnabled();
        }

        @Override
        public boolean setAutomaticOutdoorModeEnabled(boolean enabled) {
            enforceManagePermission();
            return mReady && mOMC.setAutomaticOutdoorModeEnabled(enabled);
        }

        @Override
        public int getDayColorTemperature() {
            return mReady ? mCTC.getDayColorTemperature() : 0;
        }

        @Override
        public boolean setDayColorTemperature(int temperature) {
            enforceManagePermission();
            if (!mReady) {
                return false;
            }
            mCTC.setDayColorTemperature(temperature);
            return true;
        }

        @Override
        public int getNightColorTemperature() {
            return mReady ? mCTC.getNightColorTemperature() : 0;
        }

        @Override
        public boolean setNightColorTemperature(int temperature) {
            enforceManagePermission();
            if (!mReady) {
                return false;
            }
            mCTC.setNightColorTemperature(temperature);
            return true;
        }

        @Override
        public int getColorTemperature() {
            return mReady ? mCTC.getColorTemperature() : -1;
        }

        @Override
        public HSIC getPictureAdjustment() {
            return mReady ? mPAC.getPictureAdjustment()
                    : new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        @Override
        public boolean setPictureAdjustment(final HSIC hsic) {
            enforceManagePermission();
            return mReady && mPAC.setPictureAdjustment(hsic);
        }

        @Override
        public HSIC getDefaultPictureAdjustment() {
            return mReady ? mPAC.getDefaultPictureAdjustment()
                    : new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

            pw.println();
            pw.println("LiveDisplay Service State:");
            pw.println("  mReady=" + mReady);
            pw.println("  mState=" + mState.toString());
            final LiveDisplayConfig config = mConfig;
            pw.println("  mConfig=" + (config == null ? "NULL" : config.toString()));
            pw.println("  mAwaitingNudge=" + mAwaitingNudge);

            final List<LiveDisplayFeature> features = mFeatures;
            for (int i = 0; i < features.size(); i++) {
                try {
                    features.get(i).dump(pw);
                } catch (RuntimeException e) {
                    pw.println("  Failed to dump " + features.get(i).getClass().getSimpleName()
                            + ": " + e);
                }
            }
        }

        @Override
        public boolean isNight() {
            final TwilightState twilight = mTwilightTracker.getCurrentState();
            return twilight != null && twilight.isNight();
        }

        @Override
        public boolean isAntiFlickerEnabled() {
            return mReady && mDHC.isAntiFlickerEnabled();
        }

        @Override
        public boolean setAntiFlickerEnabled(boolean enabled) {
            enforceManagePermission();
            return mReady && mDHC.setAntiFlickerEnabled(enabled);
        }
    };

    // Listener for screen on/off events
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                boolean screenOn = isScreenOn();
                if (screenOn != mState.mScreenOn) {
                    mState.mScreenOn = screenOn;
                    updateFeatures(DISPLAY_CHANGED);
                }
            }
        }
    };


    // Display postprocessing can have power impact.
    private final PowerManagerInternal.LowPowerModeListener mLowPowerModeListener =
            new PowerManagerInternal.LowPowerModeListener() {
        @Override
        public void onLowPowerModeChanged(PowerSaveState state) {
            final boolean lowPowerMode = state.globalBatterySaverEnabled;
            if (lowPowerMode != mState.mLowPowerMode) {
                mState.mLowPowerMode = lowPowerMode;
                updateFeatures(MODE_CHANGED);
            }
         }

         @Override
         public int getServiceType() {
             return SERVICE_TYPE_DUMMY;
         }
    };

    // Watch for mode changes
    private final class ModeObserver extends UserContentObserver {

        private final Uri MODE_SETTING =
                LineageSettings.System.getUriFor(LineageSettings.System.DISPLAY_TEMPERATURE_MODE);

        ModeObserver(Handler handler) {
            super(handler);

            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(MODE_SETTING, false, this, UserHandle.USER_ALL);

            observe();
        }

        @Override
        protected void update() {
            int mode = getMode();
            if (mode != mState.mMode) {
                mState.mMode = mode;

                updateFeatures(MODE_CHANGED);
            }
        }

        int getMode() {
            final LiveDisplayConfig config = mConfig;
            return getInt(LineageSettings.System.DISPLAY_TEMPERATURE_MODE,
                    config != null ? config.getDefaultMode() : MODE_OFF);
        }

        boolean setMode(int mode) {
            final LiveDisplayConfig config = mConfig;
            if (config != null && config.hasFeature(mode)
                    && mode >= MODE_FIRST && mode <= MODE_LAST) {
                putInt(LineageSettings.System.DISPLAY_TEMPERATURE_MODE, mode);
                if (mode != config.getDefaultMode()) {
                    stopNudgingMe();
                }
                return true;
            }
            return false;
        }
    }

    // Night watchman
    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged() {
            mState.mTwilight = mTwilightTracker.getCurrentState();
            updateFeatures(TWILIGHT_CHANGED);
            try {
                nudge();
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to post LiveDisplay hint", e);
            }
        }
    };

    private boolean isScreenOn() {
        final DisplayManager dm = mDisplayManager;
        if (dm == null) {
            return false;
        }
        final Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        return display != null && display.getState() == Display.STATE_ON;
    }

    private int getSunsetCounter() {
        // Counter used to determine when we should tell the user about this feature.
        // If it's not used after 3 sunsets, we'll show the hint once.
        return LineageSettings.System.getIntForUser(mContext.getContentResolver(),
                LineageSettings.System.LIVE_DISPLAY_HINTED,
                -3,
                UserHandle.USER_CURRENT);
    }


    private void updateSunsetCounter(int count) {
        LineageSettings.System.putIntForUser(mContext.getContentResolver(),
                LineageSettings.System.LIVE_DISPLAY_HINTED,
                count,
                UserHandle.USER_CURRENT);
        mAwaitingNudge = count > 0;
    }

    private void stopNudgingMe() {
        if (mAwaitingNudge) {
            updateSunsetCounter(1);
        }
    }

    /**
     * Show a friendly notification to the user about the potential benefits of decreasing
     * blue light at night. Do this only once if the feature has not been used after
     * three sunsets. It would be great to enable this by default, but we don't want
     * the change of screen color to be considered a "bug" by a user who doesn't
     * understand what's happening.
     */
    private void nudge() {
        final TwilightState twilight = mTwilightTracker.getCurrentState();
        if (!mReady || !mAwaitingNudge || twilight == null) {
            return;
        }

        int counter = getSunsetCounter();

        // check if we should send the hint only once after sunset
        boolean transition = twilight.isNight() && !mSunset;
        mSunset = twilight.isNight();
        if (!transition) {
            return;
        }

        if (counter <= 0) {
            counter++;
            updateSunsetCounter(counter);
        }
        if (counter == 0) {
            //show the notification and don't come back here
            final Intent intent = new Intent(LineageSettings.ACTION_LIVEDISPLAY_SETTINGS);
            PendingIntent result = PendingIntent.getActivity(mContext, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = new Notification.Builder(mContext)
                    .setContentTitle(mContext.getResources().getString(
                            org.lineageos.platform.internal.R.string.live_display_title))
                    .setContentText(mContext.getResources().getString(
                            org.lineageos.platform.internal.R.string.live_display_hint))
                    .setSmallIcon(org.lineageos.platform.internal.R.drawable.ic_livedisplay_notif)
                    .setStyle(new Notification.BigTextStyle().bigText(mContext.getResources()
                             .getString(
                                     org.lineageos.platform.internal.R.string.live_display_hint)))
                    .setContentIntent(result)
                    .setAutoCancel(true);

            NotificationManager nm =
                    (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notifyAsUser(null, 1, builder.build(), UserHandle.CURRENT);

            updateSunsetCounter(1);
        }
    }

    private int getInt(String setting, int defValue) {
        return LineageSettings.System.getIntForUser(mContext.getContentResolver(),
                setting, defValue, UserHandle.USER_CURRENT);
    }

    private void putInt(String setting, int value) {
        LineageSettings.System.putIntForUser(mContext.getContentResolver(),
                setting, value, UserHandle.USER_CURRENT);
    }
}
