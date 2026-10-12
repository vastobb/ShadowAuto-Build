package com.phantom.automation.ocr;

import android.graphics.Bitmap;

import com.silentauto.paddlerocr.PaddleOcrEngine;
import com.silentauto.paddlerocr.PaddleOcrResult;

import java.io.File;
import java.util.List;

/**
 * Managed PaddleOCR lifecycle with explicit status. The legacy failure mode
 * (a bare {@code IllegalStateException("Paddle OCR is not initialized")} at
 * recognize time) is replaced by pre-flight checks: {@link #status()} names
 * exactly which models, labels, or native libraries are missing, and
 * {@link #ensureInitialized()} performs init once and records the outcome.
 */
public final class OcrManager {
    public enum State {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        FAILED,
    }

    public static final class Status {
        public final State state;
        public final String detail;
        public final String modelDir;
        public final String labelPath;
        public final String libDir;

        Status(State state, String detail, String modelDir, String labelPath, String libDir) {
            this.state = state;
            this.detail = detail;
            this.modelDir = modelDir;
            this.labelPath = labelPath;
            this.libDir = libDir;
        }
    }

    private static final String[] REQUIRED_MODELS = {"det_db.nb", "rec_crnn.nb", "cls.nb"};
    private static final String[] REQUIRED_LIBS = {"libc++_shared.so", "libomp.so", "libNative.so"};

    private final Object lock = new Object();
    private PaddleOcrEngine engine;
    private State state = State.UNINITIALIZED;
    private String detail = "not configured (call ocrConfigure with model/label/lib paths)";
    private String modelDir = "";
    private String labelPath = "";
    private String libDir = "";

    public void configure(String modelDir, String labelPath, String libDir) {
        synchronized (lock) {
            boolean changed = !safe(modelDir).equals(this.modelDir)
                    || !safe(labelPath).equals(this.labelPath)
                    || !safe(libDir).equals(this.libDir);
            this.modelDir = safe(modelDir);
            this.labelPath = safe(labelPath);
            this.libDir = safe(libDir);
            if (changed) {
                closeLocked();
                state = State.UNINITIALIZED;
                detail = "paths updated, not initialized";
            }
        }
    }

    public Status status() {
        synchronized (lock) {
            if (state == State.UNINITIALIZED && !modelDir.isEmpty()) {
                String missing = missingAssets();
                detail = missing.isEmpty() ? "configured, not initialized" : missing;
            }
            return new Status(state, detail, modelDir, labelPath, libDir);
        }
    }

    /** Idempotent init. Never throws; the outcome lands in {@link #status()}. */
    public Status ensureInitialized() {
        synchronized (lock) {
            if (state == State.READY) {
                return statusLocked();
            }
            if (modelDir.isEmpty()) {
                state = State.FAILED;
                detail = "no OCR paths configured (call ocrConfigure first)";
                return statusLocked();
            }
            String missing = missingAssets();
            if (!missing.isEmpty()) {
                state = State.FAILED;
                detail = missing;
                return statusLocked();
            }
            state = State.INITIALIZING;
            detail = "loading native OCR engine";
            try {
                PaddleOcrEngine next = new PaddleOcrEngine();
                PaddleOcrEngine.Config config = new PaddleOcrEngine.Config();
                config.modelDir = modelDir;
                config.labelPath = labelPath;
                config.libDir = libDir;
                next.init(config);
                closeLocked();
                engine = next;
                state = State.READY;
                detail = "initialized from " + modelDir;
            } catch (Throwable e) {
                closeLocked();
                state = State.FAILED;
                detail = "native init failed: " + message(e)
                        + " (arm64-v8a required; check logcat PhantomShell for dlopen errors)";
            }
            return statusLocked();
        }
    }

    public List<PaddleOcrResult> recognize(Bitmap bitmap) {
        synchronized (lock) {
            Status current = ensureInitializedLocked();
            if (current.state != State.READY || engine == null) {
                throw new IllegalStateException("OCR unavailable: " + current.detail);
            }
            if (bitmap == null || bitmap.isRecycled()) {
                throw new IllegalArgumentException("screenshot bitmap is empty; retry capture");
            }
            try {
                return engine.recognize(bitmap);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException("OCR inference failed: " + message(e), e);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closeLocked();
            state = State.UNINITIALIZED;
            detail = "closed";
        }
    }

    private Status ensureInitializedLocked() {
        if (state == State.READY) {
            return statusLocked();
        }
        // Reuse the public path (re-entrant safe: synchronized is reentrant).
        return ensureInitialized();
    }

    private void closeLocked() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable ignored) {
            }
            engine = null;
        }
    }

    private Status statusLocked() {
        return new Status(state, detail, modelDir, labelPath, libDir);
    }

    /** Names every missing asset, or "" when provisioning is complete. */
    private String missingAssets() {
        StringBuilder missing = new StringBuilder();
        File models = new File(modelDir);
        if (!models.isDirectory()) {
            missing.append("model dir missing: ").append(modelDir).append("; ");
        } else {
            for (String name : REQUIRED_MODELS) {
                if (!new File(models, name).isFile()) {
                    missing.append("model missing: ").append(name).append("; ");
                }
            }
        }
        if (!new File(labelPath).isFile()) {
            missing.append("label file missing: ").append(labelPath).append("; ");
        }
        File libs = new File(libDir);
        if (!libs.isDirectory()) {
            missing.append("native lib dir missing: ").append(libDir).append("; ");
        } else {
            for (String name : REQUIRED_LIBS) {
                if (!new File(libs, name).isFile()) {
                    missing.append("native lib missing: ").append(name).append("; ");
                }
            }
        }
        if (missing.length() > 0) {
            missing.append("provision via Phantom Settings > OCR models.");
        }
        return missing.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
