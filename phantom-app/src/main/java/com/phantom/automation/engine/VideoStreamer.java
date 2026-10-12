package com.phantom.automation.engine;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Surface;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;

final class VideoStreamer implements Runnable {
    static final String CODEC_AVC = "avc";
    static final String CODEC_HEVC = "hevc";
    static final String CODEC_AUTO = "auto";

    private static final int DEFAULT_FRAME_RATE = 8;
    private static final int I_FRAME_INTERVAL_SECONDS = 1;
    private static final long REPEAT_PREVIOUS_FRAME_AFTER_US = 150_000L;
    private static final long DRAIN_TIMEOUT_US = 100_000L;

    private final LogHub logs;
    private final String taskId;
    private final String mime;
    private final int bitRate;
    private final int frameRate;
    private final int width;
    private final int height;
    private final MediaCodec encoder;
    private final Surface inputSurface;
    private final Thread thread;

    private volatile boolean running;

    /** Legacy path: AVC with default quality (behavior unchanged). */
    VideoStreamer(LogHub logs, String taskId, int width, int height) throws Exception {
        this(logs, taskId, width, height, CODEC_AVC, 0, 0);
    }

    /**
     * @param codecPref {@code auto} (HEVC when an encoder exists, else AVC),
     *                  {@code avc}, or {@code hevc}. {@code bitRate}/{@code frameRate}
     *                  {@code <= 0} select defaults.
     */
    VideoStreamer(LogHub logs, String taskId, int width, int height,
                  String codecPref, int bitRate, int frameRate) throws Exception {
        this.logs = logs;
        this.taskId = taskId == null ? "" : taskId;
        this.mime = resolveMime(codecPref);
        this.frameRate = frameRate > 0 ? frameRate : DEFAULT_FRAME_RATE;
        Prepared prepared = prepareBest(width, height, this.mime, bitRate, this.frameRate);
        this.width = prepared.width;
        this.height = prepared.height;
        this.bitRate = prepared.bitRate;
        encoder = prepared.encoder;
        inputSurface = prepared.surface;
        thread = new Thread(this, "video-streamer");
        if (this.width != width || this.height != height) {
            logs.info(this.taskId, "video encoder size adjusted: " + width + "x" + height + " -> " + this.width + "x" + this.height);
        }
        logs.info(this.taskId, "video encoder: " + this.mime + " " + this.width + "x" + this.height
                + " bitrate=" + this.bitRate + " fps=" + this.frameRate);
    }

    /** Device encoder capabilities for diagnostics; never throws. */
    static JsonObject capabilities() {
        JsonObject root = new JsonObject();
        root.add("avc", codecInfo(MediaFormat.MIMETYPE_VIDEO_AVC));
        try {
            root.add("hevc", codecInfo(MediaFormat.MIMETYPE_VIDEO_HEVC));
        } catch (Throwable e) {
            JsonObject hevc = new JsonObject();
            hevc.addProperty("available", false);
            hevc.addProperty("error", "hevc mime unsupported: " + e.getMessage());
            root.add("hevc", hevc);
        }
        return root;
    }

