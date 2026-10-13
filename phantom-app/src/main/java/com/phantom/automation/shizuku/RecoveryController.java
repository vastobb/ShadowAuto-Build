package com.phantom.automation.shizuku;

import com.phantom.automation.shizuku.PhantomUserService.Client;
import com.phantom.automation.shizuku.PhantomUserService.Client.ExecResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stale-daemon recovery through our own Shizuku UserService (no manual adb).
 * Identifies the port owner from kernel tables, kills ONLY positively
 * identified legacy shells, then starts Phantom's daemon and verifies the
 * handshake. Never reports success before a Phantom hello passes.
 */
public final class RecoveryController {
    public interface Listener {
        void onStep(String message);

        void onDone(boolean ok, String message);
    }

    private static final int PORT = 43110;
    private static final int ALT_PORT = 43111;

    private final Client client;
    private final Listener listener;
    private volatile boolean cancelled;

    public RecoveryController(Client client, Listener listener) {
        this.client = client;
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    public void run() {
        try {
            // 1. Handshake first: a healthy Phantom endpoint is adopted with
            // no killing and no forensics. This also makes recovery while a
            // valid daemon runs a safe no-op success.
            step("Probing port " + PORT + "…");
            DaemonController.HelloCheckResult probe = helloProbe();
            if (probe.phantom) {
                done(true, "Already healthy — adopted Phantom daemon (pid "
                        + probe.pid + ", protocol v" + probe.version + ").");
                return;
            }
            if (!probe.connected) {
                step("Port is free — starting Phantom daemon…");
                finishStart();
                return;
            }
            if (!probe.legacy) {
                fallbackOrAbort(FallbackDecider.Holder.FOREIGN,
                        "A foreign service holds port " + PORT + " (unrecognized handshake).");
                return;
            }
            // 2. Verified legacy-shaped responder (-32601). Narrow cleanup:
            // best-effort kernel tables first, then the legacy pidfile with
            // a cmdline identity check. Never kill on port/PID alone.
            step("Legacy-shaped responder confirmed — identifying its process…");
            if (tryTableIdentification()) {
                return;
            }
            if (tryPidfileCleanup()) {
                return;
            }
            // Identity cannot be established (restricted /proc, no verifiable
            // pid record). Do NOT kill, do NOT demand a reboot: serve on a
            // verified-free alternate port instead.
            fallbackOrAbort(FallbackDecider.Holder.LEGACY,
                    "Legacy-shaped responder on port " + PORT
                            + " but its process is unidentifiable on this build.");
        } catch (Throwable e) {
            done(false, "Recovery failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Best-effort kernel-table identification (works where /proc is open). */
    private boolean tryTableIdentification() throws Exception {
        ExecResult tables = exec("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null; echo RC=$?");
        Set<String> inodes = StaleDaemon.listeningInodes(tables.output, null, PORT);
        if (inodes.isEmpty()) {
            return false;
        }
        step("Port held by socket(s) " + inodes + " — resolving owners…");
        ExecResult fds = exec("ls -l /proc/[0-9]*/fd 2>/dev/null | grep socket:", 15000);
        Map<String, List<Integer>> byInode = StaleDaemon.pidsByInode(fds.output);
        Set<Integer> pids = new LinkedHashSet<>();
        for (String inode : inodes) {
            List<Integer> owners = byInode.get(inode);
            if (owners != null) {
                pids.addAll(owners);
            }
        }
        if (pids.isEmpty()) {
            step("Kernel tables hide the owners on this build — trying pid record…");
            return false;
        }
        List<StaleDaemon.Verdict> verdicts = new ArrayList<>();
        for (int pid : pids) {
            if (checkCancelled()) {
                return true;
            }
            String cmdline = exec("tr '\\0' ' ' </proc/" + pid + "/cmdline 2>/dev/null; echo").output;
            verdicts.add(StaleDaemon.classify(pid, cmdline));
        }
        for (StaleDaemon.Verdict verdict : verdicts) {
            step(verdict.reason);
        }
        RecoveryDecider.Plan plan = RecoveryDecider.plan(verdicts);
        switch (plan.action) {
            case REFUSE:
                fallbackOrAbort(FallbackDecider.Holder.FOREIGN, plan.reason);
                return true;
            case KILL_THEN_START:
                return killThenStart(plan.killPids);
            case RESTART_OURS:
                step(plan.reason + " (restarting verified instance)…");
                try {
                    client.stopDaemon();
                } catch (Throwable ignored) {
                }
                sleep(800);
                finishStart();
                return true;
            case START:
            default:
                step(plan.reason);
                finishStart();
                return true;
        }
    }

    /**
     * Narrow legacy cleanup: the legacy shell records its own PID; kill that
     * PID only after its cmdline positively identifies the legacy shell.
     */
    private boolean tryPidfileCleanup() throws Exception {
        ExecResult pidFile = exec("cat /data/local/tmp/silent-auto.pid 2>/dev/null");
        int pid = PidfileCleanup.parsePid(pidFile.output);
        if (pid <= 1) {
            step("No legacy pid record found.");
            return false;
        }
        ExecResult cmd = exec("tr '\\0' ' ' </proc/" + pid + "/cmdline 2>/dev/null; echo");
        PidfileCleanup.Verdict verdict =
                PidfileCleanup.decide(pidFile.output, cmd.output);
        step(verdict.reason);
        if (verdict.decision != PidfileCleanup.Decision.KILL) {
            return false;
        }
        return killThenStart(java.util.Collections.singletonList(verdict.pid));
    }

    private boolean killThenStart(List<Integer> pids) throws Exception {
        for (int pid : pids) {
            if (checkCancelled()) {
                return true;
            }
            step("Stopping verified legacy shell pid " + pid + "…");
            ExecResult kill = exec("kill " + pid, 5000);
            if (kill.exit != 0) {
                abort("Could not stop legacy pid " + pid + " (kill exit "
                        + kill.exit + "). It may have privileges Phantom "
                        + "lacks. Reboot, or stop it from its own app.");
                return true;
            }
        }
        sleep(1200);
        DaemonController.HelloCheckResult after = helloProbe();
        if (after.phantom) {
            done(true, "Legacy shell stopped; adopted Phantom daemon (pid "
                    + after.pid + ").");
            return true;
        }
        if (after.connected) {
            abort("Legacy shell stopped but port " + PORT + " still answers "
                    + "without hello — another holder appeared. Tap Recover again.");
            return true;
        }
        step("Legacy shell stopped — starting Phantom daemon…");
        finishStart();
        return true;
    }

    private void fallbackOrAbort(FallbackDecider.Holder canonical, String why) {
        step(why + " Trying alternate port " + ALT_PORT + "…");
        DaemonController.HelloCheckResult alt = DaemonController.probePort(ALT_PORT);
        FallbackDecider.Holder altKind = alt.phantom
                ? FallbackDecider.Holder.PHANTOM
                : (!alt.connected ? FallbackDecider.Holder.FREE
                        : (alt.legacy ? FallbackDecider.Holder.LEGACY
                                : FallbackDecider.Holder.FOREIGN));
        switch (FallbackDecider.decide(canonical, altKind)) {
            case ADOPT_ALTERNATE:
                com.phantom.automation.rpc.Rpc.activePort = ALT_PORT;
                done(true, "Adopted Phantom daemon on alternate port " + ALT_PORT
                        + " (pid " + alt.pid + "). Port " + PORT
                        + " stays with its unidentified holder.");
                return;
            case USE_ALTERNATE:
                step("Alternate port is free — starting Phantom daemon there…");
                finishStartOn(ALT_PORT);
                return;
            case USE_CANONICAL:
            case REFUSE:
            default:
                abort("Both port " + PORT + " and alternate " + ALT_PORT + " are held "
                        + "by services Phantom must not disturb (" + why + "). "
                        + "Stop the unknown app(s), then tap Recover again.");
        }
    }

    private void finishStart() {
        finishStartOn(PORT);
    }

    private void finishStartOn(int port) {
        try {
            int result = client.startDaemon(port);
            if (result == com.phantom.automation.engine.EngineHost.ADOPTED) {
                DaemonController.HelloCheckResult adopted = helloProbe(port);
                if (adopted.phantom) {
                    com.phantom.automation.rpc.Rpc.activePort = port;
                    done(true, port == PORT
                            ? "Recovered by adopting healthy daemon (pid "
                                    + adopted.pid + ")."
                            : "Recovered on alternate port " + port + " (pid "
                                    + adopted.pid + "). Port " + PORT
                                    + " stays with its unidentified holder.");
                } else {
                    done(false, "Adopted endpoint failed verification — tap Recover again.");
                }
                return;
            }
            if (result != com.phantom.automation.engine.EngineHost.SERVING) {
                if (result == com.phantom.automation.engine.EngineHost.LEGACY_HOLDER) {
                    done(false, "A verified legacy shell reappeared on port " + port
                            + " — tap Recover again.");
                    return;
                }
                String detail = client.getError();
                done(false, detail == null || detail.isEmpty()
                        ? "Daemon refused to start (code " + result + ")." : detail);
                return;
            }
            DaemonController.HelloCheckResult hello = helloProbe(port);
            if (hello.phantom) {
                com.phantom.automation.rpc.Rpc.activePort = port;
                done(true, port == PORT
                        ? "Recovered: phantom protocol v" + hello.version + "."
                        : "Recovered on alternate port " + port
                                + " (phantom protocol v" + hello.version + ").");
            } else {
                done(false, hello.error);
            }
        } catch (Throwable e) {
            done(false, "Start failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private DaemonController.HelloCheckResult helloProbe(int port) {
        return DaemonController.probePort(port);
    }

    private DaemonController.HelloCheckResult helloProbe() {
        return DaemonController.probePort(PORT);
    }

    private ExecResult exec(String command, int timeoutMs) throws Exception {
        return client.exec(command, timeoutMs);
    }

    private ExecResult exec(String command) throws Exception {
        return client.exec(command, 10000);
    }

    private void step(String message) {
        if (listener != null) {
            listener.onStep(message);
        }
    }

    private void abort(String message) {
        done(false, message);
    }

    private void done(boolean ok, String message) {
        if (listener != null) {
            listener.onDone(ok, message);
        }
    }

    private boolean checkCancelled() {
        if (cancelled) {
            done(false, "Recovery cancelled.");
            return true;
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
