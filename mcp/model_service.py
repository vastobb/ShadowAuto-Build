#!/usr/bin/env python3
"""Local OpenAI-compatible model service for ShadowAuto.

Implements the two endpoints the existing Android code actually calls:

* controller-app AiConfigScreen: ``GET {base}/models`` (Bearer key,
  ``{"data": [{"id": ...}]}``) and ``POST {base}/chat/completions`` with
  ``{"stream": false, "messages": [{"role": "user", "content": "Say OK."}]}``
  expecting ``choices[0].message.content`` non-empty.
* android-shell AiClient: ``POST {base}/chat/completions`` with
  ``{"stream": true, "tool_choice": "auto", "tools": [...]}`` expecting a
  ``text/event-stream`` SSE reply (``data: {...}`` lines + ``data: [DONE]``)
  carrying ``choices[0].delta.content`` / ``delta.tool_calls`` fragments.

Configure the controller app with::

    API URL: http://127.0.0.1:8410/v1
    Model:   phantom-automation (SHADOWAUTO_MODEL_ID override keeps shadowauto-mcp working)

Behavior:

* ``GET /v1/models`` returns the placeholder model id. The controller test
  button (non-streaming ``Say OK``) is answered with live shell state
  appended, so the model entry is backed by a real backend consultation
  instead of a hard-coded string.
* Streaming requests (the shell autonomous loop) receive a valid SSE
  stream whose content reflects live backend state, followed by a
  ``finish`` tool call that ends the task with an explicit reason. The
  local model performs no LLM inference: for step-by-step automation use
  the MCP server (OpenCode is the brain); for autonomous tasks point the
  controller at an external OpenAI-compatible endpoint. This keeps model
  inference separate from the Android action tools and avoids any
  circular call where the endpoint would invoke itself.
* Binds loopback only by default; never expose to the LAN.
* API key enforcement is optional (``SHADOWAUTO_API_KEY``): when empty,
  any key (including empty) is accepted; when set, the Bearer token must
  match. No external provider credentials are stored here.

Environment:

    SHADOWAUTO_MODEL_HOST  bind address, default 127.0.0.1 (keep loopback)
    SHADOWAUTO_MODEL_PORT  port, default 8410
    SHADOWAUTO_MODEL_ID    placeholder model id, default phantom-automation
    SHADOWAUTO_API_KEY     optional required Bearer key, default ""
    SHADOWAUTO_HOST / SHADOWAUTO_PORT / SHADOWAUTO_RPC_TIMEOUT
        backend shell RPC settings (see shadowauto_backend.py)
"""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from shadowauto_backend import ShellRpc

MODEL_ID = os.environ.get("SHADOWAUTO_MODEL_ID", "phantom-automation")
REQUIRED_KEY = os.environ.get("SHADOWAUTO_API_KEY", "")


def backend_summary(timeout: float = 3.0) -> str:
    """One-line live shell state for embedding in replies (never raises)."""
    try:
        raw = ShellRpc(timeout=timeout).call("status", {}, timeout=timeout).raw
        result = raw.get("result", {}) if isinstance(raw, dict) else {}
        state = result.get("state", "?") if isinstance(result, dict) else "?"
        display = result.get("displayId", "?") if isinstance(result, dict) else "?"
        tasks = result.get("tasks", []) if isinstance(result, dict) else []
        sessions = result.get("mcpSessions", []) if isinstance(result, dict) else []
        return (f"shell state={state} displayId={display} "
                f"tasks={len(tasks)} mcpSessions={len(sessions)}")
    except Exception as exc:  # backend down: degrade, do not fail the reply
        return f"shell unreachable ({exc})"


LOCAL_MODE_NOTE = (
    "Local model: no LLM inference here. "
    "For step-by-step control use the MCP server (open_session/dump_ui/tap/...); "
    "for autonomous tasks configure an external OpenAI-compatible endpoint."
)


