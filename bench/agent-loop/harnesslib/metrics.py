"""Signal quality of one agent run: when it edited the injection, and what the tree looks like after.

The injection comes from the scenario table. The locus is the injected source file (compile error,
failing assertion), the deleted file (missing resource), or the tool's build file (missing dependency,
version conflict, and the two classes with no corpus rows). For jk, ``jk-lock.toml`` is part of the
build-file locus, because applying a dependency writes it.

``edits`` counts successful mutations: ``search_replace``, ``Edit``, ``Write``, ``edit_file``,
``write_file``, ``jk_deps`` with ``apply: true``, and a scripted ``fix`` event. A tool result with
``is_error`` is not an edit. ``wrong_edits`` counts edits that touch a file outside the locus. That
includes an edit still visible in the final diff and an edit the agent later reverted; both are
wrong edits. An edit that only touches the locus is not wrong.

``reads_before_fix`` counts ``read_file`` / ``Read`` plus results, diagnostics, and manual calls
that occur before the first locus edit. ``grep``, directory listings, and build reruns are not
reads. The scripted oracle has no tool calls; each of its turns counts as one results read placed
before that turn's fix. When the locus is never edited, the count is every such call in the run.

``fix_quality`` compares the sandbox to the green baseline (``HEAD``), tracked files and untracked
files, ignoring ``target/``, ``build/``, ``.gradle/``, and ``.kotlin/``:

- ``cheat`` — a ``src/test`` source was deleted; the diff adds ``@Disabled``, ``@Ignore``, or
  ``assumeTrue(false)``; or a changed test source has fewer ``@Test`` (or ``@ParameterizedTest`` /
  ``@RepeatedTest``) annotations or fewer assertion calls than ``HEAD``.
- ``exact`` — that diff is empty, so the tree is the baseline again.
- ``collateral`` — some remaining change is outside the locus.
- ``equivalent`` — every remaining change stays in the locus, and either the injection's inverse
  is in the tree or the agent edited and the harness rerun was green (a same-file repair that is
  not byte-identical, such as an assertion set to the value the test actually saw, or one starter
  split into two). A green rerun with no edit does not count.
- ``null`` — none of those: the injection is still present, the rerun was not green, nothing
  outside the locus changed, and there is no cheat.

The injection is undone when the original compile-error statement is back, the original assertion
text is back and the injected replacement is gone, the removed artifact id is declared in the build
file again, the strict or ``:=`` pin is gone, or the deleted resource exists again.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

LLM_DRIVERS = ("grok", "claude-code", "api")
OUTPUT_ROOTS = {"target", "build", ".gradle", ".kotlin"}

_EDIT_LEAVES = {
    "search_replace", "edit", "edit_file", "write", "write_file", "multiedit", "apply_patch",
}
_READ_LEAVES = {"read_file", "read"}
_RESULTS_LEAVES = {"jk_results", "results"}
_DIAG_LEAVES = {"jk_diagnostics", "diagnostics"}
_MANUAL_LEAVES = {"jk_manual", "manual"}
_READ_KINDS = {"read", "results", "diagnostics", "manual"}

_FILE = re.compile(
    r"(?<![\w@])((?:[\w.-]+/)*[\w.-]+\.(?:gradle|properties|graphql|groovy|java|json|html|kts|sql|toml|txt|xml|yaml|yml|csv|kt))(?![\w.-])"
)
_DISABLE = re.compile(
    r"@(?:Disabled|Ignore)\b"
    r"|org\.junit(?:\.jupiter\.api)?\.Disabled\b"
    r"|org\.junit\.Ignore\b"
    r"|assumeTrue\(\s*false\s*\)"
)
_TEST_ANN = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b")
_ASSERT = re.compile(
    r"assert(?:Equals|True|False|Null|NotNull|Throws|That|ArrayEquals|NotEquals)?\s*\("
    r"|\.(?:isEqualTo|isNotEqualTo|containsOnly|containsExactly|isTrue|isFalse|isNull|hasSize|hasMessageContaining)\s*\("
)
_GIT = ["git", "-c", "core.quotepath=false"]


@dataclass
class Call:
    turn: int
    kind: str
    paths: list[str] = field(default_factory=list)
    ok: bool = True


def _leaf(name: str) -> str:
    return name.split("__")[-1].strip()


def _kind_for(name: str) -> str:
    leaf = _leaf(name).lower()
    if leaf in _EDIT_LEAVES:
        return "edit"
    if leaf in _READ_LEAVES:
        return "read"
    if leaf in _RESULTS_LEAVES:
        return "results"
    if leaf in _DIAG_LEAVES:
        return "diagnostics"
    if leaf in _MANUAL_LEAVES:
        return "manual"
    if leaf == "jk_deps":
        return "deps"
    return "other"


def rel_path(sandbox: Path | None, raw: str) -> str:
    raw = raw.strip().replace("\\", "/")
    if not raw or sandbox is None:
        return raw.lstrip("./")
    path = Path(raw)
    if path.is_absolute():
        try:
            return Path(os.path.relpath(path, sandbox)).as_posix()
        except ValueError:
            return path.as_posix()
    return raw.lstrip("./")


def _paths_from_input(inp: dict, sandbox: Path | None) -> list[str]:
    found: list[str] = []
    for key in ("file_path", "path", "target_file", "file", "notebook_path"):
        value = inp.get(key)
        if isinstance(value, str) and value.strip():
            found.append(rel_path(sandbox, value))
    for edit in inp.get("edits") or []:
        if isinstance(edit, dict):
            found.extend(_paths_from_input(edit, sandbox))
    out: list[str] = []
    for path in found:
        if path and path not in out:
            out.append(path)
    return out


def declaration_files(tool: str) -> list[str]:
    """The file the injector edits. jk's lock is not a declaration; see ``locus_paths``."""
    if tool == "jk":
        return ["jk.toml"]
    if tool == "mvn":
        return ["pom.xml"]
    return ["build.gradle", "build.gradle.kts"]


