package com.phantom.automation.engine;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Direct-control virtual-display sessions for MCP/OpenCode clients.
 *
 * <p>Unlike {@link AutomationEngine#startTask} (which runs the autonomous AI
 * tool-call loop), sessions created here perform no inference: the caller
 * inspects UI via {@code dumpUi}/{@code screenshot} and issues one action
 * per RPC. Every touch/key event is injected with the session's virtual
 * display id (see {@link InputBridge}), never the physical display.
 */
final class McpSessions {
    private final LogHub logs;
    private final AppCatalog appCatalog;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private UiBridge ui;
    private InputBridge input;
    private ClipboardBridge clipboard;

    McpSessions(LogHub logs) {
        this.logs = logs;
        this.appCatalog = new AppCatalog(logs);
    }

    synchronized JsonObject openSession(String sessionId, int width, int height, String codecPref)
            throws Exception {
        String id = Config.empty(sessionId) ? "mcp-" + nextId.getAndIncrement() : sessionId.trim();
        closeSessionLocked(id);
        Session session = new Session(id);
        session.state = SessionState.CREATING;
        sessions.put(id, session);
        try {
            DisplaySpec spec = DisplaySpec.current(logs);
            int w = width > 0 ? width : spec.width;
            int h = height > 0 ? height : spec.height;
            w = Math.max(320, Math.min(1920, w));
            h = Math.max(320, Math.min(1920, h));
            // The encoder surface feeds the virtual display; still frames use
            // the capture surface and the encoded stream feeds preview + recording.
            VideoStreamer video = new VideoStreamer(logs, id, w, h, codecPref, 0, 0);
            VirtualDisplaySession display = VirtualDisplaySession.create(
                    video.width(), video.height(), spec.dpi, video.inputSurface());
            session.display = display;
            session.video = video;
            session.createdAt = System.currentTimeMillis();
            session.state = SessionState.READY;
            video.start();
            logs.task(id, "session opened on virtual display " + display.displayId
                    + " " + display.width + "x" + display.height + " dpi=" + display.dpi
                    + " codec=" + video.mime());
        } catch (Throwable e) {
            session.state = SessionState.ERROR;
            session.lastError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            closeSessionLocked(id);
            throw e;
        }
        return sessionInfo(session);
    }

    /** Legacy shape: open with automatic codec. */
    synchronized JsonObject openSession(String sessionId, int width, int height) throws Exception {
        return openSession(sessionId, width, height, VideoStreamer.CODEC_AUTO);
    }

    synchronized JsonObject closeSession(String sessionId) {
        if (Config.empty(sessionId)) {
            for (String id : new ArrayList<>(sessions.keySet())) {
                closeSessionLocked(id);
            }
            logs.task("sessions closed");
        } else {
            closeSessionLocked(sessionId.trim());
        }
        JsonObject result = new JsonObject();
        result.addProperty("value", "closed");
        return result;
    }

    synchronized JsonObject setRecordingState(String sessionId, String state) {
        Session session = require(sessionId);
        String value = Config.empty(state) ? "idle" : state.trim().toLowerCase(Locale.US);
        if (!value.equals("idle") && !value.equals("recording")
                && !value.equals("paused") && !value.equals("error")) {
            throw new IllegalArgumentException("unsupported recording state: " + state);
        }
        session.recordingState = value;
        logs.task(session.id, "recording state -> " + value);
        return sessionInfo(session);
    }

    synchronized JsonArray statusArray() {
        JsonArray array = new JsonArray();
        for (Session session : sessions.values()) {
            array.add(sessionInfo(session));
        }
        return array;
    }

