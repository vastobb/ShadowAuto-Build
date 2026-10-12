"""Handshake against the real protocol: phantom hello, legacy shell,
incompatible daemon, session routing. No device needed (mock NDJSON)."""
from __future__ import annotations

import json
import socket
import threading
import unittest

from shadowauto_backend import ShadowAutoError, ShellRpc

METHODS = ["hello", "ping", "status", "logs", "dumpUi", "openSession",
           "closeSession", "listApps", "launchApp", "screenshot", "tap",
           "status"]


class MockDaemon(threading.Thread):
    def __init__(self, handler):
        super().__init__(daemon=True)
        self.handler = handler
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.port = self.sock.getsockname()[1]
        self.sock.listen(5)
        self.ready = threading.Event()

    def run(self):
        self.ready.set()
        while True:
            conn, _ = self.sock.accept()
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _serve(self, conn):
        with conn:
            f = conn.makefile("rwb")
            f.write(b'{"jsonrpc":"2.0","result":{"app":"phantom","protocol":2}}\n')
            f.flush()
            for line in f:
                try:
                    req = json.loads(line.decode())
                except ValueError:
                    continue
                rid, method = req.get("id"), req.get("method")
                f.write((json.dumps(self.handler(method, req.get("params", {}), rid))
                         + "\n").encode())
                f.flush()


def phantom_handler(method, params, rid):
    if method == "hello":
        return {"jsonrpc": "2.0", "id": rid,
                "result": {"app": "phantom", "version": 2, "methods": METHODS}}
    if method == "openSession":
        return {"jsonrpc": "2.0", "id": rid,
                "result": {"sessionId": params.get("sessionId") or "mcp-1",
                           "displayId": 42, "state": "READY"}}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": rid, "result": {"value": "pong"}}
    return {"jsonrpc": "2.0", "id": rid, "result": {"method": method}}


def legacy_handler(method, params, rid):
    if method in ("hello", "listApps"):
        return {"jsonrpc": "2.0", "id": rid,
                "error": {"code": -32601, "message": f"unknown method: {method}"}}
    return {"jsonrpc": "2.0", "id": rid, "result": {}}


def foreign_handler(method, params, rid):
    if method == "hello":
        return {"jsonrpc": "2.0", "id": rid,
                "result": {"app": "something-else", "version": 9, "methods": []}}
    return {"jsonrpc": "2.0", "id": rid, "result": {}}


class HandshakeTest(unittest.TestCase):
    def _rpc(self, server):
        return ShellRpc(host="127.0.0.1", port=server.port, timeout=5)

    def _server(self, handler):
        server = MockDaemon(handler)
        server.start()
        server.ready.wait(5)
        return server

    def test_phantom_hello_passes(self):
        info = self._rpc(self._server(phantom_handler)).require_phantom()
        self.assertEqual(info["app"], "phantom")
        self.assertIn("listApps", info["methods"])

    def test_session_creation_routes(self):
        reply = self._rpc(self._server(phantom_handler)).open_session("s9", 720, 1280, "avc")
        self.assertEqual(reply.result["sessionId"], "s9")
        self.assertEqual(reply.result["displayId"], 42)

    def test_legacy_daemon_is_named_not_hidden(self):
        with self.assertRaises(ShadowAutoError) as ctx:
            self._rpc(self._server(legacy_handler)).require_phantom()
        message = str(ctx.exception)
        self.assertIn("legacy", message)
        self.assertIn("kill", message)

    def test_incompatible_app_is_refused(self):
        with self.assertRaises(ShadowAutoError) as ctx:
            self._rpc(self._server(foreign_handler)).require_phantom()
        self.assertIn("foreign", str(ctx.exception))

    def test_missing_methods_rejected(self):
        def thin(method, params, rid):
            if method == "hello":
                return {"jsonrpc": "2.0", "id": rid,
                        "result": {"app": "phantom", "version": 2,
                                   "methods": ["hello", "ping"]}}
            return {"jsonrpc": "2.0", "id": rid, "result": {}}
        with self.assertRaises(ShadowAutoError) as ctx:
            self._rpc(self._server(thin)).require_phantom()
        self.assertIn("listApps", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