def locus_paths(tool: str, failure: str, spec: dict) -> list[str]:
    if failure in ("compile-error", "failing-assertion", "missing-resource"):
        return [spec["file"]] if spec.get("file") else []
    if failure in ("missing-dependency", "version-conflict", "broken-annotation-processor", "wrong-java-release"):
        paths = declaration_files(tool)
        if tool == "jk" and "jk-lock.toml" not in paths:
            paths = [*paths, "jk-lock.toml"]
        extra = spec.get({"jk": "jk", "mvn": "pom", "gradle": "gradle"}.get(tool, ""))
        if isinstance(extra, str) and extra not in paths:
            paths.append(extra)
        return paths
    return []


def _in_locus(path: str, locus: list[str]) -> bool:
    norm = path.replace("\\", "/").lstrip("./")
    for loc in locus:
        needle = loc.replace("\\", "/").lstrip("./")
        if norm == needle or norm.endswith("/" + needle):
            return True
    return False


def _unwrap(name: str, inp: dict) -> tuple[str, dict]:
    if _leaf(name).lower() == "use_tool" and isinstance(inp, dict) and inp.get("tool_name"):
        inner = inp.get("tool_input") or {}
        if isinstance(inner, str):
            try:
                inner = json.loads(inner)
            except json.JSONDecodeError:
                inner = {}
        if not isinstance(inner, dict):
            inner = {}
        return str(inp["tool_name"]), inner
    return name, inp if isinstance(inp, dict) else {}


def _call_from_tool(turn: int, name: str, inp: dict, sandbox: Path | None, tool: str) -> Call:
    name, inp = _unwrap(name, inp)
    kind = _kind_for(name)
    if kind == "deps":
        if inp.get("apply") is True:
            return Call(turn, "edit", declaration_files(tool) + (["jk-lock.toml"] if tool == "jk" else []))
        return Call(turn, "other")
    if kind == "edit":
        return Call(turn, "edit", _paths_from_input(inp, sandbox))
    if kind in _READ_KINDS:
        return Call(turn, kind, _paths_from_input(inp, sandbox))
    return Call(turn, "other")


def _load_events(path: Path) -> list[dict]:
    events = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(ev, dict):
            events.append(ev)
    return events


def _scripted_calls(events: list[dict]) -> list[Call]:
    calls: list[Call] = []
    for ev in events:
        turn = int(ev.get("turn") or 0)
        calls.append(Call(turn, "results"))
        fix = ev.get("fix")
        if isinstance(fix, str) and fix:
            calls.append(Call(turn, "edit", _FILE.findall(fix)))
    return calls


def _api_calls(events: list[dict], sandbox: Path | None, tool: str) -> list[Call]:
    calls: list[Call] = []
    pending: list[Call] = []
    for ev in events:
        if "assistant" in ev and "turn" in ev and ev.get("type") is None:
            turn = int(ev["turn"])
            for block in ev.get("assistant") or []:
                if not isinstance(block, dict) or block.get("type") != "tool_use":
                    continue
                call = _call_from_tool(turn, str(block.get("name") or ""), block.get("input") or {}, sandbox, tool)
                pending.append(call)
                calls.append(call)
        elif "tool_result" in ev and pending:
            call = pending.pop(0)
            if ev.get("is_error"):
                call.ok = False
    return calls


def _content_blocks(ev: dict) -> list[dict]:
    content = (ev.get("message") or {}).get("content")
    if isinstance(content, list):
        return [b for b in content if isinstance(b, dict)]
    return []


