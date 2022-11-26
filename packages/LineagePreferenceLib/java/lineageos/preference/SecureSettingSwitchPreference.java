/*
 * SPDX-FileCopyrightText: 2014-2016 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2018 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package lineageos.preference;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;

public class SecureSettingSwitchPreference extends SelfRemovingSwitchPreference {

    public SecureSettingSwitchPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public SecureSettingSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SecureSettingSwitchPreference(Context context) {
        super(context, null);
    }

    @Override
    protected boolean isPersisted() {
        return Settings.Secure.getString(getContext().getContentResolver(), getKey()) != null;
    }

    @Override
    protected void putBoolean(String key, boolean value) {
        Settings.Secure.putIntForUser(getContext().getContentResolver(), key, value ? 1 : 0, UserHandle.USER_CURRENT);
    }

    @Override
    protected boolean getBoolean(String key, boolean defaultValue) {
        return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                key, defaultValue ? 1 : 0, UserHandle.USER_CURRENT) != 0;
    }
}
