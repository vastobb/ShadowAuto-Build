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
                logs.task("phantom daemon listening on 127.0.0.1:" + port);
                return 0;
            } catch (Throwable e) {
                if (logs != null) {
                    logs.error("phantom daemon start failed", e);
                }
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
}
