package com.phantom.automation.shizuku;

import java.util.ArrayList;
import java.util.List;

/**
 * Timestamped transition journal for daemon lifecycle diagnosis. Pure JVM;
 * the UI surfaces it in Logs so a stuck startup names the exact step.
 */
public final class DiagLog {
    private final List<String> lines = new ArrayList<>();
    private final int capacity;

    public DiagLog() {
        this(120);
    }

    DiagLog(int capacity) {
        this.capacity = Math.max(16, capacity);
    }

    public synchronized void add(String message) {
        long seconds = System.currentTimeMillis() / 1000;
        lines.add("[" + seconds + "] " + message);
        while (lines.size() > capacity) {
            lines.remove(0);
        }
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }
}
