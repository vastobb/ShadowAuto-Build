"""Tests for the local OpenAI-compatible model service (no device needed).

Starts model_service.Handler on an ephemeral loopback port. Backend
consultation degrades gracefully when no shell is listening.
"""
from __future__ import annotations

import json
import os
import threading
import unittest
import urllib.error
import urllib.request

os.environ.setdefault("SHADOWAUTO_PORT", "1")  # nothing listens: forces degrade path

import model_service
from model_service import Handler
from http.server import ThreadingHTTPServer


def _request(server, method, path, body=None, headers=None):
    url = f"http://127.0.0.1:{server.server_address[1]}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers=headers or {})
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            return res.status, res.read().decode(), dict(res.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode(), dict(exc.headers)


class ModelServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_models_lists_placeholder(self):
        code, body, _ = _request(self.server, "GET", "/v1/models")
        self.assertEqual(code, 200)
        ids = [m["id"] for m in json.loads(body)["data"]]
        self.assertIn(model_service.MODEL_ID, ids)
        self.assertEqual(model_service.MODEL_ID, "phantom-automation")

    def test_controller_test_pattern_say_ok(self):
        code, body, _ = _request(self.server, "POST", "/v1/chat/completions", {
            "model": model_service.MODEL_ID,
            "stream": False,
            "messages": [{"role": "user", "content": "Say OK."}],
        })
        self.assertEqual(code, 200)
        content = json.loads(body)["choices"][0]["message"]["content"]
        self.assertTrue(content.strip(), "controller test needs non-empty content")
        self.assertIn("OK", content)

    def test_unknown_model_rejected_with_hint(self):
        code, body, _ = _request(self.server, "POST", "/v1/chat/completions", {
            "model": "gpt-xyz", "stream": False, "messages": []})
        self.assertEqual(code, 400)
        self.assertIn(model_service.MODEL_ID, body)

    def test_streaming_shell_pattern_valid_sse(self):
        code, body, headers = _request(self.server, "POST", "/v1/chat/completions", {
            "model": model_service.MODEL_ID,
            "stream": True,
            "tool_choice": "auto",
            "messages": [{"role": "system", "content": "x"}],
            "tools": [{"type": "function",
                       "function": {"name": "finish", "parameters": {}}}],
        }, headers={"Accept": "text/event-stream"})
        self.assertEqual(code, 200)
        self.assertIn("text/event-stream", headers.get("Content-Type", ""))
        self.assertIn("data: [DONE]", body)
        first = body.split("data: ", 1)[1].split("\n", 1)[0]
        chunk = json.loads(first)
        self.assertEqual(chunk["object"], "chat.completion.chunk")
        # Shell AiClient accumulates fragmented tool calls: must see finish.
        self.assertIn('"name": "finish"', body)

    def test_binds_loopback_only_by_default(self):
        self.assertEqual(self.server.server_address[0], "127.0.0.1")


if __name__ == "__main__":
    unittest.main()
