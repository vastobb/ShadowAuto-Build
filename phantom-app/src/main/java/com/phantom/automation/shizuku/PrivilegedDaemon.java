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

    /** Runs {@code command} via shell in this privileged process. Capped output. */
    static String exec(String command, int timeoutMs, int[] exit) {
        exit[0] = 127;
        if (command == null || command.trim().isEmpty()) {
            return "empty command refused";
        }
        try {
            Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            byte[] buffer = new byte[8192];
            final java.io.InputStream in = process.getInputStream();
            long deadline = android.os.SystemClock.uptimeMillis()
                    + Math.max(1000, Math.min(30000, timeoutMs <= 0 ? 15000 : timeoutMs));
            boolean done = false;
            while (!done && android.os.SystemClock.uptimeMillis() < deadline) {
                try {
                    while (in.available() > 0 && output.length() < 32768) {
                        int length = in.read(buffer, 0,
                                Math.min(buffer.length, 32768 - output.length()));
                        if (length < 0) {
                            break;
                        }
                        output.append(new String(buffer, 0, length,
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Throwable ignored) {
                }
                try {
                    exit[0] = process.exitValue();
                    done = true;
                } catch (IllegalThreadStateException running) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!done) {
                process.destroy();
                return output + "\n[command timed out and was killed]";
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            return output.toString();
        } catch (Throwable e) {
            return "exec failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
