package com.phantom.automation.shizuku;

/** Wires the Shizuku UserService Binder to the engine host. */
final class PrivilegedDaemon {
    private PrivilegedDaemon() {
    }

    static int getState() {
        return com.phantom.automation.engine.EngineHost.getState();
    }

    static int start(int port) {
        return com.phantom.automation.engine.EngineHost.start(port);
    }

    static void stop() {
        com.phantom.automation.engine.EngineHost.stop();
    }

    static String error() {
        return com.phantom.automation.engine.EngineHost.lastError();
    }
}
