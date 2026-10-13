package com.phantom.automation.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Narrow pidfile cleanup + alternate-port fallback decisions. */
public final class RecoveryPathsTest {
    private static final String LEGACY_CMD =
            "app_process /system/bin com.silentauto.shell.Main --port=43110";

    @Test
    public void verifiedLegacyPidIsKilled() {
        PidfileCleanup.Verdict verdict =
                PidfileCleanup.decide("4242\n", LEGACY_CMD);
        assertEquals(PidfileCleanup.Decision.KILL, verdict.decision);
        assertEquals(4242, verdict.pid);
    }

    @Test
    public void missingPidfileRefuses() {
        assertEquals(PidfileCleanup.Decision.REFUSE,
                PidfileCleanup.decide("", LEGACY_CMD).decision);
        assertEquals(PidfileCleanup.Decision.REFUSE,
                PidfileCleanup.decide(null, LEGACY_CMD).decision);
        assertEquals(PidfileCleanup.Decision.REFUSE,
                PidfileCleanup.decide("not-a-pid\n", LEGACY_CMD).decision);
    }

    @Test
    public void recycledPidWithOtherCmdlineRefuses() {
        PidfileCleanup.Verdict verdict = PidfileCleanup.decide(
                "4242\n", "com.phantom.automation:phantom_priv");
        assertEquals(PidfileCleanup.Decision.REFUSE, verdict.decision);
        assertTrue(verdict.reason.contains("does not identify"));
    }

    @Test
    public void unreadableCmdlineRefuses() {
        PidfileCleanup.Verdict verdict =
                PidfileCleanup.decide("4242\n", "");
        assertEquals(PidfileCleanup.Decision.REFUSE, verdict.decision);
    }

    @Test
    public void systemPidNeverKilled() {
        assertEquals(PidfileCleanup.Decision.REFUSE,
                PidfileCleanup.decide("1\n", LEGACY_CMD).decision);
    }

    @Test
    public void canonicalHealthyOrFreeStays() {
        assertEquals(FallbackDecider.Outcome.USE_CANONICAL, FallbackDecider.decide(
                FallbackDecider.Holder.PHANTOM, FallbackDecider.Holder.FREE));
        assertEquals(FallbackDecider.Outcome.USE_CANONICAL, FallbackDecider.decide(
                FallbackDecider.Holder.FREE, FallbackDecider.Holder.FREE));
    }

    @Test
    public void blockedCanonicalFallsBackToFreeAlternate() {
        assertEquals(FallbackDecider.Outcome.USE_ALTERNATE, FallbackDecider.decide(
                FallbackDecider.Holder.LEGACY, FallbackDecider.Holder.FREE));
        assertEquals(FallbackDecider.Outcome.USE_ALTERNATE, FallbackDecider.decide(
                FallbackDecider.Holder.FOREIGN, FallbackDecider.Holder.FREE));
    }

    @Test
    public void phantomAlternateIsAdopted() {
        assertEquals(FallbackDecider.Outcome.ADOPT_ALTERNATE, FallbackDecider.decide(
                FallbackDecider.Holder.LEGACY, FallbackDecider.Holder.PHANTOM));
    }

    @Test
    public void bothBlockedRefuses() {
        assertEquals(FallbackDecider.Outcome.REFUSE, FallbackDecider.decide(
                FallbackDecider.Holder.LEGACY, FallbackDecider.Holder.FOREIGN));
        assertEquals(FallbackDecider.Outcome.REFUSE, FallbackDecider.decide(
                FallbackDecider.Holder.FOREIGN, FallbackDecider.Holder.LEGACY));
    }
}
