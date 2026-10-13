package com.phantom.automation.shizuku;

/**
 * Pure-JVM alternate-port decision: when the canonical port is held by an
 * unidentifiable listener, serve on a verified-free alternate port instead
 * of demanding a reboot. Never redirects around a healthy or foreign
 * endpoint silently — the outcome names what was chosen and why.
 */
public final class FallbackDecider {
    public enum Holder {
        PHANTOM,
        LEGACY,
        FOREIGN,
        FREE,
    }

    public enum Outcome {
        /** Canonical is healthy or free: use it. */
        USE_CANONICAL,
        /** Canonical blocked by legacy/foreign, alternate free: serve there. */
        USE_ALTERNATE,
        /** Canonical blocked, alternate holds Phantom: adopt it. */
        ADOPT_ALTERNATE,
        /** Nowhere safe to serve. */
        REFUSE,
    }

    private FallbackDecider() {
    }

    public static Outcome decide(Holder canonical, Holder alternate) {
        if (canonical == Holder.PHANTOM || canonical == Holder.FREE) {
            return Outcome.USE_CANONICAL;
        }
        if (alternate == Holder.PHANTOM) {
            return Outcome.ADOPT_ALTERNATE;
        }
        if (alternate == Holder.FREE
                && (canonical == Holder.LEGACY || canonical == Holder.FOREIGN)) {
            return Outcome.USE_ALTERNATE;
        }
        return Outcome.REFUSE;
    }
}