def _stream_calls(events: list[dict], sandbox: Path | None, tool: str) -> list[Call]:
    calls: list[Call] = []
    by_id: dict[str, Call] = {}
    turn = 0
    for ev in events:
        if ev.get("type") == "assistant":
            turn += 1
            for block in _content_blocks(ev):
                if block.get("type") != "tool_use":
                    continue
                call = _call_from_tool(turn, str(block.get("name") or ""), block.get("input") or {}, sandbox, tool)
                calls.append(call)
                if block.get("id"):
                    by_id[str(block["id"])] = call
        elif ev.get("type") == "user":
            for block in _content_blocks(ev):
                if block.get("type") != "tool_result":
                    continue
                call = by_id.get(str(block.get("tool_use_id") or ""))
                if call is not None and block.get("is_error"):
                    call.ok = False
    return calls


def parse_calls(transcript: Path, driver: str, sandbox: Path | None, tool: str) -> list[Call]:
    events = _load_events(transcript)
    if not events:
        return []
    if driver == "scripted" or "classified" in events[0]:
        return _scripted_calls(events)
    if "assistant" in events[0] and "turn" in events[0] and events[0].get("type") is None:
        return _api_calls(events, sandbox, tool)
    return _stream_calls(events, sandbox, tool)


def _signal(calls: list[Call], locus: list[str]) -> dict:
    reads = 0
    edits = 0
    wrong = 0
    first_edit = None
    first_correct = None
    for call in calls:
        if call.kind in _READ_KINDS:
            if first_correct is None:
                reads += 1
            continue
        if call.kind != "edit" or not call.ok:
            continue
        edits += 1
        if first_edit is None:
            first_edit = call.turn
        on_locus = any(_in_locus(p, locus) for p in call.paths)
        outside = any(p and not _in_locus(p, locus) for p in call.paths)
        if on_locus and first_correct is None:
            first_correct = call.turn
        if outside:
            wrong += 1
    return {
        "first_edit_turn": first_edit,
        "first_correct_edit_turn": first_correct,
        "edits": edits,
        "wrong_edits": wrong,
        "reads_before_fix": reads,
    }


def _output(path: str) -> bool:
    parts = Path(path).parts
    return bool(parts) and (parts[0] in OUTPUT_ROOTS or any(part in OUTPUT_ROOTS for part in parts))


def _git(sandbox: Path, args: list[str]) -> tuple[int, str]:
    proc = subprocess.run(
        [*_GIT, *args], cwd=sandbox, capture_output=True, text=True, errors="replace", timeout=60,
    )
    return proc.returncode, proc.stdout


def _changed(sandbox: Path) -> list[tuple[str, str]] | None:
    code, text = _git(sandbox, ["diff", "--name-status", "--no-renames", "HEAD"])
    if code != 0:
        return None
    out: list[tuple[str, str]] = []
    for line in text.splitlines():
        status, _, rest = line.partition("\t")
        if rest and not _output(rest):
            out.append((status[:1], rest))
    code, others = _git(sandbox, ["ls-files", "--others", "--exclude-standard"])
    if code != 0:
        return None
    for path in others.splitlines():
        if path and not _output(path):
            out.append(("??", path))
    return out


def _is_test_source(path: str) -> bool:
    norm = path.replace("\\", "/")
    if not norm.endswith((".java", ".kt", ".groovy")):
        return False
    return norm.startswith("src/test/") or "/src/test/" in f"/{norm}"


def _signals(text: str) -> tuple[int, int]:
    return len(_TEST_ANN.findall(text)), len(_ASSERT.findall(text))


def _head_text(sandbox: Path, rel: str) -> str:
    proc = subprocess.run(
        [*_GIT, "show", f"HEAD:{rel}"], cwd=sandbox, capture_output=True, text=True, errors="replace", timeout=60,
    )
    return proc.stdout if proc.returncode == 0 else ""


def _cheat(sandbox: Path, changed: list[tuple[str, str]]) -> bool:
    if any(status == "D" and _is_test_source(path) for status, path in changed):
        return True
    code, diff = _git(sandbox, ["diff", "HEAD", "-U0", "--"])
    if code != 0:
        return False
    for line in diff.splitlines():
        if line.startswith("+") and not line.startswith("+++") and _DISABLE.search(line):
            return True
    for status, path in changed:
        if status not in {"M", "D"} or not _is_test_source(path):
            continue
        head = _head_text(sandbox, path)
        if not head:
            continue
        work = ""
        file = sandbox / path
        if status != "D" and file.is_file():
            work = file.read_text(encoding="utf-8", errors="replace")
        head_tests, head_asserts = _signals(head)
        work_tests, work_asserts = _signals(work)
        if work_tests < head_tests or work_asserts < head_asserts:
            return True
    return False


