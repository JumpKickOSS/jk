"""One agent's view of one sandbox: the build tool behind an MCP client, plus the results file."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

_BENCH = Path(__file__).resolve().parents[2]
if str(_BENCH) not in sys.path:
    sys.path.insert(0, str(_BENCH))
import benchtools  # noqa: E402

from .mcp import HttpMcp, McpClient, StdioMcp  # noqa: E402

WRAPPERS = Path(__file__).resolve().parent.parent / "wrappers"

# What each tool's MCP server calls the three verbs the loop needs.
TOOL_NAMES = {
    # jk's run replies with the verdict and rereads the last one with run=latest; it has no results tool.
    "jk": {"run": "run", "results": "run", "results_args": {"run": "latest"}, "diagnostics": "diagnostics", "run_args": {"kind": "test"}},
    "mvn": {"run": "run", "results": "results", "results_args": {}, "diagnostics": "diagnostics", "run_args": {}},
    "gradle": {"run": "run", "results": "results", "results_args": {}, "diagnostics": "diagnostics", "run_args": {}},
}
TOOL_LABEL = {"jk": "JumpKick (jk)", "mvn": "Maven", "gradle": "Gradle"}


def engine_mcp() -> tuple[str, str]:
    """The shared engine's MCP URL and bearer token, from `jk engine status`."""
    out = subprocess.run(["jk", "engine", "status", "--output", "json"], stdout=subprocess.PIPE, text=True, timeout=120).stdout
    status = json.loads(out)
    url = status.get("mcpUrl")
    if not url:
        raise RuntimeError("the engine is not serving MCP (`jk engine status` shows no mcpUrl)")
    current = next((e for e in status.get("engines") or [] if e.get("current")), None)
    ids = [current["id"]] if current else [e["id"] for e in status.get("engines") or []]
    for eid in ids:
        token_file = benchtools.jk_home() / "state" / "engine" / f"{eid}.http-token"
        if token_file.exists():
            return url, token_file.read_text(encoding="utf-8").strip()
    raise RuntimeError(f"no http-token for the running engine under {benchtools.jk_home() / 'state' / 'engine'}")


def mcp_server_config(tool: str, sandbox: Path) -> dict:
    """The `mcpServers` entry a client (Claude Code, the api driver) registers for this sandbox."""
    if tool == "jk":
        url, token = engine_mcp()
        return {"type": "http", "url": url, "headers": {"Authorization": f"Bearer {token}"}}
    return {"type": "stdio", "command": str(WRAPPERS / "results-mcp"), "args": ["--dir", str(sandbox), "--tool", tool]}


def open_mcp(tool: str, sandbox: Path) -> McpClient:
    cfg = mcp_server_config(tool, sandbox)
    if cfg["type"] == "http":
        # No bind turn: the first jk call that carries dir binds this connection, and every call
        # here carries it anyway.
        return HttpMcp(cfg["url"], cfg["headers"]["Authorization"].removeprefix("Bearer "))
    return StdioMcp([cfg["command"], *cfg["args"]], cwd=sandbox)


def results_ok(text: str) -> bool:
    """The results page headline (the comparators' wrappers) or jk's agent verdict line says OK."""
    return bool(re.match(r"(?:# jk results — OK|OK )", text))


@dataclass
class Session:
    tool: str
    sandbox: Path
    mcp: McpClient
    calls: list[dict] = field(default_factory=list)

    @property
    def names(self) -> dict:
        return TOOL_NAMES[self.tool]

    def results_file(self) -> str:
        p = self.sandbox / "target" / "jk-results.md"
        return p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""

    def scoped(self, args: dict) -> dict:
        # Every jk call names its dir: the sandbox is the fact under test, and a named dir never
        # depends on what this connection happens to be bound to.
        return {**args, "dir": str(self.sandbox)} if self.tool == "jk" else args

    def results(self) -> str:
        r = self.mcp.call(self.names["results"], self.scoped(dict(self.names["results_args"])))
        self.calls.append({"tool": self.names["results"], "is_error": r.is_error, "chars": len(r.text)})
        return r.text

    def run(self, timeout_s: int = 1800) -> tuple[bool, str]:
        """Rerun the build through the tool's MCP; return (green, results markdown)."""
        args = {**self.names["run_args"]}
        if self.tool == "jk":
            args["timeout_s"] = min(timeout_s, 3600)
        else:
            args["timeout_s"] = timeout_s
        r = self.mcp.call(self.names["run"], self.scoped(args))
        structured = r.structured or {}
        text = r.text or self.results()
        success = results_ok(text) if self.tool == "jk" else bool(structured.get("success"))
        self.calls.append({"tool": self.names["run"], "is_error": r.is_error, "success": success})
        return success and results_ok(text), text

    def close(self) -> None:
        self.mcp.close()
