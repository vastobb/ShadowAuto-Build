package com.phantom.automation.engine;

/**
 * Pure-JVM claim logic for the loopback RPC port. The handshake decides,
 * never the port number alone: a healthy Phantom endpoint is adopted, a
 * verified legacy holder is reported for targeted cleanup, and only a
 * successful bind means "we serve".
 */
public final class PortClaim {
    public enum Outcome {
        /** Port is free (or holder vanished): bind and serve. */
        SERVE,
        /** A healthy Phantom daemon already serves: adopt it, bind nothing. */
        ADOPT,
        /** Verified legacy holder: needs targeted cleanup, never blind kill. */
        LEGACY_HOLDER,
        /** Foreign responder: refuse, explain, do nothing. */
        FOREIGN,
        /** Holder vanished mid-flight: retry the whole claim once. */
        RETRY,
    }

    /** One hello-probe classification (no sockets here; engine fills it). */
    public static final class Probe {
        public final boolean connected;
        public final boolean phantom;
        public final boolean legacy;
        public final int version;
        public final int pid;

        public Probe(boolean connected, boolean phantom, boolean legacy,
                     int version, int pid) {
            this.connected = connected;
            this.phantom = phantom;
            this.legacy = legacy;
            this.version = version;
            this.pid = pid;
        }

        public static Probe refused() {
            return new Probe(false, false, false, 0, -1);
        }
    }

    private PortClaim() {
    }

    /** Before binding: what does the current port state call for? */
    public static Outcome onProbeBeforeBind(Probe probe) {
        if (!probe.connected) {
            return Outcome.SERVE;
        }
        if (probe.phantom) {
            return Outcome.ADOPT;
        }
        if (probe.legacy) {
            return Outcome.LEGACY_HOLDER;
        }
        return Outcome.FOREIGN;
    }

    /** Our bind failed (EADDRINUSE): re-probe and decide, never assume. */
    public static Outcome onBindConflict(Probe probe) {
        if (!probe.connected) {
            return Outcome.RETRY;
        }
        if (probe.phantom) {
            return Outcome.ADOPT;
        }
        if (probe.legacy) {
            return Outcome.LEGACY_HOLDER;
        }
        return Outcome.FOREIGN;
    }
}