    private JsonObject sessionInfo(Session session) {
        JsonObject item = new JsonObject();
        item.addProperty("sessionId", session.id);
        item.addProperty("state", session.state);
        item.addProperty("displayId", session.display == null ? -1 : session.display.displayId);
        item.addProperty("width", session.display == null ? 0 : session.display.width);
        item.addProperty("height", session.display == null ? 0 : session.display.height);
        item.addProperty("dpi", session.display == null ? 0 : session.display.dpi);
        item.addProperty("codec", session.video == null ? "" : session.video.mime());
        item.addProperty("foregroundApp", session.foregroundApp);
        item.addProperty("createdAt", session.createdAt);
        item.addProperty("recordingState", session.recordingState);
        item.addProperty("lastError", session.lastError);
        return item;
    }

    synchronized JsonArray listApps() {
        List<AppCatalog.AppInfo> apps = appCatalog.installedApps();
        JsonArray array = new JsonArray();
        for (AppCatalog.AppInfo app : apps) {
            JsonObject item = new JsonObject();
            item.addProperty("name", app.name);
            item.addProperty("packageName", app.packageName);
            array.add(item);
        }
        return array;
    }

    synchronized JsonObject launchApp(String sessionId, String packageName) throws Exception {
        Session session = require(sessionId);
        if (Config.empty(packageName)) {
            throw new IllegalArgumentException("packageName is required");
        }
        try {
            appCatalog.launchPackage(session.id, packageName.trim(), session.display.displayId);
            session.foregroundApp = packageName.trim();
            session.state = SessionState.RUNNING;
            session.lastError = "";
        } catch (Throwable e) {
            session.state = SessionState.ERROR;
            session.lastError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            throw e;
        }
        logs.task(session.id, "launched " + packageName.trim()
                + " on virtual display " + session.display.displayId);
        SystemClock.sleep(1500);
        try {
            String top = getUi().foregroundPackage(session.display.displayId);
            if (!Config.empty(top)) {
                session.foregroundApp = top;
            }
        } catch (Throwable ignored) {
        }
        JsonObject result = sessionInfo(session);
        result.addProperty("value", "launched");
        return result;
    }

    synchronized JsonObject dumpUi(String sessionId, int displayId, int width, int height, String mode) {
        Session session = sessionOrNull(sessionId);
        int targetDisplay = displayId;
        int w = width;
        int h = height;
        if (session != null) {
            targetDisplay = session.display.displayId;
            if (w <= 0) {
                w = session.display.width;
            }
            if (h <= 0) {
                h = session.display.height;
            }
        }
        String value = getUi().dump(targetDisplay, w, h, mode);
        return JsonParser.parseString(value).getAsJsonObject();
    }

