package com.phantom.automation.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stale-daemon identification: parsing plus kill/no-kill decisions. */
public final class StaleDaemonTest {
    private static final String TCP =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                    + "   0: 0100007F:A866 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0\n"
                    + "   1: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 99999 1 0000000000000000 100 0 0 10 0\n"
                    + "   2: 0100007F:A866 00000000:0000 06 00000000:00000000 00:00000000 00000000     0        0 55555 1 0000000000000000 100 0 0 10 0\n";

    @Test
    public void portHex() {
        assertEquals("A866", StaleDaemon.portHex(43110));
    }

    @Test
    public void listeningInodesFindsOnlyListenState() {
        Set<String> inodes = StaleDaemon.listeningInodes(TCP, null, 43110);
        assertEquals(new HashSet<>(Arrays.asList("12345")), inodes);
    }

    @Test
    public void pidsByInode() {
        String listing = "/proc/4242/fd/9 -> socket:[12345]\n"
                + "/proc/4242/fd/10 -> socket:[77777]\n"
                + "/proc/99/fd/3 -> /dev/null\n";
        Map<String, List<Integer>> map = StaleDaemon.pidsByInode(listing);
        assertEquals(Arrays.asList(4242), map.get("12345"));
        assertEquals(Arrays.asList(4242), map.get("77777"));
    }

    @Test
    public void legacyShellIsKillable() {
        StaleDaemon.Verdict verdict = StaleDaemon.classify(4242,
                "app_process /system/bin com.silentauto.shell.Main --port=43110");
        assertEquals(StaleDaemon.Decision.LEGACY_SHELL, verdict.decision);
    }

    @Test
    public void ownServiceIsNeverKilled() {
        StaleDaemon.Verdict verdict = StaleDaemon.classify(4243,
                "com.phantom.automation:phantom_priv");
        assertEquals(StaleDaemon.Decision.OURS, verdict.decision);
    }

    @Test
    public void unknownOwnerIsRefused() {
        StaleDaemon.Verdict verdict = StaleDaemon.classify(100,
                "/system/bin/something-else --port=43110");
        assertEquals(StaleDaemon.Decision.REFUSE, verdict.decision);
    }

    @Test
    public void emptyCmdlineAndSystemPidAreRefused() {
        assertEquals(StaleDaemon.Decision.REFUSE, StaleDaemon.classify(1, "init").decision);
        assertEquals(StaleDaemon.Decision.REFUSE, StaleDaemon.classify(4242, "").decision);
        assertEquals(StaleDaemon.Decision.REFUSE, StaleDaemon.classify(4242, null).decision);
    }

    @Test
    public void nulSeparatedCmdline() {
        StaleDaemon.Verdict verdict = StaleDaemon.classify(4242,
                "app_process\0/system/bin\0com.silentauto.shell.Main\0--port=43110\0");
        assertEquals(StaleDaemon.Decision.LEGACY_SHELL, verdict.decision);
        assertTrue(verdict.reason.contains("4242"));
    }
}
