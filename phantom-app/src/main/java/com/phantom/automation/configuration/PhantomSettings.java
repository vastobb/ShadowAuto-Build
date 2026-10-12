package com.phantom.automation.configuration;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent Phantom settings. API keys are stored but never logged. */
public final class PhantomSettings {
    private static final String PREFS = "phantom_settings";

    public String defaultSession = "";
    public int displayWidth = 720;
    public int displayHeight = 1280;
    public int displayDpi = 320;
    public String codec = "auto";
    public int bitRate;
    public int frameRate;
    public String apiBase = "http://127.0.0.1:8410/v1";
    public String apiKey = "";
    public String model = "phantom-automation";
    public String mcpRepoPath = "";
    public boolean verboseLogs;

    public static PhantomSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        PhantomSettings settings = new PhantomSettings();
        settings.defaultSession = prefs.getString("defaultSession", "");
        settings.displayWidth = prefs.getInt("displayWidth", 720);
        settings.displayHeight = prefs.getInt("displayHeight", 1280);
        settings.displayDpi = prefs.getInt("displayDpi", 320);
        settings.codec = prefs.getString("codec", "auto");
        settings.bitRate = prefs.getInt("bitRate", 0);
        settings.frameRate = prefs.getInt("frameRate", 0);
        settings.apiBase = prefs.getString("apiBase", "http://127.0.0.1:8410/v1");
        settings.apiKey = prefs.getString("apiKey", "");
        settings.model = prefs.getString("model", "phantom-automation");
        settings.mcpRepoPath = prefs.getString("mcpRepoPath", "");
        settings.verboseLogs = prefs.getBoolean("verboseLogs", false);
        return settings;
    }

    public void save(Context context) {
        prefs(context).edit()
                .putString("defaultSession", defaultSession)
                .putInt("displayWidth", displayWidth)
                .putInt("displayHeight", displayHeight)
                .putInt("displayDpi", displayDpi)
                .putString("codec", codec)
                .putInt("bitRate", bitRate)
                .putInt("frameRate", frameRate)
                .putString("apiBase", apiBase)
                .putString("apiKey", apiKey)
                .putString("model", model)
                .putString("mcpRepoPath", mcpRepoPath)
                .putBoolean("verboseLogs", verboseLogs)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
