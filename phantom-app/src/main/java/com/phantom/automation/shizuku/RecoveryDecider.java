package com.phantom.automation.shizuku;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM recovery planner: turns port-owner verdicts into one safe
 * action. Killing happens only for positively identified legacy shells;
 * anything unrecognized refuses with the exact limitation.
 */
public final class RecoveryDecider {
    public enum Action {
        /** Port is free (or holder gone): start the daemon. */
        START,
        /** Stop these legacy PIDs, then start. */
        KILL_THEN_START,
        /** Our own service holds the port: restart it. */
        RESTART_OURS,
        /** Do nothing: report the limitation. */
        REFUSE,
    }

    public static final class Plan {
        public final Action action;
        public final List<Integer> killPids;
        public final String reason;

        Plan(Action action, List<Integer> killPids, String reason) {
            this.action = action;
            this.killPids = killPids;
            this.reason = reason;
        }
    }

    private RecoveryDecider() {
    }

    public static Plan plan(List<StaleDaemon.Verdict> verdicts) {
        List<StaleDaemon.Verdict> legacy = new ArrayList<>();
        List<StaleDaemon.Verdict> ours = new ArrayList<>();
        List<StaleDaemon.Verdict> foreign = new ArrayList<>();
        if (verdicts != null) {
            for (StaleDaemon.Verdict verdict : verdicts) {
                switch (verdict.decision) {
                    case LEGACY_SHELL:
                        legacy.add(verdict);
                        break;
                    case OURS:
                        ours.add(verdict);
                        break;
                    case REFUSE:
                        foreign.add(verdict);
                        break;
                    default:
                        break;
                }
            }
        }
        if (!foreign.isEmpty()) {
            return new Plan(Action.REFUSE, new ArrayList<>(),
                    "Port is held by an unrecognized process that Phantom must not kill ("
                            + foreign.get(0).reason + "). Free the port (stop that app) "
                            + "and tap Recover again.");
        }
        if (!legacy.isEmpty()) {
            List<Integer> pids = new ArrayList<>();
            for (StaleDaemon.Verdict verdict : legacy) {
                pids.add(verdict.pid);
            }
            return new Plan(Action.KILL_THEN_START, pids,
                    "Stopping legacy shell pid(s) " + pids + "…");
        }
        if (!ours.isEmpty()) {
            return new Plan(Action.RESTART_OURS, new ArrayList<>(),
                    "Port is held by Phantom itself — restarting…");
        }
        return new Plan(Action.START, new ArrayList<>(),
                "Port is free — starting Phantom daemon…");
    }
}
