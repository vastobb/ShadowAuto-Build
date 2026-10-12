package com.phantom.automation.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.phantom.automation.R;
import com.phantom.automation.configuration.PhantomSettings;
import com.phantom.automation.ocr.OcrProvisioner;
import com.phantom.automation.rpc.RpcClient;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent settings, API config, codec choice, OCR provisioning. */
public final class SettingsActivity extends Activity implements RpcClient.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private RpcClient rpc;
    private EditText defaultSession;
    private EditText width;
    private EditText height;
    private EditText dpi;
    private Spinner codec;
    private EditText bitRate;
    private EditText frameRate;
    private EditText apiBase;
    private EditText apiKey;
    private EditText model;
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
                android.R.layout.simple_spinner_item,
                new String[]{"auto", "avc", "hevc"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        codec.setAdapter(adapter);
        load();
        findViewById(R.id.saveButton).setOnClickListener(v -> save());
        Button provision = findViewById(R.id.ocrProvisionButton);
        provision.setOnClickListener(v -> provisionOcr());
        Button refresh = findViewById(R.id.ocrRefreshButton);
        refresh.setOnClickListener(v -> queryOcrStatus());
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
        defaultSession.setText(settings.defaultSession);
        width.setText(String.valueOf(settings.displayWidth));
        height.setText(String.valueOf(settings.displayHeight));
        dpi.setText(String.valueOf(settings.displayDpi));
        codec.setSelection(indexOf(settings.codec));
        bitRate.setText(settings.bitRate > 0 ? String.valueOf(settings.bitRate) : "");
        frameRate.setText(settings.frameRate > 0 ? String.valueOf(settings.frameRate) : "");
        apiBase.setText(settings.apiBase);
        apiKey.setText(settings.apiKey);
        model.setText(settings.model);
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
        settings.defaultSession = defaultSession.getText().toString().trim();
        settings.displayWidth = number(width, 720);
        settings.displayHeight = number(height, 1280);
        settings.displayDpi = number(dpi, 320);
        settings.codec = codec.getSelectedItem().toString();
        settings.bitRate = number(bitRate, 0);
        settings.frameRate = number(frameRate, 0);
        settings.apiBase = apiBase.getText().toString().trim();
        settings.apiKey = apiKey.getText().toString();
        settings.model = model.getText().toString().trim();
        if (settings.model.isEmpty()) {
            settings.model = "phantom-automation";
        }
        settings.save(this);
        toast("Settings saved");
    }

    private void provisionOcr() {
        setOcrText("Downloading OCR models…");
        io.execute(() -> {
            File filesDir = getFilesDir();
            File cacheDir = new File(getCacheDir(), "ocr");
            OcrProvisioner.provisionModels(filesDir, cacheDir, new OcrProvisioner.Progress() {
                @Override
                public void onProgress(String file, long done, long total) {
                    setOcrText("Downloading " + file + "… " + done / 1024 + " KB"
                            + (total > 0 ? " / " + total / 1024 + " KB" : ""));
                }

                @Override
                public void onDone(File root) {
                    try {
                        OcrProvisioner.provisionNativeLibs(filesDir,
                                getApplicationInfo().sourceDir);
                    } catch (Throwable e) {
                        setOcrText("Native libs: " + e.getMessage());
                        return;
                    }
                    sendOcrConfigure();
                    setOcrText("Models ready, configuring engine…");
                }

                @Override
                public void onError(String message) {
                    setOcrText("Download failed: " + message);
                }
            });
        });
    }

    private void sendOcrConfigure() {
        File filesDir = getFilesDir();
        String params = "{\"modelDir\":\"" + OcrProvisioner.modelDir(filesDir)
                + "\",\"labelPath\":\"" + OcrProvisioner.labelFile(filesDir)
                + "\",\"libDir\":\"" + OcrProvisioner.libDir(filesDir) + "\"}";
        send("ocrConfigure", params);
    }

    private void queryOcrStatus() {
        io.execute(() -> send("ocrStatus", "{}"));
    }

    private void send(String method, String paramsJson) {
        try {
            if (rpc == null) {
                rpc = new RpcClient("127.0.0.1", 43110, this);
            }
            rpc.send(method, new JSONObject(paramsJson));
        } catch (Exception e) {
            setOcrText("RPC error: " + e.getMessage() + " (is the daemon running?)");
            rpc = null;
        }
    }

    @Override
    public void onLine(String line) {
        main.post(() -> {
            try {
                JSONObject root = new JSONObject(line);
                if (root.has("error")) {
                    setOcrText("Shell: " + root.getJSONObject("error").optString("message"));
                    return;
                }
                JSONObject result = root.optJSONObject("result");
                if (result != null && result.has("state")) {
                    setOcrText("OCR " + result.optString("state") + ": "
                            + result.optString("detail"));
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void onClosed(Exception error) {
        rpc = null;
    }

    private void setOcrText(String text) {
        main.post(() -> ocrStatus.setText(text));
    }

    private int number(EditText input, int fallback) {
        try {
            String text = input.getText().toString().trim();
            return text.isEmpty() ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
