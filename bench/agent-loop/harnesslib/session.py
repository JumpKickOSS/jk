"""One agent's view of one sandbox: the build tool behind an MCP client, plus the results file."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from .mcp import McpClient, StdioMcp

WRAPPERS = Path(__file__).resolve().parent.parent / "wrappers"

# What each tool's MCP server calls the three verbs the loop needs.
TOOL_NAMES = {
    # jk's run replies with the verdict and rereads the last one with run=latest; it has no results tool.
    "jk": {"run": "run", "results": "run", "results_args": {"run": "latest"}, "diagnostics": "diagnostics", "run_args": {}},
    "mvn": {"run": "run", "results": "results", "results_args": {}, "diagnostics": "diagnostics", "run_args": {}},
    "gradle": {"run": "run", "results": "results", "results_args": {}, "diagnostics": "diagnostics", "run_args": {}},
}
TOOL_LABEL = {"jk": "JumpKick (jk)", "mvn": "Maven", "gradle": "Gradle"}


def mcp_server_config(tool: str, sandbox: Path) -> dict:
    """The `mcpServers` entry a client (Claude Code, the api driver) registers for this sandbox."""
    if tool == "jk":
        # `jk mcp` pins the connection to the sandbox's project, as the wrapper's --dir does.
        return {"type": "stdio", "command": "jk", "args": ["-C", str(sandbox), "mcp"]}
    return {"type": "stdio", "command": str(WRAPPERS / "results-mcp"), "args": ["--dir", str(sandbox), "--tool", tool]}


def open_mcp(tool: str, sandbox: Path) -> McpClient:
    cfg = mcp_server_config(tool, sandbox)
    return StdioMcp([cfg["command"], *cfg["args"]], cwd=sandbox)


def results_ok(text: str) -> bool:
    """The results page headline (the comparators' wrappers) or jk's agent verdict line says OK.

    jk's page leads with a UTF-8 byte-order mark; the wrappers' pages do not.
    """
    return bool(re.match(r"(?:# jk results — OK|OK )", text.removeprefix("\ufeff")))


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
        """The call's arguments. jk's connection is pinned to the sandbox, so no tool names a dir."""
        return args

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
