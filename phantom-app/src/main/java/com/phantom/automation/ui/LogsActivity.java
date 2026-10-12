package com.phantom.automation.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.phantom.automation.R;
import com.phantom.automation.rpc.Rpc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shell logs with severity/session filters, copy and export. */
public final class LogsActivity extends Activity {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private TextView logView;
    private ScrollView scroller;
    private Spinner severitySpinner;
    private final List<String> lines = new ArrayList<>();
    private String sessionFilter = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        logView = findViewById(R.id.logText);
        scroller = findViewById(R.id.logScroll);
        severitySpinner = findViewById(R.id.severitySpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"all", "info", "error"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        severitySpinner.setAdapter(adapter);
        severitySpinner.setOnItemSelectedListener(new SimpleSelectedListener(this::render));
        findViewById(R.id.logRefresh).setOnClickListener(v -> refresh());
        findViewById(R.id.copyButton).setOnClickListener(v -> copy());
        findViewById(R.id.exportButton).setOnClickListener(v -> export());
        com.google.android.material.textfield.TextInputEditText filter =
                findViewById(R.id.sessionFilter);
        filter.addTextChangedListener(new SimpleWatcher(text -> {
            sessionFilter = text.trim();
            render();
        }));
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

    private void refresh() {
        io.execute(() -> {
            try {
                JSONArray logs = Rpc.callArray("logs", new JSONObject(), 10000);
                List<String> next = new ArrayList<>();
                for (int i = 0; i < logs.length(); i++) {
                    next.add(logs.optString(i));
                }
                runOnUiThread(() -> {
                    lines.clear();
                    lines.addAll(next);
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    lines.clear();
                    lines.add("[error] " + e.getMessage());
                    render();
                });
            }
        });
    }

    private void render() {
        String severity = severitySpinner.getSelectedItem().toString();
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String line : lines) {
            if ("error".equals(severity) && !isError(line)) {
                continue;
            }
            if ("info".equals(severity) && isError(line)) {
                continue;
            }
            if (!sessionFilter.isEmpty() && !line.contains(sessionFilter)) {
                continue;
            }
            out.append(line).append('\n');
            shown++;
        }
        if (shown == 0) {
            out.append(lines.isEmpty() ? "No logs yet." : "No lines match the filters.");
        }
        logView.setText(out.toString());
        scroller.post(() -> scroller.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private boolean isError(String line) {
        String lower = line.toLowerCase();
        return lower.contains("error") || lower.contains("failed")
                || lower.contains("exception") || lower.startsWith("[error]");
    }

    private String filteredText() {
        return logView.getText().toString();
    }

    private void copy() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Phantom logs", filteredText()));
            snack("Copied to clipboard.");
        }
    }

    private void export() {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Phantom logs")
                .putExtra(Intent.EXTRA_TEXT, filteredText());
        startActivity(Intent.createChooser(share, "Export logs"));
    }

    private void snack(String message) {
        Snackbar.make(findViewById(R.id.root), message == null ? "Error" : message,
                Snackbar.LENGTH_LONG).show();
    }

    private static final class SimpleWatcher implements android.text.TextWatcher {
        interface Callback {
            void onText(String text);
        }

        private final Callback callback;

        SimpleWatcher(Callback callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            callback.onText(s.toString());
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }

    private static final class SimpleSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable callback;

        SimpleSelectedListener(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                   int position, long id) {
            callback.run();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
