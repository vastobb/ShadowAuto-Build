"""ShadowAuto Shell RPC client (NDJSON over TCP).

Speaks the exact framing used by android-shell RpcServer and the
controller-app RpcClient: one JSON object per line ({jsonrpc: 2.0, id,
method, params}\\n), one JSON reply line per request, plus unsolicited
{"method": "event", ...} lines pushed to every connected client.

On-device the server binds 127.0.0.1:43110. From this container reach it
through ADB port forwarding (different network namespaces)::

    adb forward tcp:43110 tcp:43110

No custom relay is needed. This module only uses the Python standard
library so the MCP server and model service share it with no extra deps.
"""

from __future__ import annotations

import itertools
import json
import os
import socket
from dataclasses import dataclass


DEFAULT_HOST = os.environ.get("SHADOWAUTO_HOST", "127.0.0.1")
DEFAULT_PORT = int(os.environ.get("SHADOWAUTO_PORT", "43110"))
DEFAULT_TIMEOUT = float(os.environ.get("SHADOWAUTO_RPC_TIMEOUT", "15"))

UNKNOWN_METHOD = -32601


class ShadowAutoError(Exception):
    """Raised for transport failures and JSON-RPC error replies."""


@dataclass
class RpcResult:
    raw: dict

    @property
    def result(self):
        return self.raw.get("result")