    private static JsonObject codecInfo(String mime) {
        JsonObject info = new JsonObject();
        JsonArray encoders = new JsonArray();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (android.media.MediaCodecInfo codec : list.getCodecInfos()) {
                if (!codec.isEncoder()) {
                    continue;
                }
                for (String type : codec.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) {
                        encoders.add(codec.getName());
                    }
                }
            }
        } catch (Throwable e) {
            info.addProperty("error", String.valueOf(e.getMessage()));
        }
        info.addProperty("mime", mime);
        info.addProperty("available", encoders.size() > 0);
        info.add("encoders", encoders);
        return info;
    }

    private static String resolveMime(String codecPref) {
        String pref = codecPref == null ? "" : codecPref.trim().toLowerCase(java.util.Locale.US);
        if (CODEC_HEVC.equals(pref)) {
            if (!isEncoderAvailable(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                throw new IllegalStateException("HEVC encoder unavailable on this device; use avc or auto");
            }
            return MediaFormat.MIMETYPE_VIDEO_HEVC;
        }
        if (CODEC_AVC.equals(pref)) {
            return MediaFormat.MIMETYPE_VIDEO_AVC;
        }
        if (isEncoderAvailable(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            return MediaFormat.MIMETYPE_VIDEO_HEVC;
        }
        return MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    private static boolean isEncoderAvailable(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (android.media.MediaCodecInfo codec : list.getCodecInfos()) {
                if (!codec.isEncoder()) {
                    continue;
                }
                for (String type : codec.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private Prepared prepareBest(int preferredWidth, int preferredHeight,
                                 String mime, int bitRate, int frameRate) throws Exception {
        Exception last = null;
        int[][] candidates = candidates(preferredWidth, preferredHeight);
        for (int[] candidate : candidates) {
            try {
                return prepareOne(candidate[0], candidate[1], mime, bitRate, frameRate);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("No video encoder size available") : last;
    }

    private Prepared prepareOne(int width, int height, String mime, int bitRate, int frameRate) throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(mime);
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        int actualBitRate = bitRate > 0 ? bitRate : bitRateFor(width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, actualBitRate);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_PREVIOUS_FRAME_AFTER_US);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return new Prepared(width, height, actualBitRate, codec, codec.createInputSurface());
        } catch (Exception e) {
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    String mime() {
        return mime;
    }

    Surface inputSurface() {
        return inputSurface;
    }

    void start() {
        running = true;
        encoder.start();
        requestKeyFrame();
        thread.start();
    }

    void stop() {
        running = false;
        try {
            encoder.signalEndOfInputStream();
        } catch (Throwable ignored) {
        }
        try {
            thread.join(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        release();
    }

    @Override
    public void run() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int index = encoder.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    logs.videoConfig(taskId, encoder.getOutputFormat());
                } else if (index >= 0) {
                    drainOutput(index, info);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            logs.error(taskId, "video stream failed", e);
        }
    }

    private void drainOutput(int index, MediaCodec.BufferInfo info) {
        ByteBuffer data = encoder.getOutputBuffer(index);
        try {
            if (data == null || info.size <= 0) {
                return;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return;
            }
            byte[] bytes = new byte[info.size];
            data.position(info.offset);
            data.limit(info.offset + info.size);
            data.get(bytes);
            String sample = Base64.encodeToString(bytes, Base64.NO_WRAP);
            logs.videoSample(taskId, info.presentationTimeUs, info.flags, sample);
        } finally {
            encoder.releaseOutputBuffer(index, false);
        }
    }

    private void requestKeyFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
        } catch (Throwable ignored) {
        }
    }

    private void release() {
        try {
            encoder.stop();
        } catch (Throwable ignored) {
        }
        try {
            encoder.release();
        } catch (Throwable ignored) {
        }
        try {
            inputSurface.release();
        } catch (Throwable ignored) {
        }
    }

    private static int bitRateFor(int width, int height) {
        long pixels = (long) width * height;
        long target = pixels * DEFAULT_FRAME_RATE / 4;
        return (int) Math.max(900_000L, Math.min(5_000_000L, target));
    }

    private static int even(int value) {
        if (value <= 0) {
            return 2;
        }
        if ((value & 1) == 0) {
            return value;
        }
        return value - 1;
    }

    private static int alignDown(int value, int align) {
        int aligned = value / align * align;
        if (aligned < align) {
            return align;
        }
        return aligned;
    }

    private static int[][] candidates(int width, int height) {
        int exactWidth = even(width);
        int exactHeight = even(height);
        int alignedWidth = alignDown(exactWidth, 16);
        int alignedHeight = alignDown(exactHeight, 16);
        int[] scaled = scaleMaxSide(exactWidth, exactHeight, 1920);
        return new int[][]{
                {exactWidth, exactHeight},
                {alignedWidth, alignedHeight},
                scaled
        };
    }

    private static int[] scaleMaxSide(int width, int height, int maxSide) {
        int max = Math.max(width, height);
        if (max <= maxSide) {
            return new int[]{width, height};
        }
        float ratio = (float) maxSide / max;
        return new int[]{alignDown(Math.round(width * ratio), 16), alignDown(Math.round(height * ratio), 16)};
    }

    private static final class Prepared {
        final int width;
        final int height;
        final int bitRate;
        final MediaCodec encoder;
        final Surface surface;

        Prepared(int width, int height, int bitRate, MediaCodec encoder, Surface surface) {
            this.width = width;
            this.height = height;
            this.bitRate = bitRate;
            this.encoder = encoder;
            this.surface = surface;
        }
    }
}
