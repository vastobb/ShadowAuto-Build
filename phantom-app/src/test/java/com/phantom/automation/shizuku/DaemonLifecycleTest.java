package com.phantom.automation.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Lifecycle guarantees: timeouts, duplicates, generations, adoption. */
public final class DaemonLifecycleTest {
    @Test
    public void bindTimeoutBecomesActionableError() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        assertTrue(lifecycle.requestStart(0) > 0);
        assertEquals(DaemonLifecycle.State.BINDING, lifecycle.state());
        lifecycle.poll(DaemonLifecycle.BIND_TIMEOUT_MS - 1);
        assertEquals(DaemonLifecycle.State.BINDING, lifecycle.state());
        lifecycle.poll(DaemonLifecycle.BIND_TIMEOUT_MS + 1);
        assertEquals(DaemonLifecycle.State.ERROR, lifecycle.state());
        assertTrue(lifecycle.detail().contains("Binding timed out"));
    }

    @Test
    public void duplicateStartIsSuppressed() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        assertTrue(lifecycle.requestStart(0) > 0);
        assertEquals(-1, lifecycle.requestStart(100));
        assertEquals(DaemonLifecycle.State.BINDING, lifecycle.state());
    }

    @Test
    public void happyPath() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        long attempt = lifecycle.requestStart(0);
        lifecycle.onBound(100, attempt);
        assertEquals(DaemonLifecycle.State.STARTING, lifecycle.state());
        lifecycle.onStarted(200, "running", attempt);
        assertEquals(DaemonLifecycle.State.RUNNING, lifecycle.state());
        lifecycle.poll(1_000_000);
        assertEquals(DaemonLifecycle.State.RUNNING, lifecycle.state());
    }

    @Test
    public void startTimeoutAfterBound() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        long attempt = lifecycle.requestStart(0);
        lifecycle.onBound(100, attempt);
        lifecycle.poll(100 + DaemonLifecycle.START_TIMEOUT_MS + 1);
        assertEquals(DaemonLifecycle.State.ERROR, lifecycle.state());
        assertTrue(lifecycle.detail().contains("start timed out"));
    }

    @Test
    public void disconnectDuringStartLandsError() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        lifecycle.requestStart(0);
        lifecycle.onBound(10);
        lifecycle.onDisconnected(20);
        assertEquals(DaemonLifecycle.State.ERROR, lifecycle.state());
    }

    @Test
    public void stopIsAlwaysIdle() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        lifecycle.requestStart(0);
        lifecycle.onStopped(50);
        assertEquals(DaemonLifecycle.State.IDLE, lifecycle.state());
        lifecycle.onFailed(60, "boom");
        lifecycle.onStopped(70);
        assertEquals(DaemonLifecycle.State.IDLE, lifecycle.state());
    }

    @Test
    public void recoveryHasOwnTimeoutAndReportsRunning() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        assertTrue(lifecycle.requestRecover(0) > 0);
        assertEquals(-1, lifecycle.requestRecover(10));
        assertEquals(DaemonLifecycle.State.RECOVERING, lifecycle.state());
        lifecycle.onProgress(20, "probing");
        lifecycle.poll(DaemonLifecycle.RECOVER_TIMEOUT_MS + 5);
        assertEquals(DaemonLifecycle.State.ERROR, lifecycle.state());
    }

    @Test
    public void transitionsAreJournaled() {
        DiagLog diag = new DiagLog(16);
        DaemonLifecycle lifecycle = new DaemonLifecycle(diag);
        lifecycle.requestStart(0);
        lifecycle.onBound(10);
        assertTrue(diag.snapshot().size() >= 2);
    }

    @Test
    public void firstStartFailsSecondStartSucceeds() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        long first = lifecycle.requestStart(0);
        lifecycle.poll(DaemonLifecycle.BIND_TIMEOUT_MS + 1);
        assertEquals(DaemonLifecycle.State.ERROR, lifecycle.state());
        long second = lifecycle.requestStart(100);
        assertTrue(second > 0);
        assertFalse(first == second);
        lifecycle.onBound(110, second);
        lifecycle.onStarted(120, "running", second);
        assertEquals(DaemonLifecycle.State.RUNNING, lifecycle.state());
    }

    @Test
    public void lateCallbackFromOldAttemptCannotCorruptNewOne() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        long first = lifecycle.requestStart(0);
        lifecycle.poll(DaemonLifecycle.BIND_TIMEOUT_MS + 1);
        long second = lifecycle.requestStart(100);
        // Late failure from the first attempt must not kill the new one.
        lifecycle.onFailed(110, "old bind blew up", first);
        assertEquals(DaemonLifecycle.State.BINDING, lifecycle.state());
        // Late bind from the first attempt must not advance the new one.
        lifecycle.onBound(120, first);
        assertEquals(DaemonLifecycle.State.BINDING, lifecycle.state());
        lifecycle.onBound(130, second);
        assertEquals(DaemonLifecycle.State.STARTING, lifecycle.state());
    }

    @Test
    public void lateValidCallbackAdoptsRunning() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        lifecycle.requestStart(0);
        lifecycle.poll(DaemonLifecycle.BIND_TIMEOUT_MS + 1);
        assertEquals(DaemonLifecycle.State.ERROR, lifecycle.state());
        // A late callback that re-verified the endpoint reconciles state.
        lifecycle.adoptRunning(200, "reconciled: phantom hello ok");
        assertEquals(DaemonLifecycle.State.RUNNING, lifecycle.state());
    }

    @Test
    public void binderDeathInvalidatesRunning() {
        DaemonLifecycle lifecycle = new DaemonLifecycle(new DiagLog(16));
        lifecycle.requestStart(0);
        lifecycle.onBound(10);
        lifecycle.onStarted(20, "running");
        assertEquals(DaemonLifecycle.State.RUNNING, lifecycle.state());
        lifecycle.onDisconnected(30);
        assertEquals(DaemonLifecycle.State.ERROR, lifecycle.state());
    }
}
