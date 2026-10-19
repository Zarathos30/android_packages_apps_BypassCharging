/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.fakhrads.bypasscharging;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Opened from a long press on the tile; the page itself is SettingsFragment. */
public class MainActivity extends CollapsingToolbarBaseActivity {
    private static final String TAG = "SettingsFragment";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new SettingsFragment(), TAG)
                    .commit();
        }
    }
}
