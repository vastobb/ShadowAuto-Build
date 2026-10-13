package com.phantom.automation.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Hello validation: legacy, foreign, outdated, and good daemons. */
public final class HelloCheckTest {
    private static final String GOOD =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"app\":\"phantom\",\"version\":2,"
                    + "\"methods\":[\"hello\",\"ping\",\"status\",\"logs\",\"dumpUi\","
                    + "\"openSession\",\"closeSession\",\"listApps\",\"launchApp\","
                    + "\"screenshot\"]}}";
    private static final String LEGACY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,"
                    + "\"message\":\"unknown method: hello\"}}";

    @Test
    public void goodDaemonPasses() {
        HelloCheck.Result result = HelloCheck.check(GOOD, 43110);
        assertTrue(result.ok);
        assertEquals("phantom", result.app);
        assertEquals(2, result.version);
        assertTrue(result.summary.contains("10 methods"));
    }

    @Test
    public void legacyDaemonIsIdentifiedNotHidden() {
        HelloCheck.Result result = HelloCheck.check(LEGACY, 43110);
        assertFalse(result.ok);
        // -32601 alone must NOT positively claim legacy: recovery identifies.
        assertTrue(result.error.contains("not proven"));
        assertTrue(result.error.contains("Use Recover"));
    }

    @Test
    public void foreignDaemonIsRefused() {
        HelloCheck.Result result = HelloCheck.check(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"app\":\"other\"}}", 43110);
        assertFalse(result.ok);
        assertTrue(result.error.contains("foreign"));
    }

    @Test
    public void outdatedVersionIsRejected() {
        HelloCheck.Result result = HelloCheck.check(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"app\":\"phantom\","
                        + "\"version\":1,\"methods\":[\"hello\"]}}", 43110);
        assertFalse(result.ok);
        assertTrue(result.error.contains("too old"));
    }

    @Test
    public void missingMethodsAreNamed() {
        HelloCheck.Result result = HelloCheck.check(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"app\":\"phantom\","
                        + "\"version\":2,\"methods\":[\"hello\",\"ping\"]}}", 43110);
        assertFalse(result.ok);
        assertTrue(result.error.contains("listApps"));
    }

    @Test
    public void nullLineMeansNoHello() {
        HelloCheck.Result result = HelloCheck.check(null, 43110);
        assertFalse(result.ok);
    }
}
