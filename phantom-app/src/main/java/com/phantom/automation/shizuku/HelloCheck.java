package com.phantom.automation.shizuku;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-JVM validation of a daemon {@code hello} reply. Used by
 * {@link DaemonController} and unit-tested without Android.
 */
public final class HelloCheck {
    public static final String APP = "phantom";
    public static final int MIN_VERSION = 2;

    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "hello", "openSession", "closeSession", "listApps", "launchApp",
            "dumpUi", "screenshot", "status", "logs"));

    public static final class Result {
        public final boolean ok;
        public final String app;
        public final int version;
        public final List<String> methods;
        public final String summary;
        public final String error;

        private Result(boolean ok, String app, int version, List<String> methods,
                       String summary, String error) {
            this.ok = ok;
            this.app = app;
            this.version = version;
            this.methods = methods;
            this.summary = summary;
            this.error = error;
        }
    }

    private HelloCheck() {
    }

    /** Validates one candidate reply line (already id-matched by the caller). */
    public static Result check(String line, int port) {
        if (line == null) {
            return fail("Daemon closed the connection without hello.");
        }
        if (!line.contains("\"app\":\"" + APP + "\"")) {
            if (line.contains("-32601")) {
                return fail("A stale legacy shell answers on port " + port
                        + " (no hello/listApps). Use Recover — no manual adb needed.");
            }
            return fail("A foreign service answers on port " + port
                    + ". Free the port (stop that app), then Start again.");
        }
        int version = parseVersion(line);
        if (version < MIN_VERSION) {
            return fail("Daemon protocol v" + version + " is too old (need v"
                    + MIN_VERSION + "+). Reinstall the current Phantom APK.");
        }
        List<String> methods = parseMethods(line);
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!methods.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            return fail("Daemon protocol v" + version + " is missing: " + missing
                    + ". Reinstall the current Phantom APK, then retry.");
        }
        Result ok = new Result(true, APP, version, methods,
                "phantom protocol v" + version + ", " + methods.size() + " methods", "");
        return ok;
    }

    public static Result unreachable(String host, int port, String cause) {
        return fail("Daemon unreachable on " + host + ":" + port
                + " (" + cause + "). Tap Start to launch it.");
    }

    private static Result fail(String error) {
        return new Result(false, "", 0, new ArrayList<>(), "", error);
    }

    static int parseVersion(String line) {
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

    static List<String> parseMethods(String line) {
        List<String> methods = new ArrayList<>();
        int start = line.indexOf("\"methods\"");
        if (start < 0) {
            return methods;
        }
        int array = line.indexOf('[', start);
        int end = array < 0 ? -1 : line.indexOf(']', array);
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
}
