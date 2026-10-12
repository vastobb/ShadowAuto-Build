# Phantom

Isolated virtual-display automation for AI agents. OpenCode (via MCP)
launches and controls Android apps inside virtual displays — the physical
touchscreen is never an input target.

Single APK: `com.phantom.automation` (coexists with the legacy
`com.silentauto.*` apps; see `docs/phantom-migration.md`). No separate
Shell APK, no `/data/local/tmp` copy, no manual `app_process` commands:
a Shizuku UserService hosts the automation engine and the UI manages its
whole lifecycle.

## Install

1. Install Shizuku (`shizuku.rikka.app`) and start it (wireless debugging
   on Android 11+, otherwise adb from a PC once per boot).
2. Install the Phantom APK (GitHub Actions artifact `phantom-app-debug`,
   or `./gradlew :phantom-app:assembleDebug`).
3. Open Phantom → Authorize Shizuku → Start (daemon on
   `127.0.0.1:43110`).

Full-OCR builds (native libs + Paddle models) need the default Gradle
flags (no `-PenablePaddleLiteOcr=false`); the fast CI build ships Java
only, and OCR reports exactly what is missing.

## Startup

Home shows Shizuku state (with install/start/permission guidance and a
manual Reconnect) and daemon state (Start / Restart / Stop / Recover).
Recover needs no adb: it identifies the port-43110 owner from kernel
tables via the Shizuku service, stops ONLY positively identified legacy
shells, restarts Phantom's daemon, and verifies the Phantom handshake
before reporting success. Unknown owners are refused with an explanation.

- Sessions: create (resolution + `auto`/`avc`/`hevc`), view live
  (H.264/H.265), tap the preview (display-local mapping), back/scroll,
  record to `Movies/Phantom/*.mp4` (survives rotation, foreground
  service), long-press to close.
- Launcher: installed apps → launch into the default session (Settings).
- Settings: defaults, codec/bitrate/fps, API URL/key/model
  (`http://127.0.0.1:8410/v1`, `phantom-automation`), OCR download +
  status. Logs: shell ring buffer. MCP setup: in-app checklist.

## OpenCode configuration

Install on the machine that runs OpenCode (Fedora) — no checkout needed
on the phone, no paths to enter anywhere:

```sh
pip install "phantom-mcp @ git+https://github.com/vastobb/ShadowAuto-Build.git#subdirectory=mcp"
adb forward tcp:43110 tcp:43110
```

`opencode.json` (full example in `mcp/opencode.json.example`):

```jsonc
{ "mcp": { "servers": { "phantom": {
  "type": "local", "command": ["phantom-mcp"] } } } }
```

In the Phantom app, MCP Setup shows the live daemon protocol and the
exact instructions generated from it. `opencode mcp list` →
`phantom connected`. Android and Fedora loopbacks differ; the forward
bridges them, loopback-only on both ends. Flow: `check_connection` →
`open_session` → `list_apps` → `launch_app` → loop (`dump_ui` /
`screenshot` / `ocr_recognize` / `find_text` → `tap_target`/`tap`/`swipe`/
`scroll`/`focus_input`+`input_text`/`press_key`/`press_back`) →
`close_session`. 27 tools; failures are explicit errors, never guessed
coordinates.

## Local model endpoint

```sh
python3 mcp/model_service.py --port 8410   # loopback only
adb reverse tcp:8410 tcp:8410              # device -> container
```

- API URL `http://127.0.0.1:8410/v1`, model `phantom-automation`
  (`SHADOWAUTO_MODEL_ID` override available).
- `GET /v1/models`, `POST /v1/chat/completions` (streaming SSE + plain).
- Honest placeholder: answers the controller connectivity test with live
  shell state and ends streamed autonomous requests with an explicit
  handoff `finish` (no fake taps, no self-calls). Real reasoning belongs
  to OpenCode (MCP) or an external LLM configured in Settings.

## Troubleshooting

If Start sticks or fails, this build is instrumented — do this, in order:

1. Home → Start, wait for the result (every step times out: bind 20s,
   start 30s, recover 90s — it cannot stick forever).
2. Logs → Copy diagnostics (Shizuku version/uid, app version, every
   lifecycle transition with timestamps).
3. On a PC: `adb logcat -v time | grep -E "PhantomBind|PhantomDaemon"`
   while tapping Start. `PhantomBind` shows the app-side binding path;
   `PhantomDaemon` lines prove the UserService process started remotely.
   - `bindUserService returned without exception` but no
     `onServiceConnected` → Shizuku accepted but never started/called
     back (server-side or process-crash — send the full excerpt).
   - `PhantomUserService(Context) constructor` line present → remote
     startup works; the fault is later (daemon/RPC).
   - No `PhantomDaemon` lines at all → the remote process never ran.
4. Recover (Home) handles a stale legacy shell automatically — no manual
   `adb shell kill` needed. It only stops positively identified legacy
   processes and verifies the Phantom handshake before reporting success.

| Symptom | Fix |
|---|---|
| Shizuku not running | Start it in the Shizuku app → Reconnect |
| Permission denied permanently | Allow Phantom inside the Shizuku app → Reconnect |
| Daemon ping timeout | Shizuku restarted or UserService died: tap Start again |
| `shell unreachable` (MCP) | `adb forward tcp:43110 tcp:43110` |
| `-32601 unknown method` | Engine too old — reinstall current Phantom APK |
| OCR FAILED status | Settings → Download models; status names the missing file |
| Black screenshot/stream | Protected page (e.g. password screens) — platform restriction |
| HEVC unavailable | `codecCaps` shows encoders; `auto`/explicit `avc` falls back |

## Tests

- `mcp/`: `python3 -m unittest discover` (11 tests, no device).
- Android: `:phantom-app:testDebugUnitTest` in CI (tar/provisioning unit
  tests, no device). Device/emulator tests still require hardware.

License: Apache-2.0 (`LICENSE`).
