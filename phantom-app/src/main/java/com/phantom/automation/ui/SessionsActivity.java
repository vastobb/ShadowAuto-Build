package com.phantom.automation.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.phantom.automation.R;
import com.phantom.automation.rpc.RpcClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists virtual-display sessions; create/close/open. */
public final class SessionsActivity extends Activity implements RpcClient.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private RpcClient rpc;
    private LinearLayout list;
    private TextView empty;
    private EditText widthInput;
    private EditText heightInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sessions);
        list = findViewById(R.id.sessionList);
        empty = findViewById(R.id.sessionEmpty);
        widthInput = findViewById(R.id.widthInput);
        heightInput = findViewById(R.id.heightInput);
        Button create = findViewById(R.id.createButton);
        Button refresh = findViewById(R.id.refreshButton);
        create.setOnClickListener(v -> createSession());
        refresh.setOnClickListener(v -> refresh());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        io.execute(() -> send("status", "{}"));
    }

    private void createSession() {
        int width = number(widthInput, 720);
        int height = number(heightInput, 1280);
        io.execute(() -> send("openSession",
                "{\"width\":" + width + ",\"height\":" + height + ",\"codec\":\"auto\"}"));
    }

    private void closeSession(String sessionId) {
        io.execute(() -> send("closeSession", "{\"sessionId\":\"" + escape(sessionId) + "\"}"));
    }

    private void send(String method, String paramsJson) {
        try {
            if (rpc == null) {
                rpc = new RpcClient("127.0.0.1", 43110, this);
            }
            rpc.send(method, new JSONObject(paramsJson));
        } catch (Exception e) {
            toast("RPC error: " + e.getMessage() + " (is the daemon running?)");
            rpc = null;
        }
    }

    @Override
    public void onLine(String line) {
        main.post(() -> handle(line));
    }

    @Override
    public void onClosed(Exception error) {
        rpc = null;
    }

    private void handle(String line) {
        try {
            JSONObject root = new JSONObject(line);
            if (root.has("error")) {
                toast("Shell: " + root.getJSONObject("error").optString("message"));
                return;
            }
            if (!root.has("result")) {
                return;
            }
            JSONObject result = root.getJSONObject("result");
            if (result.has("mcpSessions")) {
                render(result.getJSONArray("mcpSessions"));
            } else if (result.has("sessionId")) {
                refresh();
            } else if (result.has("value")) {
                refresh();
            }
        } catch (Exception ignored) {
        }
    }

    private void render(JSONArray sessions) {
        list.removeAllViews();
        empty.setVisibility(sessions.length() == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("sessionId");
            String label = id + " · " + item.optString("state")
                    + " · display " + item.optInt("displayId")
                    + " · " + item.optInt("width") + "x" + item.optInt("height")
                    + " · " + item.optString("codec")
                    + (item.optString("foregroundApp").isEmpty()
                            ? "" : "\n" + item.optString("foregroundApp"));
            TextView view = new TextView(this);
            view.setText(label);
            view.setTextColor(0xFFE8EDF2);
            view.setTextSize(14);
            view.setPadding(16, 16, 16, 16);
            view.setOnClickListener(v -> {
                Intent intent = new Intent(this, SessionDetailActivity.class);
                intent.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, id);
                intent.putExtra(SessionDetailActivity.EXTRA_WIDTH, item.optInt("width"));
                intent.putExtra(SessionDetailActivity.EXTRA_HEIGHT, item.optInt("height"));
                startActivity(intent);
            });
            view.setOnLongClickListener(v -> {
                closeSession(id);
                return true;
            });
            list.addView(view);
        }
    }

    private int number(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
