"""What the CLI prints: bytes and lines on stdout and stderr for green and red builds and tests, under
a terminal and plain, for jk beside Maven and Gradle on the same petclinic.

Scenarios (`cli.output.<scenario>.<mode>.<counter>`):

  build-green   a build after a one-file edit
  build-red     the same edit with a compile error in it
  test-green    the whole suite (night only: the suite is the project's Testcontainers MySQL)
  test-red      the suite with one failing test added (night only)

Modes: `tty` runs under script(1) at 120 columns, so progress redraws and colour count; `plain` is
`--no-ansi` for jk, `-q` for Maven and Gradle (their quiet modes, the closest to a one-line
answer), and the `full` mode is Maven and Gradle without `-q`. Counters are `bytes` and `lines`.

The fast tier runs jk's build scenarios only; the night tier adds the tests and the other tools.
"""

from __future__ import annotations

import os
import shlex
import subprocess
import sys
from importlib.machinery import SourceFileLoader
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _petclinic as pc  # noqa: E402
import benchtools  # noqa: E402

TIERS = ("fast", "night", "release")
MEASURE = Path(__file__).resolve().parent.parent / "wall" / "measure"
FAIL_TEST = "src/test/java/org/springframework/samples/petclinic/ScoreboardRedTests.java"
RED_TEST = """package org.springframework.samples.petclinic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScoreboardRedTests {
    @Test
    void fails() {
        assertEquals(2, 1 + 2, "the scoreboard's red test");
    }
}
"""
# A terminal run redraws progress on a clock, so its size moves with how long the build took.
TTY_BAND = 0.25
# A green build should answer in one line under 120 bytes.
TARGETS = {("build-green", "plain", "lines"): 1, ("build-green", "plain", "bytes"): 120}


def captured(cmd: list[str], cwd: Path, env: dict, tty: bool) -> tuple[int, bytes]:
    env = dict(env, COLUMNS="120", LINES="40")
    if tty:
        cmd = ["script", "-qefc", f"stty cols 120 rows 40; {shlex.join(cmd)}", "/dev/null"]
        env.setdefault("TERM", "xterm-256color")
    res = subprocess.run(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    return res.returncode, res.stdout


def break_compile(work: Path, edit_file: str) -> None:
    with open(work / edit_file, "a", encoding="utf-8") as fh:
        fh.write("\nclass ScoreboardRed { int x = \"not an int\"; }\n")


def jk_rows(ctx, scenarios: list[str]) -> list[dict]:
    state = ctx.home / "cli-state"
    env = pc.engine_env(state, jk=ctx.jk)
    pc.stop(ctx.jk, state)
    work = pc.working_copy(ctx.home, ctx.jk, state, f"{pc.PROJECT}-cli")
    rows = []
    for scenario in scenarios:
        for mode in ("tty", "plain"):
            pc.settle(work, ctx.jk, env)
            (work / FAIL_TEST).unlink(missing_ok=True)
            verb = scenario.split("-")[0]
            if verb == "build":
                pc.edit(work)
            if scenario == "build-red":
                break_compile(work, pc.EDIT)
            if scenario == "test-red":
                (work / FAIL_TEST).write_text(RED_TEST, encoding="utf-8")
            cmd = [ctx.jk, verb] + (["--skip-tests"] if verb == "build" else []) + ([] if mode == "tty" else ["--no-ansi"])
            rc, out = captured(cmd, work, env, mode == "tty")
            (ctx.out / f"cli-jk-{scenario}-{mode}.txt").write_bytes(out)
            rows += output_rows("jk", scenario, mode, out, rc, ctx)
    (work / FAIL_TEST).unlink(missing_ok=True)
    pc.unedit(work)
    pc.settle(work, ctx.jk, env)
    pc.stop(ctx.jk, state)
    return rows


def tool_rows(ctx, tool: str, scenarios: list[str]) -> list[dict]:
    """Maven or Gradle in the wall harness's working copy, quiet and full."""
    w = SourceFileLoader("wall_measure_cli", str(MEASURE)).load_module()
    try:
        w.PINNED = benchtools.resolve(allow_stale=True)
    except SystemExit as e:
        ctx.skipped.append(f"cli.output [{tool}]: {e}")
        return []
    w.MVN, w.GRADLE = str(w.PINNED.maven_bin), str(w.PINNED.gradle_bin)
    repo = w.clone()
    java_home, _ = benchtools.java_for_project(repo, tool)
    w.JAVA_FOR["mvn" if tool == "maven" else "gradle"] = {
        "JAVA_HOME": str(java_home), "PATH": f"{java_home / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}"}
    work = w.working_copy(tool, repo, ctx.out / f"cli-{tool}-setup.log")
    rows = []
    for scenario in scenarios:
        for mode in ("tty", "plain", "full"):
            w.unedit(work)
            (work / FAIL_TEST).unlink(missing_ok=True)
            verb = scenario.split("-")[0]
            if verb == "build":
                w.edit(work, "cli")
            if scenario == "build-red":
                break_compile(work, w.PROJECT["edit"])
            if scenario == "test-red":
                (work / FAIL_TEST).write_text(RED_TEST, encoding="utf-8")
            cmd = w.build_cmd(tool) if verb == "build" else w.test_cmd(tool)
            if tool == "maven":
                cmd = [c for c in cmd if c != "-q"]
            if mode == "plain":
                cmd = [cmd[0], "-q", *cmd[1:]]
            rc, out = captured(cmd, work, w.tool_env(cmd), mode == "tty")
            (ctx.out / f"cli-{tool}-{scenario}-{mode}.txt").write_bytes(out)
            rows += output_rows(tool, scenario, mode, out, rc, ctx)
    (work / FAIL_TEST).unlink(missing_ok=True)
    w.unedit(work)
    return rows


def output_rows(tool: str, scenario: str, mode: str, out: bytes, rc: int, ctx) -> list[dict]:
    if (rc == 0) != scenario.endswith("green"):
        ctx.skipped.append(f"cli.output.{scenario}.{mode} [{tool}]: exit {rc}, not the scenario's outcome")
        return []
    rows = []
    for counter, value, unit in (("bytes", len(out), "bytes"), ("lines", out.count(b"\n"), "lines")):
        subject = f"cli.output.{scenario}.{mode}.{counter}"
        if ctx.wants(subject):
            rows.append({"dim": "cli", "subject": subject, "project": pc.PROJECT, "tool": tool, "kind": "count",
                         "value": value, "unit": unit, "band": TTY_BAND if mode == "tty" else 0.0,
                         "slack": (256 if mode == "tty" else 8) if unit == "bytes" else 0, "runs": 1,
                         "target": TARGETS.get((scenario, mode, counter)) if tool == "jk" else None})
    return rows


def measure(ctx) -> list[dict]:
    night = ctx.tier != "fast"
    scenarios = ["build-green", "build-red"] + (["test-green", "test-red"] if night else [])
    rows = jk_rows(ctx, scenarios)
    if night:
        for tool in ("maven", "gradle"):
            rows += tool_rows(ctx, tool, scenarios)
    return rows
