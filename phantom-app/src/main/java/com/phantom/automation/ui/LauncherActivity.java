package com.phantom.automation.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.phantom.automation.R;
import com.phantom.automation.configuration.PhantomSettings;
import com.phantom.automation.rpc.Rpc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installed apps with search, icons, and launch-into-session. */
public final class LauncherActivity extends Activity {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private LinearLayout list;
    private TextView stateLine;
    private TextInputEditText search;
    private final List<AppEntry> apps = new ArrayList<>();
    private String defaultSession = "";

    private static final class AppEntry {
        String name;
        String pkg;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        list = findViewById(R.id.appList);
        stateLine = findViewById(R.id.stateLine);
        search = findViewById(R.id.searchInput);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                render(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        NavHelper.bind(this, R.id.bottomNav, R.id.nav_apps);
    }

    @Override
    protected void onResume() {
        super.onResume();
        defaultSession = PhantomSettings.load(this).defaultSession;
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        stateLine.setText("Loading…");
        io.execute(() -> {
            try {
                JSONObject result = Rpc.call("listApps", new JSONObject(), 30000);
                JSONArray array = result.optJSONArray("apps");
                List<AppEntry> loaded = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject app = array.optJSONObject(i);
                        if (app == null) {
                            continue;
                        }
                        AppEntry entry = new AppEntry();
                        entry.name = app.optString("name");
                        entry.pkg = app.optString("packageName");
                        loaded.add(entry);
                    }
                }
                runOnUiThread(() -> {
                    apps.clear();
                    apps.addAll(loaded);
                    render(search.getText() == null ? "" : search.getText().toString());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    list.removeAllViews();
                    stateLine.setText(e.getMessage());
                });
            }
        });
    }

    private void render(String filter) {
        list.removeAllViews();
        String needle = filter.trim().toLowerCase();
        int shown = 0;
        for (AppEntry app : apps) {
            if (!needle.isEmpty()
                    && !app.name.toLowerCase().contains(needle)
                    && !app.pkg.toLowerCase().contains(needle)) {
                continue;
            }
            list.addView(card(app));
            shown++;
        }
        stateLine.setText(shown + " of " + apps.size() + " apps"
                + (defaultSession.isEmpty()
                        ? " · set a default session in Settings to launch."
                        : " · launching into " + defaultSession + "."));
    }

    private MaterialCardView card(AppEntry app) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 8;
        card.setLayoutParams(params);
        card.setCardBackgroundColor(0xFF141926);
        card.setRadius(16);
        card.setCardElevation(2);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> launch(app));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 12, 16, 12);

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(96, 96);
        icon.setLayoutParams(iconParams);
        row.addView(icon);
        loadIcon(icon, app.pkg);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = 12;
        text.setLayoutParams(textParams);

        TextView name = new TextView(this);
        name.setText(app.name);
        name.setTextColor(0xFFE8EDF2);
        name.setTextSize(15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        text.addView(name);

        TextView pkg = new TextView(this);
        pkg.setText(app.pkg);
        pkg.setTextColor(0xFF9AA7B4);
        pkg.setTextSize(12);
        text.addView(pkg);
        row.addView(text);
        card.addView(row);
        return card;
    }

    private void loadIcon(ImageView view, String pkg) {
        io.execute(() -> {
            try {
                Drawable drawable = getPackageManager().getApplicationIcon(pkg);
                runOnUiThread(() -> view.setImageDrawable(drawable));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        });
    }

    private void launch(AppEntry app) {
        if (defaultSession.isEmpty()) {
            Snackbar.make(findViewById(R.id.root),
                    "Set a default session in Settings first.", Snackbar.LENGTH_LONG).show();
            return;
        }
        io.execute(() -> {
            try {
                Rpc.call("launchApp", new JSONObject()
                        .put("sessionId", defaultSession)
                        .put("packageName", app.pkg), 45000);
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.root),
                        "Launched " + app.name + " in " + defaultSession + ".",
                        Snackbar.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.root),
                        e.getMessage() == null ? "Launch failed" : e.getMessage(),
                        Snackbar.LENGTH_LONG).show());
            }
        });
    }
}
