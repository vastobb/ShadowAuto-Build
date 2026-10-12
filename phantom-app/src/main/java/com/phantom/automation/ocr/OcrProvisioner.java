package com.phantom.automation.ocr;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Downloads the PaddleOCR runtime (native libs, OpenCV is build-time only,
 * PP-OCRv2 models, labels) into app-private storage on demand, so the APK
 * stays small and OCR works without a PC. Same upstream archives the legacy
 * Gradle provisioning used.
 */
public final class OcrProvisioner {
    public interface Progress {
        void onProgress(String file, long done, long total);

        void onDone(File root);

        void onError(String message);
    }

    private static final String[][] ARCHIVES = {
            {"https://paddleocr.bj.bcebos.com/libs/paddle_lite_libs_v2_10.tar.gz", "PaddleLite"},
            {"https://paddleocr.bj.bcebos.com/PP-OCRv2/lite/ch_PP-OCRv2.tar.gz", "models"},
            {"https://paddleocr.bj.bcebos.com/dygraph_v2.0/lite/ch_dict.tar.gz", "labels"},
    };

    public static final String MODELS_SUBDIR = "models/ch_PP-OCRv2";
    public static final String LABELS_FILE = "labels/ppocr_keys_v1.txt";
    public static final String LIB_SUBDIR = "lib/arm64-v8a";

    private OcrProvisioner() {
    }

    public static File root(File filesDir) {
        return new File(filesDir, "ocr");
    }

    public static File modelDir(File filesDir) {
        return new File(root(filesDir), MODELS_SUBDIR);
    }

    public static File labelFile(File filesDir) {
        return new File(root(filesDir), LABELS_FILE);
    }

    public static File libDir(File filesDir) {
        return new File(root(filesDir), LIB_SUBDIR);
    }

    /** True when models, labels, and native libs are all present. */
    public static boolean isProvisioned(File filesDir) {
        if (!new File(modelDir(filesDir), "det_db.nb").isFile()
                || !new File(modelDir(filesDir), "rec_crnn.nb").isFile()
                || !new File(modelDir(filesDir), "cls.nb").isFile()
                || !labelFile(filesDir).isFile()) {
            return false;
        }
        File libs = libDir(filesDir);
        return new File(libs, "libc++_shared.so").isFile()
                && new File(libs, "libomp.so").isFile()
                && new File(libs, "libNative.so").isFile();
    }

    /**
     * Downloads + extracts every archive on the calling thread. Copies the
     * already-built {@code libNative.so} pair out of the extracted PaddleLite
     * tree is NOT possible (native libs ship inside the APK); this method
     * handles models + labels, while native libs come from the APK's own
     * {@code lib/arm64-v8a} copied to {@link #libDir(File)} below.
     */
    public static void provisionModels(File filesDir, File cacheDir, Progress progress) {
        try {
            File root = root(filesDir);
            for (String[] archive : ARCHIVES) {
                if (archive[1].equals("PaddleLite")) {
                    continue;
                }
                File cached = new File(cacheDir, "ocr-" + archive[1] + ".tar.gz");
                download(archive[0], cached, archive[1], progress);
                extractTarGz(cached, root);
            }
            progress.onDone(root);
        } catch (Throwable e) {
            progress.onError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Copies the APK-bundled native libs to the OCR lib dir.
     * Reads {@code lib/arm64-v8a/*.so} out of our own APK
     * ({@code context.getApplicationInfo().sourceDir}), so OCR works with no
     * PC and no /data/local/tmp copy. Requires a full OCR build (the fast
     * {@code -PenablePaddleLiteOcr=false} build ships no native libs and
     * reports that clearly via {@code ocrStatus}). */
    public static void provisionNativeLibs(File filesDir, String apkPath) throws Exception {
        String[] names = {"libc++_shared.so", "libomp.so", "libNative.so"};
        File libs = libDir(filesDir);
        if (!libs.exists() && !libs.mkdirs()) {
            throw new IllegalStateException("cannot create " + libs);
        }
        try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(apkPath)) {
            for (String name : names) {
                File out = new File(libs, name);
                if (out.isFile()) {
                    continue;
                }
                java.util.zip.ZipEntry entry = apk.getEntry("lib/arm64-v8a/" + name);
                if (entry == null) {
                    throw new IllegalStateException("APK has no lib/arm64-v8a/" + name
                            + " (rebuild with OCR enabled: omit -PenablePaddleLiteOcr=false)");
                }
                try (InputStream in = apk.getInputStream(entry);
                     OutputStream outStream = new FileOutputStream(out)) {
                    copy(in, outStream);
                }
            }
        }
    }

    private static void download(String url, File dest, String label, Progress progress)
            throws Exception {
        if (dest.isFile() && dest.length() > 0) {
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(0);
        connection.connect();
        if (connection.getResponseCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + connection.getResponseCode() + " for " + url);
        }
        long total = connection.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536];
            long done = 0;
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
                done += length;
                progress.onProgress(label, done, total);
            }
        }
    }

    /** Minimal ustar extractor (files + directories, regular files only). */
    static void extractTarGz(File archive, File dest) throws Exception {
        try (InputStream fileIn = new java.io.FileInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(fileIn))) {
            byte[] header = new byte[512];
            while (true) {
                if (!readFully(gzip, header)) {
                    break;
                }
                if (isZeroBlock(header)) {
                    break;
                }
                String name = readString(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int type = header[156];
                File out = new File(dest, name);
                if (type == '5' || name.endsWith("/")) {
                    out.mkdirs();
                } else if (type == 0 || type == '0') {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream output = new FileOutputStream(out)) {
                        copyBounded(gzip, output, size);
                    }
                } else {
                    skipFully(gzip, size);
                }
                long skip = (512 - (size % 512)) % 512;
                skipFully(gzip, skip);
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws Exception {
        int offset = 0;
        while (offset < buffer.length) {
            int length = in.read(buffer, offset, buffer.length - offset);
            if (length == -1) {
                return offset > 0;
            }
            offset += length;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        String text = readString(header, offset, length).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[65536];
        int length;
        while ((length = in.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
    }

    private static void copyBounded(InputStream in, OutputStream out, long size) throws Exception {
        byte[] buffer = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            int length = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (length == -1) {
                throw new IllegalStateException("truncated tar entry");
            }
            out.write(buffer, 0, length);
            remaining -= length;
        }
    }

    private static void skipFully(InputStream in, long size) throws Exception {
        long remaining = size;
        byte[] buffer = new byte[65536];
        while (remaining > 0) {
            int length = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (length == -1) {
                return;
            }
            remaining -= length;
        }
    }

    /** Test helper: build a tiny tar.gz in memory. */
    static byte[] makeTestArchive() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeEntry(raw, "models/a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        byte[] zeros = new byte[1024];
        raw.write(zeros);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(gz)) {
            gzip.write(raw.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void writeEntry(ByteArrayOutputStream out, String name, byte[] data)
            throws Exception {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        String sizeOctal = String.format("%011o", data.length);
        System.arraycopy(sizeOctal.getBytes(StandardCharsets.US_ASCII), 0, header, 124, 11);
        header[156] = '0';
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 5);
        int checksum = 0;
        for (int i = 0; i < 512; i++) {
            checksum += (i >= 148 && i < 156) ? 32 : (header[i] & 0xFF);
        }
        String checksumOctal = String.format("%06o", checksum);
        System.arraycopy(checksumOctal.getBytes(StandardCharsets.US_ASCII), 0, header, 148, 6);
        out.write(header);
        out.write(data);
        int pad = (512 - (data.length % 512)) % 512;
        out.write(new byte[pad]);
    }
}
