/**
 * Copyright (C) 2017-2018 The LineageOS project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.internal.statusbar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.R;

public class NetworkTraffic extends TextView {
    private static final String TAG = "NetworkTraffic";

    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;

    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;

    private static final int REFRESH_INTERVAL = 2000;

    private static final int UNITS_KILOBITS = 0;
    private static final int UNITS_MEGABITS = 1;
    private static final int UNITS_KILOBYTES = 2;
    private static final int UNITS_MEGABYTES = 3;

    // Thresholds themselves are always defined in kbps
    private static final long AUTOHIDE_THRESHOLD_KILOBITS  = 10;
    private static final long AUTOHIDE_THRESHOLD_MEGABITS  = 100;
    private static final long AUTOHIDE_THRESHOLD_KILOBYTES = 8;
    private static final long AUTOHIDE_THRESHOLD_MEGABYTES = 80;

    protected int mLocation = 0;
    private int mMode = MODE_UPSTREAM_AND_DOWNSTREAM;
    protected boolean mBelowThreshold;
    protected boolean mConnectionAvailable;
    private long mTxKbps;
    private long mRxKbps;
    private long mLastTxBytesTotal;
    private long mLastRxBytesTotal;
    private long mLastUpdateTime;
    private int mTextSizeSingle;
    private int mTextSizeMulti;
    private long mAutoHideThreshold;
    private int mUnits;
    private boolean mShowUnits;
    protected int mIconTint = Color.WHITE;
    private SettingsObserver mObserver;
    private Drawable mDrawable;

    private boolean mScreenOn = true;
    private IDreamManager mDreamManager;

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources resources = getResources();
        mTextSizeSingle = resources.getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        mTextSizeMulti = resources.getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);

        mObserver = new SettingsObserver(mTrafficHandler);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);

        mContext.registerReceiver(mIntentReceiver, filter);
        mObserver.observe();
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mContext.unregisterReceiver(mIntentReceiver);
        mObserver.unobserve();
    }

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long now = SystemClock.elapsedRealtime();
            long timeDelta = now - mLastUpdateTime;
            if (msg.what == MESSAGE_TYPE_PERIODIC_REFRESH
                    && timeDelta >= REFRESH_INTERVAL * 0.95f) {
                // Update counters
                mLastUpdateTime = now;
                long txBytes = TrafficStats.getTotalTxBytes() - mLastTxBytesTotal;
                long rxBytes = TrafficStats.getTotalRxBytes() - mLastRxBytesTotal;
                mTxKbps = (long) (txBytes * 8f / (timeDelta / 1000f) / 1000f);
                mRxKbps = (long) (rxBytes * 8f / (timeDelta / 1000f) / 1000f);
                mLastTxBytesTotal += txBytes;
                mLastRxBytesTotal += rxBytes;
            }

            final boolean enabled = mLocation != 0;
            final boolean showUpstream =
                    mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean showDownstream =
                    mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            mBelowThreshold = mAutoHideThreshold > 0 && (!showUpstream || mTxKbps < mAutoHideThreshold)
                    && (!showDownstream || mRxKbps < mAutoHideThreshold);
            mConnectionAvailable = isConnectionAvailable();

            if (enabled && mConnectionAvailable && !mBelowThreshold) {
                // Get information for uplink ready so the line return can be added
                StringBuilder output = new StringBuilder();
                if (showUpstream) {
                    output.append(formatOutput(mTxKbps));
                }

                // Ensure text size is where it needs to be
                int textSize;
                if (showUpstream && showDownstream) {
                    output.append("\n");
                    textSize = mTextSizeMulti;
                } else {
                    textSize = mTextSizeSingle;
                }

                // Add information for downlink if it's called for
                if (showDownstream) {
                    output.append(formatOutput(mRxKbps));
                }

                // Update view if there's anything new to show
                if (!output.toString().contentEquals(getText())) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) textSize);
                    setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    setText(output.toString());
                }
            }
            updateVisibility();

            // Schedule periodic refresh
            mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
            if (enabled) {
                mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                        REFRESH_INTERVAL);
            }
        }

        private String formatOutput(long kbps) {
            final String value;
            final String unit;
            switch (mUnits) {
                case UNITS_KILOBITS:
                    value = String.format("%d", kbps);
                    unit = mContext.getString(R.string.kilobitspersecond_short);
                    break;
                case UNITS_MEGABITS:
                    value = String.format("%.1f", (float) kbps / 1000);
                    unit = mContext.getString(R.string.megabitspersecond_short);
                    break;
                case UNITS_KILOBYTES:
                    value = String.format("%d", kbps / 8);
                    unit = mContext.getString(R.string.kilobytespersecond_short);
                    break;
                case UNITS_MEGABYTES:
                    value = String.format("%.2f", (float) kbps / 8000);
                    unit = mContext.getString(R.string.megabytespersecond_short);
                    break;
                default:
                    value = "unknown";
                    unit = "unknown";
                    break;
            }

            if (mShowUnits) {
                return value + " " + unit;
            } else {
                return value;
            }
        }
    };

    protected void updateVisibility() {
        boolean enabled = mConnectionAvailable && !mBelowThreshold &&
                (mLocation == 2);
        if (enabled) {
            setVisibility(VISIBLE);
        } else {
            setText("");
            setVisibility(GONE);
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) && mScreenOn) {
                updateViewState();
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                if (!isDozeMode()) {
                    mScreenOn = true;
                    updateViewState();
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                clearHandlerCallbacks();
            }
        }
    };

    private boolean isDozeMode() {
        try {
            if (mDreamManager != null && mDreamManager.isDozing()) {
                return true;
            }
        } catch (RemoteException e) {
            return false;
        }
        return false;
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_LOCATION),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_UNITS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS),
                    false, this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private boolean isConnectionAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mLocation = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_LOCATION, 0, UserHandle.USER_CURRENT);
        mMode = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_MODE, MODE_UPSTREAM_AND_DOWNSTREAM, UserHandle.USER_CURRENT);
        mAutoHideThreshold = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT);
        mUnits = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_UNITS, /* Mbps */ 1,
                UserHandle.USER_CURRENT);
        mShowUnits = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_SHOW_UNITS, 1,
                UserHandle.USER_CURRENT) == 1;

        if (mLocation != 0) {
            updateTrafficDrawable();
            updateVisibility();
        }

        updateViewState();
    }

    private void updateViewState() {
        mTrafficHandler.sendEmptyMessage(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
    }

    protected void updateTrafficDrawable() {
        final int drawableResId;
        if (mMode == MODE_UPSTREAM_AND_DOWNSTREAM) {
            drawableResId = R.drawable.stat_sys_network_traffic_updown;
        } else if (mMode == MODE_UPSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_up;
        } else if (mMode == MODE_DOWNSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_down;
        } else {
            drawableResId = 0;
        }
        mDrawable = drawableResId != 0 ? getResources().getDrawable(drawableResId) : null;
        if (mDrawable != null) {
            mDrawable.setColorFilter(mIconTint, PorterDuff.Mode.MULTIPLY);
        }
        setCompoundDrawablesWithIntrinsicBounds(null, null, mDrawable, null);
        setTextColor(mIconTint);
    }
}
