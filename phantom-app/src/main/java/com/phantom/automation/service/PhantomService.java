package com.phantom.automation.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import com.phantom.automation.R;
import com.phantom.automation.capture.VideoEvents;
import com.phantom.automation.recording.SessionRecorder;
import com.phantom.automation.rpc.RpcClient;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the engine connection for background work. Survives Activity
 * recreation: recording continues while the UI is rebuilt, rotated, or
 * closed, and the engine (Shizuku process) is unaffected by this process.
 */
public final class PhantomService extends Service implements RpcClient.Listener {
    public static final String ACTION_START_RECORDING = "com.phantom.automation.START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "com.phantom.automation.STOP_RECORDING";
    public static final String ACTION_PAUSE_RECORDING = "com.phantom.automation.PAUSE_RECORDING";
    public static final String ACTION_RESUME_RECORDING = "com.phantom.automation.RESUME_RECORDING";
    public static final String EXTRA_SESSION_ID = "sessionId";

    private static final String CHANNEL = "phantom_service";
    private static final int NOTIFICATION_ID = 2001;

    private final IBinder binder = new LocalBinder();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final SessionRecorder recorder = new SessionRecorder();
    private RpcClient rpc;
    private String recordingSession = "";
    private VideoEvents.Config pendingConfig;

    public final class LocalBinder extends Binder {
        public PhantomService service() {
            return PhantomService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Phantom service running"));
        connectRpc();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            switch (intent.getAction()) {
                case ACTION_START_RECORDING:
                    startRecording(sessionId == null ? "" : sessionId);
                    break;
                case ACTION_STOP_RECORDING:
                    stopRecording();
                    break;
                case ACTION_PAUSE_RECORDING:
                    pauseRecording();
                    break;
                case ACTION_RESUME_RECORDING:
                    resumeRecording();
                    break;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            recorder.stop();
        } catch (Throwable ignored) {
        }
        io.shutdownNow();
        super.onDestroy();
    }

    public SessionRecorder.State recordingState() {
        return recorder.state();
    }

    public String recordingSession() {
        return recordingSession;
    }

    public File recordingOutput() {
        return recorder.output();
    }

    public String recordingError() {
        return recorder.error();
    }

    private void connectRpc() {
        io.execute(() -> {
            try {
                rpc = new RpcClient("127.0.0.1", 43110, PhantomService.this);
                rpc.send("status", new JSONObject());
            } catch (Throwable ignored) {
            }
        });
    }

    private void startRecording(String sessionId) {
        if (sessionId.isEmpty()) {
            return;
        }
        recordingSession = sessionId;
        pendingConfig = null;
        io.execute(() -> sendRpc("setRecordingState",
                "{\"sessionId\":\"" + escape(sessionId) + "\",\"state\":\"recording\"}"));
    }

    private void stopRecording() {
        File out = recorder.stop();
        String session = recordingSession;
        recordingSession = "";
        pendingConfig = null;
        io.execute(() -> {
            sendRpc("setRecordingState",
                    "{\"sessionId\":\"" + escape(session) + "\",\"state\":\"idle\"}");
            if (out != null) {
                notify("Recording saved: " + out.getAbsolutePath());
            }
        });
        if (out == null && recorder.state() == SessionRecorder.State.ERROR) {
            notify("Recording failed: " + recorder.error());
        }
        try {
            recorder.reset();
        } catch (Throwable ignored) {
        }
    }

    private void pauseRecording() {
        recorder.pause();
        io.execute(() -> sendRpc("setRecordingState",
                "{\"sessionId\":\"" + escape(recordingSession) + "\",\"state\":\"paused\"}"));
    }

    private void resumeRecording() {
        recorder.resume(lastPts);
        io.execute(() -> sendRpc("setRecordingState",
                "{\"sessionId\":\"" + escape(recordingSession) + "\",\"state\":\"recording\"}"));
    }

    private volatile long lastPts;

    private void sendRpc(String method, String paramsJson) {
        try {
            if (rpc == null) {
                connectRpc();
                return;
            }
            rpc.send(method, new JSONObject(paramsJson));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onLine(String line) {
        String type = VideoEvents.typeOf(line);
        if (type.isEmpty() || recordingSession.isEmpty()) {
            return;
        }
        JSONObject params = VideoEvents.paramsOf(line);
        if ("video.config".equals(type)) {
            VideoEvents.Config config = VideoEvents.parseConfig(params);
            if (recordingSession.equals(config.id) && config.csd0.length > 0) {
                pendingConfig = config;
            }
        } else if ("video.sample".equals(type)) {
            VideoEvents.Sample sample = VideoEvents.parseSample(params);
            if (!recordingSession.equals(sample.id) || sample.data.length == 0) {
                return;
            }
            lastPts = sample.ptsUs;
            if (recorder.state() == SessionRecorder.State.IDLE && pendingConfig != null) {
                try {
                    recorder.configure(outputFile(recordingSession), pendingConfig.mime,
                            pendingConfig.width, pendingConfig.height,
                            pendingConfig.csd0, pendingConfig.csd1);
                } catch (Throwable e) {
                    pendingConfig = null;
                }
            }
            recorder.writeSample(sample.data, sample.ptsUs, sample.flags);
        }
    }

    @Override
    public void onClosed(Exception error) {
        rpc = null;
    }

    private File outputFile(String sessionId) {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Phantom");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return new File(dir, "phantom-" + sessionId + "-" + stamp + ".mp4");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Phantom service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder.setContentTitle("Phantom")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void notify(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID + 1, notification(text));
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
