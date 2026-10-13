package com.phantom.automation.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Recovery plans: kill only legacy, restart ours, refuse the rest. */
public final class RecoveryDeciderTest {
    private static StaleDaemon.Verdict verdict(int pid, StaleDaemon.Decision decision) {
        return new StaleDaemon.Verdict(pid, decision, "test");
    }

    @Test
    public void emptyMeansPortFree() {
        RecoveryDecider.Plan plan = RecoveryDecider.plan(new ArrayList<>());
        assertEquals(RecoveryDecider.Action.START, plan.action);
        assertTrue(plan.killPids.isEmpty());
    }

    @Test
    public void legacyOnlyIsKilled() {
        RecoveryDecider.Plan plan = RecoveryDecider.plan(Arrays.asList(
                verdict(4242, StaleDaemon.Decision.LEGACY_SHELL),
                verdict(4243, StaleDaemon.Decision.LEGACY_SHELL)));
        assertEquals(RecoveryDecider.Action.KILL_THEN_START, plan.action);
        assertEquals(Arrays.asList(4242, 4243), plan.killPids);
    }

    @Test
    public void ownServiceIsRestartedNotKilled() {
        RecoveryDecider.Plan plan = RecoveryDecider.plan(Arrays.asList(
                verdict(5000, StaleDaemon.Decision.OURS)));
        assertEquals(RecoveryDecider.Action.RESTART_OURS, plan.action);
        assertTrue(plan.killPids.isEmpty());
    }

    @Test
    public void foreignHolderIsRefusedEvenWithLegacyPresent() {
        RecoveryDecider.Plan plan = RecoveryDecider.plan(Arrays.asList(
                verdict(4242, StaleDaemon.Decision.LEGACY_SHELL),
                verdict(100, StaleDaemon.Decision.REFUSE)));
        assertEquals(RecoveryDecider.Action.REFUSE, plan.action);
        assertTrue(plan.killPids.isEmpty());
        assertTrue(plan.reason.contains("must not kill"));
    }

    @Test
    public void phantomNeverClassifiedAsLegacy() {
        StaleDaemon.Verdict phantom = StaleDaemon.classify(5000, "com.phantom.automation:phantom_priv");
        List<StaleDaemon.Verdict> verdicts = new ArrayList<>();
        verdicts.add(phantom);
        RecoveryDecider.Plan plan = RecoveryDecider.plan(verdicts);
        assertEquals(RecoveryDecider.Action.RESTART_OURS, plan.action);
    }
}
