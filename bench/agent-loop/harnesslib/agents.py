"""The LLM drivers: headless claude, grok, agy, and muse, plus a Messages-API tool-use loop.

Each gets the same fixed system prompt, the tool's MCP server, and file tools on the sandbox.
Turns, tokens and wall come from the harness's own clock and the CLI's usage fields; the
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

Start with `{results_tool}`; it says what failed and where. Edit, then rerun with `{run_tool}`{run_hint}; its reply is the new result. Stop as soon as the result says OK and reply with one line: GREEN. If you cannot make it green, reply with one line starting with RED and say why.
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
                                results_tool=f"{server}: {names['results']}" + (" (run=latest)" if tool == "jk" else ""), run_tool=f"{server}: {names['run']}", run_hint=hint)


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


def _startup_failure(text: str) -> bool:
    """A login or model-id rejection, as opposed to a scenario the agent failed to fix."""
    low = text.lower()
    return any(piece in low for piece in (
        "does not exist",
        "lack access",
        "not logged",
        "no credentials",
        "please sign in",
        "no valid authentication",
        "invalid model",
        "unknown model",
        "model id",
    ))


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


def _run_streaming(cmd: list[str], cwd: Path, env: dict, max_seconds: int, transcript_path: Path,
                   on_event=None) -> tuple[list[dict], str, str | None]:
    """Run ``cmd`` in its own session. Return parsed stdout events, stderr, and why it was stopped.

    The stop reason is ``wall`` when the wall budget fired, ``turns`` when ``on_event`` asked to
    stop, or ``None`` when the process exited on its own. The process group is killed on either stop.
    """
    events: list[dict] = []
    started = time.monotonic()
    stop: str | None = None
    stderr_parts: list[str] = []
    with subprocess.Popen(
        cmd, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
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
                stop = "wall"
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
            if on_event and on_event(ev) == "kill":
                stop = stop or "turns"
                _kill_group(proc)
                break
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
        if stop:
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
    return events, "".join(stderr_parts).strip(), stop


# ------------------------------------------------------------------------- claude-code


def claude_code(tool: str, sandbox: Path, model: str, max_turns: int, max_seconds: int, transcript_path: Path,
                effort: str = "medium") -> AgentResult:
    """`claude -p` with the tool's MCP server, file tools only, a fixed system prompt, and stream-json output.

    ``--effort`` is the CLI's reasoning-effort flag (low, medium, high, xhigh, max). The wall
    budget kills the process group.
    """
    if not shutil.which("claude"):
        raise RuntimeError("the `claude` CLI is not on PATH")
    server = "jk" if tool == "jk" else "results"
    cfg = mcp_server_config(tool, sandbox)
    mcp_config = {"mcpServers": {server: cfg}}
    cmd = [
        "claude", "-p", USER_PROMPT,
        # Project sources only: the sandbox has none, so the user's hooks, plugins and skills stay out
        # while their login (not a setting) still works. --bare would refuse a subscription login.
        "--setting-sources", "project", "--disable-slash-commands",
        "--output-format", "stream-json", "--verbose",
        "--model", model,
        "--effort", effort,
        "--max-turns", str(max_turns),
        "--system-prompt", system_prompt(tool, sandbox, server),
        "--mcp-config", json.dumps(mcp_config), "--strict-mcp-config",
        "--tools", "Read,Edit,Write,Glob,Grep",
        "--permission-mode", "bypassPermissions", "--dangerously-skip-permissions",
        "--no-session-persistence",
    ]
    env = {**os.environ, "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"}
    events, stderr, stop = _run_streaming(cmd, sandbox, env, max_seconds, transcript_path)
    result = next((ev for ev in reversed(events) if ev.get("type") == "result"), None)
    extra = {"effort": effort, "file_tools_only": True, "model_usage": (result or {}).get("modelUsage")}
    if result is None:
        finding = "claude exited without a result event" + (f": {stderr[:300]}" if stderr else "")
        if stop == "wall":
            finding = f"time budget exhausted ({max_seconds}s)"
        if _startup_failure(finding):
            extra["abort"] = True
        return AgentResult(False, count_assistant_turns(events), {}, 0.0, summarize(events), finding, extra)
    usage = result.get("usage") or {}
    text = result.get("result") or ""
    claimed = bool(re.match(r"\s*GREEN\b", text))
    finding = "" if claimed else (f"agent stopped: {text.strip()[:200]}" if text.strip() else f"agent stopped ({result.get('subtype')})")
    if stop == "wall" and not claimed:
        finding = f"time budget exhausted ({max_seconds}s)"
    extra.update(session_id=result.get("session_id"), subtype=result.get("subtype"))
    if _startup_failure(finding) or _startup_failure(text):
        extra["abort"] = True
    return AgentResult(
        claimed, int(result.get("num_turns") or count_assistant_turns(events)), usage,
        float(result.get("total_cost_usd") or estimate_cost(model, usage)), summarize(events), finding, extra,
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
# old_string creates the file); `list_dir` is the glob. MCP tools are not functions grok
# will advertise: the model reaches them only through these meta-tools.
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


def _gradle_user_home() -> Path:
    """Gradle user home this process uses. Baselines use the same one, so the in-loop build stays warm."""
    raw = os.environ.get("GRADLE_USER_HOME")
    path = Path(raw).expanduser() if raw else Path.home() / ".gradle"
    return path.resolve() if path.exists() else path.absolute()


def _write_sandbox_profile(home: Path, sandbox: Path) -> None:
    """Deny reads of user grok state, the artifact cache, sibling runs, and harness sources.

    Extends `workspace` rather than `strict`: `strict` blocks DNS for the model API. Writes are the
    project, `/tmp`, and `~/.grok`, plus the Gradle user home. Gradle writes a lock beside its
    native library there; without that write the client reports that `libnative-platform.so` failed
    to load. Maven's local repository gets the same grant, so a fix that needs a download
    behaves as it does outside the sandbox.
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
    gradle_home = _gradle_user_home()
    gradle_home.mkdir(parents=True, exist_ok=True)
    m2 = Path.home() / ".m2"
    m2.mkdir(parents=True, exist_ok=True)
    lines = [
        "[profiles.agent-loop]",
        'extends = "workspace"',
        "read_write = [",
        f"  {_toml_basic(str(gradle_home.resolve()))},",
        f"  {_toml_basic(str(m2.resolve()))},",
        "]",
        "deny = [",
    ]
    for path in denies:
        if path.exists():
            lines.append(f"  {_toml_basic(str(path.resolve()))},")
    lines.append("]")
    dest = home / "sandbox.toml"
    dest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    dest.chmod(0o600)


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


