"""Protocol tests for shadowauto_backend.ShellRpc (no device needed).

A mock TCP server speaks the shell's NDJSON framing: one unsolicited
status line on connect, then one JSON reply per request id, with an
interleaved async event line to prove the client skips non-matching ids.
"""
from __future__ import annotations

import json
import socket
import threading
import unittest

from shadowauto_backend import UNKNOWN_METHOD, ShadowAutoError, ShellRpc


class MockShell(threading.Thread):
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
            # Unsolicited status push, like the real RpcServer on connect.
            f.write(b'{"jsonrpc":"2.0","result":{"state":"idle"}}\n')
            f.flush()
            for line in f:
                try:
                    req = json.loads(line.decode())
                except ValueError:
                    continue
                rid, method = req.get("id"), req.get("method")
                # Interleaved async event the client must skip.
                f.write(b'{"jsonrpc":"2.0","method":"event",'
                        b'"params":{"type":"log","message":"hi"}}\n')
                f.write((json.dumps(self.handler(method, req.get("params", {}), rid))
                         + "\n").encode())
                f.flush()


def echo_handler(method, params, rid):
    if method == "ping":
        return {"jsonrpc": "2.0", "id": rid, "result": {"value": "pong"}}
    if method == "nope":
        return {"jsonrpc": "2.0", "id": rid,
                "error": {"code": UNKNOWN_METHOD, "message": "unknown method: nope"}}
    return {"jsonrpc": "2.0", "id": rid, "result": {"method": method, "params": params}}


class BackendProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = MockShell(echo_handler)
        cls.server.start()
        cls.server.ready.wait(5)

    def rpc(self, **kw):
        kw.setdefault("timeout", 5)
        return ShellRpc(host="127.0.0.1", port=self.server.port, **kw)

    def test_ping_and_check(self):
        ok, message = self.rpc().check()
        self.assertTrue(ok, message)
        self.assertIn("pong", message)

    def test_call_skips_status_push_and_events(self):
        reply = self.rpc().call("dumpUi", {"displayId": 0})
        self.assertEqual(reply.result["method"], "dumpUi")

    def test_unknown_method_hint(self):
        with self.assertRaises(ShadowAutoError) as ctx:
            self.rpc().call("nope", {})
        self.assertIn("McpSessions", str(ctx.exception))

    def test_phantom_method_passthrough(self):
        reply = self.rpc().open_session("s1", 720, 1280, "hevc")
        self.assertEqual(reply.result["method"], "openSession")
        self.assertEqual(reply.result["params"]["codec"], "hevc")
        caps = self.rpc().codec_caps()
        self.assertEqual(caps.result["method"], "codecCaps")
        found = self.rpc().find_text("s1", "Search")
        self.assertEqual(found.result["params"]["query"], "Search")
        configured = self.rpc().ocr_configure("/m", "/l.txt", "/lib")
        self.assertEqual(configured.result["params"]["libDir"], "/lib")
        rec = self.rpc().set_recording_state("s1", "recording")
        self.assertEqual(rec.result["params"]["state"], "recording")

    def test_unreachable_error_mentions_adb_forward(self):
        dead = ShellRpc(host="127.0.0.1", port=1, timeout=1)
        with self.assertRaises(ShadowAutoError) as ctx:
            dead.call("ping", {})
        self.assertIn("adb forward", str(ctx.exception))

    def test_timeout(self):
        def slow(method, params, rid):
            import time
            time.sleep(2)
            return {"jsonrpc": "2.0", "id": rid, "result": {}}
        server = MockShell(slow)
        server.start()
        server.ready.wait(5)
        with self.assertRaises(ShadowAutoError) as ctx:
            ShellRpc(host="127.0.0.1", port=server.port, timeout=0.3).call("ping", {})
        self.assertIn("timed out", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
