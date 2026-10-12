package com.phantom.automation.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.phantom.automation.R;
import com.phantom.automation.configuration.PhantomSettings;
import com.phantom.automation.rpc.Rpc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MCP connection status, setup steps, and copyable OpenCode config. */
public final class McpSetupActivity extends Activity {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private TextView statusLine;
    private TextView configText;
    private TextInputEditText repoPath;

    private static final String MCP_SERVER_REL = "/mcp/mcp_server.py";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_setup);
        statusLine = findViewById(R.id.mcpStatus);
        configText = findViewById(R.id.mcpConfig);
        repoPath = findViewById(R.id.repoPath);
        repoPath.setText(PhantomSettings.load(this).mcpRepoPath);
        ((TextView) findViewById(R.id.mcpSteps)).setText(
                "OpenCode runs on your machine, not on this phone — only two "
                        + "things connect them:\n"
                        + "1. adb forward tcp:43110 tcp:43110 (one command, on your machine).\n"
                        + "2. The config below, pasted into OpenCode's opencode.json "
                        + "with your real checkout path.\n"
                        + "Then: opencode mcp list — expect phantom connected.");
        ((TextView) findViewById(R.id.mcpTools)).setText(
                "Sessions: open/close/status · Apps: list/launch · Observe: screenshot, "
                        + "hierarchy, OCR, find-text · Act: tap, long-press, swipe, scroll, "
                        + "text, keys, back, targets · Recording state · Tasks: start/stop "
                        + "· Logs · Codec capabilities.");
        MaterialButton copy = findViewById(R.id.copyButton);
        copy.setOnClickListener(v -> {
            String config = buildConfig();
            if (config == null) {
                snack("Enter your real repo checkout path first (e.g. /home/you/ShadowAuto-Build).");
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Phantom MCP config", config));
                snack("Configuration copied.");
            }
        });
        repoPath.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderConfig();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        findViewById(R.id.checkButton).setOnClickListener(v -> check());
        renderConfig();
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
        statusLine.setText("Checking daemon protocol…");
        io.execute(() -> {
            try {
                JSONObject hello = Rpc.call("hello", new JSONObject(), 8000);
                JSONArray methods = hello.optJSONArray("methods");
                int count = methods == null ? 0 : methods.length();
                String text = "Daemon: " + hello.optString("app")
                        + " protocol v" + hello.optInt("version")
                        + " · " + count + " methods"
                        + (hasRequired(methods) ? " · MCP ready."
                                : " · MISSING methods — reinstall Phantom.");
                runOnUiThread(() -> statusLine.setText(text));
            } catch (Exception e) {
                runOnUiThread(() -> statusLine.setText(e.getMessage()));
            }
        });
    }

    /** Complete config, or null when the repo path is not usable yet. */
    private String buildConfig() {
        String repo = repoPath.getText() == null ? ""
                : repoPath.getText().toString().trim();
        if (repo.isEmpty() || !repo.startsWith("/") || repo.contains("<") || repo.contains(">")) {
            return null;
        }
        while (repo.endsWith("/") && repo.length() > 1) {
            repo = repo.substring(0, repo.length() - 1);
        }
        return "{\n"
                + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                + "  \"mcp\": { \"servers\": { \"phantom\": {\n"
                + "    \"type\": \"local\",\n"
                + "    \"command\": [\"python3\", \"" + repo + MCP_SERVER_REL + "\"],\n"
                + "    \"environment\": {\n"
                + "      \"SHADOWAUTO_HOST\": \"127.0.0.1\",\n"
                + "      \"SHADOWAUTO_PORT\": \"43110\"\n"
                + "    }\n"
                + "  } } }\n"
                + "}";
    }

    private void renderConfig() {
        String config = buildConfig();
        configText.setText(config == null
                ? "Enter your checkout path above — nothing to copy until it is set."
                : config);
        PhantomSettings settings = PhantomSettings.load(this);
        settings.mcpRepoPath = repoPath.getText() == null ? ""
                : repoPath.getText().toString().trim();
        settings.save(this);
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
