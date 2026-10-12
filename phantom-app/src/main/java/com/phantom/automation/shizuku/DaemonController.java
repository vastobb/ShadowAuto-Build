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
                    String detail = service.getError();
                    if (detail == null || detail.isEmpty()) {
                        detail = "Daemon refused to start (code " + result + ").";
                    }
                    set(State.ERROR, detail);
                    return;
                }
                // Defense in depth: the daemon verified its own hello before
                // reporting success, but an outdated UserService from a
                // previous APK install may still be bound — re-check here.
                Hello hello = hello("127.0.0.1", RPC_PORT, PING_TIMEOUT_MS);
                if (!hello.ok) {
                    set(State.ERROR, hello.error);
                    return;
                }
                if (ping("127.0.0.1", RPC_PORT, PING_TIMEOUT_MS)) {
                    set(State.RUNNING, "Daemon running: " + hello.summary + ".");
                } else {
                    set(State.ERROR, "Daemon answered hello but ping timed out. "
                            + "Restart Shizuku and tap Start again.");
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
        return hello(host, port, timeoutMs).ok;
    }

    /** Loopback hello handshake with identity + capability validation. */
    static Hello hello(String host, int port, int timeoutMs) {
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
                if (!line.contains("\"app\":\"phantom\"")) {
                    if (line.contains("-32601")) {
                        return Hello.fail("A stale legacy shell answers on port " + port
                                + " (no hello/listApps). Kill it: adb shell "
                                + "\"kill $(cat /data/local/tmp/silent-auto.pid)\", "
                                + "then tap Start again.");
                    }
                    return Hello.fail("A foreign service answers on port " + port
                            + ". Free the port, then retry.");
                }
                java.util.List<String> methods = methodsOf(line);
                java.util.List<String> missing = missingRequired(methods);
                if (!missing.isEmpty()) {
                    return Hello.fail("Daemon protocol v? is missing: " + missing
                            + ". Reinstall the current Phantom APK, then retry.");
                }
                return Hello.ok("phantom protocol, " + methods.size() + " methods");
            }
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return Hello.fail("Daemon unreachable on " + host + ":" + port
                    + " (" + message + "). Tap Start to launch it.");
        }
        return Hello.fail("Daemon closed the connection without hello.");
    }

    static final class Hello {
        final boolean ok;
        final String summary;
        final String error;

        private Hello(boolean ok, String summary, String error) {
            this.ok = ok;
            this.summary = summary;
            this.error = error;
        }

        static Hello ok(String summary) {
            return new Hello(true, summary, "");
        }

        static Hello fail(String error) {
            return new Hello(false, "", error);
        }
    }

    private static java.util.List<String> methodsOf(String line) {
        java.util.List<String> methods = new java.util.ArrayList<>();
        int start = line.indexOf("\"methods\"");
        if (start < 0) {
            return methods;
        }
        int array = line.indexOf('[', start);
        int end = line.indexOf(']', array);
        if (array < 0 || end < 0) {
            return methods;
        }
        for (String part : line.substring(array + 1, end).split(",")) {
            String name = part.trim();
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                methods.add(name.substring(1, name.length() - 1));
            }
        }
        return methods;
    }

    private static java.util.List<String> missingRequired(java.util.List<String> methods) {
        String[] required = {"hello", "openSession", "closeSession", "listApps",
                "launchApp", "dumpUi", "screenshot", "status", "logs"};
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String name : required) {
            if (!methods.contains(name)) {
                missing.add(name);
            }
        }
        return missing;
    }
}
