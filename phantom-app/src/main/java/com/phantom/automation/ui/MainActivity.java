package com.phantom.automation.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.phantom.automation.R;
import com.phantom.automation.shizuku.ShizukuManager;

/** Phantom home: Shizuku state, permission entry point, service overview. */
public final class MainActivity extends Activity implements ShizukuManager.Listener {
    private ShizukuManager shizuku;
    private TextView statusText;
    private TextView detailText;
    private Button permissionButton;
    private Button reconnectButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.shizukuStatus);
        detailText = findViewById(R.id.shizukuDetail);
        permissionButton = findViewById(R.id.permissionButton);
        reconnectButton = findViewById(R.id.reconnectButton);
        permissionButton.setOnClickListener(v -> onPermission());
        reconnectButton.setOnClickListener(v -> {
            if (shizuku != null) {
                shizuku.refresh();
            }
        });
        shizuku = new ShizukuManager(this);
        shizuku.attach();
    }

    @Override
    protected void onDestroy() {
        if (shizuku != null) {
            shizuku.detach();
            shizuku = null;
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
        runOnUiThread(() -> render(state));
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
    }
}
