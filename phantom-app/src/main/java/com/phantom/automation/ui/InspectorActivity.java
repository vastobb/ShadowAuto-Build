package com.phantom.automation.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.phantom.automation.R;
import com.phantom.automation.rpc.Rpc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Inspector: session screenshot with OCR/target boxes, accessibility
 * hierarchy summary, OCR results, and validated find-and-tap (tap only on
 * a verified hit — never guessed coordinates).
 */
public final class InspectorActivity extends Activity {
    public static final String EXTRA_SESSION_ID = "sessionId";

    private final ExecutorService io = Executors.newCachedThreadPool();
    private Spinner sessionSpinner;
    private ImageView shot;
    private OverlayView overlay;
    private TextView hierarchyText;
    private TextView ocrText;
    private TextView findResult;
    private TextView stateLine;
    private TextInputEditText queryInput;
    private MaterialButton tapButton;
    private View loading;
    private final List<String> sessions = new ArrayList<>();
    private JSONObject lastHit;
    private int shotWidth = 1;
    private int shotHeight = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inspector);
        sessionSpinner = findViewById(R.id.sessionSpinner);
        shot = findViewById(R.id.shotView);
        overlay = findViewById(R.id.shotOverlay);
        hierarchyText = findViewById(R.id.hierarchyText);
        ocrText = findViewById(R.id.ocrText);
        findResult = findViewById(R.id.findResult);
        stateLine = findViewById(R.id.stateLine);
        queryInput = findViewById(R.id.queryInput);
        tapButton = findViewById(R.id.tapButton);
        loading = findViewById(R.id.loading);
        findViewById(R.id.captureButton).setOnClickListener(v -> capture());
        findViewById(R.id.findButton).setOnClickListener(v -> find());
        tapButton.setOnClickListener(v -> tapHit());
        tapButton.setEnabled(false);
        NavHelper.bind(this, R.id.bottomNav, R.id.nav_inspect);
        loadSessions(getIntent().getStringExtra(EXTRA_SESSION_ID));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private String session() {
        Object selected = sessionSpinner.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    private void loadSessions(String preselect) {
        io.execute(() -> {
            try {
                JSONObject result = Rpc.call("status", new JSONObject(), 8000);
                JSONArray array = result.optJSONArray("mcpSessions");
                List<String> ids = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        String id = array.optJSONObject(i).optString("sessionId");
                        if (!id.isEmpty()) {
                            ids.add(id);
                        }
                    }
                }
                runOnUiThread(() -> {
                    sessions.clear();
                    sessions.addAll(ids);
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, ids);
                    adapter.setDropDownViewResource(
                            android.R.layout.simple_spinner_dropdown_item);
                    sessionSpinner.setAdapter(adapter);
                    if (preselect != null && ids.contains(preselect)) {
                        sessionSpinner.setSelection(ids.indexOf(preselect));
                    }
                    stateLine.setText(ids.isEmpty()
                            ? "No sessions — create one on Home first."
                            : ids.size() + " session(s). Capture to inspect.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> stateLine.setText(e.getMessage()));
            }
        });
    }

    private void capture() {
        String id = session();
        if (id.isEmpty()) {
            snack("Select a session first.");
            return;
        }
        setLoading(true);
        overlay.clear();
        tapButton.setEnabled(false);
        lastHit = null;
        io.execute(() -> {
            try {
                JSONObject shotResult = Rpc.call("screenshot",
                        new JSONObject().put("sessionId", id), 30000);
                byte[] png = Base64.decode(shotResult.optString("data"), Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                JSONObject dump = Rpc.call("dumpUi", new JSONObject()
                        .put("sessionId", id).put("mode", "simple"), 20000);
                JSONObject ocr;
                try {
                    ocr = Rpc.call("ocrRecognize",
                            new JSONObject().put("sessionId", id), 45000);
                } catch (Exception e) {
                    ocr = new JSONObject().put("error", e.getMessage());
                }
                JSONObject dumpFinal = dump;
                JSONObject ocrFinal = ocr;
                runOnUiThread(() -> {
                    setLoading(false);
                    renderShot(bitmap);
                    renderHierarchy(dumpFinal);
                    renderOcr(ocrFinal);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    snack(e.getMessage());
                });
            }
        });
    }

    private void renderShot(Bitmap bitmap) {
        if (bitmap == null) {
            snack("Screenshot decode failed — retry capture.");
            return;
        }
        shotWidth = bitmap.getWidth();
        shotHeight = bitmap.getHeight();
        shot.setImageBitmap(bitmap);
    }

    private void renderHierarchy(JSONObject dump) {
        JSONArray targets = dump.optJSONArray("targets");
        JSONArray inputs = dump.optJSONArray("inputs");
        int targetCount = targets == null ? 0 : targets.length();
        int inputCount = inputs == null ? 0 : inputs.length();
        StringBuilder out = new StringBuilder();
        out.append(targetCount).append(" targets · ").append(inputCount).append(" inputs\n");
        appendNodes(out, targets, "targetIndex", 8);
        appendNodes(out, inputs, "inputIndex", 8);
        hierarchyText.setText(out.toString());
    }

    private void appendNodes(StringBuilder out, JSONArray nodes, String indexKey, int max) {
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < Math.min(nodes.length(), max); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            out.append("#").append(node.optInt(indexKey))
                    .append(" ").append(firstLine(node.optString("label")))
                    .append("\n");
        }
        if (nodes.length() > max) {
            out.append("… +").append(nodes.length() - max).append(" more\n");
        }
    }

    private void renderOcr(JSONObject ocr) {
        if (ocr.has("error")) {
            ocrText.setText("OCR: " + ocr.optString("error"));
            return;
        }
        JSONArray results = ocr.optJSONArray("results");
        int count = results == null ? 0 : results.length();
        StringBuilder out = new StringBuilder();
        out.append(count).append(" regions\n");
        List<OverlayView.Box> boxes = new ArrayList<>();
        if (results != null) {
            for (int i = 0; i < Math.min(count, 12); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONObject bounds = item.optJSONObject("bounds");
                if (bounds != null) {
                    boxes.add(new OverlayView.Box(bounds.optInt("left"), bounds.optInt("top"),
                            bounds.optInt("right"), bounds.optInt("bottom"), false));
                }
                out.append(firstLine(item.optString("text")))
                        .append(" (").append(item.optDouble("confidence", 0)).append(")\n");
            }
            if (count > 12) {
                out.append("… +").append(count - 12).append(" more\n");
            }
        }
        ocrText.setText(out.toString());
        overlay.setBoxes(boxes, shotWidth, shotHeight);
    }

    private void find() {
        String id = session();
        String query = queryInput.getText() == null ? ""
                : queryInput.getText().toString().trim();
        if (id.isEmpty() || query.isEmpty()) {
            snack("Select a session and enter search text.");
            return;
        }
        setLoading(true);
        tapButton.setEnabled(false);
        lastHit = null;
        io.execute(() -> {
            try {
                JSONObject hit = Rpc.call("findText", new JSONObject()
                        .put("sessionId", id).put("query", query), 45000);
                runOnUiThread(() -> {
                    setLoading(false);
                    lastHit = hit;
                    JSONObject bounds = hit.optJSONObject("bounds");
                    JSONObject center = hit.optJSONObject("center");
                    findResult.setText("Found via " + hit.optString("source") + ": "
                            + firstLine(hit.optString("text", hit.optString("label")))
                            + " @ " + (center == null ? "?" : center));
                    List<OverlayView.Box> boxes = new ArrayList<>();
                    if (bounds != null) {
                        boxes.add(new OverlayView.Box(bounds.optInt("left"), bounds.optInt("top"),
                                bounds.optInt("right"), bounds.optInt("bottom"), true));
                    }
                    overlay.setBoxes(boxes, shotWidth, shotHeight);
                    tapButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    findResult.setText(e.getMessage());
                });
            }
        });
    }

    private void tapHit() {
        JSONObject hit = lastHit;
        String id = session();
        if (hit == null || id.isEmpty()) {
            return;
        }
        tapButton.setEnabled(false);
        io.execute(() -> {
            try {
                int index = hit.optInt("targetIndex", 0);
                if (index > 0) {
                    Rpc.call("tapTarget", new JSONObject()
                            .put("sessionId", id).put("targetIndex", index), 15000);
                } else {
                    tapCenter(id, hit);
                }
                runOnUiThread(() -> {
                    snack("Tapped — capture again to verify.");
                    capture();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    snack(e.getMessage());
                    tapButton.setEnabled(true);
                });
            }
        });
    }

    private void tapCenter(String id, JSONObject hit) throws Exception {
        JSONObject center = hit.optJSONObject("center");
        if (center == null) {
            throw new IllegalStateException("Hit has no coordinates — capture again.");
        }
        Rpc.call("tap", new JSONObject().put("sessionId", id)
                .put("x", center.optInt("x")).put("y", center.optInt("y")), 15000);
    }

    private String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.length() > 64 ? text.substring(0, 64) + "…" : text;
        return line.replace('\n', ' ');
    }

    private void setLoading(boolean loading) {
        this.loading.setVisibility(loading ? android.view.View.VISIBLE
                : android.view.View.GONE);
    }

    private void snack(String message) {
        Snackbar.make(findViewById(R.id.root), message == null ? "Error" : message,
                Snackbar.LENGTH_LONG).show();
    }
}