def reasoning_count(usage: dict | None, model_usage: dict | None = None) -> int:
    """Reasoning tokens, or 0 when the API does not report them separately from output."""
    usage = usage or {}
    for key in ("reasoning_tokens", "reasoningTokens", "thinking_tokens"):
        if usage.get(key) is not None:
            return int(usage[key])
    total = 0
    for entry in (model_usage or {}).values():
        if not isinstance(entry, dict):
            continue
        for key in ("reasoningTokens", "reasoning_tokens", "thinking_tokens"):
            if entry.get(key):
                total += int(entry[key])
                break
    return total


def _merge_grok_usage(usage: dict, persisted: dict | None) -> dict:
    """Stream usage matches claude-code's buckets. `grok usage` adds reasoning, and fills buckets the stream left empty.

    `grok usage` `inputTokens` already includes cache reads, so it is not copied on top of a stream split.
    """
    session = (persisted or {}).get("session") or {}
    if session.get("reasoningTokens") is not None:
        usage["reasoning_tokens"] = session["reasoningTokens"]
    else:
        usage.setdefault("reasoning_tokens", 0)
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
            "file_tools_only": not unexpected,
        }
        if unexpected:
            return AgentResult(False, turns, usage, _grok_cost(result, persisted), summarize(events),
                               f"grok advertised tools outside the file and MCP set: {', '.join(unexpected)}", extra)
        if "keeping full grok toolset" in stderr:
            extra["file_tools_only"] = False
            return AgentResult(False, turns, usage, _grok_cost(result, persisted), summarize(events),
                               "grok ignored the tool allowlist and kept its full toolset", extra)
        if result is None:
            finding = "grok exited without a result event" + (f": {stderr[:300]}" if stderr else "")
            if timed_out or time.monotonic() - started > max_seconds:
                finding = f"time budget exhausted ({max_seconds}s)"
            if _startup_failure(finding) or _startup_failure(stderr):
                extra["abort"] = True
                finding = stderr[:300] or finding
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
            extra["file_tools_only"] = False
            finding = f"agent used a tool outside the file and MCP set: {', '.join(used)}"
        return AgentResult(claimed, turns, usage, _grok_cost(result, persisted), summary, finding, extra)