    synchronized JsonObject screenshot(String sessionId, int displayId) throws Exception {
        Session session = requireDisplay(sessionId, displayId);
        Bitmap bitmap = session.display.captureBitmap(8, 120);
        if (bitmap == null) {
            throw new IllegalStateException("screenshot capture returned no frame");
        }
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IllegalStateException("screenshot PNG encode failed");
            }
            JsonObject result = new JsonObject();
            result.addProperty("sessionId", session.id);
            result.addProperty("displayId", session.display.displayId);
            result.addProperty("width", bitmap.getWidth());
            result.addProperty("height", bitmap.getHeight());
            result.addProperty("mime", "image/png");
            result.addProperty("data", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP));
            logs.task(session.id, "mcp screenshot " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " on virtual display " + session.display.displayId);
            return result;
        } finally {
            bitmap.recycle();
        }
    }

    synchronized JsonObject tap(String sessionId, int x, int y) throws Exception {
        Session session = require(sessionId);
        int localX = clamp(x, 0, session.display.width - 1);
        int localY = clamp(y, 0, session.display.height - 1);
        boolean injected = getInput().tap(session.display.displayId, localX, localY);
        logs.task(session.id, "mcp tap display-local " + localX + "," + localY
                + " on virtual display " + session.display.displayId + " injected=" + injected);
        return boolResult(session, "tapped", injected);
    }

    synchronized JsonObject longPress(String sessionId, int x, int y, int durationMs) throws Exception {
        Session session = require(sessionId);
        int localX = clamp(x, 0, session.display.width - 1);
        int localY = clamp(y, 0, session.display.height - 1);
        boolean injected = getInput().longPress(session.display.displayId, localX, localY, durationMs);
        logs.task(session.id, "mcp long press " + localX + "," + localY
                + " on virtual display " + session.display.displayId + " injected=" + injected);
        return boolResult(session, "longPressed", injected);
    }

    synchronized JsonObject swipe(String sessionId, int startX, int startY, int endX, int endY, int durationMs)
            throws Exception {
        Session session = require(sessionId);
        int sx = clamp(startX, 0, session.display.width - 1);
        int sy = clamp(startY, 0, session.display.height - 1);
        int ex = clamp(endX, 0, session.display.width - 1);
        int ey = clamp(endY, 0, session.display.height - 1);
        int duration = durationMs > 0 ? durationMs : 420;
        boolean injected = getInput().swipe(session.display.displayId, sx, sy, ex, ey, duration);
        logs.task(session.id, "mcp swipe " + sx + "," + sy + " -> " + ex + "," + ey
                + " on virtual display " + session.display.displayId + " injected=" + injected);
        return boolResult(session, "swiped", injected);
    }

    synchronized JsonObject scroll(String sessionId, String direction, int distance) throws Exception {
        Session session = require(sessionId);
        String dir = Config.empty(direction) ? "down" : direction.toLowerCase(Locale.US);
        int width = session.display.width;
        int height = session.display.height;
        int centerX = Math.max(1, width / 2);
        int centerY = Math.max(1, height / 2);
        int minX = Math.min(40, Math.max(0, width - 1));
        int minY = Math.min(40, Math.max(0, height - 1));
        int maxX = Math.max(minX, width - 40);
        int maxY = Math.max(minY, height - 40);
        int vertical = distance > 0 ? distance : Math.max(240, height * 3 / 5);
        int horizontal = distance > 0 ? distance : Math.max(180, width * 3 / 5);
        int startX = centerX;
        int startY = centerY;
        int endX = centerX;
        int endY = centerY;
        if ("up".equals(dir)) {
            startY = clamp(centerY - vertical / 2, minY, maxY);
            endY = clamp(startY + vertical, minY, maxY);
        } else if ("left".equals(dir)) {
            startX = clamp(centerX - horizontal / 2, minX, maxX);
            endX = clamp(startX + horizontal, minX, maxX);
        } else if ("right".equals(dir)) {
            startX = clamp(centerX + horizontal / 2, minX, maxX);
            endX = clamp(startX - horizontal, minX, maxX);
        } else {
            dir = "down";
            startY = clamp(centerY + vertical / 2, minY, maxY);
            endY = clamp(startY - vertical, minY, maxY);
        }
        boolean injected = getInput().swipe(session.display.displayId, startX, startY, endX, endY, 420);
        logs.task(session.id, "mcp scroll " + dir + " via swipe " + startX + "," + startY
                + " -> " + endX + "," + endY + " on virtual display "
                + session.display.displayId + " injected=" + injected);
        return boolResult(session, "scrolled", injected);
    }

    synchronized JsonObject pressKey(String sessionId, String key) throws Exception {
        Session session = require(sessionId);
        int keyCode = keyCode(key);
        if (keyCode == 0) {
            throw new IllegalArgumentException("unsupported key: " + key
                    + " (use back, enter, search, tab, dpad_up, dpad_down, dpad_left, dpad_right, dpad_center)");
        }
        boolean injected = getInput().key(session.display.displayId, keyCode);
        logs.task(session.id, "mcp press key " + key + " displayId=" + session.display.displayId
                + " keyCode=" + keyCode + " injected=" + injected);
        return boolResult(session, "keyPressed", injected);
    }

    synchronized JsonObject pressBack(String sessionId) throws Exception {
        Session session = require(sessionId);
        boolean injected = getInput().key(session.display.displayId, KeyEvent.KEYCODE_BACK);
        logs.task(session.id, "mcp press back on virtual display "
                + session.display.displayId + " injected=" + injected);
        return boolResult(session, "backPressed", injected);
    }

    synchronized JsonObject inputText(String sessionId, String text) throws Exception {
        Session session = require(sessionId);
        String value = text == null ? "" : text;
        UiBridge bridge = getUi();
        if (bridge.setText(session.display.displayId, value)) {
            logs.task(session.id, "mcp text set by accessibility");
            return boolResult(session, "inputted", true);
        }
        getClipboard().setText(value);
        if (bridge.paste(session.display.displayId)) {
            logs.task(session.id, "mcp text pasted from clipboard by accessibility");
            return boolResult(session, "inputted", true);
        }
        getInput().paste(session.display.displayId);
        logs.task(session.id, "mcp text pasted from clipboard by key events");
        return boolResult(session, "inputted", true);
    }

    synchronized JsonObject tapTarget(String sessionId, int targetIndex) throws Exception {
        Session session = require(sessionId);
        if (targetIndex <= 0) {
            throw new IllegalArgumentException("targetIndex must be > 0 (see dumpUi targets)");
        }
        UiBridge.ClickResult result = getUi().clickTarget(session.display.displayId, targetIndex);
        if (result == null) {
            throw new IllegalStateException("tap target not found: " + targetIndex);
        }
        if (result.clicked) {
            logs.task(session.id, "mcp tap target clicked by accessibility: #" + result.targetIndex);
            return targetResult(session, result.targetIndex, result.x, result.y, true);
        }
        int x = clamp(result.x, 0, session.display.width - 1);
        int y = clamp(result.y, 0, session.display.height - 1);
        boolean injected = getInput().tap(session.display.displayId, x, y);
        logs.task(session.id, "mcp tap target fallback display-local " + x + "," + y
                + " for #" + result.targetIndex + " injected=" + injected);
        return targetResult(session, result.targetIndex, x, y, injected);
    }

    synchronized JsonObject focusInput(String sessionId, int inputIndex, String query) throws Exception {
        Session session = require(sessionId);
        UiBridge.FocusResult result = getUi().focusInput(session.display.displayId, inputIndex, query);
        if (result == null) {
            throw new IllegalStateException("input not found"
                    + (Config.empty(query) ? "" : ": " + query));
        }
        if (result.accessibility) {
            logs.task(session.id, "mcp focused input by accessibility");
            return targetResult(session, result.targetIndex, result.x, result.y, true);
        }
        int x = clamp(result.x, 0, session.display.width - 1);
        int y = clamp(result.y, 0, session.display.height - 1);
        boolean injected = getInput().tap(session.display.displayId, x, y);
        logs.task(session.id, "mcp focus input fallback tap " + x + "," + y + " injected=" + injected);
        return targetResult(session, result.targetIndex, x, y, injected);
    }

    synchronized JsonObject clearText(String sessionId) throws Exception {
        Session session = require(sessionId);
        UiBridge bridge = getUi();
        if (bridge.setText(session.display.displayId, "")) {
            logs.task(session.id, "mcp text cleared by accessibility");
            return boolResult(session, "cleared", true);
        }
        if (bridge.selectAll(session.display.displayId)) {
            bridge.deleteSelection(session.display.displayId);
            logs.task(session.id, "mcp text cleared by selection");
            return boolResult(session, "cleared", true);
        }
        getInput().selectAll(session.display.displayId);
        getInput().delete(session.display.displayId);
        logs.task(session.id, "mcp clear text sent by key events");
        return boolResult(session, "cleared", true);
    }

    private Session require(String sessionId) {
        Session session = sessionOrNull(sessionId);
        if (session == null || session.display == null) {
            throw new IllegalStateException("session not found: " + sessionId
                    + " (call openSession first; ids are never reused while open)");
        }
        if (SessionState.ERROR.equals(session.state)) {
            throw new IllegalStateException("session " + session.id + " is in ERROR: "
                    + session.lastError + " (close it and open a new session)");
        }
        return session;
    }

    private Session requireDisplay(String sessionId, int displayId) {
        Session byId = sessionOrNull(sessionId);
        if (byId != null && byId.display != null) {
            return byId;
        }
        if (displayId > 0) {
            for (Session session : sessions.values()) {
                if (session.display != null && session.display.displayId == displayId) {
                    return session;
                }
            }
        }
        if (!sessions.isEmpty()) {
            return sessions.values().iterator().next();
        }
        throw new IllegalStateException("no mcp session open (call openSession first)");
    }

    private Session sessionOrNull(String sessionId) {
        if (Config.empty(sessionId)) {
            return null;
        }
        return sessions.get(sessionId.trim());
    }

    private void closeSessionLocked(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        session.state = SessionState.STOPPING;
        if (session.video != null) {
            try {
                session.video.stop();
            } catch (Throwable ignored) {
            }
            session.video = null;
        }
        if (session.display != null) {
            try {
                session.display.release();
            } catch (Throwable ignored) {
            }
            session.display = null;
        }
        session.state = SessionState.STOPPED;
        logs.task(session.id, "session closed");
    }

    private UiBridge getUi() {
        if (ui == null) {
            ui = new UiBridge(logs);
            ui.connect();
        }
        return ui;
    }

    private InputBridge getInput() throws Exception {
        if (input == null) {
            input = new InputBridge();
        }
        return input;
    }

    private ClipboardBridge getClipboard() {
        if (clipboard == null) {
            clipboard = new ClipboardBridge();
        }
        return clipboard;
    }

    private JsonObject boolResult(Session session, String field, boolean value) {
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", session.id);
        result.addProperty("displayId", session.display.displayId);
        result.addProperty(field, value);
        return result;
    }

    private JsonObject targetResult(Session session, int targetIndex, int x, int y, boolean ok) {
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", session.id);
        result.addProperty("displayId", session.display.displayId);
        result.addProperty("targetIndex", targetIndex);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("injected", ok);
        return result;
    }

    private static int keyCode(String key) {
        String value = Config.empty(key) ? "" : key.toLowerCase(Locale.US);
        if ("back".equals(value)) {
            return KeyEvent.KEYCODE_BACK;
        }
        if ("enter".equals(value)) {
            return KeyEvent.KEYCODE_ENTER;
        }
        if ("search".equals(value)) {
            return KeyEvent.KEYCODE_SEARCH;
        }
        if ("tab".equals(value)) {
            return KeyEvent.KEYCODE_TAB;
        }
        if ("dpad_up".equals(value)) {
            return KeyEvent.KEYCODE_DPAD_UP;
        }
        if ("dpad_down".equals(value)) {
            return KeyEvent.KEYCODE_DPAD_DOWN;
        }
        if ("dpad_left".equals(value)) {
            return KeyEvent.KEYCODE_DPAD_LEFT;
        }
        if ("dpad_right".equals(value)) {
            return KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        if ("dpad_center".equals(value)) {
            return KeyEvent.KEYCODE_DPAD_CENTER;
        }
        return 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Session {
        final String id;
        VirtualDisplaySession display;
        VideoStreamer video;
        String state = SessionState.READY;
        String foregroundApp = "";
        long createdAt;
        String recordingState = "idle";
        String lastError = "";

        Session(String id) {
            this.id = id;
        }
    }

    /** Explicit session lifecycle; ERROR never transitions back to READY. */
    static final class SessionState {
        static final String CREATING = "CREATING";
        static final String READY = "READY";
        static final String RUNNING = "RUNNING";
        static final String STOPPING = "STOPPING";
        static final String STOPPED = "STOPPED";
        static final String ERROR = "ERROR";

        private SessionState() {
        }
    }
}
