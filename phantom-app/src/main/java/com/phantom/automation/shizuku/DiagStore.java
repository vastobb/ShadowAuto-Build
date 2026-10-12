package com.phantom.automation.shizuku;

/**
 * Process-wide diagnostics journal. Every DaemonController in this process
 * writes lifecycle transitions here so the Logs screen can show them next
 * to shell logs (the two run in different processes otherwise).
 */
public final class DiagStore {
    private static final DiagLog INSTANCE = new DiagLog();

    private DiagStore() {
    }

    public static DiagLog get() {
        return INSTANCE;
    }
}
