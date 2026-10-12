package com.phantom.automation.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.TextureView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.phantom.automation.R;
import com.phantom.automation.capture.VideoDecoder;
import com.phantom.automation.capture.VideoEvents;
import com.phantom.automation.recording.SessionRecorder;
import com.phantom.automation.rpc.Rpc;
import com.phantom.automation.rpc.RpcClient;
import com.phantom.automation.service.PhantomService;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live session viewer: video preview, display-local touch, hardware keys,
 * recording (service-owned), screenshot handoff to the Inspector.
 */
public final class SessionDetailActivity extends Activity implements RpcClient.Listener {
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";

    private final ExecutorService io = Executors.newCachedThreadPool();
    private RpcClient rpc;
    private VideoDecoder decoder;
    private TextureView screen;
    private TextView info;
    private MaterialButton recordButton;
    private String sessionId = "";
    private int displayWidth;
    private int displayHeight;
    private PhantomService service;
    private boolean bound;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PhantomService.LocalBinder) binder).service();
            runOnUiThread(() -> renderRecord());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_detail);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        displayWidth = getIntent().getIntExtra(EXTRA_WIDTH, 720);
        displayHeight = getIntent().getIntExtra(EXTRA_HEIGHT, 1280);
        screen = findViewById(R.id.detailTexture);
        info = findViewById(R.id.detailInfo);
        recordButton = findViewById(R.id.recordButton);
        decoder = new VideoDecoder(screen, new android.os.Handler(getMainLooper()));
        info.setText(sessionId + " · " + displayWidth + "×" + displayHeight);
        screen.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                tapOnView(event.getX(), event.getY());
            }
            return true;
        });
        act(R.id.backButton, "pressBack", "{}");
        act(R.id.scrollUpButton, "scroll", "{\"direction\":\"up\"}");
        act(R.id.scrollDownButton, "scroll", "{\"direction\":\"down\"}");
        findViewById(R.id.recordButton).setOnClickListener(v -> toggleRecording());
        findViewById(R.id.shotButton).setOnClickListener(v -> openInspector());
        startService(new Intent(this, PhantomService.class));
        bindService(new Intent(this, PhantomService.class), serviceConnection, BIND_AUTO_CREATE);
        bound = true;
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            try {
                unbindService(serviceConnection);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        if (decoder != null) {
            decoder.release();
        }
        io.shutdownNow();
        super.onDestroy();
    }

    private void act(int buttonId, String method, String extraParams) {
        findViewById(buttonId).setOnClickListener(v -> io.execute(() -> {
            try {
                JSONObject params = new JSONObject(extraParams);
                params.put("sessionId", sessionId);
                Rpc.call(method, params, 10000);
            } catch (Exception e) {
                snack(e.getMessage());
            }
        }));
    }

    private void tapOnView(float viewX, float viewY) {
        if (screen.getWidth() <= 0 || screen.getHeight() <= 0) {
            return;
        }
        int x = Math.round(viewX / screen.getWidth() * displayWidth);
        int y = Math.round(viewY / screen.getHeight() * displayHeight);
        io.execute(() -> {
            try {
                Rpc.call("tap", new JSONObject().put("sessionId", sessionId)
                        .put("x", x).put("y", y), 10000);
            } catch (Exception e) {
                snack(e.getMessage());
            }
        });
    }

    private void openInspector() {
        Intent intent = new Intent(this, InspectorActivity.class);
        intent.putExtra(InspectorActivity.EXTRA_SESSION_ID, sessionId);
        startActivity(intent);
    }

    private void toggleRecording() {
        if (service == null) {
            snack("Service not bound yet — retry in a moment.");
            return;
        }
        boolean recording = service.recordingState() == SessionRecorder.State.RECORDING
                && sessionId.equals(service.recordingSession());
        Intent intent = new Intent(this, PhantomService.class)
                .putExtra(PhantomService.EXTRA_SESSION_ID, sessionId);
        intent.setAction(recording
                ? PhantomService.ACTION_STOP_RECORDING
                : PhantomService.ACTION_START_RECORDING);
        startService(intent);
        renderRecord();
        snack(recording ? "Recording stopped — check Movies/Phantom."
                : "Recording started.");
    }

    private void renderRecord() {
        boolean recording = service != null
                && service.recordingState() == SessionRecorder.State.RECORDING
                && sessionId.equals(service.recordingSession());
        recordButton.setText(recording ? "Stop ●" : "Record");
    }

    @Override
    public void onLine(String line) {
        String type = VideoEvents.typeOf(line);
        if (type.isEmpty()) {
            return;
        }
        runOnUiThread(() -> {
            try {
                if ("video.config".equals(type)) {
                    VideoEvents.Config config =
                            VideoEvents.parseConfig(VideoEvents.paramsOf(line));
                    if (sessionId.equals(config.id) && config.csd0.length > 0) {
                        decoder.configure(config.mime, config.width, config.height,
                                base64(config.csd0), base64(config.csd1));
                    }
                } else if ("video.sample".equals(type)) {
                    VideoEvents.Sample sample =
                            VideoEvents.parseSample(VideoEvents.paramsOf(line));
                    if (sessionId.equals(sample.id) && sample.data.length > 0) {
                        decoder.queueSample(base64(sample.data), sample.ptsUs, sample.flags);
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onClosed(Exception error) {
        rpc = null;
        subscribe();
    }

    @Override
    protected void onResume() {
        super.onResume();
        subscribe();
        renderRecord();
    }

    /** Opens the persistent event stream (a status call establishes it). */
    private void subscribe() {
        io.execute(() -> {
            try {
                if (rpc == null) {
                    rpc = new RpcClient("127.0.0.1", 43110, this);
                }
                rpc.send("status", new JSONObject());
            } catch (Throwable ignored) {
            }
        });
    }

    private void snack(String message) {
        runOnUiThread(() -> Snackbar.make(findViewById(R.id.root),
                message == null ? "Error" : message, Snackbar.LENGTH_LONG).show());
    }

    private static String base64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }
}
