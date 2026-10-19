/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Bypass is always off after a reboot: the charging control settings from before it was turned
 * on are put back as early as the boot allows. A charging-control setting left on would
 * otherwise be applied again on every boot.
 *
 * The fast charging limit does stay on: the kernel forgets it on reboot, so it is written again.
 * On LOCKED_BOOT_COMPLETED init may not have opened up the node yet (it does that on
 * dev.bootcomplete); BOOT_COMPLETED writes it again.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BypassCharging";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        Log.i(TAG, "Boot: turning bypass charging off (" + action + ")");
        new BypassCharging(context).restore();

        ChargeLimit limit = new ChargeLimit(context);
        if (limit.isEnabled() && !limit.apply()) {
            Log.w(TAG, "Boot: fast charging limit not set yet (" + action + ")");
        }
    }
}
