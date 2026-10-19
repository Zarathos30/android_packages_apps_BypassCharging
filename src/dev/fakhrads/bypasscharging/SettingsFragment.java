/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SelectorWithWidgetPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SliderPreference;

/**
 * The settings page: bypass on/off and its mode, then the fast charging limit with a live
 * reading of the power going into the battery.
 */
public class SettingsFragment extends SettingsBasePreferenceFragment {
    private static final long READING_INTERVAL_MS = 2000;

    private BypassCharging mBypass;
    private ChargeLimit mLimit;
    private BatteryManager mBatteryManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private MainSwitchPreference mBypassSwitch;
    private SelectorWithWidgetPreference mModeSmart;
    private SelectorWithWidgetPreference mModeBypass;
    private SelectorWithWidgetPreference mLimitOff;
    private SelectorWithWidgetPreference mLimitOn;
    private SliderPreference mWatts;
    private Preference mReading;

    // From the last ACTION_BATTERY_CHANGED
    private int mVoltageMv;
    private int mStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            mStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            updateReading();
        }
    };

    private final Runnable mReadingLoop = new Runnable() {
        @Override
        public void run() {
            updateReading();
            mHandler.postDelayed(this, READING_INTERVAL_MS);
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = requireContext();
        mBypass = new BypassCharging(context);
        mLimit = new ChargeLimit(context);
        mBatteryManager = context.getSystemService(BatteryManager.class);

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        mBypassSwitch = new MainSwitchPreference(context);
        mBypassSwitch.setTitle(R.string.bypass_switch_title);
        mBypassSwitch.setOnPreferenceChangeListener((pref, value) -> {
            boolean ok = mBypass.setActive((Boolean) value);
            if (!ok) {
                showFailed(mBypass.getLastError());
            }
            changed();
            return ok;
        });
        screen.addPreference(mBypassSwitch);

        PreferenceCategory modes = new PreferenceCategory(context);
        modes.setTitle(R.string.category_bypass_mode);
        screen.addPreference(modes);
        mModeSmart = selector(modes, R.string.mode_smart, getString(R.string.summary_smart,
                BypassCharging.SMART_LIMIT, BypassCharging.SMART_LIMIT - 3));
        mModeBypass = selector(modes, R.string.mode_bypass, getString(R.string.summary_bypass));
        mModeSmart.setOnClickListener(pref -> setMode(BypassCharging.MODE_SMART));
        mModeBypass.setOnClickListener(pref -> setMode(BypassCharging.MODE_BYPASS));

        PreferenceCategory speed = new PreferenceCategory(context);
        speed.setTitle(R.string.category_fast_charging);
        screen.addPreference(speed);
        mLimitOff = selector(speed, R.string.limit_off_title, getString(R.string.limit_off_summary));
        mLimitOn = selector(speed, R.string.limit_on_title, null);
        mLimitOff.setOnClickListener(pref -> setLimit(false, mLimit.getWatts()));
        mLimitOn.setOnClickListener(pref -> setLimit(true, mLimit.getWatts()));

        mWatts = new SliderPreference(context);
        mWatts.setTitle(R.string.limit_watts_title);
        mWatts.setMin(ChargeLimit.MIN_WATTS);
        mWatts.setMax(ChargeLimit.MAX_WATTS);
        mWatts.setSliderIncrement(ChargeLimit.STEP_WATTS);
        mWatts.setTickVisible(true);
        mWatts.setShowSliderValue(true);
        mWatts.setIconSpaceReserved(false);
        // Not persisted by the preference: ChargeLimit keeps the value
        mWatts.setPersistent(false);
        mWatts.setOnPreferenceChangeListener((pref, value) -> {
            setLimit(true, (Integer) value);
            return true;
        });
        speed.addPreference(mWatts);

        mReading = new Preference(context);
        mReading.setTitle(R.string.reading_title);
        mReading.setSelectable(false);
        mReading.setIconSpaceReserved(false);
        speed.addPreference(mReading);

        FooterPreference footer = new FooterPreference(context);
        footer.setSummary(R.string.footer);
        screen.addPreference(footer);

        setPreferenceScreen(screen);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        Intent sticky = requireContext().registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            mBatteryReceiver.onReceive(requireContext(), sticky);
        }
        mHandler.post(mReadingLoop);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mReadingLoop);
        requireContext().unregisterReceiver(mBatteryReceiver);
    }

    private SelectorWithWidgetPreference selector(PreferenceCategory category, int title,
            CharSequence summary) {
        SelectorWithWidgetPreference pref = new SelectorWithWidgetPreference(requireContext());
        pref.setTitle(title);
        pref.setSummary(summary);
        pref.setPersistent(false);
        category.addPreference(pref);
        return pref;
    }

    private void setMode(int mode) {
        if (!mBypass.setMode(mode)) {
            showFailed(mBypass.getLastError());
        }
        changed();
    }

    private void setLimit(boolean enabled, int watts) {
        if (!mLimit.set(enabled, watts)) {
            showFailed(getString(R.string.limit_failed));
        }
        refresh();
    }

    private void changed() {
        refresh();
        TileService.requestListeningState(requireContext(),
                new ComponentName(requireContext(), BypassTileService.class));
    }

    private void refresh() {
        boolean supported = mBypass.isSupported();
        boolean active = supported && mBypass.isActive();
        boolean bypassMode = mBypass.getMode() == BypassCharging.MODE_BYPASS;
        mBypassSwitch.setEnabled(supported);
        mBypassSwitch.setChecked(active);
        if (!supported) {
            mBypassSwitch.setSummary(R.string.not_supported);
        } else if (!active) {
            mBypassSwitch.setSummary(R.string.status_off);
        } else {
            mBypassSwitch.setSummary(getString(bypassMode
                    ? R.string.subtitle_bypass_on : R.string.subtitle_smart_on,
                    mBypass.getActiveLimit()));
        }
        mModeSmart.setChecked(!bypassMode);
        mModeBypass.setChecked(bypassMode);

        boolean limitSupported = mLimit.isSupported();
        boolean limited = mLimit.isEnabled();
        int watts = mLimit.getWatts();
        mLimitOff.setEnabled(limitSupported);
        mLimitOn.setEnabled(limitSupported);
        mLimitOff.setChecked(!limited);
        mLimitOn.setChecked(limited);
        mLimitOn.setSummary(limitSupported ? getString(R.string.limit_on_summary, watts)
                : getString(R.string.limit_not_supported));
        mWatts.setValue(watts);
        mWatts.setSummary(getString(R.string.watts, watts));
        mWatts.setEnabled(limitSupported && limited);
    }

    private void updateReading() {
        if (mReading == null) {
            return;
        }
        int microAmps = mBatteryManager == null ? 0
                : mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (mStatus != BatteryManager.BATTERY_STATUS_CHARGING || microAmps == 0
                || microAmps == Integer.MIN_VALUE || mVoltageMv <= 0) {
            mReading.setSummary(R.string.reading_idle);
            return;
        }
        // The sign of current_now differs between drivers
        double amps = Math.abs(microAmps) / 1e6;
        double volts = mVoltageMv / 1e3;
        mReading.setSummary(getString(R.string.reading_value, amps * volts, volts, amps));
    }

    private void showFailed(String error) {
        Toast.makeText(requireContext(), R.string.toggle_failed, Toast.LENGTH_SHORT).show();
        // Full reason and the grant state of each requested permission, to copy into a report
        StringBuilder sb = new StringBuilder(error == null ? "unknown error" : error);
        Context context = requireContext();
        try {
            String[] perms = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (perms != null) {
                for (String perm : perms) {
                    sb.append('\n').append(perm).append(": ")
                            .append(context.checkSelfPermission(perm)
                                    == PackageManager.PERMISSION_GRANTED ? "granted" : "DENIED");
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Not possible for our own package
        }
        String details = sb.toString();
        new AlertDialog.Builder(context)
                .setTitle(R.string.toggle_failed)
                .setMessage(details)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(android.R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.app_name), details));
                })
                .show();
    }
}
