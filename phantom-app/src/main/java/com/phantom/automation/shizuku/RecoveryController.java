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
    private static final String LEGACY_PID_FILE = "/data/local/tmp/silent-auto.pid";

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
            step("Probing port " + PORT + "…");
            ExecResult tables = exec("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null; echo RC=$?");
            Set<String> inodes = StaleDaemon.listeningInodes(tables.output, null, PORT);
            if (inodes.isEmpty()) {
                step("Port is free — starting Phantom daemon…");
                finishStart();
                return;
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
                abort("Socket owners not visible (restricted /proc). Limitation: this "
                        + "Android build hides other processes' file descriptors, so "
                        + "Phantom cannot identify the holder. Reboot the device or "
                        + "stop the app that serves port " + PORT + ", then Start.");
                return;
            }
            String pidFile = exec("cat " + LEGACY_PID_FILE + " 2>/dev/null").output.trim();
            List<StaleDaemon.Verdict> verdicts = new ArrayList<>();
            for (int pid : pids) {
                if (checkCancelled()) {
                    return;
                }
                String cmdline = exec("tr '\\0' ' ' </proc/" + pid + "/cmdline 2>/dev/null; echo").output;
                verdicts.add(StaleDaemon.classify(pid, cmdline));
            }
            boolean legacyPidFileMatch = false;
            for (StaleDaemon.Verdict verdict : verdicts) {
                step(verdict.reason);
                if (verdict.decision == StaleDaemon.Decision.LEGACY_SHELL
                        && String.valueOf(verdict.pid).equals(pidFile)) {
                    legacyPidFileMatch = true;
                }
            }
            List<StaleDaemon.Verdict> legacy = filter(verdicts, StaleDaemon.Decision.LEGACY_SHELL);
            List<StaleDaemon.Verdict> ours = filter(verdicts, StaleDaemon.Decision.OURS);
            List<StaleDaemon.Verdict> foreign = filter(verdicts, StaleDaemon.Decision.REFUSE);
            if (!foreign.isEmpty()) {
                abort("Port " + PORT + " is held by an unrecognized process that "
                        + "Phantom must not kill (" + foreign.get(0).reason + "). Limitation: "
                        + "only positively identified legacy shells are stopped. Free the "
                        + "port (stop that app) and tap Recover again."
                        + (legacyPidFileMatch ? "" : ""));
                return;
            }
            if (!legacy.isEmpty()) {
                for (StaleDaemon.Verdict verdict : legacy) {
                    if (checkCancelled()) {
                        return;
                    }
                    step("Stopping legacy shell pid " + verdict.pid + "…");
                    ExecResult kill = exec("kill " + verdict.pid, 5000);
                    if (kill.exit != 0) {
                        abort("Could not stop legacy pid " + verdict.pid + " (kill exit "
                                + kill.exit + "). It may have been started with privileges "
                                + "Phantom lacks. Reboot, or stop it from its own app.");
                        return;
                    }
                }
                sleep(1200);
                step("Legacy shell stopped — starting Phantom daemon…");
                finishStart();
                return;
            }
            if (!ours.isEmpty()) {
                step("Port is held by Phantom itself — restarting…");
                try {
                    client.stopDaemon();
                } catch (Throwable ignored) {
                }
                sleep(800);
                finishStart();
                return;
            }
            abort("No actionable owner found.");
        } catch (Throwable e) {
            done(false, "Recovery failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void finishStart() {
        try {
            int result = client.startDaemon(PORT);
            if (result != 0) {
                String detail = client.getError();
                done(false, detail == null || detail.isEmpty()
                        ? "Daemon refused to start (code " + result + ")." : detail);
                return;
            }
            DaemonController.Hello hello =
                    DaemonController.hello("127.0.0.1", PORT, 8000);
            if (hello.ok) {
                done(true, "Recovered: " + hello.summary + ".");
            } else {
                done(false, hello.error);
            }
        } catch (Throwable e) {
            done(false, "Start failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
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

    private static List<StaleDaemon.Verdict> filter(
            List<StaleDaemon.Verdict> verdicts, StaleDaemon.Decision decision) {
        List<StaleDaemon.Verdict> out = new ArrayList<>();
        for (StaleDaemon.Verdict verdict : verdicts) {
            if (verdict.decision == decision) {
                out.add(verdict);
            }
        }
        return out;
    }
}
