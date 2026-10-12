package com.phantom.automation.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.phantom.automation.R;
import com.phantom.automation.shizuku.DaemonController;
import com.phantom.automation.shizuku.ShizukuManager;

/** Phantom home: Shizuku state, permission entry point, daemon lifecycle. */
public final class MainActivity extends Activity
        implements ShizukuManager.Listener, DaemonController.Listener {
    private ShizukuManager shizuku;
    private DaemonController daemon;
    private ShizukuManager.State shizukuState = ShizukuManager.State.UNAVAILABLE;
    private TextView statusText;
    private TextView detailText;
    private Button permissionButton;
    private Button reconnectButton;
    private TextView daemonText;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.shizukuStatus);
        detailText = findViewById(R.id.shizukuDetail);
        permissionButton = findViewById(R.id.permissionButton);
        reconnectButton = findViewById(R.id.reconnectButton);
        daemonText = findViewById(R.id.daemonStatus);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        permissionButton.setOnClickListener(v -> onPermission());
        reconnectButton.setOnClickListener(v -> {
            if (shizuku != null) {
                shizuku.refresh();
            }
        });
        startButton.setOnClickListener(v -> {
            if (daemon != null) {
                daemon.start();
            }
        });
        stopButton.setOnClickListener(v -> {
            if (daemon != null) {
                daemon.stop();
            }
        });
        shizuku = new ShizukuManager(this);
        daemon = new DaemonController(this, this);
        shizuku.attach();
        renderDaemon(DaemonController.State.IDLE, getString(R.string.daemon_idle_detail));
    }

    @Override
    protected void onDestroy() {
        if (shizuku != null) {
            shizuku.detach();
            shizuku = null;
        }
        if (daemon != null) {
            daemon.release();
            daemon = null;
        }
        super.onDestroy();
    }

    private void onPermission() {
        if (shizuku == null) {
            return;
        }
        boolean sent = shizuku.requestPermission();
        if (!sent) {
            shizuku.refresh();
        }
    }

    @Override
    public void onStateChanged(ShizukuManager.State state) {
        runOnUiThread(() -> {
            shizukuState = state;
            render(state);
        });
    }

    @Override
    public void onState(DaemonController.State state, String detail) {
        runOnUiThread(() -> renderDaemon(state, detail));
    }

    private void render(ShizukuManager.State state) {
        switch (state) {
            case READY:
                statusText.setText(getString(R.string.shizuku_ready));
                detailText.setText(getString(R.string.shizuku_ready_detail));
                permissionButton.setEnabled(false);
                break;
            case NEEDS_PERMISSION:
                statusText.setText(getString(R.string.shizuku_permission));
                detailText.setText(getString(R.string.shizuku_permission_detail));
                permissionButton.setEnabled(true);
                break;
            case DENIED_PERMANENTLY:
                statusText.setText(getString(R.string.shizuku_denied));
                detailText.setText(getString(R.string.shizuku_denied_detail));
                permissionButton.setEnabled(false);
                break;
            case UNSUPPORTED_VERSION:
                statusText.setText(getString(R.string.shizuku_unsupported));
                detailText.setText(getString(R.string.shizuku_unsupported_detail));
                permissionButton.setEnabled(false);
                break;
            case UNAVAILABLE:
            default:
                statusText.setText(getString(R.string.shizuku_missing));
                boolean installed = ShizukuManager.isInstalledHint(this);
                detailText.setText(getString(installed
                        ? R.string.shizuku_missing_start_detail
                        : R.string.shizuku_missing_install_detail));
                permissionButton.setEnabled(false);
                break;
        }
        startButton.setEnabled(state == ShizukuManager.State.READY);
    }

    private void renderDaemon(DaemonController.State state, String detail) {
        daemonText.setText(detail);
        stopButton.setEnabled(state == DaemonController.State.RUNNING
                || state == DaemonController.State.ERROR);
    }
}
