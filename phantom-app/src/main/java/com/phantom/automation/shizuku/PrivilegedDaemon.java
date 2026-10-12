package com.phantom.automation.shizuku;

/** Lifecycle stub for the privileged RPC daemon (engine lands in M2). */
final class PrivilegedDaemon {
    private PrivilegedDaemon() {
    }

    static int getState() {
        return 0;
    }

    static int start(int port) {
        return 0;
    }

    static void stop() {
    }
}
