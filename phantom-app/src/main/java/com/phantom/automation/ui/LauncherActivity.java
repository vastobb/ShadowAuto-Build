package com.phantom.automation.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.phantom.automation.R;
import com.phantom.automation.configuration.PhantomSettings;
import com.phantom.automation.rpc.RpcClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installed apps; tap to launch into the default session. */
public final class LauncherActivity extends Activity implements RpcClient.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private RpcClient rpc;
    private LinearLayout list;
    private String defaultSession = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        list = findViewById(R.id.appList);
        findViewById(R.id.launcherRefresh).setOnClickListener(v -> refresh());
    }

    @Override
    protected void onResume() {
        super.onResume();
        defaultSession = PhantomSettings.load(this).defaultSession;
        ((TextView) findViewById(R.id.launcherHint)).setText(defaultSession.isEmpty()
                ? "No default session set (Settings). Listing apps only."
                : "Launching into session: " + defaultSession);
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        io.execute(() -> send("listApps", "{}"));
    }

    private void send(String method, String paramsJson) {
        try {
            if (rpc == null) {
                rpc = new RpcClient("127.0.0.1", 43110, this);
            }
            rpc.send(method, new JSONObject(paramsJson));
        } catch (Exception e) {
            toast("RPC error: " + e.getMessage());
            rpc = null;
        }
    }

    @Override
    public void onLine(String line) {
        main.post(() -> {
            try {
                JSONObject root = new JSONObject(line);
                if (root.has("error")) {
                    toast("Shell: " + root.getJSONObject("error").optString("message"));
                    return;
                }
                JSONObject result = root.optJSONObject("result");
                if (result != null && result.has("apps")) {
                    render(result.getJSONArray("apps"));
                } else if (result != null && result.has("value")) {
                    toast("Launched");
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void onClosed(Exception error) {
        rpc = null;
    }

    private void render(JSONArray apps) {
        list.removeAllViews();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) {
                continue;
            }
            String name = app.optString("name");
            String pkg = app.optString("packageName");
            TextView view = new TextView(this);
            view.setText(name + "\n" + pkg);
            view.setTextColor(0xFFE8EDF2);
            view.setTextSize(14);
            view.setPadding(16, 16, 16, 16);
            view.setOnClickListener(v -> {
                if (defaultSession.isEmpty()) {
                    toast("Set a default session in Settings first");
                    return;
                }
                io.execute(() -> send("launchApp", "{\"sessionId\":\"" + escape(defaultSession)
                        + "\",\"packageName\":\"" + escape(pkg) + "\"}"));
            });
            list.addView(view);
        }
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
