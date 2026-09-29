"""A small MCP client: newline-delimited JSON-RPC over stdio (`jk mcp`, `wrappers/results-mcp`).

It exposes three calls (`tools`, `call`, `close`). `call` returns the text content joined, the
`structuredContent` when the server sent one, and the `isError` flag.
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

PROTOCOL_VERSION = "2024-11-05"
CLIENT_INFO = {"name": "agent-loop-harness", "version": "1"}


@dataclass
class ToolResult:
    text: str
    structured: dict | None = None
    is_error: bool = False


@dataclass
class McpClient:
    """Shared JSON-RPC plumbing; subclasses supply `_send`."""

    _id: int = field(default=0, init=False)
    _tools: list[dict] | None = field(default=None, init=False)

    def _send(self, payload: dict) -> dict:
        raise NotImplementedError

    def request(self, method: str, params: dict | None = None) -> dict:
        self._id += 1
        resp = self._send({"jsonrpc": "2.0", "id": self._id, "method": method, "params": params or {}})
        if "error" in resp:
            raise RuntimeError(f"mcp {method}: {resp['error'].get('message')}")
        return resp.get("result") or {}

    def initialize(self) -> dict:
        info = self.request("initialize", {"protocolVersion": PROTOCOL_VERSION, "capabilities": {}, "clientInfo": CLIENT_INFO})
        self._notify("notifications/initialized")
        return info

    def _notify(self, method: str) -> None:
        try:
            self._send({"jsonrpc": "2.0", "method": method})
        except Exception:
            pass

    def tools(self) -> list[dict]:
        if self._tools is None:
            self._tools = list(self.request("tools/list").get("tools") or [])
        return self._tools

    def call(self, name: str, arguments: dict | None = None) -> ToolResult:
        result = self.request("tools/call", {"name": name, "arguments": arguments or {}})
        text = "\n".join(c.get("text", "") for c in result.get("content") or [] if c.get("type") == "text")
        return ToolResult(text, result.get("structuredContent"), bool(result.get("isError")))

    def close(self) -> None:
        pass


class StdioMcp(McpClient):
    """One server process; one JSON object per line each way."""

    def __init__(self, cmd: list[str], cwd: Path | None = None):
        super().__init__()
        self.proc = subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.initialize()

    def _send(self, payload: dict) -> dict:
        assert self.proc.stdin and self.proc.stdout
        self.proc.stdin.write(json.dumps(payload) + "\n")
        self.proc.stdin.flush()
        if "id" not in payload:
            return {}
        line = self.proc.stdout.readline()
        if not line:
            raise RuntimeError("mcp server closed its stdout")
        return json.loads(line)

    def close(self) -> None:
        try:
            self.proc.stdin.close()  # type: ignore[union-attr]
            self.proc.wait(timeout=10)
        except Exception:
            self.proc.kill()


def anthropic_tools(client: McpClient, prefix: str = "") -> list[dict]:
    """MCP tool cards as Messages-API tool definitions."""
    out = []
    for t in client.tools():
        schema = dict(t.get("inputSchema") or {"type": "object", "properties": {}})
        schema.setdefault("type", "object")
        schema.setdefault("properties", {})
        out.append({"name": prefix + t["name"], "description": t.get("description", ""), "input_schema": schema})
    return out
