package com.phantom.automation.shizuku;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explicit UI-to-engine interface: binds the Shizuku UserService, starts the
 * loopback RPC daemon, and verifies the Phantom handshake. Behavior is driven
 * by {@link DaemonLifecycle}: every async step has a timeout (the UI can
 * never stick), duplicates are suppressed, disconnects land in ERROR, and
 * every transition is journaled to {@link DiagLog}.
 */
public final class DaemonController implements ServiceConnector.Listener {
    public enum State {
        IDLE,
        BINDING,
        STARTING,
        RUNNING,
        ERROR,
    }

    public interface Listener {
        void onState(State state, String detail);
    }

    public static final int RPC_PORT = 43110;
    private static final int HELLO_TIMEOUT_MS = 8000;
    private static final long WATCHDOG_MS = 1000;

    private final Context context;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private final DiagLog diag = new DiagLog();
    private final DaemonLifecycle lifecycle = new DaemonLifecycle(diag);
    private ServiceConnector connector;
    private PhantomUserService.Client client;
    private RecoveryController.Listener pendingRecovery;
    private boolean released;

    public DaemonController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Last hello summary after RUNNING ("" otherwise). */
    public String helloSummary() {
        return lifecycle.state() == DaemonLifecycle.State.RUNNING ? lifecycle.detail() : "";
    }

    public DiagLog diagnostics() {
        return diag;
    }

    public void start() {
        if (!lifecycle.requestStart(now())) {
            return;
        }
        emit();
        armWatchdog();
        if (connector == null) {
            connector = new ServiceConnector(context, this);
        }
        connector.bind();
    }

    public void stop() {
        pendingRecovery = null;
        io.execute(() -> {
            try {
                if (client != null) {
                    client.stopDaemon();
                }
            } catch (RemoteException ignored) {
            } finally {
                client = null;
            }
            if (connector != null) {
                connector.unbind();
            }
            lifecycle.onStopped(now());
            emit();
        });
    }

    /** Stop, rebind, and start again. */
    public void restart() {
        pendingRecovery = null;
        io.execute(() -> {
            try {
                if (client != null) {
                    client.stopDaemon();
                }
            } catch (RemoteException ignored) {
            } finally {
                client = null;
            }
            if (connector != null) {
                connector.unbind();
            }
            lifecycle.requestRestart(now());
            emit();
            armWatchdog();
            if (connector == null) {
                connector = new ServiceConnector(context, this);
            }
            connector.bind();
        });
    }

    /**
     * Recover a stale/foreign port holder without manual adb: identify via
     * kernel tables, stop ONLY positively identified legacy shells, start
     * Phantom's daemon, verify hello. Reports each step.
     */
    public void recover(RecoveryController.Listener recovery) {
        pendingRecovery = null;
        if (client != null) {
            lifecycle.requestRecover(now());
            emit();
            armWatchdog();
            RecoveryController controller = new RecoveryController(client,
                    wrapRecovery(recovery));
            io.execute(controller::run);
            return;
        }
        pendingRecovery = recovery;
        lifecycle.requestRecover(now());
        emit();
        armWatchdog();
        if (connector == null) {
            connector = new ServiceConnector(context, this);
        }
        connector.bind();
    }

    public void release() {
        released = true;
        watchdog.removeCallbacksAndMessages(null);
        try {
            if (connector != null) {
                connector.unbind();
            }
        } catch (Throwable ignored) {
        }
        connector = null;
        client = null;
        io.shutdownNow();
    }

    @Override
    public void onConnected(PhantomUserService.Client service) {
        this.client = service;
        RecoveryController.Listener recovery = pendingRecovery;
        pendingRecovery = null;
        if (recovery != null) {
            lifecycle.onBound(now());
            emit();
            RecoveryController controller = new RecoveryController(service,
                    wrapRecovery(recovery));
            io.execute(controller::run);
            return;
        }
        lifecycle.onBound(now());
        emit();
        io.execute(() -> {
            try {
                int result = service.startDaemon(RPC_PORT);
                if (result != 0) {
                    String detail = service.getError();
                    if (detail == null || detail.isEmpty()) {
                        detail = "Daemon refused to start (code " + result + ").";
                    }
                    lifecycle.onFailed(now(), detail);
                    emit();
                    return;
                }
                // The daemon verified its own hello before reporting success,
                // but an outdated UserService from a previous APK install may
                // still be bound — re-check here before claiming RUNNING.
                HelloCheck.Result hello = hello("127.0.0.1", RPC_PORT, HELLO_TIMEOUT_MS);
                if (!hello.ok) {
                    lifecycle.onFailed(now(), hello.error);
                    emit();
                    return;
                }
                lifecycle.onStarted(now(), "Daemon running: " + hello.summary + ".");
                emit();
            } catch (RemoteException e) {
                lifecycle.onFailed(now(),
                        "Binder call failed: " + ServiceConnector.describeError(e));
                emit();
            }
        });
    }

    @Override
    public void onDisconnected() {
        client = null;
        lifecycle.onDisconnected(now());
        emit();
    }

    @Override
    public void onError(String message) {
        lifecycle.onFailed(now(), message);
        emit();
    }

    private RecoveryController.Listener wrapRecovery(RecoveryController.Listener recovery) {
        return new RecoveryController.Listener() {
            @Override
            public void onStep(String message) {
                recovery.onStep(message);
                lifecycle.onProgress(now(), message);
                emit();
            }

            @Override
            public void onDone(boolean ok, String message) {
                recovery.onDone(ok, message);
                if (ok) {
                    lifecycle.onStarted(now(), message);
                } else {
                    lifecycle.onFailed(now(), message);
                }
                emit();
            }
        };
    }

    private void emit() {
        if (listener != null && !released) {
            android.util.Log.i("PhantomDaemon", lifecycle.state() + ": " + lifecycle.detail());
            listener.onState(map(lifecycle.state()), lifecycle.detail());
        }
    }

    private void armWatchdog() {
        watchdog.removeCallbacksAndMessages(null);
        watchdog.postDelayed(new Runnable() {
            @Override
            public void run() {
                DaemonLifecycle.State before = lifecycle.state();
                lifecycle.poll(now());
                if (lifecycle.state() != before) {
                    emit();
                }
                if (!released && isTransient(lifecycle.state())) {
                    watchdog.postDelayed(this, WATCHDOG_MS);
                }
            }
        }, WATCHDOG_MS);
    }

    private static boolean isTransient(DaemonLifecycle.State state) {
        return state == DaemonLifecycle.State.BINDING
                || state == DaemonLifecycle.State.STARTING
                || state == DaemonLifecycle.State.RECOVERING;
    }

    private static State map(DaemonLifecycle.State state) {
        switch (state) {
            case RUNNING:
                return State.RUNNING;
            case ERROR:
                return State.ERROR;
            case IDLE:
                return State.IDLE;
            case RECOVERING:
            case STARTING:
                return State.STARTING;
            case BINDING:
            default:
                return State.BINDING;
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** Loopback hello handshake with identity + capability validation. */
    static HelloCheck.Result hello(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStreamWriter writer = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{}}\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"id\":1")) {
                    continue;
                }
                return HelloCheck.check(line, port);
            }
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return HelloCheck.unreachable(host, port, message);
        }
        return HelloCheck.check(null, port);
    }
}
