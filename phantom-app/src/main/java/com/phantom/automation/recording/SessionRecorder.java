package com.phantom.automation.recording;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Records one session's encoded stream to MP4. Fed with the same
 * {@code video.config}/{@code video.sample} events that drive the live
 * preview, so recording reuses the single capture engine and always captures
 * the virtual display (never the physical screen or launcher UI).
 *
 * <p>Lifecycle independent of any Activity: owned by the foreground service.
 * Pause drops samples and offsets presentation timestamps on resume, so the
 * output stays continuous and finalized.
 */
public final class SessionRecorder {
    public enum State {
        IDLE,
        RECORDING,
        PAUSED,
        STOPPED,
        ERROR,
    }

    private final Object lock = new Object();
    private MediaMuxer muxer;
    private int track = -1;
    private File output;
    private State state = State.IDLE;
    private String error = "";
    private boolean sawConfig;
    private long pausedTotalUs;
    private long pauseStartedAtUs = -1;
    private long firstPtsUs = -1;

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public String error() {
        synchronized (lock) {
            return error;
        }
    }

    public File output() {
        synchronized (lock) {
            return output;
        }
    }

    /**
     * Configure the track from a {@code video.config} event. Must precede
     * {@link #writeSample}. Neither csd may be empty.
     */
    public void configure(File file, String mime, int width, int height,
                          byte[] csd0, byte[] csd1) {
        synchronized (lock) {
            if (state == State.RECORDING || state == State.PAUSED) {
                throw new IllegalStateException("recorder busy: stop before reconfiguring");
            }
            if (file == null) {
                throw new IllegalArgumentException("output file is required");
            }
            if (mime == null || mime.isEmpty()) {
                throw new IllegalArgumentException("mime is required");
            }
            if (csd0 == null || csd0.length == 0 || csd1 == null || csd1.length == 0) {
                throw new IllegalStateException("codec config missing (csd-0/csd-1); "
                        + "wait for the next video.config event");
            }
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("cannot create " + parent);
                }
                MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.clone()));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.clone()));
                muxer = new MediaMuxer(file.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                track = muxer.addTrack(format);
                muxer.start();
                output = file;
                sawConfig = true;
                pausedTotalUs = 0;
                pauseStartedAtUs = -1;
                firstPtsUs = -1;
                state = State.RECORDING;
                error = "";
            } catch (Throwable e) {
                failLocked("configure failed: " + message(e));
                throw new IllegalStateException(error, e);
            }
        }
    }

    /** Write one {@code video.sample} payload (pass through while paused = dropped). */
    public void writeSample(byte[] data, long ptsUs, int flags) {
        synchronized (lock) {
            if (state == State.PAUSED) {
                return;
            }
            if (state != State.RECORDING || muxer == null || track < 0) {
                return;
            }
            if (data == null || data.length == 0) {
                return;
            }
            try {
                if (firstPtsUs < 0) {
                    firstPtsUs = ptsUs;
                }
                lastSeenPts = ptsUs;
                long adjusted = ptsUs - firstPtsUs - pausedTotalUs;
                if (adjusted < 0) {
                    adjusted = 0;
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(0, data.length, adjusted,
                        (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
                muxer.writeSampleData(track, ByteBuffer.wrap(data), info);
            } catch (Throwable e) {
                failLocked("write failed: " + message(e));
            }
        }
    }

    public void pause() {
        synchronized (lock) {
            if (state != State.RECORDING) {
                return;
            }
            pauseStartedAtUs = lastSeenPtsLocked();
            state = State.PAUSED;
        }
    }

    public void resume(long nowPtsUs) {
        synchronized (lock) {
            if (state != State.PAUSED) {
                return;
            }
            if (pauseStartedAtUs >= 0 && nowPtsUs > pauseStartedAtUs) {
                pausedTotalUs += nowPtsUs - pauseStartedAtUs;
            }
            pauseStartedAtUs = -1;
            state = State.RECORDING;
        }
    }

    /** Finalize the file. Returns the output, or null when nothing was recorded. */
    public File stop() {
        synchronized (lock) {
            File result = (sawConfig && state != State.ERROR) ? output : null;
            try {
                if (muxer != null && (state == State.RECORDING || state == State.PAUSED)) {
                    muxer.stop();
                }
            } catch (Throwable e) {
                if (state != State.ERROR) {
                    failLocked("finalize failed: " + message(e));
                }
                result = null;
            } finally {
                try {
                    if (muxer != null) {
                        muxer.release();
                    }
                } catch (Throwable ignored) {
                }
                muxer = null;
                track = -1;
                if (state != State.ERROR) {
                    state = State.STOPPED;
                }
            }
            return result;
        }
    }

    public void reset() {
        synchronized (lock) {
            if (state == State.RECORDING || state == State.PAUSED) {
                throw new IllegalStateException("stop recording before reset");
            }
            output = null;
            sawConfig = false;
            error = "";
            state = State.IDLE;
        }
    }

    private long lastSeenPts = -1;
    private long lastSeenPtsLocked() {
        return lastSeenPts < 0 ? 0 : lastSeenPts;
    }

    private void failLocked(String message) {
        state = State.ERROR;
        error = message;
        try {
            if (muxer != null) {
                muxer.release();
            }
        } catch (Throwable ignored) {
        }
        muxer = null;
        track = -1;
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
