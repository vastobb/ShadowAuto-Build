package com.phantom.automation.capture;

import android.util.Base64;

import org.json.JSONObject;

/** Parses the loopback RPC {@code event} lines for video and log routing. */
public final class VideoEvents {
    public static final class Config {
        public String id = "";
        public String mime = "video/avc";
        public int width;
        public int height;
        public byte[] csd0 = new byte[0];
        public byte[] csd1 = new byte[0];
    }

    public static final class Sample {
        public String id = "";
        public long ptsUs;
        public int flags;
        public byte[] data = new byte[0];
    }

    private VideoEvents() {
    }

    /** Returns the event type, or "" for non-event lines. */
    public static String typeOf(String line) {
        try {
            JSONObject root = new JSONObject(line);
            if (!"event".equals(root.optString("method"))) {
                return "";
            }
            return root.optJSONObject("params").optString("type");
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static JSONObject paramsOf(String line) {
        try {
            return new JSONObject(line).optJSONObject("params");
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    public static Config parseConfig(JSONObject params) {
        Config config = new Config();
        config.id = params.optString("taskId", params.optString("sessionId"));
        config.mime = params.optString("mime", "video/avc");
        config.width = params.optInt("width");
        config.height = params.optInt("height");
        config.csd0 = decode(params.optString("csd0"));
        config.csd1 = decode(params.optString("csd1"));
        return config;
    }

    public static Sample parseSample(JSONObject params) {
        Sample sample = new Sample();
        sample.id = params.optString("taskId", params.optString("sessionId"));
        sample.ptsUs = params.optLong("ptsUs");
        sample.flags = params.optInt("flags");
        sample.data = decode(params.optString("data"));
        return sample;
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }
}
