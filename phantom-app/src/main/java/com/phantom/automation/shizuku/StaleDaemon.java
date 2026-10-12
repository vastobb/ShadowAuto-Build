package com.phantom.automation.shizuku;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Identifies the process listening on the daemon port from shell-visible
 * kernel tables. Pure Java (no Android APIs) so the parsing and the
 * kill/no-kill decisions are unit-tested on the JVM.
 *
 * <p>Safety contract: {@link Decision#kill} is returned ONLY when the
 * command line positively identifies the obsolete legacy shell
 * ({@code app_process … com.silentauto.shell.Main}). Phantom's own service
 * yields {@link Decision#OURS}, anything else yields
 * {@link Decision#REFUSE} with a reason. PIDs ≤ 1 are never actionable.
 */
public final class StaleDaemon {
    public enum Decision {
        LEGACY_SHELL,
        OURS,
        REFUSE,
    }

    public static final class Verdict {
        public final int pid;
        public final Decision decision;
        public final String reason;

        Verdict(int pid, Decision decision, String reason) {
            this.pid = pid;
            this.decision = decision;
            this.reason = reason;
        }
    }

    private StaleDaemon() {
    }

    /** 43110 -> "A866". */
    public static String portHex(int port) {
        return String.format(Locale.US, "%04X", port);
    }

    /**
     * Parses {@code /proc/net/tcp} + {@code tcp6} text. Returns the socket
     * inodes in LISTEN state (0A) bound to the port on any local address.
     */
    public static Set<String> listeningInodes(String tcpText, String tcp6Text, int port) {
        Set<String> inodes = new LinkedHashSet<>();
        String want = ":" + portHex(port);
        for (String table : new String[]{tcpText, tcp6Text}) {
            if (table == null) {
                continue;
            }
            for (String line : table.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("sl")) {
                    continue;
                }
                String[] cols = trimmed.split("\\s+");
                if (cols.length < 10) {
                    continue;
                }
                String local = cols[1];
                String state = cols[3];
                String inode = cols[9];
                if (local.toUpperCase(Locale.US).endsWith(want.toUpperCase(Locale.US))
                        && "0A".equalsIgnoreCase(state)
                        && !inode.equals("0")) {
                    inodes.add(inode);
                }
            }
        }
        return inodes;
    }

    /**
     * Maps inodes to PIDs from {@code ls -l /proc/[0-9]*/fd} style output
     * lines ({@code /proc/123/fd/45 -> socket:[6789]}).
     */
    public static Map<String, List<Integer>> pidsByInode(String fdListing) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        if (fdListing == null) {
            return result;
        }
        for (String line : fdListing.split("\n")) {
            int proc = line.indexOf("/proc/");
            int arrow = line.indexOf("socket:[");
            if (proc < 0 || arrow < 0) {
                continue;
            }
            int pidStart = proc + "/proc/".length();
            int pidEnd = pidStart;
            while (pidEnd < line.length() && Character.isDigit(line.charAt(pidEnd))) {
                pidEnd++;
            }
            int close = line.indexOf(']', arrow);
            if (pidEnd == pidStart || close < 0) {
                continue;
            }
            try {
                int pid = Integer.parseInt(line.substring(pidStart, pidEnd));
                String inode = line.substring(arrow + "socket:[".length(), close);
                result.computeIfAbsent(inode, k -> new ArrayList<>()).add(pid);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    /** Classifies one PID by its (NUL-normalized) command line. */
    public static Verdict classify(int pid, String cmdline) {
        String flat = cmdline == null ? "" : cmdline.replace('\0', ' ').trim();
        if (pid <= 1 || flat.isEmpty()) {
            return new Verdict(pid, Decision.REFUSE,
                    pid + ": no trustworthy identity (empty cmdline); refusing to kill");
        }
        String lower = flat.toLowerCase(Locale.US);
        if (lower.contains("app_process") && lower.contains("com.silentauto.shell.main")) {
            return new Verdict(pid, Decision.LEGACY_SHELL,
                    pid + ": positively identified legacy shell (" + shortCmd(flat) + ")");
        }
        if (lower.contains("phantom_priv") || lower.contains("com.phantom.automation")) {
            return new Verdict(pid, Decision.OURS,
                    pid + ": Phantom's own service (" + shortCmd(flat) + ")");
        }
        return new Verdict(pid, Decision.REFUSE,
                pid + ": unrecognized owner (" + shortCmd(flat) + "); refusing to kill");
    }

    private static String shortCmd(String cmdline) {
        return cmdline.length() > 120 ? cmdline.substring(0, 120) + "…" : cmdline;
    }
}
