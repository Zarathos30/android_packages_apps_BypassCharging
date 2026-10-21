/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Cool charging: slows fast charging step by step once the battery passes 40 °C, so it settles
 * around 42 °C instead of climbing to 47 °C.
 *
 * The charger driver (MCA JEITA tables) keeps full current up to 48 °C. This adds its own vote
 * to the ichg_limit MIN votable that ChargeLimit uses, so it can only lower the current: the
 * user's power limit, the thermal limits and the driver's own limits all still apply.
 *
 * It follows ACTION_BATTERY_CHANGED, which BatteryService sends on every temperature change, in
 * the app's persistent process. The vote is dropped when the charger is unplugged or the feature
 * is turned off, and the first update after the process starts always writes, so a vote left by
 * an earlier process is replaced.
 */
final class CoolCharging {
    private static final String TAG = "CoolCharging";

    // Voter name in the MCA votables, next to ChargeLimit's
    private static final String USER = "coolcharging";

    // Battery temperature (tenths of °C) at which each step starts, and its battery current (mA)
    private static final int[] STEP_TEMPS = {400, 410, 420, 430};
    private static final int[] STEP_MA = {10000, 7000, 4500, 2500};
    // A step is left once the battery is this much (tenths of °C) below where it starts
    private static final int HYSTERESIS = 5;

    private static final int NO_STEP = -1;
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private static final String PREFS = "bypass_charging";
    private static final String KEY_ENABLED = "cool_enabled";

    private static CoolCharging sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler;

    // Only touched on mHandler's thread
    private Intent mBattery;
    private int mStep = UNKNOWN;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mBattery = intent;
            update();
        }
    };

    static synchronized CoolCharging get(Context context) {
        if (sInstance == null) {
            sInstance = new CoolCharging(context.getApplicationContext());
        }
        return sInstance;
    }

    private CoolCharging(Context context) {
        mContext = context;
        // Device protected storage: the process starts before the first unlock
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    void start() {
        mContext.registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                null, mHandler);
    }

    boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        mHandler.post(this::update);
    }

    private void update() {
        if (mBattery == null) {
            return;
        }
        int temp = mBattery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN);
        boolean plugged = mBattery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        int step = !isEnabled() || !plugged || temp == UNKNOWN ? NO_STEP : stepFor(temp);
        if (step == mStep) {
            return;
        }
        int ma = step == NO_STEP ? 0 : STEP_MA[step];
        if (ChargeLimit.vote(USER, ma)) {
            Log.i(TAG, "Battery " + temp / 10f + " °C: "
                    + (ma == 0 ? "no limit" : "limit " + ma + " mA"));
            mStep = step;
        }
    }

    private int stepFor(int temp) {
        int step = mStep == UNKNOWN ? NO_STEP : mStep;
        while (step + 1 < STEP_TEMPS.length && temp >= STEP_TEMPS[step + 1]) {
            step++;
        }
        while (step > NO_STEP && temp < STEP_TEMPS[step] - HYSTERESIS) {
            step--;
        }
        return step;
    }
}