class Handler(BaseHTTPRequestHandler):
    server_version = "PhantomModel/1.0"

    # -- helpers ------------------------------------------------------
    def _send_json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            return None

    def _auth_ok(self) -> bool:
        if not REQUIRED_KEY:
            return True
        auth = self.headers.get("Authorization") or ""
        return auth == f"Bearer {REQUIRED_KEY}"

    def log_message(self, *args) -> None:  # quieter stderr
        pass

    # -- routes --------------------------------------------------------
    def do_GET(self) -> None:
        path = urllib.parse.urlparse(self.path).path
        if path in ("/v1/models", "/v1/models/"):
            self._send_json(200, {
                "object": "list",
                "data": [{
                    "id": MODEL_ID,
                    "object": "model",
                    "created": int(time.time()),
                    "owned_by": "shadowauto-local",
                }],
            })
            return
        self._send_json(404, {"error": {"message": f"unknown path: {path}",
                                        "type": "not_found"}})

    def do_POST(self) -> None:
        path = urllib.parse.urlparse(self.path).path
        if path not in ("/v1/chat/completions", "/v1/chat/completions/"):
            self._send_json(404, {"error": {"message": f"unknown path: {path}",
                                            "type": "not_found"}})
            return
        if not self._auth_ok():
            self._send_json(401, {"error": {"message": "invalid API key",
                                            "type": "auth"}})
            return
        body = self._read_json()
        if body is None:
            self._send_json(400, {"error": {"message": "invalid JSON body",
                                            "type": "bad_request"}})
            return
        model = body.get("model", "")
        if model and model != MODEL_ID:
            self._send_json(400, {"error": {
                "message": f"unknown model '{model}'. Available: ['{MODEL_ID}']",
                "type": "model_not_found"}})
            return
        accept_sse = "text/event-stream" in (self.headers.get("Accept") or "")
        if body.get("stream") is True or accept_sse or body.get("tools"):
            self._handle_streaming(body)
        else:
            self._handle_simple(body)

    # -- chat backends --------------------------------------------------
    def _handle_simple(self, body: dict) -> None:
        summary = backend_summary()
        content = f"OK ({summary}). {LOCAL_MODE_NOTE}"
        self._send_json(200, {
            "id": f"chatcmpl-local-{int(time.time() * 1000)}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": MODEL_ID,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0,
                      "total_tokens": 0},
        })

    def _handle_streaming(self, body: dict) -> None:
        summary = backend_summary()
        completion_id = f"chatcmpl-local-{int(time.time() * 1000)}"
        created = int(time.time())
        content = (f"Local model here ({summary}). {LOCAL_MODE_NOTE} "
                   "Ending this autonomous task without device actions.")

        def chunk(delta: dict, finish: str | None) -> bytes:
            return ("data: " + json.dumps({
                "id": completion_id,
                "object": "chat.completion.chunk",
                "created": created,
                "model": MODEL_ID,
                "choices": [{"index": 0, "delta": delta,
                             "finish_reason": finish}],
            }) + "\n\n").encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        # Content tokens first (shell logs them as ai deltas).
        half = max(1, len(content) // 2)
        self.wfile.write(chunk({"role": "assistant",
                                "content": content[:half]}, None))
        self.wfile.write(chunk({"content": content[half:]}, None))
        # Then the finish tool call (fragmented form also accepted by AiClient).
        self.wfile.write(chunk({"tool_calls": [{
            "index": 0,
            "id": "call_local_finish",
            "type": "function",
            "function": {
                "name": "finish",
                "arguments": json.dumps({
                    "reason": f"local-model handoff: {summary}; "
                              "no device actions taken; use MCP session tools "
                              "or an external LLM for autonomous tasks",
                }),
            },
        }]}, "tool_calls"))
        self.wfile.write(b"data: [DONE]\n\n")


def build_server(host: str, port: int) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server


def main() -> None:
    global MODEL_ID
    parser = argparse.ArgumentParser(description="Phantom local model service")
    parser.add_argument("--host", default=os.environ.get("SHADOWAUTO_MODEL_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("SHADOWAUTO_MODEL_PORT", "8410")))
    parser.add_argument("--model", default=MODEL_ID)
    args = parser.parse_args()
    MODEL_ID = args.model
    if args.host not in ("127.0.0.1", "localhost", "::1"):
        print(f"WARNING: binding non-loopback {args.host}; default is 127.0.0.1")
    server = build_server(args.host, args.port)
    print(f"Phantom local model service on http://{args.host}:{args.port}/v1 "
          f"model={MODEL_ID}")
    print("Controller API URL: "
          f"http://{args.host}:{args.port}/v1  (device needs: adb reverse "
          f"tcp:{args.port} tcp:{args.port})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
