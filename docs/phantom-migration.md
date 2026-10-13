# Phantom migration summary (ShadowAuto → Phantom)

## Identity

- App name/label: Phantom. `applicationId com.phantom.automation`,
  namespace `com.phantom.automation.*` (engine core ported from
  `com.silentauto.shell`, RPC client from `com.silentauto.controller`).
- **Coexistence: yes.** Different application IDs, no shared processes or
  providers. Phantom can be installed alongside the old controller/shell
  apps; uninstalling them is not required (the old `app_process` shell is
  APK-independent and unaffected).
- Branch `backup-shadowauto-mcp` preserves the pre-rebuild tree. Old
  modules (`android-shell`, `controller-app`, `web-launcher`, `mcp/`
  legacy flow) are frozen, still build in CI.
- License: Apache-2.0 retained (`LICENSE` unchanged; no per-file headers
  existed to preserve).

## Architecture decisions

- One APK. The engine runs in a Shizuku UserService (shell UID 2000, or
  root uid 0 — never assumed) serving the unchanged NDJSON RPC on
  `127.0.0.1:43110`. UI ↔ engine via `DaemonController`/`ServiceConnector`
  (explicit interfaces) + `RpcClient`. No `/data/local/tmp` copy, no
  manual commands. Plain-Binder protocol (no AIDL toolchain): transact
  codes 1/2/3 + Shizuku destroy code 16777115.
- Shizuku `dev.rikka.shizuku:api:provider:13.1.5` (Maven Central).
  States: UNAVAILABLE/NEEDS_PERMISSION/DENIED_PERMANENTLY/READY/
  UNSUPPORTED_VERSION, manual reconnect, binder-death → reconnect prompt.
- Sessions: `CREATING → READY → RUNNING → STOPPING → STOPPED`, plus
  `ERROR` (open/launch failure; requires close + reopen). Each session has
  its own encoder surface → preview and recording share one capture
  engine. IDs are never reused while open.
- Input safety: every event carries the session display ID
  (`InputBridge.setDisplayId`); no physical-display fallback anywhere;
  `findText` errors instead of guessing coordinates.
- Video: `VideoStreamer` codec `auto/avc/hevc` + bitrate/fps params,
  `MediaCodecList` capability report (`codecCaps`), HEVC→AVC fallback.
  Recording muxes the streamed samples app-side (`MediaMuxer` MP4,
  pause via pts offset). Decoder is mime-generic.
- OCR: PaddleOCR retained (no second engine). `OcrManager` states
  UNINITIALIZED/INITIALIZING/READY/FAILED with per-file missing-asset
  detail; models download on demand into app-private storage; native libs
  copied from the own APK (full-OCR builds only).
- Daemon lifecycle: `DaemonLifecycle` state machine (IDLE/BINDING/STARTING/
  RUNNING/RECOVERING/ERROR) with 20s/30s/90s deadlines — no step can stick
  forever; duplicates suppressed; disconnects land in ERROR; transitions
  journaled (`DiagLog`) and logged to logcat (`PhantomDaemon`). Attempt
  generations tag every bind/start/recover: stale callbacks cannot corrupt
  a newer attempt, while a late-but-verified endpoint is adopted
  (`adoptRunning`) instead of leaving a live daemon reported dead.
  Restart reuses a healthy daemon; `refresh()` re-verifies hello on every
  resume (binder-ok/RPC-dead distinguished). `hello` parsing isolated in
  `HelloCheck` (unproven listeners are never called legacy — only recovery
  may claim that, through `RecoveryDecider`).
- Port claim by handshake (`PortClaim` + `EngineHost` result codes
  SERVING/ADOPTED/LEGACY_HOLDER): probe before bind (healthy Phantom
  adopted, no new socket), bind when free, verified legacy/foreign
  reported, one retry on mid-claim races. `hello` carries the daemon pid
  (shown on Home/MCP/diagnostics). Recovery is hello-first (adopt
  healthy), kernel tables best-effort, then narrow pidfile+cmdline
  cleanup (`PidfileCleanup`, tested) with post-kill verification; kills
  are never based on port alone and user-facing messages contain no
  manual-adb kill commands. When the holder cannot be identified, recovery
  serves on verified-free alternate port 43111 (`FallbackDecider`,
  tested) instead of demanding a reboot; all UI/service clients follow
  `Rpc.activePort`, and MCP instructions render the live port.
- MCP: installable `phantom-mcp` console script (`mcp/pyproject.toml`,
  install-verified with 27 tools); path-free `opencode.json.example`;
  Android setup screen carries no repo path and separates daemon status,
  control-API status, and off-device OpenCode connectivity honestly.
- Local API: default model `shadowauto-mcp` → `phantom-automation`
  (`SHADOWAUTO_MODEL_ID` restores the old ID); honest-handoff behavior
  kept, inference stays outside the endpoint (no self-calls).
- UI replaced: Home, Sessions, live viewer, Launcher, MCP setup,
  Settings, Logs. The legacy multi-task pager/goal UI was intentionally
  dropped in favor of sessions (autonomous `startTask` remains via RPC).

## Verification (actual)

- CI `Build APK`: `phantom-app:assembleDebug` + legacy APKs green;
  `:phantom-app:testDebugUnitTest` green (tar/provisioning, RPC registry,
  stale-daemon parsing JVM tests).
- CI identity gate: `aapt2 dump badging` asserts
  `application-label:'Phantom'`, launcher `label='Phantom'`, plus icon /
  nav / menu resources present in the APK.
- Python: 15/15 unittest green (RPC framing incl. new methods, SSE,
  model list, dispatch/backend/MCP compat). MCP stdio handshake
  re-verified: 27/27 tools listed.
- APK dex check: Phantom + Shizuku classes present in `phantom-app-debug`.

## Genuine remaining limitations

1. No device in this environment: on-device behavior (UserService start,
   virtual-display creation under Shizuku, input injection, HEVC encoder
   presence, OCR native load, recording output, upgrade install, and the
   new stale-daemon recovery path) is **not verified** — needs a
   Shizuku-capable device. The recovery logic's parsing/decisions are
   JVM-tested; its shell-command execution is not.
2. `Shizuku.bindUserService`/`unbindUserService` signatures and
   `UserServiceArgs` options were matched to 13.1.5 at compile time;
   runtime behavior (daemon mode, process naming) needs device proof.
3. `targetSdk 36` with Shizuku 13.1.5 (2023): compiles; runtime on
   Android 14+ restrictions (foreground-service types declared:
   `dataSync`) needs device proof.
4. Fast CI build (`-PenablePaddleLiteOcr=false`) ships no OCR native
   libs: full-OCR compile (NDK/CMake, ~1 GB downloads) not yet run in CI.
5. Without Shizuku the app is limited mode (status/diagnostics only);
   no MediaProjection fallback was added (would be a new feature).
6. Multi-session simultaneity depends on the device/OEM; not claimed.
7. Virtual displays are separate rendering/input targets, not security
   isolation — documented in code and README.
