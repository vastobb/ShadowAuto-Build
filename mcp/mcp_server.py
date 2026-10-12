#!/usr/bin/env python3
"""ShadowAuto MCP server (stdio) for OpenCode.

OpenCode starts this over the MCP stdio transport and calls the tools
below to drive an Android virtual display step by step: OpenCode is the
brain (screen inspection -> decision -> tool call), the on-device shell
is the hands (display-targeted input injection, never the physical
screen).

Transport: stdio only. Configuration via environment:

    SHADOWAUTO_HOST  shell TCP host (default 127.0.0.1, via adb forward)
    SHADOWAUTO_PORT  shell TCP port (default 43110)
    SHADOWAUTO_RPC_TIMEOUT  seconds per RPC (default 15)

Typical OpenCode flow:
    1. check_connection  (fail fast with a useful message)
    2. open_session -> {sessionId, displayId, width, height}
    3. list_apps / launch_app
    4. loop: dump_ui (or screenshot) -> tap/tap_target/swipe/scroll/
       focus_input/input_text/press_key/press_back -> observe
    5. close_session (or stop_task for autonomous tasks)
"""

from __future__ import annotations

import json
import os

from mcp.server.mcpserver import MCPServer

from shadowauto_backend import DEFAULT_PORT, ShadowAutoError, ShellRpc

SERVER_NAME = "shadowauto"

_rpc: ShellRpc | None = None


def rpc() -> ShellRpc:
    global _rpc
    if _rpc is None:
        _rpc = ShellRpc(
            host=os.environ.get("SHADOWAUTO_HOST", "127.0.0.1"),
            port=int(os.environ.get("SHADOWAUTO_PORT", str(DEFAULT_PORT))),
            timeout=float(os.environ.get("SHADOWAUTO_RPC_TIMEOUT", "15")),
        )
    return _rpc


def _dump(value) -> str:
    """Compact JSON for tool results (single text payload)."""
    if isinstance(value, dict) and "result" in value:
        value = value["result"]
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _call(fn, *args, **kwargs) -> str:
    try:
        return _dump(fn(*args, **kwargs).raw)
    except ShadowAutoError as exc:
        return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)


mcp = MCPServer(name=SERVER_NAME)


@mcp.tool(description="Check TCP reachability of the on-device shell (ping).")
def check_connection() -> str:
    ok, message = rpc().check()
    return json.dumps({"ok": ok, "message": message}, ensure_ascii=False)


@mcp.tool(description="Shell status: autonomous tasks plus open MCP sessions.")
def status() -> str:
    return _call(rpc().status)


@mcp.tool(description="Recent shell log lines (200-line ring buffer).")
def logs() -> str:
    return _call(rpc().logs)


@mcp.tool(description="Open a direct-control virtual-display session (no AI loop). Returns sessionId/displayId/size.")
def open_session(session_id: str = "", width: int = 0, height: int = 0) -> str:
    return _call(rpc().open_session, session_id, width, height)


@mcp.tool(description="Close one MCP session, or all when session_id is empty.")
def close_session(session_id: str = "") -> str:
    return _call(rpc().close_session, session_id)


@mcp.tool(description="Accessibility UI dump for a session (windows, inputs, targets). Prefer over screenshots for tapping.")
def dump_ui(session_id: str = "", mode: str = "simple") -> str:
    return _call(rpc().dump_ui, 0, 0, 0, mode or "simple", session_id)


@mcp.tool(description="Still PNG screenshot of the session virtual display (base64). Use dump_ui for coordinates first.")
def screenshot(session_id: str = "") -> str:
    return _call(rpc().screenshot, session_id, 0)


@mcp.tool(description="List launchable apps on the device (name + packageName).")
def list_apps() -> str:
    return _call(rpc().list_apps)


@mcp.tool(description="Launch an app package onto the session virtual display (not the physical screen).")
def launch_app(session_id: str, package_name: str) -> str:
    return _call(rpc().launch_app, session_id, package_name)


@mcp.tool(description="Tap display-local coordinates x,y on the session virtual display.")
def tap(session_id: str, x: int, y: int) -> str:
    return _call(rpc().tap, session_id, x, y)


@mcp.tool(description="Long-press display-local coordinates on the session virtual display.")
def long_press(session_id: str, x: int, y: int, duration_ms: int = 550) -> str:
    return _call(rpc().long_press, session_id, x, y, duration_ms)


@mcp.tool(description="Drag/swipe between display-local points on the session virtual display.")
def swipe(session_id: str, start_x: int, start_y: int, end_x: int, end_y: int,
          duration_ms: int = 420) -> str:
    return _call(rpc().swipe, session_id, start_x, start_y, end_x, end_y, duration_ms)


@mcp.tool(description="Scroll content (direction up/down/left/right) via swipe on the session display.")
def scroll(session_id: str, direction: str = "down", distance: int = 0) -> str:
    return _call(rpc().scroll, session_id, direction, distance)


@mcp.tool(description="Press a key on the session display: back, enter, search, tab, dpad_up/down/left/right/center.")
def press_key(session_id: str, key: str) -> str:
    return _call(rpc().press_key, session_id, key)


@mcp.tool(description="Press Back on the session virtual display.")
def press_back(session_id: str) -> str:
    return _call(rpc().press_back, session_id)


@mcp.tool(description="Type text into the focused field (accessibility set-text, else clipboard paste). Focus first.")
def input_text(session_id: str, text: str) -> str:
    return _call(rpc().input_text, session_id, text)


@mcp.tool(description="Tap an accessibility target by targetIndex from dump_ui (prefers accessibility click).")
def tap_target(session_id: str, target_index: int) -> str:
    return _call(rpc().tap_target, session_id, target_index)


@mcp.tool(description="Focus an input field by inputIndex or text query from dump_ui.")
def focus_input(session_id: str, input_index: int = 0, query: str = "") -> str:
    return _call(rpc().focus_input, session_id, input_index, query)


@mcp.tool(description="Clear the focused text field.")
def clear_text(session_id: str) -> str:
    return _call(rpc().clear_text, session_id)


@mcp.tool(description="Start an autonomous AI-driven task (shell runs its own LLM loop with the given OpenAI endpoint). For OpenCode-driven control use open_session + action tools instead.")
def start_task(goal: str, api_key: str = "", api_base: str = "",
               model: str = "", task_id: str = "") -> str:
    return _call(rpc().start_task, goal, api_key, api_base, model, task_id)


@mcp.tool(description="Cancel one autonomous task, or all when task_id is empty.")
def stop_task(task_id: str = "") -> str:
    return _call(rpc().stop_task, task_id)


if __name__ == "__main__":
    mcp.run(transport="stdio")
