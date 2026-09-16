"""The LLM drivers: headless Claude Code (`claude -p`) and a Messages-API tool-use loop.

Both get the same fixed system prompt, the tool's MCP server, and file access confined to the
sandbox. Turns, tokens and wall come from the harness's own clock and the API's usage fields;
the transcript is every event the driver saw, written beside the row.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

from .mcp import anthropic_tools
from .session import TOOL_LABEL, TOOL_NAMES, mcp_server_config, open_mcp, results_ok

SYSTEM_PROMPT = """You are fixing a {tool_label} project at {sandbox}. The build is red; make it green.

Use only the tools you are given: the `{server}` MCP tools to see why the build failed and to rerun it, and the file tools to read and edit files under {sandbox}. Do not run shell commands, do not use git to revert or check out files, do not delete tests: fix the source or the build file the results point at.

Start with `{results_tool}`; it says what failed and where. Edit, then rerun with `{run_tool}`{run_hint} and read the results again. Stop as soon as the results headline says OK and reply with one line: GREEN. If you cannot make it green, reply with one line starting with RED and say why.
"""
USER_PROMPT = "The build is red. Make it green."

# List price per million tokens, for the cost estimate a row carries (the API bills the account).
PRICES = {
    "claude-sonnet-5": (2.00, 10.00),
    "claude-opus-5": (5.00, 25.00),
    "claude-haiku-4-5": (1.00, 5.00),
}


def system_prompt(tool: str, sandbox: Path, server: str) -> str:
    names = TOOL_NAMES[tool]
    hint = f" (kind=test, dir={sandbox}; pass dir on every jk tool call)" if tool == "jk" else ""
    return SYSTEM_PROMPT.format(tool_label=TOOL_LABEL[tool], sandbox=sandbox, server=server,
                                results_tool=f"{server}: {names['results']}", run_tool=f"{server}: {names['run']}", run_hint=hint)


def estimate_cost(model: str, usage: dict) -> float:
    base = next((v for k, v in PRICES.items() if model.startswith(k)), None)
    if base is None:
        return 0.0
    inp, out = base
    return (usage.get("input_tokens", 0) * inp + usage.get("cache_creation_input_tokens", 0) * inp * 1.25
            + usage.get("cache_read_input_tokens", 0) * inp * 0.1 + usage.get("output_tokens", 0) * out) / 1_000_000


@dataclass
class AgentResult:
    green_claimed: bool
    turns: int
    usage: dict
    cost_usd: float
    transcript: list[dict]
    finding: str = ""
    extra: dict = field(default_factory=dict)


# ------------------------------------------------------------------------- claude-code


def claude_code(tool: str, sandbox: Path, model: str, max_turns: int, max_seconds: int, transcript_path: Path) -> AgentResult:
    """`claude -p` with the tool's MCP server, file tools only, a fixed system prompt, and stream-json output."""
    if not shutil.which("claude"):
        raise RuntimeError("the `claude` CLI is not on PATH")
    server = "jk" if tool == "jk" else "results"
    cfg = mcp_server_config(tool, sandbox)
    mcp_config = {"mcpServers": {server: cfg}}
    cmd = [
        "claude", "-p", USER_PROMPT,
        "--bare",
        "--output-format", "stream-json", "--verbose",
        "--model", model,
        "--max-turns", str(max_turns),
        "--system-prompt", system_prompt(tool, sandbox, server),
        "--mcp-config", json.dumps(mcp_config), "--strict-mcp-config",
        "--tools", "Read,Edit,Write,Glob,Grep",
        "--permission-mode", "bypassPermissions", "--dangerously-skip-permissions",
        "--no-session-persistence",
    ]
    env = {**os.environ, "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"}
    events: list[dict] = []
    started = time.monotonic()
    with subprocess.Popen(cmd, cwd=sandbox, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env) as proc, \
            transcript_path.open("w", encoding="utf-8") as out:
        assert proc.stdout
        result: dict | None = None
        try:
            for line in proc.stdout:
                out.write(line)
                if time.monotonic() - started > max_seconds:
                    proc.kill()
                    break
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue
                events.append(ev)
                if ev.get("type") == "result":
                    result = ev
        finally:
            stderr = proc.stderr.read() if proc.stderr else ""
            proc.wait()
    if result is None:
        finding = "claude exited without a result event" + (f": {stderr.strip()[:300]}" if stderr.strip() else "")
        if time.monotonic() - started > max_seconds:
            finding = f"time budget exhausted ({max_seconds}s)"
        return AgentResult(False, count_assistant_turns(events), {}, 0.0, summarize(events), finding)
    usage = result.get("usage") or {}
    text = result.get("result") or ""
    claimed = bool(re.match(r"\s*GREEN\b", text))
    finding = "" if claimed else (f"agent stopped: {text.strip()[:200]}" if text.strip() else f"agent stopped ({result.get('subtype')})")
    return AgentResult(
        claimed, int(result.get("num_turns") or count_assistant_turns(events)), usage,
        float(result.get("total_cost_usd") or estimate_cost(model, usage)), summarize(events), finding,
        {"session_id": result.get("session_id"), "subtype": result.get("subtype"), "model_usage": result.get("modelUsage")},
    )


