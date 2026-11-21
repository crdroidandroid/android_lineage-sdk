/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.internal.util;

import android.content.ContentResolver;
import android.os.UserHandle;

import lineageos.providers.LineageSettings;

public class DeviceKeysConstants {
    // Available custom actions to perform on a key press.
    // Must match values for KEY_HOME_LONG_PRESS_ACTION in:
    //   sdk/src/java/lineageos/providers/LineageSettings.java
    public enum Action {
        NOTHING,
        MENU,
        APP_SWITCH,
        SEARCH,
        VOICE_SEARCH,
        IN_APP_SEARCH,
        LAUNCH_CAMERA,
        SLEEP,
        LAST_APP,
        SPLIT_SCREEN,
        KILL_APP,
        PLAY_PAUSE_MUSIC,
        SCREENSHOT,
        PARTIAL_SCREENSHOT,
        CYCLE_RINGER_MODE,
        TORCH,
        VOLUME_PANEL,
        CLEAR_ALL_NOTIFICATIONS,
        NOTIFICATIONS,
        QS_PANEL,
        POWER_MENU,
        CONTEXTUAL_SEARCH,
        SILENCE_CALL,
        LAUNCH_APP;

        public static Action fromIntSafe(int id) {
            if (id < NOTHING.ordinal() || id >= Action.values().length) {
                return NOTHING;
            }
            return Action.values()[id];
        }

        public static Action fromSettings(ContentResolver cr, String setting, Action def) {
            return fromIntSafe(LineageSettings.System.getIntForUser(cr,
                    setting, def.ordinal(), UserHandle.USER_CURRENT));
        }
    }

    /** Action of the Quick Tap gesture when KEY_QUICK_TAP_ACTION is unset. */
    public static final Action QUICK_TAP_DEFAULT_ACTION = Action.SCREENSHOT;

    /**
     * Whether the Quick Tap gesture can perform {@code action} while the screen is off.
     */
    public static boolean canQuickTapRunWithScreenOff(Action action) {
        switch (action) {
            case PLAY_PAUSE_MUSIC:
            case CYCLE_RINGER_MODE:
            case TORCH:
            case SILENCE_CALL:
            case SLEEP:
            case LAUNCH_CAMERA:
            case POWER_MENU:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether the Quick Tap gesture wakes the screen before performing {@code action}
     * while the screen is off.
     */
    public static boolean doesQuickTapWakeForAction(Action action) {
        return action == Action.LAUNCH_CAMERA || action == Action.POWER_MENU;
    }

    /**
     * Setting holding the app (flattened ComponentName) opened when the action stored in
     * {@code actionSetting} is {@link Action#LAUNCH_APP}.
     */
    public static String getLaunchAppSetting(String actionSetting) {
        return actionSetting + LineageSettings.System.KEY_ACTION_LAUNCH_APP_SUFFIX;
    }

    /**
     * Setting holding the shortcut id opened when the action stored in {@code actionSetting}
     * is {@link Action#LAUNCH_APP}. Empty or unset opens the app itself.
     */
    public static String getLaunchShortcutSetting(String actionSetting) {
        return actionSetting + LineageSettings.System.KEY_ACTION_LAUNCH_SHORTCUT_SUFFIX;
    }

    // Masks for checking presence of hardware keys.
    // Must match values in:
    //   lineage/res/res/values/config.xml
    public static final int KEY_MASK_HOME = 0x01;
    public static final int KEY_MASK_BACK = 0x02;
    public static final int KEY_MASK_MENU = 0x04;
    public static final int KEY_MASK_ASSIST = 0x08;
    public static final int KEY_MASK_APP_SWITCH = 0x10;
    public static final int KEY_MASK_CAMERA = 0x20;
    public static final int KEY_MASK_VOLUME = 0x40;
}

