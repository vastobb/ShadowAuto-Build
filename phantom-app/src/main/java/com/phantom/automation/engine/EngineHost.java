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
    private static String lastHandshake = "";

    private EngineHost() {
    }

    /** Start result codes (returned through the UserService Binder). */
    public static final int SERVING = 0;
    public static final int START_FAILED = 1;
    public static final int BIND_FAILED = 2;
    public static final int ADOPTED = 3;
    public static final int LEGACY_HOLDER = 4;

    /**
     * Claim the loopback RPC port by handshake, not by assumption:
     * adopt a healthy Phantom endpoint, bind when free, report verified
     * legacy/foreign holders for the caller to handle. Idempotent: a live
     * server of ours returns {@link #SERVING}; repeated taps are safe.
     */
    public static int start(int port) {
        synchronized (LOCK) {
            if (serverThread != null && serverThread.isAlive()) {
                lastError = "";
                return SERVING;
            }
            // 1. Probe before binding: a healthy Phantom endpoint wins
            // over any new socket we could create.
            PortClaim.Probe first = probe(port, 1200);
            PortClaim.Outcome plan = PortClaim.onProbeBeforeBind(first);
            if (plan == PortClaim.Outcome.ADOPT) {
                lastError = "";
                lastHandshake = describe(first);
                return ADOPTED;
            }
            if (plan == PortClaim.Outcome.LEGACY_HOLDER) {
                lastError = "Verified legacy shell holds port " + port
                        + " (hello answered -32601). Use Recover for targeted cleanup.";
                return LEGACY_HOLDER;
            }
            if (plan == PortClaim.Outcome.FOREIGN) {
                lastError = "A foreign service holds port " + port
                        + ". Free the port, then retry.";
                return BIND_FAILED;
            }
            // 2. Port looks free: prepare and bind.
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
            } catch (Throwable e) {
                if (logs != null) {
                    logs.error("phantom daemon start failed", e);
                }
                lastError = message(e);
                stopLocked();
                return START_FAILED;
            }
            // 3. Confirm OUR socket serves Phantom (a holder may have won
            // the race between probe and bind).
            String helloError = verifyHello(port, 6000);
            if (helloError == null) {
                logs.task("phantom daemon listening on 127.0.0.1:" + port
                        + " pid=" + android.os.Process.myPid());
                lastError = "";
                lastHandshake = "serving, pid=" + android.os.Process.myPid();
                return SERVING;
            }
            PortClaim.Probe conflict = probe(port, 1200);
            PortClaim.Outcome resolution = PortClaim.onBindConflict(conflict);
            stopLocked();
            if (resolution == PortClaim.Outcome.ADOPT) {
                lastError = "";
                lastHandshake = describe(conflict);
                return ADOPTED;
            }
            if (resolution == PortClaim.Outcome.RETRY) {
                lastError = "";
                return startRetry(port);
            }
            if (resolution == PortClaim.Outcome.LEGACY_HOLDER) {
                lastError = "Verified legacy shell holds port " + port
                        + " (hello answered -32601). Use Recover for targeted cleanup.";
                return LEGACY_HOLDER;
            }
            lastError = helloError;
            return BIND_FAILED;
        }
    }

    /** Single retry after a holder vanished mid-claim. */
    private static int startRetry(int port) {
        PortClaim.Probe probe = probe(port, 1500);
        if (PortClaim.onProbeBeforeBind(probe) == PortClaim.Outcome.SERVE) {
            try {
                ShellContext.init();
                if (logs == null) {
                    logs = new LogHub();
                }
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
            } catch (Throwable e) {
                lastError = message(e);
                stopLocked();
                return START_FAILED;
            }
            String helloError = verifyHello(port, 4000);
            if (helloError == null) {
                logs.task("phantom daemon listening on 127.0.0.1:" + port);
                lastError = "";
                return SERVING;
            }
            lastError = helloError;
            stopLocked();
            return BIND_FAILED;
        }
        lastError = "Port state changed during startup; tap Start again.";
        return BIND_FAILED;
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

    public static String lastHandshake() {
        synchronized (LOCK) {
            return lastHandshake;
        }
    }

    /** Single hello probe classifying whoever answers (or nothing). */
    static PortClaim.Probe probe(int port, long timeoutMs) {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), (int) timeoutMs);
            socket.setSoTimeout((int) timeoutMs);
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
                    return new PortClaim.Probe(true, true, false,
                            versionOf(line), pidOf(line));
                }
                if (line.contains("-32601")) {
                    return new PortClaim.Probe(true, false, true, 0, -1);
                }
                return new PortClaim.Probe(true, false, false, 0, -1);
            }
            socket.close();
        } catch (Throwable ignored) {
        }
        return PortClaim.Probe.refused();
    }

    private static String describe(PortClaim.Probe probe) {
        return "adopted healthy daemon (pid " + probe.pid
                + ", protocol v" + probe.version + ")";
    }

    private static int versionOf(String line) {
        int key = line.indexOf("\"version\"");
        if (key < 0) {
            return 0;
        }
        int colon = line.indexOf(':', key);
        if (colon < 0) {
            return 0;
        }
        int end = colon + 1;
        while (end < line.length() && (Character.isDigit(line.charAt(end))
                || line.charAt(end) == ' ')) {
            end++;
        }
        try {
            return Integer.parseInt(line.substring(colon + 1, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int pidOf(String line) {
        int key = line.indexOf("\"pid\"");
        if (key < 0) {
            return -1;
        }
        int colon = line.indexOf(':', key);
        if (colon < 0) {
            return -1;
        }
        int end = colon + 1;
        while (end < line.length() && (Character.isDigit(line.charAt(end))
                || line.charAt(end) == ' ' || line.charAt(end) == '-')) {
            end++;
        }
        try {
            return Integer.parseInt(line.substring(colon + 1, end).trim());
        } catch (NumberFormatException e) {
            return -1;
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
                        return "a listener without hello holds port " + port
                                + " (often a stale legacy shell, not proven). "
                                + "Use Recover to identify it.";
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
