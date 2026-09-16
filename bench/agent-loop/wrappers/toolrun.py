"""Run Maven or Gradle through `jk mvn` / `jk gradle`, then write `target/jk-results.md`.

Shared by `mvn-results` and `gradle-results`: the tool's console goes to the terminal and to
`target/<tool>.log`, the JUnit XML written during the run is collected, and the report plus
`target/jk-diagnostics.json` land beside it. The process exits with the tool's exit code.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

import jkresults

DEFAULT_ARGS = {
    "mvn": ["-B", "-ntp", "-V"],
    "gradle": ["--console=plain"],
}
DEFAULT_GOAL = {"mvn": ["test"], "gradle": ["test"]}


def main(tool: str, argv: list[str]) -> int:
    project_dir = Path.cwd().resolve()
    args = argv or DEFAULT_GOAL[tool]
    target = project_dir / "target"
    target.mkdir(exist_ok=True)
    log_path = target / f"{tool}.log"
    results_path = target / "jk-results.md"
    cmd = ["jk", tool, *DEFAULT_ARGS[tool], *args]
    started = time.time()
    with log_path.open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            log.write(line)
        exit_code = proc.wait()
    wall_ms = int((time.time() - started) * 1000)
    kind = "test" if any(a in ("test", "verify", "check", "build") for a in args) else "build"
    coord = jkresults.maven_coord(project_dir) if tool == "mvn" else jkresults.gradle_coord(project_dir)
    report = jkresults.Report(
        tool=tool,
        tool_label=f"{jkresults.wrapper_version(project_dir, tool)} via {tool}-results",
        kind=kind,
        coord=coord,
        exit_code=exit_code,
        wall_ms=wall_ms,
        project_dir=str(project_dir),
        results_path=str(results_path),
        log_path=str(log_path),
    )
    text = log_path.read_text(encoding="utf-8", errors="replace")
    # JUnit XML first: the log parsers use the failing tests to decide what a failed test
    # task needs said about it. A file from before this run is stale; only what the run
    # wrote counts.
    jkresults.collect_junit(project_dir, started, report)
    if tool == "mvn":
        m = jkresults.MVN_VERSION.search(text)
        if m and not any(c.isdigit() for c in report.tool_label):
            report.tool_label = f"Maven {m.group(1)} via mvn-results"
        jkresults.parse_maven_log(text, report)
    else:
        jkresults.parse_gradle_log(text, report)
    results_path.write_text(jkresults.render(report), encoding="utf-8")
    (target / "jk-diagnostics.json").write_text(jkresults.diagnostics_json(report), encoding="utf-8")
    if os.environ.get("RESULTS_QUIET") is None:
        sys.stderr.write(f"Results: {results_path}\n")
    return exit_code
