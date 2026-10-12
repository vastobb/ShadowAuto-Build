package com.phantom.automation.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.phantom.automation.R;
import com.phantom.automation.configuration.PhantomSettings;
import com.phantom.automation.ocr.OcrProvisioner;
import com.phantom.automation.rpc.Rpc;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Grouped settings with save confirmation and OCR provisioning. */
public final class SettingsActivity extends Activity {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private TextInputEditText defaultSession;
    private TextInputEditText width;
    private TextInputEditText height;
    private TextInputEditText dpi;
    private Spinner codec;
    private TextInputEditText bitRate;
    private TextInputEditText frameRate;
    private TextInputEditText apiBase;
    private TextInputEditText apiKey;
    private TextInputEditText model;
    private TextView ocrStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        defaultSession = findViewById(R.id.setDefaultSession);
        width = findViewById(R.id.setWidth);
        height = findViewById(R.id.setHeight);
        dpi = findViewById(R.id.setDpi);
        codec = findViewById(R.id.setCodec);
        bitRate = findViewById(R.id.setBitRate);
        frameRate = findViewById(R.id.setFrameRate);
        apiBase = findViewById(R.id.setApiBase);
        apiKey = findViewById(R.id.setApiKey);
        model = findViewById(R.id.setModel);
        ocrStatus = findViewById(R.id.ocrStatus);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"auto", "avc", "hevc"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        codec.setAdapter(adapter);
        load();
        findViewById(R.id.saveButton).setOnClickListener(v -> save());
        findViewById(R.id.ocrProvisionButton).setOnClickListener(v -> provisionOcr());
        findViewById(R.id.ocrRefreshButton).setOnClickListener(v -> queryOcrStatus());
        findViewById(R.id.logsButton).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LogsActivity.class)));
        NavHelper.bind(this, R.id.bottomNav, R.id.nav_settings);
    }

    @Override
    protected void onResume() {
        super.onResume();
        queryOcrStatus();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        PhantomSettings settings = PhantomSettings.load(this);
        set(defaultSession, settings.defaultSession);
        set(width, String.valueOf(settings.displayWidth));
        set(height, String.valueOf(settings.displayHeight));
        set(dpi, String.valueOf(settings.displayDpi));
        codec.setSelection(indexOf(settings.codec));
        set(bitRate, settings.bitRate > 0 ? String.valueOf(settings.bitRate) : "");
        set(frameRate, settings.frameRate > 0 ? String.valueOf(settings.frameRate) : "");
        set(apiBase, settings.apiBase);
        set(apiKey, settings.apiKey);
        set(model, settings.model);
    }

    private void set(TextInputEditText field, String value) {
        field.setText(value == null ? "" : value);
    }

    private int indexOf(String value) {
        for (int i = 0; i < codec.getCount(); i++) {
            if (codec.getItemAtPosition(i).toString().equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private void save() {
        PhantomSettings settings = PhantomSettings.load(this);
        settings.defaultSession = text(defaultSession).trim();
        settings.displayWidth = number(width, 720);
        settings.displayHeight = number(height, 1280);
        settings.displayDpi = number(dpi, 320);
        settings.codec = codec.getSelectedItem().toString();
        settings.bitRate = number(bitRate, 0);
        settings.frameRate = number(frameRate, 0);
        settings.apiBase = text(apiBase).trim();
        settings.apiKey = text(apiKey);
        settings.model = text(model).trim();
        if (settings.model.isEmpty()) {
            settings.model = "phantom-automation";
        }
        settings.save(this);
        snack("Settings saved.");
    }

    private void provisionOcr() {
        setOcrText("Downloading OCR models…");
        io.execute(() -> OcrProvisioner.provisionModels(getFilesDir(),
                new File(getCacheDir(), "ocr"), new OcrProvisioner.Progress() {
                    @Override
                    public void onProgress(String file, long done, long total) {
                        setOcrText("Downloading " + file + "… " + done / 1024 + " KB"
                                + (total > 0 ? " / " + total / 1024 + " KB" : ""));
                    }

                    @Override
                    public void onDone(File root) {
                        try {
                            OcrProvisioner.provisionNativeLibs(getFilesDir(),
                                    getApplicationInfo().sourceDir);
                        } catch (Throwable e) {
                            setOcrText("Native libs: " + e.getMessage());
                            return;
                        }
                        setOcrText("Models ready — configuring engine…");
                        sendOcrConfigure();
                    }

                    @Override
                    public void onError(String message) {
                        setOcrText("Download failed: " + message);
                    }
                }));
    }

    private void sendOcrConfigure() {
        File filesDir = getFilesDir();
        io.execute(() -> {
            try {
                JSONObject result = Rpc.call("ocrConfigure", new JSONObject()
                        .put("modelDir", OcrProvisioner.modelDir(filesDir).getAbsolutePath())
                        .put("labelPath", OcrProvisioner.labelFile(filesDir).getAbsolutePath())
                        .put("libDir", OcrProvisioner.libDir(filesDir).getAbsolutePath()), 20000);
                runOnUiThread(() -> setOcrText("OCR " + result.optString("state") + ": "
                        + result.optString("detail")));
            } catch (Exception e) {
                runOnUiThread(() -> setOcrText(e.getMessage()));
            }
        });
    }

    private void queryOcrStatus() {
        io.execute(() -> {
            try {
                JSONObject result = Rpc.call("ocrStatus", new JSONObject(), 8000);
                runOnUiThread(() -> setOcrText("OCR " + result.optString("state") + ": "
                        + result.optString("detail")));
            } catch (Exception e) {
                runOnUiThread(() -> setOcrText(e.getMessage()));
            }
        });
    }

    private void setOcrText(String text) {
        runOnUiThread(() -> ocrStatus.setText(text == null ? "" : text));
    }

    private String text(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private int number(TextInputEditText field, int fallback) {
        try {
            String value = text(field).trim();
            return value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void snack(String message) {
        Snackbar.make(findViewById(R.id.root), message == null ? "Error" : message,
                Snackbar.LENGTH_SHORT).show();
    }
}
