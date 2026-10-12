package com.phantom.automation.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Protocol identity for compatibility checks. Bump {@link #VERSION} when the
 * method set changes incompatibly. Clients must call {@code hello} first and
 * refuse to drive a daemon whose {@code app} is not {@code phantom} — a stale
 * legacy {@code app_process} shell may still hold the port.
 */
public final class RpcProtocol {
    public static final String APP = "phantom";
    public static final int VERSION = 2;

    /** Every method the Phantom daemon implements (incl. legacy task loop). */
    public static final String[] METHODS = {
            "hello", "ping", "status", "logs", "dumpUi",
            "openSession", "closeSession", "codecCaps", "setRecordingState",
            "listApps", "launchApp", "screenshot",
            "tap", "longPress", "swipe", "scroll", "pressKey", "pressBack",
            "inputText", "tapTarget", "focusInput", "clearText",
            "ocrStatus", "ocrConfigure", "ocrRecognize", "findText",
            "stopTask", "startTask",
    };

    /** Methods the Phantom UI strictly requires (absent = stale/foreign daemon). */
    public static final String[] REQUIRED = {
            "hello", "openSession", "closeSession", "listApps", "launchApp",
            "dumpUi", "screenshot", "status", "logs",
    };

    private RpcProtocol() {
    }

    static JsonObject hello() {
        JsonObject out = new JsonObject();
        out.addProperty("app", APP);
        out.addProperty("version", VERSION);
        JsonArray methods = new JsonArray();
        for (String method : METHODS) {
            methods.add(method);
        }
        out.add("methods", methods);
        return out;
    }

    /** Returns the missing REQUIRED methods, or empty when compatible. */
    public static java.util.List<String> missing(java.util.Collection<String> methods) {
        java.util.List<String> absent = new java.util.ArrayList<>();
        for (String required : REQUIRED) {
            if (!methods.contains(required)) {
                absent.add(required);
            }
        }
        return absent;
    }
}
