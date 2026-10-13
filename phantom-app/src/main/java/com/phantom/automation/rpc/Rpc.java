package com.phantom.automation.rpc;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot blocking RPC for UI threads-off work. Opens a connection per
 * call, skips interleaved event lines, matches the reply id. All loopback
 * traffic stays on-device.
 */
public final class Rpc {
    public static final String HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 43110;
    /** Port the daemon was verified on (canonical, or alternate fallback). */
    public static volatile int activePort = DEFAULT_PORT;

    private static final AtomicInteger IDS = new AtomicInteger(1);

    private Rpc() {
    }

    public static JSONObject call(String method, JSONObject params, int timeoutMs)
            throws RpcException {
        JSONObject reply = raw(method, params, timeoutMs);
        JSONObject result = reply.optJSONObject("result");
        if (result == null) {
            throw new RpcException("Unexpected reply shape for '" + method + "'.");
        }
        return result;
    }

    /** For methods whose result is a JSON array (e.g. logs). */
    public static org.json.JSONArray callArray(String method, JSONObject params, int timeoutMs)
            throws RpcException {
        JSONObject reply = raw(method, params, timeoutMs);
        org.json.JSONArray result = reply.optJSONArray("result");
        if (result == null) {
            throw new RpcException("Unexpected reply shape for '" + method + "'.");
        }
        return result;
    }

    private static JSONObject raw(String method, JSONObject params, int timeoutMs)
            throws RpcException {
        int id = IDS.getAndIncrement();
        JSONObject request = new JSONObject();
        try {
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.put("params", params == null ? new JSONObject() : params);
        } catch (Exception e) {
            throw new RpcException("Bad request: " + e.getMessage());
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, activePort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStreamWriter writer = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(request + "\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject reply;
                try {
                    reply = new JSONObject(line);
                } catch (Exception ignored) {
                    continue;
                }
                if (reply.optInt("id", -1) != id) {
                    continue;
                }
                if (reply.has("error") && !reply.isNull("error")) {
                    throw new RpcException(shellMessage(reply));
                }
                return reply;
            }
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("Daemon unreachable at " + HOST + ":" + activePort
                    + " — start it on Home first (" + shortMessage(e) + ").");
        }
        throw new RpcException("Daemon closed the connection without a reply.");
    }

    private static String shellMessage(JSONObject reply) {
        try {
            return reply.getJSONObject("error").optString("message", "Shell error");
        } catch (Exception ignored) {
            return "Shell error";
        }
    }

    private static String shortMessage(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    public static final class RpcException extends Exception {
        RpcException(String message) {
            super(message);
        }
    }
}
