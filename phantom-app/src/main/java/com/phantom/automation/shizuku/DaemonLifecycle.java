package com.phantom.automation.shizuku;

/**
 * Pure-JVM lifecycle for the privileged daemon connection. All async steps
 * carry deadlines, so the UI can never stick forever: {@link #poll(long)}
 * converts an expired step into an ERROR with an actionable message.
 * Duplicate starts are suppressed; every transition is journaled to
 * {@link DiagLog} for on-device diagnosis.
 *
 * <p>States: IDLE → BINDING → STARTING → RUNNING, plus RECOVERING (entered
 * from IDLE/ERROR via the recovery flow) and ERROR. STOP always lands IDLE.
 */
public final class DaemonLifecycle {
    public enum State {
        IDLE,
        BINDING,
        STARTING,
        RUNNING,
        RECOVERING,
        ERROR,
    }

    public static final long BIND_TIMEOUT_MS = 20_000;
    public static final long START_TIMEOUT_MS = 30_000;
    public static final long RECOVER_TIMEOUT_MS = 90_000;

    private final DiagLog diag;
    private State state = State.IDLE;
    private String detail = "Idle.";
    private long deadlineMs;
    private long nowMs;
    /** Attempt counter: every request bumps it; callbacks carry the attempt
     * they belong to so stale async work cannot corrupt a newer attempt. */
    private long generation;

    public DaemonLifecycle(DiagLog diag) {
        this.diag = diag;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String detail() {
        return detail;
    }

    public synchronized long generation() {
        return generation;
    }

    /** Returns the new attempt id, or -1 when the request was suppressed. */
    public synchronized long requestStart(long nowMs) {
        this.nowMs = nowMs;
        if (state == State.BINDING || state == State.STARTING || state == State.RECOVERING) {
            diag.add("start suppressed (already " + state + ", gen " + generation + ")");
            return -1;
        }
        generation++;
        transition(State.BINDING, "Binding privileged service…", nowMs + BIND_TIMEOUT_MS);
        return generation;
    }

    /** Returns the new attempt id. Restart always proceeds. */
    public synchronized long requestRestart(long nowMs) {
        this.nowMs = nowMs;
        generation++;
        transition(State.BINDING, "Restarting privileged service…", nowMs + BIND_TIMEOUT_MS);
        return generation;
    }

    /** Returns the new attempt id, or -1 when already recovering. */
    public synchronized long requestRecover(long nowMs) {
        this.nowMs = nowMs;
        if (state == State.RECOVERING) {
            diag.add("recover suppressed (already RECOVERING, gen " + generation + ")");
            return -1;
        }
        generation++;
        transition(State.RECOVERING, "Recovering stale service…", nowMs + RECOVER_TIMEOUT_MS);
        return generation;
    }

    /** False when the callback belongs to an older attempt: ignore it. */
    public synchronized boolean checkAttempt(long attempt) {
        if (attempt != generation) {
            diag.add("stale callback ignored (attempt " + attempt
                    + ", current gen " + generation + ")");
            return false;
        }
        return true;
    }

    public synchronized void onBound(long nowMs) {
        onBound(nowMs, generation);
    }

    public synchronized void onBound(long nowMs, long attempt) {
        this.nowMs = nowMs;
        if (!checkAttempt(attempt)) {
            return;
        }
        if (state != State.BINDING && state != State.RECOVERING) {
            diag.add("late bind ignored in " + state);
            return;
        }
        if (state == State.RECOVERING) {
            diag.add("bound for recovery; recovery flow owns the next step");
            return;
        }
        transition(State.STARTING, "Starting daemon…", nowMs + START_TIMEOUT_MS);
    }

    public synchronized void onStarted(long nowMs, String summary) {
        onStarted(nowMs, summary, generation);
    }

    public synchronized void onStarted(long nowMs, String summary, long attempt) {
        this.nowMs = nowMs;
        if (!checkAttempt(attempt)) {
            return;
        }
        if (state == State.STARTING || state == State.RECOVERING || state == State.BINDING) {
            transition(State.RUNNING, summary, 0);
        } else {
            diag.add("late start ignored in " + state);
        }
    }

    /**
     * Adopt a freshly verified daemon as RUNNING even from ERROR/IDLE.
     * Used when a late callback proves the endpoint healthy (reconcile
     * instead of leaving a running daemon reported as dead).
     */
    public synchronized void adoptRunning(long nowMs, String summary) {
        this.nowMs = nowMs;
        if (state == State.RUNNING) {
            return;
        }
        transition(State.RUNNING, summary, 0);
    }

    public synchronized void onFailed(long nowMs, String message) {
        onFailed(nowMs, message, generation);
    }

    public synchronized void onFailed(long nowMs, String message, long attempt) {
        this.nowMs = nowMs;
        if (!checkAttempt(attempt)) {
            return;
        }
        transition(State.ERROR, message, 0);
    }

    public synchronized void onDisconnected(long nowMs) {
        this.nowMs = nowMs;
        if (state == State.IDLE) {
            return;
        }
        transition(State.ERROR, "Privileged service disconnected (Shizuku restarted?). "
                + "Tap Start to reconnect.", 0);
    }

    public synchronized void onStopped(long nowMs) {
        this.nowMs = nowMs;
        transition(State.IDLE, "Privileged service stopped.", 0);
    }

    public synchronized void onProgress(long nowMs, String message) {
        this.nowMs = nowMs;
        if (state == State.STARTING || state == State.RECOVERING) {
            detail = message;
            diag.add(state + ": " + message);
        }
    }

    /** Converts expired steps to ERROR. Call from a watchdog timer. */
    public synchronized void poll(long nowMs) {
        this.nowMs = nowMs;
        if (deadlineMs <= 0 || nowMs < deadlineMs) {
            return;
        }
        switch (state) {
            case BINDING:
                transition(State.ERROR, "Binding timed out after "
                        + (BIND_TIMEOUT_MS / 1000) + "s with no Shizuku callback. "
                        + "The Shizuku service may have died or the UserService crashed "
                        + "on launch — open the Shizuku app to verify it is running, "
                        + "then tap Start again.", 0);
                break;
            case STARTING:
                transition(State.ERROR, "Daemon start timed out after "
                        + (START_TIMEOUT_MS / 1000) + "s. The privileged process may "
                        + "have died during engine init — check Shizuku status, "
                        + "then use Recover.", 0);
                break;
            case RECOVERING:
                transition(State.ERROR, "Recovery timed out after "
                        + (RECOVER_TIMEOUT_MS / 1000) + "s. Retry Recover, or restart "
                        + "Shizuku and the device if it persists.", 0);
                break;
            default:
                deadlineMs = 0;
                break;
        }
    }

    private void transition(State next, String message, long deadline) {
        state = next;
        detail = message;
        deadlineMs = deadline;
        diag.add(next + ": " + message);
    }
}
