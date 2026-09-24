"""The LLM drivers: headless Claude Code, headless Grok, and a Messages-API tool-use loop.

Each gets the same fixed system prompt, the tool's MCP server, and file tools on the sandbox.
Turns, tokens and wall come from the harness's own clock and the API's usage fields; the
transcript is every event the driver saw, written beside the row.
"""

from __future__ import annotations

import json
import os
import queue
import re
import shutil
import signal
import subprocess
import tempfile
import threading
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
    cost_usd: float | None
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


# ----------------------------------------------------------------------------------- grok

# Allowlist ids `grok --tools` accepts. `search_replace` is both edit and write (an empty
# old_string creates the file); `list_dir` is the glob. MCP stays on the always-on meta-tools.
GROK_FILE_TOOLS = ("read_file", "search_replace", "grep", "list_dir")
GROK_META_TOOLS = ("search_tool", "use_tool")


def _toml_basic(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _write_grok_config(home: Path, tool: str, sandbox: Path) -> str:
    """User-scope MCP config for this run. `home` is a throwaway GROK_HOME, not `~/.grok`."""
    server = "jk" if tool == "jk" else "results"
    cfg = mcp_server_config(tool, sandbox)
    lines = [
        "[cli]",
        "auto_update = false",
        "",
        "[memory]",
        "enabled = false",
        "",
        "[memory_v2]",
        "enabled = false",
        "",
        "[session]",
        "load_envrc = false",
        "save_on_end = false",
        "",
        "[features]",
        "telemetry = false",
        "feedback = false",
        "codebase_indexing = false",
        "web_fetch = false",
        "image_gen = false",
        "video_gen = false",
        "lsp_tools = false",
        "ask_user_question = false",
        "session_recap = false",
        "",
        "[compat.claude]",
        "skills = false",
        "rules = false",
        "agents = false",
        "mcps = false",
        "hooks = false",
        "sessions = false",
        "",
        "[compat.cursor]",
        "skills = false",
        "rules = false",
        "agents = false",
        "mcps = false",
        "hooks = false",
        "sessions = false",
        "",
        "[compat.codex]",
        "sessions = false",
        "",
    ]
    if cfg["type"] == "http":
        lines += [
            f"[mcp_servers.{server}]",
            f"url = {_toml_basic(cfg['url'])}",
            "enabled = true",
            "",
            f"[mcp_servers.{server}.headers]",
        ]
        for key, value in cfg["headers"].items():
            lines.append(f"{_toml_basic(key) if not key.isidentifier() else key} = {_toml_basic(value)}")
    else:
        args = ", ".join(_toml_basic(a) for a in cfg["args"])
        lines += [
            f"[mcp_servers.{server}]",
            f"command = {_toml_basic(cfg['command'])}",
            f"args = [{args}]",
            "enabled = true",
        ]
    path = home / "config.toml"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    path.chmod(0o600)
    return server


def _grok_env(home: Path) -> dict[str, str]:
    """Isolated home, memory off, vendor compat off. The login is a copy, not the user's file."""
    env = dict(os.environ)
    env.pop("GROK_AGENT", None)
    env["GROK_HOME"] = str(home)
    env["GROK_MEMORY"] = "0"
    env["GROK_DISABLE_AUTOUPDATER"] = "1"
    for vendor in ("CLAUDE", "CURSOR"):
        for surface in ("SKILLS", "RULES", "AGENTS", "MCPS", "HOOKS", "SESSIONS"):
            env[f"GROK_{vendor}_{surface}_ENABLED"] = "0"
    if not env.get("XAI_API_KEY"):
        auth = Path.home() / ".grok" / "auth.json"
        if not auth.is_file():
            raise RuntimeError("grok has no credentials: set XAI_API_KEY or run `grok login`")
        dest = home / "auth.json"
        shutil.copy2(auth, dest)
        dest.chmod(0o600)
        env["GROK_AUTH_PATH"] = str(dest)
    return env


def _write_sandbox_profile(home: Path, sandbox: Path) -> None:
    """Deny reads of user grok state, the artifact cache, sibling runs, and harness sources.

    Extends `workspace` rather than `strict`: `strict` blocks DNS for the model API. Writes are the
    project, `/tmp`, and `~/.grok` except the paths denied below.
    """
    denies: list[Path] = []
    user_grok = Path.home() / ".grok"
    for name in ("auth.json", "config.toml", "trusted_folders.toml", "memory", "memory-v2", "sessions"):
        denies.append(user_grok / name)
    denies.append(Path.home() / ".ssh")
    denies.append(Path.home() / ".jk" / "store" / "repos")
    loop = Path(__file__).resolve().parents[1]
    denies += [loop / "harnesslib", loop / "scenario", loop / "scenarios.toml"]
    # <date>/<driver>/<tool>/<repo.failure>/sandbox — deny sibling tools and sibling drivers.
    tool_dir = sandbox.parent.parent
    driver_dir = tool_dir.parent
    if sandbox.name == "sandbox" and tool_dir.name in {"jk", "mvn", "gradle"} and driver_dir.name == "grok":
        date_dir = driver_dir.parent
        for parent, keep in ((driver_dir, tool_dir), (date_dir, driver_dir)):
            if not parent.is_dir():
                continue
            for sib in parent.iterdir():
                if sib.resolve() != keep.resolve():
                    denies.append(sib)
    lines = ["[profiles.agent-loop]", 'extends = "workspace"', "deny = ["]
    for path in denies:
        if path.exists():
            lines.append(f"  {_toml_basic(str(path.resolve()))},")
    lines.append("]")
    dest = home / "sandbox.toml"
    dest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    dest.chmod(0o600)


def _kill_group(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        try:
            proc.kill()
        except ProcessLookupError:
            pass


def _pump(stream, sink: queue.Queue) -> None:
    try:
        for line in stream:
            sink.put(line)
    finally:
        sink.put(None)


def _unexpected_tools(names: list[str]) -> list[str]:
    """Advertised tools that are neither file tools, MCP meta-tools, nor `server__tool` keys."""
    allowed = set(GROK_FILE_TOOLS) | set(GROK_META_TOOLS)
    return [name for name in names if name not in allowed and "__" not in name]


def _grok_persisted_usage(env: dict[str, str], session_id: str | None, socket: Path) -> dict | None:
    if not session_id:
        return None
    proc = subprocess.run(
        ["grok", "usage", session_id, "--leader-socket", str(socket)],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=60,
    )
    if proc.returncode != 0 or not proc.stdout.strip():
        return None
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None


def _grok_cost(result: dict | None, persisted: dict | None) -> float | None:
    """Dollars from `grok usage` ticks, else the stream's cost. Zero means unreported, not free."""
    session = (persisted or {}).get("session") or {}
    if session and not session.get("costIsPartial"):
        ticks = session.get("costUsdTicks")
        if isinstance(ticks, int) and ticks > 0:
            return ticks / 10_000_000_000
    cost = (result or {}).get("total_cost_usd")
    if isinstance(cost, (int, float)) and cost > 0:
        return float(cost)
    return None


def _merge_grok_usage(usage: dict, persisted: dict | None) -> dict:
    """Stream usage matches claude-code's buckets. `grok usage` adds reasoning, and fills buckets the stream left empty.

    `grok usage` `inputTokens` already includes cache reads, so it is not copied on top of a stream split.
    """
    session = (persisted or {}).get("session") or {}
    if session.get("reasoningTokens") is not None:
        usage["reasoning_tokens"] = session["reasoningTokens"]
    if any(usage.get(k) for k in ("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")):
        return usage
    if not session:
        return usage
    cached = session.get("cachedReadTokens") or 0
    created = session.get("cacheCreationTokens") or 0
    usage["input_tokens"] = max(0, (session.get("inputTokens") or 0) - cached - created)
    usage["output_tokens"] = session.get("outputTokens") or 0
    usage["cache_read_input_tokens"] = cached
    usage["cache_creation_input_tokens"] = created
    return usage


def grok(tool: str, sandbox: Path, model: str, max_turns: int, max_seconds: int, transcript_path: Path,
         effort: str = "high") -> AgentResult:
    """`grok -p` with the tool's MCP server, file tools only, and the same system prompt as Claude Code.

    Config, sessions and memory live in a throwaway directory (`GROK_HOME`) that this function deletes.
    A copy of the login is passed as `GROK_AUTH_PATH`. File tools run under `--sandbox agent-loop`.
    """
    if not shutil.which("grok"):
        raise RuntimeError("the `grok` CLI is not on PATH")
    with tempfile.TemporaryDirectory(prefix="jk-agent-loop-grok-") as tmp:
        home = Path(tmp)
        server = _write_grok_config(home, tool, sandbox)
        _write_sandbox_profile(home, sandbox)
        env = _grok_env(home)
        socket = home / "leader.sock"
        cmd = [
            "grok", "-p", USER_PROMPT,
            "--verbatim",
            "--output-format", "streaming-messages-json",
            "--model", model,
            "--reasoning-effort", effort,
            "--max-turns", str(max_turns),
            "--system-prompt-override", system_prompt(tool, sandbox, server),
            "--tools", ",".join(GROK_FILE_TOOLS),
            "--disable-web-search",
            "--no-subagents",
            "--no-plan",
            "--always-approve",
            "--permission-mode", "bypassPermissions",
            "--sandbox", "agent-loop",
            "--leader-socket", str(socket),
        ]
        events: list[dict] = []
        started = time.monotonic()
        timed_out = False
        result: dict | None = None
        advertised: list[str] = []
        stderr_parts: list[str] = []
        with subprocess.Popen(
            cmd, cwd=sandbox, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, errors="replace", env=env, start_new_session=True,
        ) as proc, transcript_path.open("w", encoding="utf-8") as out:
            assert proc.stdout and proc.stderr
            stdout_q: queue.Queue = queue.Queue()
            stderr_q: queue.Queue = queue.Queue()
            threads = [
                threading.Thread(target=_pump, args=(proc.stdout, stdout_q), daemon=True),
                threading.Thread(target=_pump, args=(proc.stderr, stderr_q), daemon=True),
            ]
            for thread in threads:
                thread.start()
            stdout_done = False
            while not stdout_done:
                if time.monotonic() - started > max_seconds:
                    timed_out = True
                    _kill_group(proc)
                    break
                try:
                    line = stdout_q.get(timeout=0.25)
                except queue.Empty:
                    if proc.poll() is not None and stdout_q.empty():
                        break
                    continue
                if line is None:
                    stdout_done = True
                    break
                out.write(line)
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue
                events.append(ev)
                if ev.get("type") == "system" and ev.get("subtype") == "init":
                    advertised = list(ev.get("tools") or [])
                    if _unexpected_tools(advertised):
                        _kill_group(proc)
                        break
                elif ev.get("type") == "result":
                    result = ev
            while True:
                try:
                    line = stdout_q.get_nowait()
                except queue.Empty:
                    break
                if not line:
                    continue
                out.write(line)
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue
                events.append(ev)
                if ev.get("type") == "result":
                    result = ev
            if timed_out or _unexpected_tools(advertised):
                _kill_group(proc)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                _kill_group(proc)
                proc.wait(timeout=5)
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                try:
                    line = stderr_q.get(timeout=0.2)
                except queue.Empty:
                    if proc.poll() is not None:
                        break
                    continue
                if line is None:
                    break
                stderr_parts.append(line)
        stderr = "".join(stderr_parts).strip()
        # One `assistant` event per model response: the same count as claude-code's stream-json.
        turns = count_assistant_turns(events)
        unexpected = _unexpected_tools(advertised)
        persisted = _grok_persisted_usage(env, (result or {}).get("session_id"), socket)
        usage = _merge_grok_usage(dict((result or {}).get("usage") or {}), persisted)
        extra = {
            "session_id": (result or {}).get("session_id"),
            "subtype": (result or {}).get("subtype"),
            "tools": advertised,
            "model_usage": (result or {}).get("modelUsage"),
            "effort": effort,
        }
        if unexpected:
            return AgentResult(False, turns, usage, _grok_cost(result, persisted), summarize(events),
                               f"grok advertised tools outside the file and MCP set: {', '.join(unexpected)}", extra)
        if "keeping full grok toolset" in stderr:
            return AgentResult(False, turns, usage, _grok_cost(result, persisted), summarize(events),
                               "grok ignored the tool allowlist and kept its full toolset", extra)
        if result is None:
            finding = "grok exited without a result event" + (f": {stderr[:300]}" if stderr else "")
            if timed_out or time.monotonic() - started > max_seconds:
                finding = f"time budget exhausted ({max_seconds}s)"
            return AgentResult(False, turns, usage, _grok_cost(None, persisted), summarize(events), finding, extra)
        if result.get("num_turns"):
            turns = int(result["num_turns"])
        text = result.get("result") or ""
        claimed = bool(re.match(r"\s*GREEN\b", text))
        finding = "" if claimed else (f"agent stopped: {text.strip()[:200]}" if text.strip() else f"agent stopped ({result.get('subtype')})")
        if timed_out and not claimed:
            finding = f"time budget exhausted ({max_seconds}s)"
        summary = summarize(events)
        used = _unexpected_tools([step["tool_use"] for step in summary if step.get("tool_use")])
        if used:
            claimed = False
            finding = f"agent used a tool outside the file and MCP set: {', '.join(used)}"
        return AgentResult(claimed, turns, usage, _grok_cost(result, persisted), summary, finding, extra)


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