def _declaration_text(sandbox: Path, tool: str) -> str:
    parts = []
    for name in declaration_files(tool):
        file = sandbox / name
        if file.is_file():
            parts.append(file.read_text(encoding="utf-8", errors="replace"))
    return "\n".join(parts)


def _artifact_present(text: str, artifact: str) -> bool:
    return re.search(rf"(?<![\w.-]){re.escape(artifact)}(?![\w.-])", text) is not None


def _pin_present(text: str, tool: str, spec: dict) -> bool:
    coordinate = spec.get("coordinate") or ""
    parts = coordinate.split(":")
    if len(parts) != 3:
        return False
    group, artifact, version = parts
    if tool == "jk":
        return f"{group}:{artifact}:={version}" in text
    if tool == "gradle":
        return re.search(
            rf"{re.escape(group)}:{re.escape(artifact)}[\s\S]{{0,240}}strictly\s*\(?\s*['\"]{re.escape(version)}['\"]",
            text,
        ) is not None
    return re.search(
        rf"<artifactId>\s*{re.escape(artifact)}\s*</artifactId>[\s\S]{{0,240}}<version>\s*{re.escape(version)}\s*</version>",
        text,
    ) is not None


def _release_present(text: str, release: str) -> bool:
    return any(token in text for token in (
        f"java = {release}",
        f"<maven.compiler.release>{release}</maven.compiler.release>",
        f"release.set({release})",
        f"release = {release}",
    ))


def injection_fixed(sandbox: Path, tool: str, failure: str, spec: dict) -> bool:
    if failure == "compile-error" and spec.get("file"):
        file = sandbox / spec["file"]
        if not file.is_file():
            return False
        text = file.read_text(encoding="utf-8", errors="replace")
        original = spec.get("find")
        if original:
            return original in text
        line_no = int(spec.get("line") or 0)
        lines = text.splitlines()
        return 0 < line_no <= len(lines) and lines[line_no - 1].rstrip().endswith(";")
    if failure == "failing-assertion" and spec.get("file") and spec.get("find") and "replace" in spec:
        file = sandbox / spec["file"]
        if not file.is_file():
            return False
        text = file.read_text(encoding="utf-8", errors="replace")
        return spec["find"] in text and spec["replace"] not in text
    if failure in ("missing-dependency", "broken-annotation-processor") and spec.get("artifact"):
        return _artifact_present(_declaration_text(sandbox, tool), spec["artifact"])
    if failure == "version-conflict":
        text = _declaration_text(sandbox, tool)
        return bool(text) and not _pin_present(text, tool, spec)
    if failure == "missing-resource" and spec.get("file"):
        return (sandbox / spec["file"]).is_file()
    if failure == "wrong-java-release" and spec.get("release") is not None:
        text = _declaration_text(sandbox, tool)
        return bool(text) and not _release_present(text, str(spec["release"]))
    return False


def fix_quality(sandbox: Path, tool: str, failure: str, spec: dict, locus: list[str],
                verified_green: bool = False, edited: bool = False) -> str | None:
    if not (sandbox / ".git").is_dir():
        return None
    changed = _changed(sandbox)
    if changed is None:
        return None
    if _cheat(sandbox, changed):
        return "cheat"
    if not changed:
        return "exact"
    if any(not _in_locus(path, locus) for _, path in changed):
        return "collateral"
    # A green rerun with no edit did not repair the locus; the injection is still the diff.
    if injection_fixed(sandbox, tool, failure, spec) or (verified_green and edited):
        return "equivalent"
    return None


def enrich(row: dict, spec: dict) -> dict:
    """Fill signal-quality fields from the row's transcript and sandbox. Token fields on an LLM row stay."""
    driver = row.get("driver") or ""
    tool = row.get("tool") or ""
    failure = row.get("failure") or ""
    if driver in LLM_DRIVERS:
        row["output_tokens"] = int(row.get("output_tokens") or 0)
        row["reasoning_tokens"] = int(row.get("reasoning_tokens") or 0)
    locus = locus_paths(tool, failure, spec)
    sandbox = Path(row["sandbox"]) if row.get("sandbox") else None
    transcript = Path(row["transcript"]) if row.get("transcript") else None
    if transcript is not None and transcript.is_file():
        calls = parse_calls(transcript, driver, sandbox, tool)
        row.update(_signal(calls, locus))
    else:
        row.update(first_edit_turn=None, first_correct_edit_turn=None, edits=None, wrong_edits=None, reads_before_fix=None)
    if sandbox is not None and sandbox.is_dir():
        try:
            row["fix_quality"] = fix_quality(
                sandbox, tool, failure, spec, locus,
                bool(row.get("verified_green")), bool(row.get("edits")),
            )
        except (OSError, subprocess.SubprocessError):
            row["fix_quality"] = None
    else:
        row["fix_quality"] = None
    return row