class ShellRpc:
    """One-shot NDJSON client: a fresh TCP connection per call."""

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT,
                 timeout: float = DEFAULT_TIMEOUT):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._ids = itertools.count(1)

    # -- low level ----------------------------------------------------
    def call(self, method: str, params: dict | None = None,
             timeout: float | None = None) -> RpcResult:
        request_id = next(self._ids)
        payload = json.dumps({
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params or {},
        }) + "\n"
        deadline = (timeout or self.timeout)
        try:
            sock = socket.create_connection((self.host, self.port), timeout=deadline)
        except OSError as exc:
            raise ShadowAutoError(
                f"shell unreachable at {self.host}:{self.port}: {exc}. "
                "Start the shell on the device and run: "
                f"adb forward tcp:{self.port} tcp:{self.port}"
            ) from exc
        try:
            sock.settimeout(deadline)
            sock.sendall(payload.encode("utf-8"))
            # The server first pushes an unsolicited status object (no id)
            # and LogHub may interleave {"method":"event"} lines: skip
            # everything that is not our reply id.
            buf = b""
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    raise ShadowAutoError("shell closed the connection before replying")
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        message = json.loads(line.decode("utf-8"))
                    except ValueError:
                        continue
                    if not isinstance(message, dict):
                        continue
                    if message.get("id") != request_id:
                        continue  # status push or async event line
                    if "error" in message and message["error"] is not None:
                        err = message["error"]
                        code = err.get("code") if isinstance(err, dict) else None
                        text = err.get("message") if isinstance(err, dict) else str(err)
                        if code == UNKNOWN_METHOD:
                            raise ShadowAutoError(
                                f"shell does not know method '{method}' ({text}). "
                                "Install the APK built from this repo (McpSessions RPC)."
                            )
                        raise ShadowAutoError(f"shell error for '{method}': {text}")
                    return RpcResult(raw=message)
        except socket.timeout as exc:
            raise ShadowAutoError(
                f"shell at {self.host}:{self.port} timed out waiting for '{method}' "
                f"after {deadline}s"
            ) from exc
        finally:
            try:
                sock.close()
            except OSError:
                pass

    # -- connection check ---------------------------------------------
    def check(self, timeout: float = 5) -> tuple[bool, str]:
        try:
            reply = self.call("ping", {}, timeout=timeout)
            if isinstance(reply.result, dict) and reply.result.get("value") == "pong":
                return True, f"shell pong from {self.host}:{self.port}"
            return True, f"shell answered ping at {self.host}:{self.port}"
        except ShadowAutoError as exc:
            return False, str(exc)

    def hello(self, timeout: float = 5) -> dict:
        """Identity handshake: {app, version, methods}. Raises on legacy daemons."""
        try:
            reply = self.call("hello", {}, timeout=timeout)
        except ShadowAutoError as exc:
            if "does not know method" in str(exc):
                raise ShadowAutoError(
                    "stale legacy shell answers (no hello/listApps). Kill it: "
                    "adb shell \"kill $(cat /data/local/tmp/silent-auto.pid)\" "
                    "then Start the Phantom daemon."
                ) from exc
            raise
        result = reply.result
        if not isinstance(result, dict) or result.get("app") != "phantom":
            raise ShadowAutoError(f"foreign service on {self.host}:{self.port}: {result!r}")
        return result

    def require_phantom(self, required: tuple = ("openSession", "listApps", "launchApp",
                                                  "dumpUi", "screenshot"),
                         timeout: float = 5) -> dict:
        """hello + required-method check. Never returns a fake success."""
        info = self.hello(timeout=timeout)
        methods = info.get("methods", [])
        missing = [m for m in required if m not in methods]
        if missing:
            raise ShadowAutoError(
                f"daemon protocol v{info.get('version')} is missing: {missing}. "
                "Reinstall the current Phantom APK.")
        return info

    # -- existing RPC ---------------------------------------------------
    def ping(self) -> RpcResult:
        return self.call("ping", {})

    def status(self) -> RpcResult:
        return self.call("status", {})

    def logs(self) -> RpcResult:
        return self.call("logs", {})

    def dump_ui(self, display_id: int = 0, width: int = 0, height: int = 0,
                mode: str = "simple", session_id: str = "") -> RpcResult:
        params: dict = {"displayId": display_id, "width": width,
                         "height": height, "mode": mode or "simple"}
        if session_id:
            params["sessionId"] = session_id
        return self.call("dumpUi", params)

    def start_task(self, goal: str, api_key: str = "", api_base: str = "",
                   model: str = "", task_id: str = "") -> RpcResult:
        return self.call("startTask", {
            "taskId": task_id, "goal": goal, "apiKey": api_key,
            "apiBase": api_base, "model": model,
        }, timeout=max(self.timeout, 30))

    def stop_task(self, task_id: str = "") -> RpcResult:
        return self.call("stopTask", {"taskId": task_id} if task_id else {})

    # -- MCP session RPC (needs the APK built from this repo) -----------
    def open_session(self, session_id: str = "", width: int = 0,
                     height: int = 0, codec: str = "auto") -> RpcResult:
        return self.call("openSession", {"sessionId": session_id,
                                         "width": width, "height": height,
                                         "codec": codec or "auto"},
                         timeout=max(self.timeout, 30))

    def close_session(self, session_id: str = "") -> RpcResult:
        return self.call("closeSession", {"sessionId": session_id})

    def codec_caps(self) -> RpcResult:
        return self.call("codecCaps", {})

    def set_recording_state(self, session_id: str, state: str) -> RpcResult:
        return self.call("setRecordingState", {"sessionId": session_id,
                                               "state": state})

    def list_apps(self) -> RpcResult:
        return self.call("listApps", {}, timeout=max(self.timeout, 30))

    def launch_app(self, session_id: str, package_name: str) -> RpcResult:
        return self.call("launchApp", {"sessionId": session_id,
                                       "packageName": package_name},
                         timeout=max(self.timeout, 45))

    def screenshot(self, session_id: str = "", display_id: int = 0) -> RpcResult:
        return self.call("screenshot", {"sessionId": session_id,
                                        "displayId": display_id},
                         timeout=max(self.timeout, 30))

    def tap(self, session_id: str, x: int, y: int) -> RpcResult:
        return self.call("tap", {"sessionId": session_id, "x": x, "y": y})

    def long_press(self, session_id: str, x: int, y: int,
                   duration_ms: int = 550) -> RpcResult:
        return self.call("longPress", {"sessionId": session_id, "x": x,
                                       "y": y, "durationMs": duration_ms})

    def swipe(self, session_id: str, start_x: int, start_y: int, end_x: int,
              end_y: int, duration_ms: int = 420) -> RpcResult:
        return self.call("swipe", {"sessionId": session_id, "startX": start_x,
                                   "startY": start_y, "endX": end_x,
                                   "endY": end_y, "durationMs": duration_ms})

    def scroll(self, session_id: str, direction: str = "down",
               distance: int = 0) -> RpcResult:
        return self.call("scroll", {"sessionId": session_id,
                                    "direction": direction, "distance": distance})

    def press_key(self, session_id: str, key: str) -> RpcResult:
        return self.call("pressKey", {"sessionId": session_id, "key": key})

    def press_back(self, session_id: str) -> RpcResult:
        return self.call("pressBack", {"sessionId": session_id})

    def input_text(self, session_id: str, text: str) -> RpcResult:
        return self.call("inputText", {"sessionId": session_id, "text": text},
                         timeout=max(self.timeout, 30))

    def tap_target(self, session_id: str, target_index: int) -> RpcResult:
        return self.call("tapTarget", {"sessionId": session_id,
                                       "targetIndex": target_index})

    def focus_input(self, session_id: str, input_index: int = 0,
                    query: str = "") -> RpcResult:
        return self.call("focusInput", {"sessionId": session_id,
                                        "inputIndex": input_index, "query": query})

    def clear_text(self, session_id: str) -> RpcResult:
        return self.call("clearText", {"sessionId": session_id})

    # -- OCR RPC (Phantom engine; -32601 on legacy shells) ------------------
    def ocr_status(self) -> RpcResult:
        return self.call("ocrStatus", {})

    def ocr_configure(self, model_dir: str, label_path: str,
                      lib_dir: str) -> RpcResult:
        return self.call("ocrConfigure", {"modelDir": model_dir,
                                          "labelPath": label_path,
                                          "libDir": lib_dir})

    def ocr_recognize(self, session_id: str) -> RpcResult:
        return self.call("ocrRecognize", {"sessionId": session_id},
                         timeout=max(self.timeout, 45))

    def find_text(self, session_id: str, query: str) -> RpcResult:
        return self.call("findText", {"sessionId": session_id, "query": query},
                         timeout=max(self.timeout, 45))
