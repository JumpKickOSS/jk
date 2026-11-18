"""Per-call extraction from agent-loop transcripts (claude-code, grok, codex, cursor, agy, muse).

calls(transcript) -> list of dict(name, cat, args, out_bytes, ms, ok, text)
  name: jk.<tool> for jk's MCP tools (direct, grok use_tool, agy call_mcp_tool, cursor mcpToolCall),
        jk-shell for a shell command that runs jk, else the agent's own tool name.
  cat:  jk | discover (looking up a tool card) | read | search | edit | shell | answer | other
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

JK_SHELL = re.compile(r"(?:^|[;&|]\s*|\s)(?:\S*/)?jk\s")
READ = {"read", "read_file", "view_file", "readtoolcall"}
SEARCH = {"grep", "glob", "list_dir", "search", "greptoolcall", "globtoolcall", "find_by_name", "grep_search",
          "list_directory", "codebase_search"}
EDIT = {"edit", "write", "multiedit", "search_replace", "edit_file", "write_file", "edittoolcall", "deletetoolcall",
        "replace_file_content", "multi_replace_file_content", "write_to_file", "file_change", "apply_patch"}
DISCOVER = {"search_tool", "getmcptoolstoolcall", "tool.search"}


def _unshell(cmd: str) -> str:
    m = re.match(r"^\S*(?:zsh|bash|sh)\s+-l?c\s+(.*)$", cmd, re.S)
    if m:
        s = m.group(1).strip()
        if len(s) >= 2 and s[0] == s[-1] and s[0] in "'\"":
            s = s[1:-1]
        return s
    return cmd


def _shell_cat(cmd: str) -> str:
    c = _unshell(cmd).strip()
    if JK_SHELL.search(" " + c):
        return "jk-shell"
    first = re.split(r"\s+", c)[0] if c else ""
    first = first.split("/")[-1]
    if first in ("cat", "sed", "head", "tail", "nl", "less", "awk") and not re.search(r"\bsed\s+-i", c):
        return "read"
    if first in ("rg", "grep", "find", "ls", "fd", "tree", "git") or c.startswith("rg "):
        return "search"
    if first in ("python3", "python", "perl", "apply_patch", "mv", "cp", "rm", "mkdir") or re.search(r"\bsed\s+-i", c):
        return "edit"
    return "shell"


def _cat(name: str, args: dict) -> str:
    leaf = name.lower()
    if leaf.startswith("jk."):
        return "jk"
    if leaf == "jk-shell":
        return "jk"
    if leaf in DISCOVER:
        return "discover"
    if leaf in READ:
        p = str(args.get("Path") or args.get("path") or args.get("file_path") or "")
        if "/mcp/jk/" in p:
            return "discover"
        return "read"
    if leaf in SEARCH:
        return "search"
    if leaf in EDIT:
        return "edit"
    if leaf in ("bash", "shell", "run_command", "command_execution", "shelltoolcall"):
        return _shell_cat(str(args.get("command") or args.get("CommandLine") or ""))
    return "other"


def _text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        out = []
        for c in content:
            if isinstance(c, dict):
                if isinstance(c.get("text"), str):
                    out.append(c["text"])
                elif isinstance(c.get("text"), dict):
                    out.append(str(c["text"].get("text") or ""))
                elif c.get("type") == "image":
                    out.append("[image]")
            else:
                out.append(str(c))
        return "\n".join(out)
    if content is None:
        return ""
    return json.dumps(content)


def _jkname(name: str, args: dict) -> tuple[str, dict]:
    if name.startswith("mcp__jk__"):
        return "jk." + name[len("mcp__jk__"):], args
    if name == "use_tool":
        inner = str(args.get("tool_name") or "")
        ti = args.get("tool_input")
        if isinstance(ti, str):
            try:
                ti = json.loads(ti)
            except Exception:
                ti = {}
        if inner.startswith("jk__"):
            return "jk." + inner[4:], ti or {}
        return inner or name, ti or {}
    if name == "call_mcp_tool":
        server = args.get("ServerName") or args.get("server_name")
        tool = args.get("ToolName") or args.get("tool_name")
        inner = args.get("Arguments") if args.get("Arguments") is not None else args.get("arguments")
        if isinstance(inner, str):
            try:
                inner = json.loads(inner)
            except Exception:
                inner = {}
        if server == "jk":
            return "jk." + str(tool), inner or {}
        return f"{server}.{tool}", inner or {}
    return name, args


def _load(p: Path) -> list[dict]:
    out = []
    for line in p.read_text(errors="replace").splitlines():
        try:
            d = json.loads(line)
        except Exception:
            continue
        if isinstance(d, dict):
            out.append(d)
    return out


def _mk(name, args, out, ms, ok=True):
    name, args = _jkname(name, args if isinstance(args, dict) else {})
    if name in ("bash", "Bash", "command_execution") and JK_SHELL.search(" " + _unshell(str(args.get("command") or ""))):
        name = "jk-shell"
    return {"name": name, "cat": _cat(name, args), "args": args, "out_bytes": len(out.encode()), "ms": ms, "ok": ok,
            "text": out}


def stream_calls(ev: list[dict]) -> list[dict]:
    pending = {}
    order = []
    for d in ev:
        t = d.get("_t_ms")
        msg = d.get("message") if isinstance(d.get("message"), dict) else {}
        for b in msg.get("content") or [] if isinstance(msg.get("content"), list) else []:
            if not isinstance(b, dict):
                continue
            if b.get("type") == "tool_use":
                pending[b.get("id")] = (b.get("name") or "", b.get("input") or {}, t)
                order.append(b.get("id"))
            elif b.get("type") == "tool_result" and b.get("tool_use_id") in pending:
                n, a, t0 = pending[b["tool_use_id"]]
                txt = _text(b.get("content"))
                c = _mk(n, a, txt, (t - t0) if (t is not None and t0 is not None) else None, not b.get("is_error"))
                pending[b["tool_use_id"]] = c
    return [pending[i] for i in order if isinstance(pending.get(i), dict)]


def codex_calls(ev: list[dict]) -> list[dict]:
    started = {}
    out = []
    for d in ev:
        it = d.get("item") if isinstance(d.get("item"), dict) else None
        if not it:
            continue
        typ = it.get("type")
        if d.get("type") == "item.started":
            started[it.get("id")] = d.get("_t_ms")
            continue
        if d.get("type") != "item.completed":
            continue
        ms = (d.get("_t_ms") - started[it["id"]]) if it.get("id") in started and d.get("_t_ms") is not None else None
        if typ == "mcp_tool_call":
            res = it.get("result") or {}
            txt = _text(res.get("content")) if isinstance(res, dict) else ""
            if it.get("error"):
                txt += json.dumps(it.get("error"))
            name = f"mcp__{it.get('server')}__{it.get('tool')}"
            out.append(_mk(name, it.get("arguments") or {}, txt, ms, not it.get("error")))
        elif typ == "command_execution":
            cmd = it.get("command") or ""
            c = _mk("command_execution", {"command": cmd}, it.get("aggregated_output") or "", ms, it.get("exit_code") == 0)
            out.append(c)
        elif typ == "file_change":
            out.append(_mk("file_change", {"paths": [x.get("path") for x in it.get("changes") or []]}, "", ms))
    return out


def cursor_calls(ev: list[dict]) -> list[dict]:
    out = []
    for d in ev:
        if d.get("type") != "tool_call" or d.get("subtype") != "completed":
            continue
        tc = d.get("tool_call") or {}
        key = next((k for k in tc if k.endswith("ToolCall")), None)
        if not key:
            continue
        body = tc[key] or {}
        args = body.get("args") or {}
        res = body.get("result") or {}
        ok = "success" in res
        succ = res.get("success") or res.get("error") or {}
        ms = None
        try:
            ms = int(tc.get("completedAtMs")) - int(tc.get("startedAtMs"))
        except Exception:
            pass
        if key == "mcpToolCall":
            name = f"mcp__{args.get('providerIdentifier') or args.get('serverIdentifier')}__{args.get('toolName')}"
            txt = _text(succ.get("content"))
            out.append(_mk(name, args.get("args") or {}, txt, ms, ok and not succ.get("isError")))
        elif key == "getMcpToolsToolCall":
            out.append(_mk("getMcpToolsToolCall", {"tool": args.get("toolName")}, _text(succ.get("content")), ms, ok))
        elif key == "shellToolCall":
            out.append(_mk("shellToolCall", {"command": args.get("command")}, json.dumps(succ), ms, ok))
        else:
            a = {k: v for k, v in args.items() if k != "toolCallId"}
            out.append(_mk(key, a, json.dumps(succ) if not isinstance(succ.get("content"), str) else succ["content"], ms, ok))
    return out


def agy_calls(ev: list[dict]) -> list[dict]:
    out = []
    for d in ev:
        s = d.get("step_update") if d.get("event") == "step_update" else None
        if not isinstance(s, dict) or s.get("state") not in ("DONE", "ERROR") or s.get("step_type") != "tool":
            continue
        info = s.get("tool_info") or {}
        o = str(info.get("output") or "")
        c = _mk(s.get("tool_name") or "", info.get("parameters") or {}, o, int(float(s.get("duration_seconds") or 0) * 1000),
                s.get("state") == "DONE" and not info.get("error"))
        m = re.fullmatch(r"\d+ lines, (\d+) bytes", o.strip())
        if m:
            c["out_bytes"] = int(m.group(1))
        out.append(c)
    return out


def muse_calls(ev: list[dict]) -> list[dict]:
    sched = {}
    out = []
    for d in ev:
        p = d.get("payload") if isinstance(d.get("payload"), dict) else {}
        e = p.get("event") if isinstance(p.get("event"), dict) else {}
        k = str(e.get("idempotency_key") or "")
        if d.get("payload_type") == "task.lifecycle.scheduled" and k.startswith("tool:"):
            sched[k[5:]] = d.get("_t_ms")
        if d.get("payload_type") == "tool.result":
            f = p.get("correlation_facts") or {}
            name = str(f.get("tool_name") or "")
            t0 = sched.get(p.get("call_id"))
            ms = d.get("_t_ms") - t0 if (t0 is not None and d.get("_t_ms") is not None) else None
            args = {}
            ef = p.get("edit_facts") or {}
            if ef.get("path"):
                args["path"] = ef["path"]
            txt = str(p.get("text") or "")
            m = re.search(r"Read text file `([^`]+)`", txt)
            if m:
                args["path"] = m.group(1)
            out.append(_mk(name, args, txt, ms, f.get("outcome") != "failure"))
    return out


def calls(transcript: Path, driver: str) -> list[dict]:
    ev = _load(transcript)
    if driver == "codex":
        return codex_calls(ev)
    if driver == "cursor":
        return cursor_calls(ev)
    if driver == "agy":
        return agy_calls(ev)
    if driver == "muse":
        return muse_calls(ev)
    return stream_calls(ev)


def assistant_texts(transcript: Path, driver: str) -> list[str]:
    out = []
    for d in _load(transcript):
        if driver == "codex":
            it = d.get("item") or {}
            if it.get("type") in ("agent_message", "reasoning"):
                out.append(str(it.get("text") or ""))
        elif driver in ("claude-code", "grok", "cursor"):
            if d.get("type") == "assistant":
                for b in (d.get("message") or {}).get("content") or []:
                    if isinstance(b, dict) and b.get("type") in ("text", "thinking"):
                        out.append(str(b.get("text") or b.get("thinking") or ""))
            elif d.get("type") == "thinking" and driver == "cursor":
                out.append(str(d.get("text") or ""))
        elif driver == "agy":
            s = d.get("step_update") or {}
            for k in ("text_delta", "thinking_delta"):
                if s.get(k):
                    out.append(str(s[k]))
        elif driver == "muse":
            p = d.get("payload") or {}
            if isinstance(p, dict) and isinstance(p.get("delta"), str):
                out.append(p["delta"])
    return out


if __name__ == "__main__":
    for c in calls(Path(sys.argv[1]), sys.argv[2]):
        a = json.dumps(c["args"])[:160]
        print(f"{c['cat']:8} {c['name']:22} {c['out_bytes']:>7}B {str(c['ms']):>7}ms ok={c['ok']} {a}")
