/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Caps the fast charging power through the MCA charge interface (ichg_limit).
 *
 * Each write is "user type value" and adds a vote from this app to a MCA_VOTE_MIN votable, so
 * the battery current is the lowest of this limit, the thermal limits and the charger's own
 * limits: it can only make charging slower than stock, never faster.
 * - quick ("single#multi" mA): charge pump current, with one or both pumps
 * - buck (mA): PMIC charging, used by slow chargers and when the charge pump hands over
 * Writing 0 drops the limit. The votes stay across unplugging and are gone after a reboot, so
 * BootReceiver applies the limit again.
 *
 * The driver limits current into the battery, not power from the charger. The limit is set in
 * watts and turned into current at REFERENCE_MV, a battery voltage typical of fast charging;
 * the real power drifts a little with the battery voltage (about 3.8 V to 4.5 V).
 *
 * The node is labeled vendor_sysfs_charge_limit and made writable by init.onyx.rc, in the
 * device tree.
 */
final class ChargeLimit {
    private static final String TAG = "BypassCharging";

    private static final String NODE =
            "/sys/devices/platform/soc/soc:mca_charge_interface/ichg_limit";
    // Voter name in the MCA votables, shown by the driver logs
    private static final String USER = "bypasscharging";

    static final int MIN_WATTS = 5;
    // onyx charges the battery at 15.6 A at most (div4), about 65 W at 4.2 V
    static final int MAX_WATTS = 60;
    static final int STEP_WATTS = 5;
    static final int DEFAULT_WATTS = 30;
    private static final int REFERENCE_MV = 4200;

    private static final String PREFS = "bypass_charging";
    private static final String KEY_ENABLED = "limit_enabled";
    private static final String KEY_WATTS = "limit_watts";

    private final SharedPreferences mPrefs;

    ChargeLimit(Context context) {
        // Device protected storage: BootReceiver runs before the first unlock
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isSupported() {
        return new File(NODE).canWrite();
    }

    boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, false);
    }

    int getWatts() {
        return mPrefs.getInt(KEY_WATTS, DEFAULT_WATTS);
    }

    /** @return true if the kernel took the new setting */
    boolean set(boolean enabled, int watts) {
        watts = Math.max(MIN_WATTS, Math.min(watts, MAX_WATTS));
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).putInt(KEY_WATTS, watts).apply();
        return apply();
    }

    /** Writes the saved setting to the kernel. */
    boolean apply() {
        return vote(USER, isEnabled() ? getWatts() * 1000000 / REFERENCE_MV : 0);
    }

    /** Sets the battery current limit (mA) of voter {@code user}; 0 drops its vote. */
    static boolean vote(String user, int ma) {
        return write(user, "quick " + ma + "#" + ma) && write(user, "buck " + ma);
    }

    private static boolean write(String user, String command) {
        // One vote per write: the driver parses a single "user type value" per store
        try (FileOutputStream out = new FileOutputStream(NODE)) {
            out.write((user + " " + command).getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write '" + command + "' to " + NODE, e);
            return false;
        }
    }
}
