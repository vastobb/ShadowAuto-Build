package com.phantom.automation.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.phantom.automation.R;
import com.phantom.automation.rpc.Rpc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP setup without any repository path: the installable
 * {@code phantom-mcp} tool (pip) needs no checkout, so the generated
 * instructions contain real commands only. OpenCode itself runs off-device
 * over stdio and reports nothing back — this screen states that honestly
 * instead of claiming a connection it cannot see.
 */
public final class McpSetupActivity extends Activity {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private TextView statusLine;
    private TextView apiLine;
    private TextView toolsLine;
    private TextView configText;
    private String instructions = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_setup);
        statusLine = findViewById(R.id.mcpStatus);
        apiLine = findViewById(R.id.mcpApi);
        toolsLine = findViewById(R.id.mcpTools);
        configText = findViewById(R.id.mcpConfig);
        toolsLine.setText(
                "Sessions: open/close/status · Apps: list/launch · Observe: screenshot, "
                        + "hierarchy, OCR, find-text · Act: tap, long-press, swipe, scroll, "
                        + "text, keys, back, targets · Recording state · Tasks: start/stop "
                        + "· Logs · Codec capabilities.");
        MaterialButton copy = findViewById(R.id.copyButton);
        copy.setOnClickListener(v -> {
            if (instructions.isEmpty()) {
                snack("Start the daemon first — instructions need the live protocol version.");
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Phantom MCP setup", instructions));
                snack("Instructions copied.");
            }
        });
        findViewById(R.id.checkButton).setOnClickListener(v -> check());
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void check() {
        statusLine.setText("Checking…");
        apiLine.setText("");
        io.execute(() -> {
            try {
                JSONObject hello = Rpc.call("hello", new JSONObject(), 8000);
                JSONArray methods = hello.optJSONArray("methods");
                int count = methods == null ? 0 : methods.length();
                int version = hello.optInt("version");
                int pid = hello.optInt("pid", -1);
                boolean ready = hasRequired(methods);
                String status = "Phantom service: running (protocol v" + version
                        + (pid > 0 ? ", pid " + pid : "") + ").";
                String api = "Control API: 127.0.0.1:43110 reachable, " + count
                        + " methods" + (ready ? "." : " — MISSING methods, reinstall Phantom.");
                String fedora = "OpenCode (Fedora): not visible from this device. "
                        + "Stdio servers report nothing back — verify there with "
                        + "`opencode mcp list` (expect phantom connected).";
                instructions = "Phantom MCP setup (OpenCode on Fedora):\n"
                        + "1. adb forward tcp:43110 tcp:43110\n"
                        + "   (bridges Fedora loopback to Android loopback; loopback-only, no LAN)\n"
                        + "2. pip install \"phantom-mcp @ "
                        + "git+https://github.com/vastobb/ShadowAuto-Build.git#subdirectory=mcp\"\n"
                        + "3. opencode.json mcp.servers.phantom: "
                        + "{\"type\":\"local\",\"command\":[\"phantom-mcp\"]}\n"
                        + "4. opencode mcp list\n"
                        + "Daemon verified here: phantom protocol v" + version
                        + ", " + count + " methods.";
                runOnUiThread(() -> {
                    statusLine.setText(status);
                    apiLine.setText(api + "\n" + fedora);
                    configText.setText(instructions);
                });
            } catch (Exception e) {
                instructions = "";
                runOnUiThread(() -> {
                    statusLine.setText("Phantom service: not running.");
                    apiLine.setText(e.getMessage());
                    configText.setText(
                            "Start the daemon on Home first — setup instructions "
                                    + "are generated from the live protocol version.");
                });
            }
        });
    }

    private boolean hasRequired(JSONArray methods) {
        if (methods == null) {
            return false;
        }
        String all = methods.toString();
        for (String name : new String[]{"openSession", "listApps", "launchApp",
                "dumpUi", "screenshot"}) {
            if (!all.contains("\"" + name + "\"")) {
                return false;
            }
        }
        return true;
    }

    private void snack(String message) {
        Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_SHORT).show();
    }
}
