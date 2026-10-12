package com.silentauto.shell;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class RpcServer {
    private final int port;
    private final AutomationEngine engine;
    private final LogHub logs;

    RpcServer(int port, AutomationEngine engine, LogHub logs) {
        this.port = port;
        this.engine = engine;
        this.logs = logs;
    }

    void run() throws Exception {
        ServerSocket server = new ServerSocket(port, 20, InetAddress.getByName("127.0.0.1"));
        while (true) {
            Socket socket = server.accept();
            new Thread(() -> handle(socket), "rpc-client").start();
        }
    }

    private void handle(Socket socket) {
        Client client = null;
        try {
            client = new Client(socket);
            logs.add(client);
            client.send(JsonRpc.result(null, engine.statusJson()));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                client.send(dispatch(JsonParser.parseString(line).getAsJsonObject()));
            }
        } catch (Throwable e) {
            logs.error("rpc client closed", e);
        } finally {
            if (client != null) {
                logs.remove(client);
            }
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private JsonObject dispatch(JsonObject request) {
        JsonElement id = JsonRpc.id(request);
        try {
            String method = JsonRpc.text(request, "method");
            JsonObject params = JsonRpc.params(request);
            if ("ping".equals(method)) {
                return JsonRpc.result(id, "pong");
            }
            if ("status".equals(method)) {
                return JsonRpc.result(id, engine.statusJson());
            }
            if ("logs".equals(method)) {
                return JsonRpc.result(id, JsonRpc.stringArray(logs.snapshot()));
            }
            if ("dumpUi".equals(method)) {
                String dumpSession = JsonRpc.text(params, "sessionId");
                if (!dumpSession.isEmpty()) {
                    return JsonRpc.result(id, engine.mcp().dumpUi(
                            dumpSession,
                            integer(params, "displayId", 0),
                            integer(params, "width", 0),
                            integer(params, "height", 0),
                            JsonRpc.text(params, "mode")
                    ));
                }
                return JsonRpc.result(id, engine.dumpUiJson(
                        integer(params, "displayId", 0),
                        integer(params, "width", 0),
                        integer(params, "height", 0),
                        JsonRpc.text(params, "mode")
                ));
            }
            if ("openSession".equals(method)) {
                return JsonRpc.result(id, engine.mcp().openSession(
                        JsonRpc.text(params, "sessionId"),
                        integer(params, "width", 0),
                        integer(params, "height", 0)
                ));
            }
            if ("closeSession".equals(method)) {
                return JsonRpc.result(id, engine.mcp().closeSession(JsonRpc.text(params, "sessionId")));
            }
            if ("listApps".equals(method)) {
                JsonObject value = new JsonObject();
                value.add("apps", engine.mcp().listApps());
                return JsonRpc.result(id, value);
            }
            if ("launchApp".equals(method)) {
                return JsonRpc.result(id, engine.mcp().launchApp(
                        JsonRpc.text(params, "sessionId"),
                        JsonRpc.text(params, "packageName")
                ));
            }
            if ("screenshot".equals(method)) {
                return JsonRpc.result(id, engine.mcp().screenshot(
                        JsonRpc.text(params, "sessionId"),
                        integer(params, "displayId", 0)
                ));
            }
            if ("tap".equals(method)) {
                return JsonRpc.result(id, engine.mcp().tap(
                        JsonRpc.text(params, "sessionId"),
                        integer(params, "x", 0),
                        integer(params, "y", 0)
                ));
            }
            if ("longPress".equals(method)) {
                return JsonRpc.result(id, engine.mcp().longPress(
                        JsonRpc.text(params, "sessionId"),
                        integer(params, "x", 0),
                        integer(params, "y", 0),
                        integer(params, "durationMs", 550)
                ));
            }
            if ("swipe".equals(method)) {
                return JsonRpc.result(id, engine.mcp().swipe(
                        JsonRpc.text(params, "sessionId"),
                        integer(params, "startX", 0),
                        integer(params, "startY", 0),
                        integer(params, "endX", 0),
                        integer(params, "endY", 0),
                        integer(params, "durationMs", 420)
                ));
            }
            if ("scroll".equals(method)) {
                return JsonRpc.result(id, engine.mcp().scroll(
                        JsonRpc.text(params, "sessionId"),
                        JsonRpc.text(params, "direction"),
                        integer(params, "distance", 0)
                ));
            }
            if ("pressKey".equals(method)) {
                return JsonRpc.result(id, engine.mcp().pressKey(
                        JsonRpc.text(params, "sessionId"),
                        JsonRpc.text(params, "key")
                ));
            }
            if ("pressBack".equals(method)) {
                return JsonRpc.result(id, engine.mcp().pressBack(JsonRpc.text(params, "sessionId")));
            }
            if ("inputText".equals(method)) {
                return JsonRpc.result(id, engine.mcp().inputText(
                        JsonRpc.text(params, "sessionId"),
                        JsonRpc.text(params, "text")
                ));
            }
            if ("tapTarget".equals(method)) {
                return JsonRpc.result(id, engine.mcp().tapTarget(
                        JsonRpc.text(params, "sessionId"),
                        integer(params, "targetIndex", 0)
                ));
            }
            if ("focusInput".equals(method)) {
                return JsonRpc.result(id, engine.mcp().focusInput(
                        JsonRpc.text(params, "sessionId"),
                        integer(params, "inputIndex", 0),
                        JsonRpc.text(params, "query")
                ));
            }
            if ("clearText".equals(method)) {
                return JsonRpc.result(id, engine.mcp().clearText(JsonRpc.text(params, "sessionId")));
            }
            if ("stopTask".equals(method)) {
                String taskId = JsonRpc.text(params, "taskId");
                if (taskId.isEmpty()) {
                    engine.stopTask();
                } else {
                    engine.stopTask(taskId);
                }
                return JsonRpc.result(id, "stopped");
            }
            if ("startTask".equals(method)) {
                String taskId = engine.startTask(new AutomationEngine.TaskRequest(
                        JsonRpc.text(params, "taskId"),
                        JsonRpc.text(params, "goal"),
                        JsonRpc.text(params, "apiKey"),
                        JsonRpc.text(params, "apiBase"),
                        JsonRpc.text(params, "model")
                ));
                JsonObject value = new JsonObject();
                value.addProperty("taskId", taskId);
                value.addProperty("value", "started");
                return JsonRpc.result(id, value);
            }
            return JsonRpc.error(id, -32601, "unknown method: " + method);
        } catch (Throwable e) {
            return JsonRpc.error(id, -32000, e.getMessage());
        }
    }

    private int integer(JsonObject object, String name, int fallback) {
        try {
            if (object.has(name) && !object.get(name).isJsonNull()) {
                return object.get(name).getAsInt();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static final class Client implements LogHub.Sink {
        private final BufferedWriter writer;

        Client(Socket socket) throws Exception {
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public synchronized void send(JsonObject object) {
            try {
                writer.write(JsonRpc.GSON.toJson(object));
                writer.write('\n');
                writer.flush();
            } catch (Exception ignored) {
            }
        }
    }
}