# ------------------------------------------------------------------------------------ agy

# Registry names the custom agent may run. `call_mcp_tool` is how the model reaches MCP, but it is
# not a registry component: naming it in the agent file fails executor construction, and the
# session still exposes it when an MCP server is configured. `init.tools` lists the whole catalog,
# including `run_command`; the executor allowlist is this set.
AGY_FILE_TOOLS = (
    "view_file", "list_dir", "grep_search", "find_by_name",
    "replace_file_content", "multi_replace_file_content", "write_to_file",
)
AGY_ALLOWED_USE = set(AGY_FILE_TOOLS) | {"call_mcp_tool"}


def _agy_agent_md(tool: str, sandbox: Path, server: str) -> str:
    tools = "\n".join(f"  - {name}" for name in AGY_FILE_TOOLS)
    body = system_prompt(tool, sandbox, server).strip()
    return (
        "---\n"
        "name: agent-loop\n"
        "description: File tools and the build MCP server for one agent-loop scenario.\n"
        "tools:\n"
        f"{tools}\n"
        "mainAgent: true\n"
        "subagent: false\n"
        "---\n"
        f"{body}\n"
    )


def _write_agy_home(home: Path, tool: str, sandbox: Path) -> str:
    """MCP config, permissions, and the file-tool agent under a throwaway ``HOME``.

    agy reads ``$HOME/.gemini``. The oauth token file is a copy. ``~/.m2`` is a symlink so a
    Maven fix still uses the warm local repository; Gradle is pointed at the real user home
    with ``GRADLE_USER_HOME`` on the child environment.
    """
    real = Path.home()
    token = real / ".gemini" / "antigravity-cli" / "antigravity-oauth-token"
    if not token.is_file():
        raise RuntimeError("agy has no credentials: launch `agy` once to sign in")
    cli = home / ".gemini" / "antigravity-cli"
    cli.mkdir(parents=True)
    dest = cli / "antigravity-oauth-token"
    shutil.copy2(token, dest)
    dest.chmod(0o600)
    server = "jk" if tool == "jk" else "results"
    cfg = mcp_server_config(tool, sandbox)
    if cfg["type"] == "http":
        entry = {"disabled": False, "serverUrl": cfg["url"], "headers": cfg["headers"]}
    else:
        entry = {"disabled": False, "command": cfg["command"], "args": cfg["args"]}
    config_dir = home / ".gemini" / "config"
    config_dir.mkdir(parents=True)
    mcp_path = config_dir / "mcp_config.json"
    mcp_path.write_text(json.dumps({"mcpServers": {server: entry}}, indent=2) + "\n", encoding="utf-8")
    mcp_path.chmod(0o600)
    settings = {
        "toolPermission": "always-proceed",
        "allowNonWorkspaceAccess": False,
        "permissions": {
            "deny": ["command(*)", "read_url(*)", "execute_url(*)"],
            "allow": ["mcp(*)"],
        },
    }
    settings_path = cli / "settings.json"
    settings_path.write_text(json.dumps(settings, indent=2) + "\n", encoding="utf-8")
    settings_path.chmod(0o600)
    agent = config_dir / "agents" / "agent-loop"
    agent.mkdir(parents=True)
    (agent / "agent.md").write_text(_agy_agent_md(tool, sandbox, server), encoding="utf-8")
    m2 = real / ".m2"
    if m2.is_dir():
        (home / ".m2").symlink_to(m2)
    return server


def _agy_turns(events: list[dict]) -> int:
    """Model steps: ``agent_response`` steps that reached ``DONE``. ``result.num_turns`` counts user turns."""
    return sum(
        1 for ev in events
        if ev.get("event") == "step_update"
        and (ev.get("step_update") or {}).get("step_type") == "agent_response"
        and (ev.get("step_update") or {}).get("state") == "DONE"
    )


