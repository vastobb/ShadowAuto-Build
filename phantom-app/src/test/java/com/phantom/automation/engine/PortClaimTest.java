package com.phantom.automation.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Port-claim matrix: handshake decides, never the port number alone. */
public final class PortClaimTest {
    @Test
    public void freePortMeansServe() {
        assertEquals(PortClaim.Outcome.SERVE,
                PortClaim.onProbeBeforeBind(PortClaim.Probe.refused()));
    }

    @Test
    public void healthyPhantomIsAdopted() {
        PortClaim.Probe probe = new PortClaim.Probe(true, true, false, 2, 4242);
        assertEquals(PortClaim.Outcome.ADOPT,
                PortClaim.onProbeBeforeBind(probe));
        assertEquals(PortClaim.Outcome.ADOPT,
                PortClaim.onBindConflict(probe));
    }

    @Test
    public void verifiedLegacyIsReported() {
        PortClaim.Probe probe = new PortClaim.Probe(true, false, true, 0, -1);
        assertEquals(PortClaim.Outcome.LEGACY_HOLDER,
                PortClaim.onProbeBeforeBind(probe));
        assertEquals(PortClaim.Outcome.LEGACY_HOLDER,
                PortClaim.onBindConflict(probe));
    }

    @Test
    public void foreignIsRefused() {
        PortClaim.Probe probe = new PortClaim.Probe(true, false, false, 0, -1);
        assertEquals(PortClaim.Outcome.FOREIGN,
                PortClaim.onProbeBeforeBind(probe));
        assertEquals(PortClaim.Outcome.FOREIGN,
                PortClaim.onBindConflict(probe));
    }

    @Test
    public void vanishedHolderRetries() {
        assertEquals(PortClaim.Outcome.RETRY,
                PortClaim.onBindConflict(PortClaim.Probe.refused()));
    }
}
