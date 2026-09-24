"""Run the pinned Maven or Gradle, then write `target/jk-results.md`.

Shared by `mvn-results` and `gradle-results`. The binary is the one `bench/tools.toml`
names, provisioned through jk and executed directly — not `jk mvn` / `jk gradle`, which
would follow the repo wrapper. The console goes to the terminal and to `target/<tool>.log`.
The process exits with the tool's exit code. `target/jk-bench.json` records the pin, the
binary, and the JDK the process was given.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

_BENCH = Path(__file__).resolve().parents[2]
if str(_BENCH) not in sys.path:
    sys.path.insert(0, str(_BENCH))
import benchtools  # noqa: E402

import jkresults  # noqa: E402

DEFAULT_ARGS = {
    "mvn": ["-B", "-ntp", "-V"],
    "gradle": ["--console=plain"],
}
DEFAULT_GOAL = {"mvn": ["test"], "gradle": ["test"]}
_FLAGS = ("--allow-stale-tools", "--offline-tools")


def _take_flags(argv: list[str]) -> tuple[bool, bool, list[str]]:
    allow = "--allow-stale-tools" in argv or os.environ.get("JK_BENCH_ALLOW_STALE_TOOLS") == "1"
    offline = "--offline-tools" in argv or os.environ.get("JK_BENCH_OFFLINE_TOOLS") == "1"
    if "--allow-stale-tools" in argv:
        os.environ["JK_BENCH_ALLOW_STALE_TOOLS"] = "1"
    if "--offline-tools" in argv:
        os.environ["JK_BENCH_OFFLINE_TOOLS"] = "1"
    return allow, offline, [arg for arg in argv if arg not in _FLAGS]


def _write_meta(project: Path, meta: dict) -> None:
    target = project / "target"
    target.mkdir(exist_ok=True)
    (target / "jk-bench.json").write_text(json.dumps(meta, indent=1) + "\n", encoding="utf-8")


def _launch_env(project: Path, tool: str, tools: benchtools.Tools) -> tuple[dict, dict]:
    env = os.environ.copy()
    stripped = [key for key in benchtools.STRIPPED if key in env]
    for key in stripped:
        env.pop(key, None)
    meta = benchtools.stamp(tools)
    meta["tool"] = tool
    meta["env_stripped"] = stripped
    try:
        home, line = benchtools.java_for_project(project, tool)
    except benchtools.JdkUnavailable as exc:
        meta["jdk"] = str(exc)
        if exc.detail:
            meta["jdk_detail"] = exc.detail.splitlines()[0][:300]
        _write_meta(project, meta)
        raise SystemExit(str(exc)) from exc
    env["JAVA_HOME"] = str(home)
    env["PATH"] = str(home / "bin") + os.pathsep + env.get("PATH", "")
    meta["java_home"] = str(home)
    meta["java_version"] = line
    jvm = project / ".mvn" / "jvm.config"
    if tool == "mvn" and jvm.is_file():
        meta["maven_jvm_config"] = jvm.read_text(encoding="utf-8", errors="replace")[:500]
    return env, meta


def main(tool: str, argv: list[str]) -> int:
    project_dir = Path.cwd().resolve()
    allow, offline, argv = _take_flags(argv)
    tools = benchtools.resolve(allow_stale=allow, offline=offline)
    args = argv or DEFAULT_GOAL[tool]
    target = project_dir / "target"
    target.mkdir(exist_ok=True)
    log_path = target / f"{tool}.log"
    results_path = target / "jk-results.md"
    binary = tools.maven_bin if tool == "mvn" else tools.gradle_bin
    if binary is None:
        raise SystemExit(f"refusing to run: no pinned {tool} binary")
    env, meta = _launch_env(project_dir, tool, tools)
    _write_meta(project_dir, meta)
    cmd = [str(binary), *DEFAULT_ARGS[tool], *args]
    started = time.time()
    with log_path.open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", env=env)
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            log.write(line)
        exit_code = proc.wait()
    wall_ms = int((time.time() - started) * 1000)
    kind = "test" if any(a in ("test", "verify", "check", "build") for a in args) else "build"
    coord = jkresults.maven_coord(project_dir) if tool == "mvn" else jkresults.gradle_coord(project_dir)
    label = "Maven" if tool == "mvn" else "Gradle"
    version = tools.maven_version if tool == "mvn" else tools.gradle_version
    report = jkresults.Report(
        tool=tool,
        tool_label=f"{label} {version} via {tool}-results",
        kind=kind,
        coord=coord,
        exit_code=exit_code,
        wall_ms=wall_ms,
        project_dir=str(project_dir),
        results_path=str(results_path),
        log_path=str(log_path),
    )
    text = log_path.read_text(encoding="utf-8", errors="replace")
    if tool == "gradle":
        rejected = benchtools.gradle_incompatibility(text)
        if rejected:
            meta["status"] = "incompatible-with-latest-gradle"
            meta["error"] = rejected
            _write_meta(project_dir, meta)
    # JUnit XML first: the log parsers use the failing tests to decide what a failed test
    # task needs said about it. A file from before this run is stale; only what the run
    # wrote counts.
    jkresults.collect_junit(project_dir, started, report)
    if tool == "mvn":
        jkresults.parse_maven_log(text, report)
    else:
        jkresults.parse_gradle_log(text, report)
    results_path.write_text(jkresults.render(report), encoding="utf-8")
    (target / "jk-diagnostics.json").write_text(jkresults.diagnostics_json(report), encoding="utf-8")
    if os.environ.get("RESULTS_QUIET") is None:
        sys.stderr.write(f"Results: {results_path}\n")
    return exit_code