def _agy_usage(events: list[dict], result: dict | None) -> dict:
    """``result.usage`` is the sum of the per-step usages. A killed run has only the steps."""
    usage = dict((result or {}).get("usage") or {})
    if not usage:
        total = {"input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0, "thinking_tokens": 0}
        for ev in events:
            step = ev.get("step_update") or {}
            if step.get("state") != "DONE" or step.get("step_type") != "agent_response":
                continue
            for key, value in (step.get("usage") or {}).items():
                if isinstance(value, int):
                    total[key] = total.get(key, 0) + value
        usage = {k: v for k, v in total.items() if v}
    # `total_tokens` is input + output, and `thinking_tokens` is smaller than output, so the
    # thinking count is already inside `output_tokens`. Leave reasoning at 0 so the table does not add it twice.
    inp = int(usage.get("input_tokens") or 0)
    out = int(usage.get("output_tokens") or 0)
    total = usage.get("total_tokens")
    thinking = int(usage.get("thinking_tokens") or 0)
    inside_output = total is None or int(total) == inp + out
    usage["reasoning_tokens"] = 0 if inside_output else thinking
    usage.setdefault("cache_read_input_tokens", int(usage.get("cache_read_tokens") or 0))
    usage.setdefault("cache_creation_input_tokens", int(usage.get("cache_write_tokens") or 0))
    return usage


def _agy_summary(events: list[dict]) -> list[dict]:
    out: list[dict] = []
    text: list[str] = []
    for ev in events:
        step = ev.get("step_update") if ev.get("event") == "step_update" else None
        if not isinstance(step, dict) or step.get("state") != "DONE":
            continue
        if step.get("step_type") == "agent_response":
            continue
        if step.get("step_type") == "tool":
            info = step.get("tool_info") if isinstance(step.get("tool_info"), dict) else {}
            out.append({
                "tool_use": step.get("tool_name"),
                "input": json.dumps(info.get("parameters") or {})[:200],
            })
    for ev in events:
        step = ev.get("step_update") or {}
        if step.get("step_type") == "agent_response" and step.get("text_delta"):
            text.append(step["text_delta"])
    joined = "".join(text).strip()
    if joined:
        out.append({"text": joined[:300]})
    return out


def _agy_cost(result: dict | None) -> float | None:
    cost = (result or {}).get("total_cost_usd")
    if cost is None:
        cost = ((result or {}).get("usage") or {}).get("cost_usd")
    if isinstance(cost, (int, float)) and cost > 0:
        return float(cost)
    return None


def agy(tool: str, sandbox: Path, model: str, max_turns: int, max_seconds: int, transcript_path: Path,
        effort: str = "high") -> AgentResult:
    """`agy -p` with a throwaway ``HOME``, file tools only, and the same system prompt.

    There is no turn-cap flag. ``--print-timeout`` is the wall budget, and the harness also kills
    the process group at that deadline and when ``agent_response`` steps reach ``max_turns``.
    """
    if not shutil.which("agy"):
        raise RuntimeError("the `agy` CLI is not on PATH")
    with tempfile.TemporaryDirectory(prefix="jk-agent-loop-agy-") as tmp:
        home = Path(tmp)
        server = _write_agy_home(home, tool, sandbox)
        env = dict(os.environ)
        env["HOME"] = str(home)
        env["GRADLE_USER_HOME"] = str(_gradle_user_home())
        responses = {"n": 0}

        def on_event(ev: dict) -> str | None:
            step = ev.get("step_update") or {}
            if ev.get("event") == "step_update" and step.get("step_type") == "agent_response" and step.get("state") == "DONE":
                responses["n"] += 1
                if responses["n"] >= max_turns:
                    return "kill"
            return None

        cmd = [
            "agy", "-p", USER_PROMPT,
            "--output-format", "stream-json",
            "--model", model,
            "--effort", effort,
            "--agent", "agent-loop",
            "--dangerously-skip-permissions",
            "--disable-slash-commands",
            "--print-timeout", f"{max(1, int(max_seconds))}s",
        ]
        events, stderr, stop = _run_streaming(cmd, sandbox, env, max_seconds, transcript_path, on_event)
        result_ev = next((ev for ev in reversed(events) if ev.get("event") == "result"), None)
        result = (result_ev or {}).get("result") if isinstance((result_ev or {}).get("result"), dict) else None
        turns = _agy_turns(events)
        usage = _agy_usage(events, result)
        summary = _agy_summary(events)
        used = [step["tool_use"] for step in summary if step.get("tool_use")]
        outside = [name for name in used if name not in AGY_ALLOWED_USE]
        extra = {
            "effort": effort,
            "tools": list(AGY_FILE_TOOLS) + ["call_mcp_tool"],
            "file_tools_only": not outside,
            "server": server,
            "model_usage": None,
        }
        if outside:
            return AgentResult(False, turns, usage, _agy_cost(result), summary,
                               f"agent used a tool outside the file and MCP set: {', '.join(outside)}", extra)
        if result is None:
            finding = "agy exited without a result event" + (f": {stderr[:300]}" if stderr else "")
            if stop == "wall":
                finding = f"time budget exhausted ({max_seconds}s)"
            elif stop == "turns":
                finding = f"turn budget exhausted ({max_turns})"
            if _startup_failure(finding) or _startup_failure(stderr):
                extra["abort"] = True
                finding = (stderr or finding)[:300]
            return AgentResult(False, turns, usage, None, summary, finding, extra)
        if result.get("status") == "ERROR":
            err = str(result.get("error") or result.get("response") or "")
            extra["abort"] = bool(_startup_failure(err))
            return AgentResult(False, turns, usage, _agy_cost(result), summary, err[:300], extra)
        text = result.get("response") or ""
        claimed = bool(re.match(r"\s*GREEN\b", text))
        finding = "" if claimed else (f"agent stopped: {text.strip()[:200]}" if text.strip() else f"agent stopped ({result.get('status')})")
        if stop == "wall" and not claimed:
            finding = f"time budget exhausted ({max_seconds}s)"
        elif stop == "turns" and not claimed:
            finding = f"turn budget exhausted ({max_turns})"
        return AgentResult(claimed, turns, usage, _agy_cost(result), summary, finding, extra)


# ----------------------------------------------------------------------------------- muse

MUSE_FILE_TOOLS = ("read_file", "search", "write_file", "edit_file")
# Shell names muse still exposes when ``--disable-shell`` is ignored. A used one flags the row.
MUSE_SHELL_TOOLS = {"bash", "shell", "exec_command", "run_command"}


def _muse_mcp_entry(tool: str, sandbox: Path) -> tuple[str, dict]:
    server = "jk" if tool == "jk" else "results"
    cfg = mcp_server_config(tool, sandbox)
    if cfg["type"] == "http":
        entry = {
            "type": "streamable-http",
            "url": cfg["url"],
            "headers": cfg["headers"],
            "mode": "optional",
        }
    else:
        entry = {
            "type": "stdio",
            "command": cfg["command"],
            "args": cfg["args"],
            "mode": "optional",
        }
    return server, entry


def _write_muse_home(config: Path, data: Path, tool: str, sandbox: Path, model: str, effort: str) -> str:
    """Settings and a copied login under throwaway XDG config and data roots.

    Muse reads ``$XDG_CONFIG_HOME/muse/settings.json`` (else ``~/.config/muse``) and sessions
    under ``$XDG_DATA_HOME/muse``. The system prompt, the file toolset, and the MCP server live
    in that settings file. The model catalog is copied so a short id resolves the same way it
    does for an ordinary launch.
    """
    real_config = Path.home() / ".config" / "muse"
    auth = real_config / "auth.json"
    if not auth.is_file():
        raise RuntimeError("muse has no credentials: run `muse login`")
    muse = config / "muse"
    muse.mkdir(parents=True)
    dest = muse / "auth.json"
    shutil.copy2(auth, dest)
    dest.chmod(0o600)
    catalog = Path.home() / ".local" / "share" / "muse" / "model-catalog"
    if catalog.is_dir():
        (data / "muse").mkdir(parents=True, exist_ok=True)
        shutil.copytree(catalog, data / "muse" / "model-catalog")
    server, entry = _muse_mcp_entry(tool, sandbox)
    settings = {
        "schema_version": 1,
        "provider": "meta",
        "model": model,
        "reasoning_effort": effort,
        "run": {
            "system_prompt": system_prompt(tool, sandbox, server),
            "toolset": list(MUSE_FILE_TOOLS),
            "subagent_delegation_mode": "off",
        },
        "mcpServers": {server: entry},
    }
    path = muse / "settings.json"
    path.write_text(json.dumps(settings, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)
    return server


def _muse_parts(ev: dict) -> tuple[dict, dict]:
    payload = ev.get("payload") if isinstance(ev.get("payload"), dict) else {}
    event = payload.get("event") if isinstance(payload.get("event"), dict) else {}
    return payload, event


def _muse_model_task(task_kind: str) -> bool:
    return "model" in task_kind and "response" in task_kind


def _muse_session_events(data: Path) -> tuple[list[dict], list[dict]]:
    """Main-session events, and every session log under the throwaway data root.

    ``muse exec --json`` does not emit ``model_completed`` or ``assistant_message_committed``.
    Those live in ``session.jsonl``. Subagent logs are included in the second list (usage) and
    excluded from the first (the reply belongs to the main session).
    """
    root = data / "muse" / "sessions"
    main: list[dict] = []
    everything: list[dict] = []
    if not root.is_dir():
        return main, everything
    for path in sorted(root.rglob("session.jsonl")):
        batch: list[dict] = []
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if not line.strip():
                continue
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(ev, dict):
                batch.append(ev)
        everything.extend(batch)
        if "subagent" not in path.parts:
            main.extend(batch)
    return main, everything


def _muse_usage(events: list[dict]) -> dict:
    """Sum ``model_completed.usage``. Each object is one model call, not a running total.

    ``input_tokens`` is the counted-once prompt (cache included). Cache is stored beside it
    and subtracted here so the row's input column stays the uncached share. ``cached_tokens``
    repeats ``cache_read_tokens`` when the write split is zero, so it is not added again.
    ``output_tokens`` includes ``reasoning_tokens`` on this provider; both are kept.
    """
    input_tokens = output_tokens = cache_read = cache_write = reasoning = 0
    found = False
    for ev in events:
        _, event = _muse_parts(ev)
        if event.get("kind") != "model_completed" or not isinstance(event.get("usage"), dict):
            continue
        usage = event["usage"]
        found = True
        read = int(usage.get("cache_read_tokens") or 0)
        write = int(usage.get("cache_write_tokens") or 0)
        cached = int(usage.get("cached_tokens") or 0)
        if read == 0 and write == 0 and cached:
            read = cached
        inp = int(usage.get("input_tokens") or 0)
        if inp >= read + write:
            inp -= read + write
        input_tokens += inp
        output_tokens += int(usage.get("output_tokens") or 0)
        cache_read += read
        cache_write += write
        reasoning += int(usage.get("reasoning_tokens") or usage.get("thinking_tokens") or 0)
    if not found:
        return {"reasoning_tokens": 0}
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "cache_read_input_tokens": cache_read,
        "cache_creation_input_tokens": cache_write,
        "reasoning_tokens": reasoning,
    }


def _muse_cost(events: list[dict]) -> float | None:
    found: list[float] = []

    def walk(value) -> None:
        if isinstance(value, dict):
            for key in ("cost_usd", "total_cost_usd"):
                if isinstance(value.get(key), (int, float)) and value[key] > 0:
                    found.append(float(value[key]))
            micros = value.get("cost_micros")
            if isinstance(micros, int) and micros > 0:
                found.append(micros / 1_000_000)
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    for ev in events:
        walk(ev.get("payload"))
    return found[-1] if found else None


def _muse_delta_texts(events: list[dict]) -> list[str]:
    """Visible text of each model call, from ``run.output.delta`` only.

    Deltas that belong to one call are joined. Calls are not joined together: the terminal
    event's ``text`` is that join, which glues a ``GREEN`` reply to the next message.
    """
    groups: dict[str, list[str]] = {}
    order: list[str] = []
    active: str | None = None
    for ev in events:
        payload, event = _muse_parts(ev)
        task_kind = str(event.get("task_kind") or "")
        task_id = str(payload.get("task_id") or event.get("task_id") or "")
        if ev.get("payload_type") == "task.lifecycle.proposed" and _muse_model_task(task_kind) and task_id:
            active = task_id
            if task_id not in groups:
                order.append(task_id)
                groups[task_id] = []
        elif ev.get("payload_type") == "run.output.delta" and active and payload.get("text"):
            groups[active].append(str(payload["text"]))
    return ["".join(groups[task_id]) for task_id in order if groups[task_id]]


def _muse_committed_texts(events: list[dict]) -> list[str]:
    texts: list[str] = []
    for ev in events:
        _, event = _muse_parts(ev)
        if event.get("kind") == "assistant_message_committed" and event.get("text"):
            texts.append(str(event["text"]))
    return texts


def _muse_final_text(stdout: list[dict], session: list[dict]) -> str:
    """The last assistant message. A committed message replaces that call's deltas; it is not appended."""
    deltas = _muse_delta_texts(stdout)
    committed = _muse_committed_texts(session)
    if committed and (not deltas or len(committed) == len(deltas) or len(committed) > len(deltas)):
        return committed[-1]
    if deltas:
        return deltas[-1]
    return committed[-1] if committed else ""


def _muse_step_cap(reason: str) -> bool:
    return "terminal state" in reason and "step" in reason


def _muse_turns(events: list[dict]) -> tuple[int, list[dict]]:
    """Completed ``model.*.response`` tasks, plus the tool calls that followed them.

    A task counts when it completes, so a model id rejected before a response is zero turns.
    Tool calls on ``--json`` are ``tool.result`` events (``assistant_tool_calls_committed`` in the
    session log). The reply text is added by the caller from the last message, not from every delta.
    """
    model_tasks: set[str] = set()
    completed = 0
    summary: list[dict] = []
    seen_results = False
    for ev in events:
        payload, event = _muse_parts(ev)
        kind = str(event.get("kind") or payload.get("kind") or "")
        task_kind = str(event.get("task_kind") or "")
        task_id = str(payload.get("task_id") or event.get("task_id") or "")
        if ev.get("payload_type") == "task.lifecycle.proposed" and _muse_model_task(task_kind) and task_id:
            model_tasks.add(task_id)
        if ev.get("payload_type") == "task.lifecycle.completed" and task_id in model_tasks:
            completed += 1
        if ev.get("payload_type") == "tool.result":
            seen_results = True
            facts = payload.get("correlation_facts") if isinstance(payload.get("correlation_facts"), dict) else {}
            name = str(facts.get("tool_name") or "")
            if name:
                edit = payload.get("edit_facts") if isinstance(payload.get("edit_facts"), dict) else {}
                detail = str(edit.get("path") or "") or str(payload.get("text") or "")
                summary.append({"tool_use": name, "input": detail[:200]})
        elif kind == "assistant_tool_calls_committed" and not seen_results:
            for call in event.get("tool_calls") or []:
                if isinstance(call, dict) and call.get("name"):
                    summary.append({"tool_use": call["name"], "input": json.dumps(call.get("args") or call.get("arguments") or {})[:200]})
    return completed, summary


def _muse_terminal(events: list[dict]) -> dict | None:
    for ev in reversed(events):
        if str(ev.get("payload_type") or "").startswith("run.terminal."):
            payload = ev.get("payload")
            return payload if isinstance(payload, dict) else None
    return None


def muse(tool: str, sandbox: Path, model: str, max_turns: int, max_seconds: int, transcript_path: Path,
         effort: str = "xhigh") -> AgentResult:
    """`muse exec --json` with throwaway XDG roots, file tools, and the same system prompt.

    ``--max-model-steps`` is the turn cap: one step is one model call. ``--disable-shell`` and
    ``--disable-web-tools`` drop those tools; ``run.toolset`` is the file allowlist and
    ``subagent_delegation_mode`` is ``off``. There is no system-prompt flag; the prompt is
    ``run.system_prompt`` in the throwaway settings. The wall budget kills the process group.
    Approvals are off (``--approval-mode never`` and ``--disable-sandbox``). The session log
    stays under the throwaway data root so usage can be read, then that root is deleted.
    """
    if not shutil.which("muse"):
        raise RuntimeError("the `muse` CLI is not on PATH")
    with tempfile.TemporaryDirectory(prefix="jk-agent-loop-muse-") as tmp:
        root = Path(tmp)
        config, data = root / "config", root / "data"
        server = _write_muse_home(config, data, tool, sandbox, model, effort)
        env = dict(os.environ)
        env["XDG_CONFIG_HOME"] = str(config)
        env["XDG_DATA_HOME"] = str(data)
        env["MUSE_NO_AUTO_UPDATE"] = "1"
        cmd = [
            "muse", "exec", "--json",
            "--provider", "meta",
            "--model", model,
            "--reasoning-effort", effort,
            # One model step is one model call (`model.*.response`), including a call that
            # requests tools. Tool tasks are not steps, so this cap is the harness turn budget.
            "--max-model-steps", str(max_turns),
            "--workspace", str(sandbox),
            "--approval-mode", "never",
            "--disable-sandbox",
            "--disable-shell",
            "--disable-web-tools",
            "--no-foreign-personal-context",
            USER_PROMPT,
        ]
        events, stderr, stop = _run_streaming(cmd, sandbox, env, max_seconds, transcript_path)
        main_session, all_sessions = _muse_session_events(data)
        turns, summary = _muse_turns(events)
        text = _muse_final_text(events, main_session)
        if text:
            summary.append({"text": text[:300]})
        usage = _muse_usage(all_sessions)
        terminal = _muse_terminal(events)
        used = [step["tool_use"] for step in summary if step.get("tool_use")]
        shell_used = [name for name in used if name.split(".")[-1].split("__")[-1] in MUSE_SHELL_TOOLS]
        extra = {
            "effort": effort,
            "tools": list(MUSE_FILE_TOOLS),
            "file_tools_only": not shell_used,
            "server": server,
            "shell_disabled": True,
        }
        cost = _muse_cost(all_sessions) if all_sessions else _muse_cost(events)
        if shell_used:
            return AgentResult(False, turns, usage, cost, summary,
                               f"agent used a shell tool: {', '.join(shell_used)}", extra)
        reason = str((terminal or {}).get("reason") or "")
        step_cap = _muse_step_cap(reason)
        if terminal is None and not text:
            finding = "muse exited without a terminal event" + (f": {stderr[:300]}" if stderr else "")
            if stop == "wall":
                finding = f"time budget exhausted ({max_seconds}s)"
            if _startup_failure(finding) or _startup_failure(stderr):
                extra["abort"] = True
                finding = (stderr or finding)[:300]
            return AgentResult(False, turns, usage, cost, summary, finding, extra)
        if _startup_failure(reason) or (not text and _startup_failure(stderr)):
            extra["abort"] = True
            return AgentResult(False, turns, usage, cost, summary, (reason or stderr)[:300], extra)
        if (terminal or {}).get("terminal") == "failed" and not text and not step_cap:
            return AgentResult(False, turns, usage, cost, summary, (reason or stderr or "muse run failed")[:300], extra)
        claimed = bool(re.match(r"\s*GREEN\b", text))
        finding = "" if claimed else (f"agent stopped: {text.strip()[:200]}" if text.strip() else "")
        if step_cap and not claimed:
            finding = f"turn budget exhausted ({max_turns})"
        elif stop == "wall" and not claimed:
            finding = f"time budget exhausted ({max_seconds}s)"
        return AgentResult(claimed, turns, usage, cost, summary, finding, extra)


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


def api(tool: str, sandbox: Path, model: str, max_turns: int, max_seconds: int, transcript_path: Path,
       effort: str = "medium") -> AgentResult:
    """A manual Messages-API loop: MCP tools bridged through the harness, file tools confined to the sandbox.

    ``effort`` is sent as ``output_config.effort`` alongside adaptive thinking.
    """
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
    usage = {
        "input_tokens": 0, "output_tokens": 0, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0,
        "reasoning_tokens": 0,
    }
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
                    thinking={"type": "adaptive"}, output_config={"effort": effort},
                    tools=tools, messages=messages,
                )
                for k in ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"):
                    usage[k] += int(getattr(response.usage, k, 0) or 0)
                blob = response.usage.model_dump() if hasattr(response.usage, "model_dump") else {}
                usage["reasoning_tokens"] += reasoning_count(blob)
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
    return AgentResult(
        claimed, turns, usage, estimate_cost(model, usage), transcript, finding,
        {"effort": effort, "file_tools_only": True},
    )


def claims_green_from_results(sandbox: Path) -> bool:
    p = sandbox / "target" / "jk-results.md"
    return p.exists() and results_ok(p.read_text(encoding="utf-8", errors="replace"))
