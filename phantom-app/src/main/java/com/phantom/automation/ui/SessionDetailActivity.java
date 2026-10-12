package com.phantom.automation.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.phantom.automation.R;
import com.phantom.automation.capture.VideoDecoder;
import com.phantom.automation.capture.VideoEvents;
import com.phantom.automation.rpc.RpcClient;
import com.phantom.automation.service.PhantomService;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live viewer for one session: H.264/H.265 preview, tap-to-touch with
 * display-local coordinate mapping, back/scroll controls, and recording
 * controls backed by the foreground service (survives rotation).
 */
public final class SessionDetailActivity extends Activity implements RpcClient.Listener {
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private RpcClient rpc;
    private VideoDecoder decoder;
    private TextureView screen;
    private TextView info;
    private String sessionId = "";
    private int displayWidth;
    private int displayHeight;
    private PhantomService service;
    private boolean bound;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PhantomService.LocalBinder) binder).service();
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
        decoder = new VideoDecoder(screen, main);
        info.setText("Session " + sessionId + " · " + displayWidth + "x" + displayHeight);
        screen.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                tapOnView(event.getX(), event.getY());
            }
            return true;
        });
        wire(R.id.backButton, "{\"sessionId\":\"" + escape(sessionId) + "\"}", "pressBack");
        wire(R.id.scrollUpButton, "{\"sessionId\":\"" + escape(sessionId)
                + "\",\"direction\":\"up\"}", "scroll");
        wire(R.id.scrollDownButton, "{\"sessionId\":\"" + escape(sessionId)
                + "\",\"direction\":\"down\"}", "scroll");
        findViewById(R.id.recordButton).setOnClickListener(v -> toggleRecording());
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

    private void wire(int buttonId, String paramsJson, String method) {
        findViewById(buttonId).setOnClickListener(v ->
                io.execute(() -> send(method, paramsJson)));
    }

    private void tapOnView(float viewX, float viewY) {
        if (screen.getWidth() <= 0 || screen.getHeight() <= 0) {
            return;
        }
        int x = Math.round(viewX / screen.getWidth() * displayWidth);
        int y = Math.round(viewY / screen.getHeight() * displayHeight);
        io.execute(() -> send("tap", "{\"sessionId\":\"" + escape(sessionId)
                + "\",\"x\":" + x + ",\"y\":" + y + "}"));
    }

    private void toggleRecording() {
        if (service == null) {
            toast("Service not bound yet");
            return;
        }
        boolean recording = service.recordingState()
                == com.phantom.automation.recording.SessionRecorder.State.RECORDING
                && sessionId.equals(service.recordingSession());
        Intent intent = new Intent(this, PhantomService.class)
                .putExtra(PhantomService.EXTRA_SESSION_ID, sessionId);
        intent.setAction(recording
                ? PhantomService.ACTION_STOP_RECORDING
                : PhantomService.ACTION_START_RECORDING);
        startService(intent);
        Button record = findViewById(R.id.recordButton);
        record.setText(recording ? "Record" : "Stop");
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
        String type = VideoEvents.typeOf(line);
        if (type.isEmpty()) {
            return;
        }
        main.post(() -> {
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
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static String base64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
