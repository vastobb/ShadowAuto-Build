package com.phantom.automation.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import com.phantom.automation.R;
import com.phantom.automation.rpc.RpcClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shell log ring buffer. */
public final class LogsActivity extends Activity implements RpcClient.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private RpcClient rpc;
    private TextView logView;
    private ScrollView scroller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        logView = findViewById(R.id.logText);
        scroller = findViewById(R.id.logScroll);
        findViewById(R.id.logRefresh).setOnClickListener(v -> refresh());
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
                if (rpc == null) {
                    rpc = new RpcClient("127.0.0.1", 43110, this);
                }
                rpc.send("logs", new JSONObject("{}"));
            } catch (Exception e) {
                setText("RPC error: " + e.getMessage() + " (is the daemon running?)");
                rpc = null;
            }
        });
    }

    @Override
    public void onLine(String line) {
        main.post(() -> {
            try {
                JSONObject root = new JSONObject(line);
                if (!root.has("result")) {
                    return;
                }
                JSONArray logs = root.optJSONArray("result");
                if (logs == null) {
                    return;
                }
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < logs.length(); i++) {
                    out.append(logs.optString(i)).append('\n');
                }
                setText(out.toString());
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void onClosed(Exception error) {
        rpc = null;
    }

    private void setText(String text) {
        main.post(() -> {
            logView.setText(text);
            scroller.post(() -> scroller.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
