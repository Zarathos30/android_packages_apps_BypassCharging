/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.app.Application;

/** The app's process is persistent so cool charging can follow the battery temperature. */
public class BypassChargingApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CoolCharging.get(this).start();
    }
}
