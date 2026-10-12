# Phantom MCP server (installable)

OpenCode drives Phantom through this stdio MCP server. Install it on the
machine that runs OpenCode (e.g. Fedora) — no repository checkout needed
on the Android device, and no paths to configure:

```sh
pip install "phantom-mcp @ git+https://github.com/vastobb/ShadowAuto-Build.git#subdirectory=mcp"
# or from a local checkout: pip install ./mcp
```

`opencode.json`:

```jsonc
{ "mcp": { "servers": { "phantom": {
  "type": "local",
  "command": ["phantom-mcp"],
  "environment": {"SHADOWAUTO_HOST": "127.0.0.1", "SHADOWAUTO_PORT": "43110"}
} } } }
```

Bridge the namespaces (Android and Fedora loopbacks differ), loopback-only
on both ends, no LAN exposure:

```sh
adb forward tcp:43110 tcp:43110
```

Then `opencode mcp list` (expect `phantom connected`). The Android MCP
screen shows live daemon protocol status; OpenCode-side connectivity can
only be verified on that machine. Legacy `mcp_server.py` direct launch
still works for development.

Native MCP support for ShadowAuto, built on the existing code. OpenCode
becomes the brain (inspect screen → decide → call tool); the on-device
shell stays the hands (virtual display + display-targeted input, never
the physical screen).

## What was added

| File | Purpose |
|---|---|
| `../android-shell/.../shell/McpSessions.java` | New: direct-control virtual-display sessions (no AI loop). All input carries the session display id. |
| `../android-shell/.../shell/AutomationEngine.java` | Extended: `mcp()` sessions accessor; `status` now also lists `mcpSessions`. Task loop untouched. |
| `../android-shell/.../shell/RpcServer.java` | Extended: `openSession/closeSession/listApps/launchApp/screenshot/tap/longPress/swipe/scroll/pressKey/pressBack/inputText/tapTarget/focusInput/clearText` (+`sessionId` on `dumpUi`). Same NDJSON framing. |
| `mcp/shadowauto_backend.py` | Shell RPC client (stdlib only): NDJSON framing, skips status pushes/event lines, timeouts, `adb forward` hints. |
| `mcp/mcp_server.py` | MCP server, official SDK, **stdio** transport, 21 tools. |
| `mcp/model_service.py` | Local OpenAI-compatible API (`GET /v1/models`, `POST /v1/chat/completions`, streaming SSE + non-streaming). Loopback only. |
| `mcp/adb_forward.sh` | Bridges the two `127.0.0.1` namespaces via `adb forward`/`adb reverse`. |
| `mcp/opencode.json.example` | OpenCode config with absolute command path. |

## 1. Install

```sh
python3 -m pip install --user -r mcp/requirements.txt  # mcp==2.3.0
chmod +x mcp/adb_forward.sh
```

Get the new shell APK (MCP RPC needs `McpSessions` on device) from
GitHub Actions artifacts of this repo (`silent-shell-debug`), or build:

```sh
./gradlew :android-shell:assembleDebug -PenablePaddleLiteOcr=false
```

## 2. Startup

```sh
# Terminal 1: push shell, start it, forward RPC (container -> device)
adb push android-shell/build/outputs/apk/debug/android-shell-debug.apk /data/local/tmp/silent-shell.apk
adb shell "CLASSPATH=/data/local/tmp/silent-shell.apk nohup sh -c \
  'exec app_process /system/bin com.silentauto.shell.Main --port=43110' \
  >/data/local/tmp/silent-auto.log 2>&1 </dev/null &"
bash mcp/adb_forward.sh

# Terminal 2: local model service (loopback only, default :8410)
python3 mcp/model_service.py --port 8410
FORWARD_MODEL=1 bash mcp/adb_forward.sh   # device -> container for the model URL

# Controller app: API URL http://127.0.0.1:8410/v1 , Model shadowauto-mcp
```

`SHADOWAUTO_MODEL_PORT`, `SHADOWAUTO_MODEL_ID`, `SHADOWAUTO_API_KEY`
(optional Bearer enforcement; empty = accept any), `SHADOWAUTO_HOST`,
`SHADOWAUTO_PORT`, `SHADOWAUTO_RPC_TIMEOUT` configure ports/model/auth.

## 3. OpenCode configuration

Merge `mcp/opencode.json.example` into your project `opencode.json`
(or `opencode mcp add shadowauto -- python3
/home/flux/ShadowAuto-Build/mcp/mcp_server.py` with the same env), then:

```sh
opencode mcp list   # expect: shadowauto connected
```

Drive a task in OpenCode:

1. `check_connection` (fail fast if the shell/forward is down).
2. `open_session` → `list_apps` → `launch_app`.
3. Loop: `dump_ui` (targets/inputs with display-local coords) →
   `tap_target`/`tap`/`swipe`/`scroll`/`focus_input`+`input_text`/
   `press_key`/`press_back` → `dump_ui` again. Use `screenshot`
   only when layout nodes are missing.
4. `close_session`. Autonomous LLM tasks: `start_task`/`status`/
   `logs`/`stop_task` (unchanged legacy path).

## 4. Design notes (read before debugging)

* **No circular calls.** `model_service` never calls `startTask` and never
  calls itself; it only reads `status` for live state. The shell
  autonomous loop still needs an external LLM; the local `shadowauto-mcp`
  model answers the controller test with live state and ends streamed
  autonomous requests with an explicit `finish` handoff reason (no fake
  taps). Inference (OpenCode / external LLM) stays separate from the
  Android action tools.
* **Display safety.** Every `InputBridge` event gets `setDisplayId`
  of the session virtual display (`McpSessions`), same mechanism as the
  existing task loop. No tool touches the physical display.
* **Networking.** Container and Android loopbacks differ; `adb forward
  tcp:43110` (RPC) and `adb reverse tcp:8410` (model URL) bridge them.
  No extra relay process; nothing listens on the LAN by default.
* **Backwards compatible.** Old RPC (`ping/status/logs/dumpUi/startTask/
  stopTask`) is byte-identical; `status` only gains `mcpSessions`. Old
  APKs answer new methods with `-32601`, which the backend reports as
  "install the APK built from this repo".

## 5. Tests

```sh
cd mcp && python3 -m unittest discover -v
```

10 tests, stdlib only, no device needed: RPC framing (status-push/event
skipping, id matching, unknown-method hint, timeout, unreachable hint)
and model-service endpoints (models list, `Say OK` controller pattern,
unknown-model hint, valid SSE `finish` chunk for the shell pattern,
loopback bind). The emulator test
(`node scripts/emulator-mock-ai-test.mjs`) needs a device/emulator with
`adb` and is not runnable in this container.

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| `shell unreachable ... adb forward` | Start shell on device, run `bash mcp/adb_forward.sh`. |
| `-32601 unknown method` | Device runs the old APK; install the new `silent-shell-debug`. |
| Controller `HTTP 400 unknown model` | Pick `shadowauto-mcp` (or set `SHADOWAUTO_MODEL_ID`). |
| Controller `HTTP 401` | `SHADOWAUTO_API_KEY` is set: type the same key in the app. |
| Task ends with `local-model handoff` | Expected: local model does no inference. Use MCP tools or set an external LLM URL in the controller. |
| `screenshot` empty/black | Protected/DRM page, or no frame yet; retry, or rely on `dumpUi`. |
| MCP `startup timeout` in OpenCode | Check `python3 mcp/mcp_server.py` deps (`pip install -r mcp/requirements.txt`). |
