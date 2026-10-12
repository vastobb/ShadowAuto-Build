package com.phantom.automation.shizuku;

import android.content.Context;
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
 * loopback RPC daemon, and verifies it with a ping. All Binder/RPC errors
 * surface as actionable state, never silent success.
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
    private static final int PING_TIMEOUT_MS = 5000;

    private final Context context;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ServiceConnector connector;
    private PhantomUserService.Client client;
    private volatile State state = State.IDLE;

    public DaemonController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (state == State.BINDING || state == State.STARTING) {
            return;
        }
        set(State.BINDING, "Binding privileged service…");
        if (connector == null) {
            connector = new ServiceConnector(context, this);
        }
        connector.bind();
    }

    public void stop() {
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
            set(State.IDLE, "Privileged service stopped.");
        });
    }

    public void release() {
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
        set(State.STARTING, "Starting daemon on 127.0.0.1:" + RPC_PORT + "…");
        io.execute(() -> {
            try {
                int result = service.startDaemon(RPC_PORT);
                if (result != 0) {
                    set(State.ERROR, "Daemon refused to start (code " + result + ").");
                    return;
                }
                if (ping("127.0.0.1", RPC_PORT, PING_TIMEOUT_MS)) {
                    set(State.RUNNING, "Daemon running on 127.0.0.1:" + RPC_PORT + ".");
                } else {
                    set(State.ERROR, "Daemon started but ping timed out. "
                            + "Check the Shizuku service is alive and retry.");
                }
            } catch (RemoteException e) {
                set(State.ERROR, "Binder call failed: " + ServiceConnector.describeError(e));
            }
        });
    }

    @Override
    public void onDisconnected() {
        client = null;
        set(State.ERROR, "Privileged service disconnected (Shizuku restarted?). "
                + "Tap Start to reconnect.");
    }

    @Override
    public void onError(String message) {
        set(State.ERROR, message);
    }

    private void set(State next, String detail) {
        state = next;
        if (listener != null) {
            listener.onState(next, detail);
        }
    }

    /** One-shot NDJSON ping over loopback. False on any failure. */
    static boolean ping(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStreamWriter writer = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"id\":1") && line.contains("pong")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
