package com.phantom.automation.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.phantom.automation.R;
import com.phantom.automation.configuration.PhantomSettings;
import com.phantom.automation.rpc.Rpc;
import com.phantom.automation.shizuku.DaemonController;
import com.phantom.automation.shizuku.ShizukuManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home: connection status, session summary, primary session action.
 * Shizuku state is visible but compact; the session CTA dominates.
 */
public final class MainActivity extends Activity
        implements ShizukuManager.Listener, DaemonController.Listener {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private ShizukuManager shizuku;
    private DaemonController daemon;
    private ShizukuManager.State shizukuState = ShizukuManager.State.UNAVAILABLE;
    private DaemonController.State daemonState = DaemonController.State.IDLE;

    private TextView shizukuDot;
    private TextView shizukuLine;
    private TextView daemonDot;
    private TextView daemonLine;
    private TextView sessionLine;
    private MaterialButton primaryButton;
    private MaterialButton shizukuButton;
    private MaterialButton daemonButton;
    private View loadingRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        shizukuDot = findViewById(R.id.shizukuDot);
        shizukuLine = findViewById(R.id.shizukuLine);
        daemonDot = findViewById(R.id.daemonDot);
        daemonLine = findViewById(R.id.daemonLine);
        sessionLine = findViewById(R.id.sessionLine);
        primaryButton = findViewById(R.id.primaryButton);
        shizukuButton = findViewById(R.id.shizukuButton);
        daemonButton = findViewById(R.id.daemonButton);
        loadingRow = findViewById(R.id.loadingRow);
        findViewById(R.id.restartButton).setOnClickListener(v -> {
            if (shizukuState == ShizukuManager.State.READY) {
                daemon.restart();
            } else {
                snack("Authorize Shizuku first, then restart the daemon.");
            }
        });
        findViewById(R.id.recoverButton).setOnClickListener(v -> {
            if (shizukuState == ShizukuManager.State.READY) {
                recover();
            } else {
                snack("Recovery runs through Shizuku — authorize it first.");
            }
        });
        findViewById(R.id.mcpCard).setOnClickListener(v ->
                startActivity(new Intent(this, McpSetupActivity.class)));
        shizukuButton.setOnClickListener(v -> onShizukuAction());
        daemonButton.setOnClickListener(v -> onDaemonAction());
        primaryButton.setOnClickListener(v -> onPrimaryAction());
        NavHelper.bind(this, R.id.bottomNav, R.id.nav_home);
        shizuku = new ShizukuManager(this);
        daemon = new DaemonController(this, this);
        shizuku.attach();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSessions();
    }

    @Override
    protected void onDestroy() {
        if (shizuku != null) {
            shizuku.detach();
        }
        if (daemon != null) {
            daemon.release();
        }
        io.shutdownNow();
        super.onDestroy();
    }

    private void onShizukuAction() {
        if (shizukuState == ShizukuManager.State.NEEDS_PERMISSION) {
            if (!shizuku.requestPermission()) {
                shizuku.refresh();
            }
        } else {
            shizuku.refresh();
        }
    }

    private void onDaemonAction() {
        if (daemonState == DaemonController.State.RUNNING) {
            daemon.stop();
        } else if (shizukuState == ShizukuManager.State.READY) {
            daemon.start();
        } else {
            snack("Authorize Shizuku first, then start the daemon.");
        }
    }

    private void onPrimaryAction() {
        if (daemonState != DaemonController.State.RUNNING) {
            snack("Start the daemon first — sessions need it.");
            return;
        }
        setLoading(true);
        io.execute(() -> {
            try {
                PhantomSettings settings = PhantomSettings.load(this);
                JSONObject params = new JSONObject()
                        .put("width", settings.displayWidth)
                        .put("height", settings.displayHeight)
                        .put("codec", settings.codec);
                JSONObject result = Rpc.call("openSession", params, 30000);
                String id = result.optString("sessionId");
                runOnUiThread(() -> {
                    setLoading(false);
                    Intent intent = new Intent(this, SessionDetailActivity.class);
                    intent.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, id);
                    intent.putExtra(SessionDetailActivity.EXTRA_WIDTH,
                            result.optInt("width", settings.displayWidth));
                    intent.putExtra(SessionDetailActivity.EXTRA_HEIGHT,
                            result.optInt("height", settings.displayHeight));
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    snack(e.getMessage());
                });
            }
        });
    }

    private void refreshSessions() {
        io.execute(() -> {
            try {
                JSONObject result = Rpc.call("status", new JSONObject(), 5000);
                JSONArray sessions = result.optJSONArray("mcpSessions");
                int count = sessions == null ? 0 : sessions.length();
                String def = PhantomSettings.load(this).defaultSession;
                runOnUiThread(() -> sessionLine.setText(count == 0
                        ? "No active sessions."
                        : count + " active session" + (count == 1 ? "" : "s")
                        + (def.isEmpty() ? "." : " · default " + def + ".")));
            } catch (Exception ignored) {
                runOnUiThread(() -> sessionLine.setText("Daemon offline — session info unavailable."));
            }
        });
    }

    private void recover() {
        setLoading(true);
        snack("Recovering: identifying the port holder…");
        daemon.recover(new com.phantom.automation.shizuku.RecoveryController.Listener() {
            @Override
            public void onStep(String message) {
                runOnUiThread(() -> daemonLine.setText(message));
            }

            @Override
            public void onDone(boolean ok, String message) {
                runOnUiThread(() -> {
                    setLoading(false);
                    snack(message);
                    refreshSessions();
                });
            }
        });
    }

    @Override
    public void onStateChanged(ShizukuManager.State state) {
        runOnUiThread(() -> {
            shizukuState = state;
            renderShizuku();
        });
    }

    @Override
    public void onState(DaemonController.State state, String detail) {
        runOnUiThread(() -> {
            daemonState = state;
            renderDaemon(detail);
            if (state == DaemonController.State.RUNNING) {
                refreshSessions();
            }
        });
    }

    private void renderShizuku() {
        switch (shizukuState) {
            case READY:
                dot(shizukuDot, true);
                shizukuLine.setText("Shizuku ready");
                shizukuButton.setText("Refresh");
                break;
            case NEEDS_PERMISSION:
                dot(shizukuDot, false);
                shizukuLine.setText("Shizuku needs permission");
                shizukuButton.setText("Authorize");
                break;
            case DENIED_PERMANENTLY:
                dot(shizukuDot, false);
                shizukuLine.setText("Permission denied — allow in Shizuku app");
                shizukuButton.setText("Recheck");
                break;
            case UNSUPPORTED_VERSION:
                dot(shizukuDot, false);
                shizukuLine.setText("Shizuku too old (pre-v11) — update it");
                shizukuButton.setText("Recheck");
                break;
            case UNAVAILABLE:
            default:
                dot(shizukuDot, false);
                shizukuLine.setText(ShizukuManager.isInstalledHint(this)
                        ? "Shizuku installed but not started"
                        : "Shizuku not installed");
                shizukuButton.setText("Recheck");
                break;
        }
        renderDaemonButton();
    }

    private void renderDaemon(String detail) {
        dot(daemonDot, daemonState == DaemonController.State.RUNNING);
        daemonLine.setText(detail);
        renderDaemonButton();
    }

    private void renderDaemonButton() {
        if (daemonState == DaemonController.State.RUNNING) {
            daemonButton.setText("Stop");
            daemonButton.setEnabled(true);
        } else if (daemonState == DaemonController.State.BINDING
                || daemonState == DaemonController.State.STARTING) {
            daemonButton.setText("Starting…");
            daemonButton.setEnabled(false);
        } else {
            daemonButton.setText("Start");
            daemonButton.setEnabled(shizukuState == ShizukuManager.State.READY);
        }
    }

    private void setLoading(boolean loading) {
        loadingRow.setVisibility(loading ? View.VISIBLE : View.GONE);
        primaryButton.setEnabled(!loading);
    }

    private void dot(TextView view, boolean ok) {
        view.setText(ok ? "●" : "○");
        view.setTextColor(ok ? 0xFF4ADE80 : 0xFFFBBF24);
    }

    private void snack(String message) {
        Snackbar.make(findViewById(R.id.root), message == null ? "Error" : message,
                Snackbar.LENGTH_LONG).show();
    }
}