def count_assistant_turns(events: list[dict]) -> int:
    return sum(1 for e in events if e.get("type") == "assistant")


def summarize(events: list[dict]) -> list[dict]:
    """The transcript's spine: each tool call and each assistant text, without payload bulk."""
    out: list[dict] = []
    for e in events:
        if e.get("type") != "assistant":
            continue
        for block in (e.get("message") or {}).get("content") or []:
            if block.get("type") == "tool_use":
                out.append({"tool_use": block.get("name"), "input": json.dumps(block.get("input"))[:200]})
            elif block.get("type") == "text" and block.get("text", "").strip():
                out.append({"text": block["text"][:300]})
    return out


# -------------------------------------------------------------------------------- api


FILE_TOOLS = [
    {"name": "read_file", "description": "Read a text file under the project; returns its content with 1-based line numbers.",
     "input_schema": {"type": "object", "properties": {"path": {"type": "string", "description": "Path relative to the project root"}}, "required": ["path"]}},
    {"name": "list_files", "description": "List files under a directory of the project (recursive, build outputs excluded).",
     "input_schema": {"type": "object", "properties": {"path": {"type": "string", "description": "Directory relative to the project root; default '.'"}}}},
    {"name": "edit_file", "description": "Replace one exact occurrence of `old` with `new` in a file under the project.",
     "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"}}, "required": ["path", "old", "new"]}},
    {"name": "write_file", "description": "Create or overwrite a file under the project with the given content.",
     "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
]
SKIP_DIRS = {"target", "build", ".gradle", ".git", ".kotlin"}


def confined(sandbox: Path, rel: str) -> Path:
    p = (sandbox / rel).resolve()
    if p != sandbox and sandbox not in p.parents:
        raise ValueError(f"{rel} is outside the project")
    return p


def file_tool(sandbox: Path, name: str, args: dict) -> str:
    if name == "read_file":
        text = confined(sandbox, args["path"]).read_text(encoding="utf-8", errors="replace")
        return "\n".join(f"{i:4d}\t{line}" for i, line in enumerate(text.split("\n"), 1))
    if name == "list_files":
        root = confined(sandbox, args.get("path") or ".")
        out = []
        for p in sorted(root.rglob("*")):
            if p.is_file() and not (set(p.relative_to(sandbox).parts) & SKIP_DIRS):
                out.append(str(p.relative_to(sandbox)))
        return "\n".join(out[:500])
    if name == "edit_file":
        p = confined(sandbox, args["path"])
        text = p.read_text(encoding="utf-8")
        n = text.count(args["old"])
        if n != 1:
            raise ValueError(f"`old` occurs {n} times in {args['path']}; it must occur exactly once")
        p.write_text(text.replace(args["old"], args["new"]), encoding="utf-8")
        return f"edited {args['path']}"
    if name == "write_file":
        p = confined(sandbox, args["path"])
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(args["content"], encoding="utf-8")
        return f"wrote {args['path']}"
    raise ValueError(f"unknown tool {name}")


