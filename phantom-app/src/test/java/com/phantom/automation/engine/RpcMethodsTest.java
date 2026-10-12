package com.phantom.automation.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The dispatch table must expose everything the UI and MCP layers call. */
public final class RpcMethodsTest {
    private static final String[] UI_REQUIRED = {
            "hello", "ping", "status", "logs", "dumpUi",
            "openSession", "closeSession", "listApps", "launchApp", "screenshot",
            "tap", "longPress", "swipe", "scroll", "pressKey", "pressBack",
            "inputText", "tapTarget", "focusInput", "clearText",
            "ocrStatus", "ocrConfigure", "ocrRecognize", "findText",
            "codecCaps", "setRecordingState", "startTask", "stopTask",
    };

    @Test
    public void registryCoversUiRequirements() {
        Set<String> methods = new HashSet<>(Arrays.asList(RpcProtocol.METHODS));
        for (String required : UI_REQUIRED) {
            assertTrue("RpcProtocol.METHODS lacks " + required,
                    methods.contains(required));
        }
        Set<String> required = new HashSet<>(Arrays.asList(RpcProtocol.REQUIRED));
        for (String core : new String[]{"hello", "openSession", "listApps",
                "launchApp", "dumpUi", "screenshot", "status", "logs"}) {
            assertTrue("RpcProtocol.REQUIRED lacks " + core, required.contains(core));
        }
    }

    @Test
    public void helloShape() {
        assertEquals("phantom", RpcProtocol.APP);
        assertTrue(RpcProtocol.VERSION >= 2);
        assertTrue(RpcProtocol.METHODS.length >= UI_REQUIRED.length);
    }

    @Test
    public void missingDetection() {
        java.util.List<String> absent =
                RpcProtocol.missing(java.util.Collections.singleton("hello"));
        assertTrue(absent.contains("listApps"));
        assertTrue(RpcProtocol.missing(
                new HashSet<>(Arrays.asList(RpcProtocol.METHODS))).isEmpty());
    }
}
