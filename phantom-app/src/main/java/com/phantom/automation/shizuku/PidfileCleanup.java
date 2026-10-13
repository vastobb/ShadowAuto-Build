package com.phantom.automation.shizuku;

/**
 * Pure-JVM decisions for the narrow legacy pidfile path: kill exactly the
 * recorded PID and only after its command line positively identifies the
 * legacy shell. Anything else refuses with the reason.
 */
public final class PidfileCleanup {
    public enum Decision {
        KILL,
        REFUSE,
    }

    public static final class Verdict {
        public final Decision decision;
        public final int pid;
        public final String reason;

        Verdict(Decision decision, int pid, String reason) {
            this.decision = decision;
            this.pid = pid;
            this.reason = reason;
        }
    }

    private PidfileCleanup() {
    }

    public static Verdict decide(String pidfileContent, String cmdline) {
        int pid = parsePid(pidfileContent);
        if (pid <= 1) {
            return new Verdict(Decision.REFUSE, -1,
                    "No legacy pid record found.");
        }
        StaleDaemon.Verdict identity = StaleDaemon.classify(pid, cmdline);
        if (identity.decision != StaleDaemon.Decision.LEGACY_SHELL) {
            return new Verdict(Decision.REFUSE, pid,
                    "Pid record " + pid + " does not identify a legacy shell ("
                            + identity.decision + ") — refusing.");
        }
        return new Verdict(Decision.KILL, pid,
                "Pid record " + pid + " positively identifies the legacy shell.");
    }

    static int parsePid(String content) {
        if (content == null) {
            return -1;
        }
        String first = content.trim().split("\\s+")[0];
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
