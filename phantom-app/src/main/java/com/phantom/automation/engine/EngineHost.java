package com.phantom.automation.engine;

/**
 * Public entry point into the automation engine for other Phantom packages.
 * Runs the same core as the legacy shell (virtual displays, UI inspection,
 * display-targeted input, NDJSON RPC) but hosted in the Shizuku UserService
 * process instead of a manual {@code app_process} shell.
 */
public final class EngineHost {
    private static final Object LOCK = new Object();
    private static LogHub logs;
    private static AutomationEngine engine;
    private static RpcServer rpc;
    private static Thread serverThread;
    private static int rpcPort;
    private static String lastError = "";

    private EngineHost() {
    }

    /** Start the loopback RPC daemon. Returns 0 on success. */
    public static int start(int port) {
        synchronized (LOCK) {
            if (serverThread != null && serverThread.isAlive()) {
                return 0;
            }
            try {
                if (android.os.Looper.myLooper() == null) {
                    android.os.Looper.prepare();
                }
            } catch (Throwable ignored) {
            }
            try {
                ShellContext.init();
                logs = new LogHub();
                engine = new AutomationEngine(new Config(port, null, null, null), logs);
                rpc = new RpcServer(port, engine, logs);
                rpcPort = port;
                serverThread = new Thread(() -> {
                    try {
                        rpc.run();
                    } catch (Throwable e) {
                        if (logs != null) {
                            logs.error("phantom rpc stopped", e);
                        }
                    }
                }, "phantom-rpc");
                serverThread.setDaemon(true);
                serverThread.start();
                // Verify OUR daemon answers with the Phantom identity. A bare
                // port probe cannot distinguish us from a stale legacy shell
                // still holding 43110, so handshake here instead of hiding it.
                String helloError = verifyHello(port, 6000);
                if (helloError != null) {
                    lastError = helloError;
                    stopLocked();
                    return 2;
                }
                logs.task("phantom daemon listening on 127.0.0.1:" + port);
                lastError = "";
                return 0;
            } catch (Throwable e) {
                if (logs != null) {
                    logs.error("phantom daemon start failed", e);
                }
                lastError = message(e);
                stopLocked();
                return 1;
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        if (rpc != null) {
            try {
                rpc.stop();
            } catch (Throwable ignored) {
            }
            rpc = null;
        }
        engine = null;
        logs = null;
        serverThread = null;
    }

    /** 0=idle, 1=running. */
    public static int getState() {
        synchronized (LOCK) {
            return serverThread != null && serverThread.isAlive() ? 1 : 0;
        }
    }

    public static int port() {
        synchronized (LOCK) {
            return rpcPort;
        }
    }

    public static String lastError() {
        synchronized (LOCK) {
            return lastError;
        }
    }

    /**
     * Handshakes the loopback daemon. Returns null when OUR Phantom daemon
     * answers, otherwise an actionable error (stale daemon / unreachable).
     */
    private static String verifyHello(int port, long timeoutMs) {
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        String lastProblem = "no daemon answered";
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 800);
                socket.setSoTimeout(1500);
                java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                        socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{}}\n");
                writer.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("\"id\":1")) {
                        continue;
                    }
                    socket.close();
                    if (line.contains("\"app\":\"phantom\"")) {
                        return null;
                    }
                    if (line.contains("-32601")) {
                        return "a stale legacy shell holds port " + port
                                + " (it has no hello/listApps). Kill it: adb shell "
                                + "\"kill $(cat /data/local/tmp/silent-auto.pid)\" "
                                + "then tap Start again.";
                    }
                    return "a foreign service holds port " + port
                            + " (unexpected hello reply). Free the port, then retry.";
                }
                socket.close();
                lastProblem = "daemon closed the connection";
            } catch (Throwable e) {
                lastProblem = message(e);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted while waiting for the daemon";
            }
        }
        return "daemon unreachable on 127.0.0.1:" + port + " (" + lastProblem + ")";
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