def api(tool: str, sandbox: Path, model: str, max_turns: int, max_seconds: int, transcript_path: Path) -> AgentResult:
    """A manual Messages-API loop: MCP tools bridged through the harness, file tools confined to the sandbox."""
    try:
        import anthropic
    except ImportError:
        raise RuntimeError("the api driver needs the anthropic SDK: pip install anthropic") from None
    server = "jk" if tool == "jk" else "results"
    mcp = open_mcp(tool, sandbox)
    mcp_names = {t["name"] for t in mcp.tools()}
    tools = anthropic_tools(mcp) + FILE_TOOLS
    client = anthropic.Anthropic()
    messages: list[dict] = [{"role": "user", "content": USER_PROMPT}]
    usage = {"input_tokens": 0, "output_tokens": 0, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}
    transcript: list[dict] = []
    started = time.monotonic()
    claimed, finding, turns = False, "", 0
    try:
        with transcript_path.open("w", encoding="utf-8") as out:
            while turns < max_turns:
                if time.monotonic() - started > max_seconds:
                    finding = f"time budget exhausted ({max_seconds}s)"
                    break
                turns += 1
                # One cache breakpoint after the system prompt: the tool cards and the prompt are the
                # stable prefix every turn re-sends.
                response = client.messages.create(
                    model=model, max_tokens=16000,
                    system=[{"type": "text", "text": system_prompt(tool, sandbox, server), "cache_control": {"type": "ephemeral"}}],
                    thinking={"type": "adaptive"}, tools=tools, messages=messages,
                )
                for k in usage:
                    usage[k] += getattr(response.usage, k, 0) or 0
                content = [b.model_dump() for b in response.content]
                out.write(json.dumps({"turn": turns, "assistant": content, "stop_reason": response.stop_reason}) + "\n")
                messages.append({"role": "assistant", "content": content})
                text = "\n".join(b.text for b in response.content if b.type == "text")
                if text.strip():
                    transcript.append({"text": text[:300]})
                if response.stop_reason == "refusal":
                    finding = "the model refused"
                    break
                tool_uses = [b for b in response.content if b.type == "tool_use"]
                if not tool_uses:
                    claimed = bool(re.match(r"\s*GREEN\b", text))
                    finding = "" if claimed else f"agent stopped: {text.strip()[:200]}"
                    break
                results = []
                for tu in tool_uses:
                    transcript.append({"tool_use": tu.name, "input": json.dumps(tu.input)[:200]})
                    try:
                        if tu.name in mcp_names:
                            r = mcp.call(tu.name, tu.input)
                            body, is_error = r.text or json.dumps(r.structured or {}), r.is_error
                        else:
                            body, is_error = file_tool(sandbox, tu.name, tu.input), False
                    except Exception as e:
                        body, is_error = f"{type(e).__name__}: {e}", True
                    results.append({"type": "tool_result", "tool_use_id": tu.id, "content": body[:60_000], "is_error": is_error})
                    out.write(json.dumps({"turn": turns, "tool_result": tu.name, "is_error": is_error, "chars": len(body)}) + "\n")
                messages.append({"role": "user", "content": results})
            else:
                finding = f"turn budget exhausted ({max_turns})"
    finally:
        mcp.close()
    return AgentResult(claimed, turns, usage, estimate_cost(model, usage), transcript, finding)


def claims_green_from_results(sandbox: Path) -> bool:
    p = sandbox / "target" / "jk-results.md"
    return p.exists() and results_ok(p.read_text(encoding="utf-8", errors="replace"))
