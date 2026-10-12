package com.phantom.automation.ui;

import android.app.Activity;
import android.content.Intent;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.phantom.automation.R;

/** Shared bottom-navigation wiring for the five tab activities. */
public final class NavHelper {
    private NavHelper() {
    }

    public static void bind(Activity activity, int viewId, int selectedId) {
        BottomNavigationView nav = activity.findViewById(viewId);
        if (nav == null) {
            return;
        }
        nav.setSelectedItemId(selectedId);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedId) {
                return true;
            }
            Class<?> target = null;
            if (id == R.id.nav_home) {
                target = MainActivity.class;
            } else if (id == R.id.nav_sessions) {
                target = SessionsActivity.class;
            } else if (id == R.id.nav_apps) {
                target = LauncherActivity.class;
            } else if (id == R.id.nav_inspect) {
                target = InspectorActivity.class;
            } else if (id == R.id.nav_settings) {
                target = SettingsActivity.class;
            }
            if (target != null) {
                activity.startActivity(new Intent(activity, target));
                activity.overridePendingTransition(0, 0);
            }
            return true;
        });
    }
}
