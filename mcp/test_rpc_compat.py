"""UI/backend compatibility: every RPC method the Python layer calls
must be registered in the daemon that Phantom actually launches
(phantom-app/.../engine/RpcServer.java). Catches renames and drift
without a device.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RPC_SERVER = (REPO / "phantom-app" / "src" / "main" / "java"
              / "com" / "phantom" / "automation" / "engine" / "RpcServer.java")
BACKEND = Path(__file__).resolve().parent / "shadowauto_backend.py"
MCP_SERVER = Path(__file__).resolve().parent / "mcp_server.py"


def registered_methods() -> set[str]:
    text = RPC_SERVER.read_text()
    return set(re.findall(r'"([a-zA-Z]+)"\.equals\(method\)', text))


def backend_called_methods() -> set[str]:
    text = BACKEND.read_text()
    return set(re.findall(r'self\.call\("([a-zA-Z]+)"', text))


class RpcCompatTest(unittest.TestCase):
    def test_dispatch_table_present(self):
        methods = registered_methods()
        for required in ("hello", "openSession", "closeSession", "listApps",
                         "launchApp", "dumpUi", "screenshot", "status", "logs",
                         "ping", "tap", "inputText", "ocrRecognize", "findText",
                         "codecCaps", "startTask", "stopTask"):
            self.assertIn(required, methods, f"RpcServer lacks {required}")

    def test_backend_methods_all_registered(self):
        missing = backend_called_methods() - registered_methods()
        self.assertEqual(missing, set(),
                         f"backend calls unregistered methods: {missing}")

    def test_protocol_registry_matches_dispatch(self):
        text = (REPO / "phantom-app" / "src" / "main" / "java" / "com"
                / "phantom" / "automation" / "engine" / "RpcProtocol.java").read_text()
        listed = set(re.findall(r'"([a-zA-Z]+)"', text.split("METHODS = {", 1)[1].split("};", 1)[0]))
        self.assertEqual(listed, registered_methods(),
                         "RpcProtocol.METHODS drifted from RpcServer dispatch")

    def test_mcp_server_names_real_backend_methods(self):
        backend = BACKEND.read_text()
        mcp = MCP_SERVER.read_text()
        for tool in re.findall(r'rpc\(\)\.([a-z_]+)', mcp):
            snake = tool
            self.assertIn(f"def {snake}(", backend,
                          f"mcp tool calls missing backend method {snake}")


if __name__ == "__main__":
    unittest.main()
