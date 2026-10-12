package com.phantom.automation.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.phantom.automation.R;
import com.phantom.automation.configuration.PhantomSettings;
import com.phantom.automation.rpc.Rpc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Session list, creation (resolution/density/codec), open, close. */
public final class SessionsActivity extends Activity {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private LinearLayout list;
    private TextView stateLine;
    private TextInputEditText widthInput;
    private TextInputEditText heightInput;
    private Spinner codecSpinner;
    private View loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sessions);
        list = findViewById(R.id.sessionList);
        stateLine = findViewById(R.id.stateLine);
        widthInput = findViewById(R.id.widthInput);
        heightInput = findViewById(R.id.heightInput);
        codecSpinner = findViewById(R.id.codecSpinner);
        loading = findViewById(R.id.loading);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"auto", "avc", "hevc"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        codecSpinner.setAdapter(adapter);
        PhantomSettings settings = PhantomSettings.load(this);
        widthInput.setText(String.valueOf(settings.displayWidth));
        heightInput.setText(String.valueOf(settings.displayHeight));
        codecSpinner.setSelection(indexOf(settings.codec));
        findViewById(R.id.createButton).setOnClickListener(v -> createSession());
        findViewById(R.id.refreshButton).setOnClickListener(v -> refresh());
        NavHelper.bind(this, R.id.bottomNav, R.id.nav_sessions);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private int indexOf(String value) {
        for (int i = 0; i < codecSpinner.getCount(); i++) {
            if (codecSpinner.getItemAtPosition(i).toString().equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private void refresh() {
        setLoading(true);
        stateLine.setText("");
        io.execute(() -> {
            try {
                JSONObject result = Rpc.call("status", new JSONObject(), 8000);
                JSONArray sessions = result.optJSONArray("mcpSessions");
                runOnUiThread(() -> {
                    setLoading(false);
                    render(sessions == null ? new JSONArray() : sessions);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    list.removeAllViews();
                    stateLine.setText(e.getMessage());
                });
            }
        });
    }

    private void createSession() {
        int width = number(widthInput, 720);
        int height = number(heightInput, 1280);
        String codec = codecSpinner.getSelectedItem().toString();
        setLoading(true);
        io.execute(() -> {
            try {
                JSONObject params = new JSONObject()
                        .put("width", width).put("height", height).put("codec", codec);
                JSONObject result = Rpc.call("openSession", params, 30000);
                runOnUiThread(() -> {
                    setLoading(false);
                    openDetail(result.optString("sessionId"),
                            result.optInt("width", width), result.optInt("height", height));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    snack(e.getMessage());
                });
            }
        });
    }

    private void closeSession(String sessionId) {
        io.execute(() -> {
            try {
                Rpc.call("closeSession",
                        new JSONObject().put("sessionId", sessionId), 10000);
                runOnUiThread(this::refresh);
            } catch (Exception e) {
                runOnUiThread(() -> snack(e.getMessage()));
            }
        });
    }

    private void openDetail(String sessionId, int width, int height) {
        Intent intent = new Intent(this, SessionDetailActivity.class);
        intent.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, sessionId);
        intent.putExtra(SessionDetailActivity.EXTRA_WIDTH, width);
        intent.putExtra(SessionDetailActivity.EXTRA_HEIGHT, height);
        startActivity(intent);
    }

    private void render(JSONArray sessions) {
        list.removeAllViews();
        if (sessions.length() == 0) {
            stateLine.setText("No sessions yet — create one above.");
            return;
        }
        stateLine.setText(sessions.length() + " active session"
                + (sessions.length() == 1 ? "" : "s") + " · tap to open, ✕ to close.");
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            list.addView(card(item));
        }
    }

    private MaterialCardView card(JSONObject item) {
        String id = item.optString("sessionId");
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 8;
        card.setLayoutParams(params);
        card.setCardBackgroundColor(0xFF141926);
        card.setRadius(8);
        card.setCardElevation(0);
        card.setStrokeColor(0xFF232B3D);
        card.setStrokeWidth(2);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openDetail(id, item.optInt("width"), item.optInt("height")));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(16, 16, 16, 16);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        text.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText(id + " · " + item.optString("state"));
        title.setTextColor(0xFFE8EDF2);
        title.setTextSize(15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        text.addView(title);

        String sub = "display " + item.optInt("displayId")
                + " · " + item.optInt("width") + "x" + item.optInt("height")
                + " · " + item.optString("codec")
                + ("recording".equals(item.optString("recordingState")) ? " · ●REC" : "")
                + (item.optString("foregroundApp").isEmpty()
                        ? "" : "\n" + item.optString("foregroundApp"));
        String err = item.optString("lastError");
        if (!err.isEmpty() && !"ERROR".equals(item.optString("state"))) {
            err = "";
        }
        TextView subtitle = new TextView(this);
        subtitle.setText(sub + (err.isEmpty() ? "" : "\n" + err));
        subtitle.setTextColor(0xFF9AA7B4);
        subtitle.setTextSize(13);
        text.addView(subtitle);
        row.addView(text);

        MaterialButton close = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        close.setText("✕");
        close.setOnClickListener(v -> closeSession(id));
        row.addView(close);
        card.addView(row);
        return card;
    }

    private int number(TextInputEditText input, int fallback) {
        try {
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            return text.isEmpty() ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void setLoading(boolean loading) {
        this.loading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void snack(String message) {
        Snackbar.make(findViewById(R.id.root), message == null ? "Error" : message,
                Snackbar.LENGTH_LONG).show();
    }
}
