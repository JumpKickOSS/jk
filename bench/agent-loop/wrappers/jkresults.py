"""Turn a Maven or Gradle run into the report shape `jk` writes to `target/jk-results.md`.

The parsers read the tool's console log and the JUnit XML it left behind; the renderer
writes the same sections jk writes (headline, why-lines, counts, Files, Failures, Tests,
Failed steps, Warnings, Modules) so a consumer written against jk's file reads this one
unchanged. Section budgets match jk's renderer.
"""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from pathlib import Path

MAX_ERRORS = 40
MAX_WARNINGS = 20
MAX_STACK_LINES = 24
MAX_SNIPPET_LINES = 16
MAX_MESSAGE_CHARS = 2_000
MAX_FAILED_TASKS = 40
MAX_MODULES = 24
MAX_PACKAGES = 80
MAX_FAILED_TESTS = 40
MAX_WHY = 3
MAX_WHY_CHARS = 200

SOURCE_EXT = r"(?:java|kt|kts|groovy|scala)"


@dataclass
class Diag:
    task: str
    module: str = ""
    file: str | None = None
    line: int = 0
    col: int = 0
    message: str = ""
    snippet: list[str] = field(default_factory=list)
    severity: str = "error"

    def key(self) -> tuple:
        return (self.file, self.line, self.col, self.message.split("\n", 1)[0])


@dataclass
class TestCase:
    module: str
    class_name: str
    name: str
    status: str
    duration_ms: int = 0
    failure_type: str = ""
    failure_message: str = ""
    failure_stack: str = ""

    @property
    def package(self) -> str:
        return self.class_name.rpartition(".")[0] or "(default)"


@dataclass
class Step:
    module: str
    task: str
    status: str
    duration_ms: int = 0


@dataclass
class ModuleOutcome:
    name: str
    outcome: str
    duration_ms: int = 0


@dataclass
class Report:
    tool: str
    tool_label: str
    kind: str
    coord: str
    exit_code: int
    wall_ms: int
    project_dir: str
    results_path: str
    log_path: str
    junit_dirs: list[str] = field(default_factory=list)
    diags: list[Diag] = field(default_factory=list)
    tests: list[TestCase] = field(default_factory=list)
    steps: list[Step] = field(default_factory=list)
    modules: list[ModuleOutcome] = field(default_factory=list)

    @property
    def success(self) -> bool:
        return self.exit_code == 0

    def errors(self) -> list[Diag]:
        return [d for d in self.diags if d.severity == "error"]

    def warnings(self) -> list[Diag]:
        return [d for d in self.diags if d.severity == "warning"]

    def failed_tests(self) -> list[TestCase]:
        return [t for t in self.tests if t.status == "fail"]


# ----------------------------------------------------------------------------- maven

# Maven prints a plugin by prefix in a goal header (`compiler:3.14.0:compile`) and by artifact
# id in a failure line (`org.apache.maven.plugins:maven-compiler-plugin:3.14.0:compile`).
MVN_GOAL_TASKS = [
    (re.compile(r"(?:^|:)kotlin(?:-maven-plugin)?:[^:]*:test-compile"), "compile-test-kotlin"),
    (re.compile(r"(?:^|:)kotlin(?:-maven-plugin)?:[^:]*:compile"), "compile-kotlin"),
    (re.compile(r"(?:^|:)(?:maven-)?compiler(?:-plugin)?:[^:]*:testCompile"), "compile-test-java"),
    (re.compile(r"(?:^|:)(?:maven-)?compiler(?:-plugin)?:[^:]*:compile"), "compile-java"),
    (re.compile(r"(?:^|:)(?:maven-)?surefire(?:-plugin)?:[^:]*:test"), "run-tests"),
    (re.compile(r"(?:^|:)(?:maven-)?failsafe(?:-plugin)?:[^:]*:(?:integration-test|verify)"), "run-integration-tests"),
    (re.compile(r"(?:^|:)(?:maven-)?resources(?:-plugin)?:[^:]*:(?:test)?[rR]esources"), "copy-resources"),
    (re.compile(r"(?:^|:)(?:maven-)?jar(?:-plugin)?:[^:]*:jar"), "package-jar"),
]
MVN_HEADER = re.compile(r"^\[INFO\] --- (\S+?):(\S+?):(\S+?)(?: \((\S+)\))? @ (\S+) ---")
MVN_REACTOR = re.compile(
    r"^\[INFO\] (\S.*?) \.{2,} (SUCCESS|FAILURE|SKIPPED)(?: \[\s*([\d.]+) (s|min)\])?"
)
MVN_JAVAC = re.compile(rf"^\[(ERROR|WARNING)\] (/\S+?\.{SOURCE_EXT}):\[(\d+),(\d+)\] (.*)$")
MVN_KOTLINC = re.compile(rf"^\[(ERROR|WARNING)\] (/\S+?\.{SOURCE_EXT}): \((\d+), (\d+)\):? (.*)$")
MVN_CONT = re.compile(r"^\[(?:ERROR|WARNING)\] {2,}(\S.*)$")
MVN_GOAL_FAIL = re.compile(
    r"^\[ERROR\] Failed to execute goal (?:(\S+?) \((\S+)\) )?on project (\S+?): (.*)$"
)
MVN_TOTAL = re.compile(r"^\[INFO\] Total time:\s+([\d.]+) (s|min)")


def mvn_task(plugin_goal: str) -> str:
    for pat, task in MVN_GOAL_TASKS:
        if pat.search(plugin_goal):
            return task
    if "Could not resolve" in plugin_goal or "Could not find artifact" in plugin_goal:
        return "resolve"
    tail = plugin_goal.rsplit(":", 1)[-1]
    return tail or "build"


def parse_maven_log(text: str, report: Report) -> None:
    lines = text.splitlines()
    current_task = "build"
    current_module = ""
    module_times: dict[str, tuple[str, int]] = {}
    seen: set[tuple] = set()
    total_ms = 0
    i = 0
    while i < len(lines):
        line = lines[i]
        m = MVN_HEADER.match(line)
        if m:
            current_task = mvn_task(f"{m.group(1)}:{m.group(2)}:{m.group(3)}")
            current_module = m.group(5)
            i += 1
            continue
        m = MVN_REACTOR.match(line)
        if m:
            secs = float(m.group(3) or 0) * (60 if m.group(4) == "min" else 1)
            module_times[m.group(1)] = (m.group(2), int(secs * 1000))
            i += 1
            continue
        m = MVN_TOTAL.match(line)
        if m:
            total_ms = int(float(m.group(1)) * (60 if m.group(2) == "min" else 1) * 1000)
            i += 1
            continue
        m = MVN_JAVAC.match(line) or MVN_KOTLINC.match(line)
        if m:
            sev = "error" if m.group(1) == "ERROR" else "warning"
            file, ln, col, msg = m.group(2), int(m.group(3)), int(m.group(4)), m.group(5)
            extra = []
            j = i + 1
            while j < len(lines):
                c = MVN_CONT.match(lines[j])
                if not c:
                    break
                extra.append(c.group(1))
                j += 1
            message = f"{relative(file, report.project_dir)}:{ln}: {sev}: {msg}"
            if extra:
                message += "\n" + "\n".join(extra)
            d = Diag(current_task, current_module, file, ln, col, message, [], sev)
            if d.key() not in seen:
                seen.add(d.key())
                report.diags.append(d)
            i = j
            continue
        m = MVN_GOAL_FAIL.match(line)
        if m:
            plugin, module, msg = m.group(1) or "", m.group(3), m.group(4)
            task = mvn_task(plugin) if plugin else mvn_task(msg)
            report.steps.append(Step(module, task, "FAIL", module_times.get(module, ("", 0))[1]))
            if task == "resolve" or not any(d.task == task and d.severity == "error" for d in report.diags):
                body = [msg]
                j = i + 1
                while j < len(lines) and lines[j].startswith("[ERROR] ") and not lines[j].startswith("[ERROR] -> "):
                    body.append(lines[j][8:].rstrip())
                    j += 1
                d = Diag(task, module, None, 0, 0, "\n".join(body).strip(), [], "error")
                if d.key() not in seen:
                    seen.add(d.key())
                    report.diags.append(d)
            i += 1
            continue
        i += 1
    for name, (status, ms) in module_times.items():
        outcome = {"SUCCESS": "ok", "FAILURE": "FAIL", "SKIPPED": "skipped"}[status]
        report.modules.append(ModuleOutcome(name, outcome, ms))
    if report.wall_ms == 0 and total_ms:
        report.wall_ms = total_ms


# ---------------------------------------------------------------------------- gradle

GRADLE_TASK = re.compile(r"^> Task (:(?:[\w.-]+:)*)([\w-]+)(?: (FAILED|UP-TO-DATE|NO-SOURCE|SKIPPED|FROM-CACHE))?$")
GRADLE_JAVAC = re.compile(rf"^(/\S+?\.{SOURCE_EXT}):(\d+): (error|warning): (.*)$")
GRADLE_KOTLINC = re.compile(rf"^([ew]): (?:file://)?(/\S+?\.{SOURCE_EXT}):(\d+):(\d+) (.*)$")
GRADLE_KOTLINC_OLD = re.compile(rf"^([ew]): (/\S+?\.{SOURCE_EXT}): \((\d+), (\d+)\): (.*)$")
GRADLE_COUNT = re.compile(r"^\d+ (?:error|warning)s?$")
GRADLE_WENT_WRONG = "* What went wrong:"
GRADLE_TASK_FAILED = re.compile(r"Execution failed for task '(:(?:[\w.-]+:)*)([\w-]+)'")
GRADLE_RESOLVE = re.compile(
    r"Could not (?:resolve|find) |Could not resolve all|Cannot find a version of|satisfies the version constraints"
)

GRADLE_TASK_NAMES = {
    "compileJava": "compile-java",
    "compileTestJava": "compile-test-java",
    "compileKotlin": "compile-kotlin",
    "compileTestKotlin": "compile-test-kotlin",
    "compileGroovy": "compile-groovy",
    "compileTestGroovy": "compile-test-groovy",
    "processResources": "copy-resources",
    "processTestResources": "copy-test-resources",
    "test": "run-tests",
    "integrationTest": "run-integration-tests",
    "jar": "package-jar",
    "bootJar": "package-boot-jar",
}


def gradle_task(name: str) -> str:
    return GRADLE_TASK_NAMES.get(name, name)


def gradle_module(path: str) -> str:
    return path.strip(":").replace(":", "/")


def parse_gradle_log(text: str, report: Report) -> None:
    lines = text.splitlines()
    current_task = "build"
    current_module = ""
    failed: list[tuple[str, str]] = []
    seen: set[tuple] = set()
    modules_seen: dict[str, str] = {}
    i = 0
    while i < len(lines):
        line = lines[i]
        m = GRADLE_TASK.match(line)
        if m:
            current_module = gradle_module(m.group(1))
            current_task = gradle_task(m.group(2))
            modules_seen.setdefault(current_module, "ok")
            if m.group(3) == "FAILED":
                failed.append((current_module, current_task))
                modules_seen[current_module] = "FAIL"
            i += 1
            continue
        m = GRADLE_JAVAC.match(line)
        if m:
            file, ln, sev, msg = m.group(1), int(m.group(2)), m.group(3), m.group(4)
            snippet: list[str] = []
            j = i + 1
            while j < len(lines):
                nxt = lines[j]
                if (GRADLE_JAVAC.match(nxt) or GRADLE_TASK.match(nxt) or GRADLE_COUNT.match(nxt)
                        or nxt.startswith("FAILURE:") or nxt.startswith("> Task") or not nxt.strip()):
                    break
                snippet.append(nxt.rstrip("\n"))
                j += 1
            detail_prefixes = ("symbol:", "location:", "required:", "found:", "reason:")
            col = 0
            source: list[str] = []
            detail: list[str] = []
            for s in snippet:
                if s.strip() == "^":
                    col = s.index("^") + 1
                    source.append(s)
                elif s.lstrip().startswith(detail_prefixes):
                    detail.append(s.strip())
                else:
                    source.append(s)
            message = f"{relative(file, report.project_dir)}:{ln}: {sev}: {msg}"
            if detail:
                message += "\n" + "\n".join(detail)
            d = Diag(current_task, current_module, file, ln, col, message, source, sev)
            if d.key() not in seen:
                seen.add(d.key())
                report.diags.append(d)
            i = j
            continue
        m = GRADLE_KOTLINC.match(line) or GRADLE_KOTLINC_OLD.match(line)
        if m:
            sev = "error" if m.group(1) == "e" else "warning"
            file, ln, col, msg = m.group(2), int(m.group(3)), int(m.group(4)), m.group(5)
            task = current_task if current_task.startswith("compile") else "compile-kotlin"
            message = f"{relative(file, report.project_dir)}:{ln}: {sev}: {msg}"
            d = Diag(task, current_module, file, ln, col, message, [], sev)
            if d.key() not in seen:
                seen.add(d.key())
                report.diags.append(d)
            i += 1
            continue
        if line.startswith(GRADLE_WENT_WRONG):
            body: list[str] = []
            j = i + 1
            while j < len(lines) and not lines[j].startswith("* Try:") and not lines[j].startswith("* Where:"):
                if lines[j].strip():
                    body.append(lines[j].rstrip())
                j += 1
            text_body = "\n".join(body).strip()
            tm = GRADLE_TASK_FAILED.search(text_body)
            module = gradle_module(tm.group(1)) if tm else ""
            task = gradle_task(tm.group(2)) if tm else "build"
            if GRADLE_RESOLVE.search(text_body):
                task = "resolve"
            if (module, task) not in failed and not (tm and (module, gradle_task(tm.group(2))) in failed):
                failed.append((module, task))
            covered = any(d.severity == "error" and d.task == task and d.module == module for d in report.diags)
            # A test task that failed with named failing tests is told by the Tests section; one
            # that failed without any (engine could not start, classpath broken) is told here.
            has_failing_tests = task == "run-tests" and bool(report.failed_tests())
            if not covered and not has_failing_tests:
                d = Diag(task, module, None, 0, 0, text_body, [], "error")
                if d.key() not in seen:
                    seen.add(d.key())
                    report.diags.append(d)
            i = j
            continue
        i += 1
    for module, task in failed:
        report.steps.append(Step(module, task, "FAIL", 0))
    if len(modules_seen) > 1 or any(m for m in modules_seen):
        for name, outcome in modules_seen.items():
            if name:
                report.modules.append(ModuleOutcome(name, outcome, 0))


# ------------------------------------------------------------------------- junit xml

JUNIT_DIRS = ("target/surefire-reports", "target/failsafe-reports", "build/test-results")
PRUNE = {".git", "node_modules", ".gradle", ".jk"}


def find_junit_dirs(root: Path) -> list[Path]:
    found: list[Path] = []
    for dirpath, dirnames, _ in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNE]
        rel = os.path.relpath(dirpath, root).replace(os.sep, "/")
        for d in JUNIT_DIRS:
            if rel == d or rel.endswith("/" + d):
                found.append(Path(dirpath))
                dirnames[:] = []
                break
    return found


def module_of(junit_dir: Path, root: Path) -> str:
    rel = junit_dir.relative_to(root).as_posix()
    for d in JUNIT_DIRS:
        if rel == d:
            return ""
        if rel.endswith("/" + d):
            return rel[: -len(d) - 1]
    return ""


def collect_junit(root: Path, since: float, report: Report) -> None:
    for junit_dir in find_junit_dirs(root):
        module = module_of(junit_dir, root)
        used = False
        for xml_path in sorted(junit_dir.rglob("*.xml")):
            if xml_path.stat().st_mtime < since:
                continue
            try:
                tree = ET.parse(xml_path)
            except ET.ParseError:
                continue
            suites = [tree.getroot()] if tree.getroot().tag == "testsuite" else list(tree.getroot().iter("testsuite"))
            for suite in suites:
                for case in suite.iter("testcase"):
                    used = True
                    report.tests.append(test_case(case, suite, module))
        if used:
            report.junit_dirs.append(junit_dir.relative_to(root).as_posix())


def test_case(case: ET.Element, suite: ET.Element, module: str) -> TestCase:
    class_name = case.get("classname") or suite.get("name") or ""
    name = case.get("name") or ""
    ms = int(float(case.get("time") or 0) * 1000)
    fail = case.find("failure")
    if fail is None:
        fail = case.find("error")
    if fail is not None:
        stack = (fail.text or "").strip()
        message = (fail.get("message") or "").strip()
        if not stack and message:
            stack = message
        return TestCase(module, class_name, name, "fail", ms, fail.get("type") or "", message, stack)
    if case.find("skipped") is not None:
        return TestCase(module, class_name, name, "skip", ms)
    return TestCase(module, class_name, name, "pass", ms)


# ----------------------------------------------------------------------------- render


def relative(file: str | None, project_dir: str) -> str:
    if not file:
        return ""
    if project_dir and file.startswith(project_dir):
        rel = file[len(project_dir):].lstrip("/\\")
        return rel or file
    return file


def fmt_duration(ms: int) -> str:
    if ms <= 0:
        return "0ms"
    if ms < 1000:
        return f"{ms}ms"
    if ms < 60_000:
        return f"{ms / 1000:.1f}s"
    return f"{ms // 60_000}m {round(ms % 60_000 / 1000)}s"


def clip_lines(text: str, n: int) -> str:
    lines = text.splitlines()
    if len(lines) <= n:
        return text
    return "\n".join(lines[:n]) + "\n…"


def clip_one_line(text: str, n: int) -> str:
    return text if len(text) <= n else text[: n - 1] + "…"


def fence(out: list[str], body: str) -> None:
    out.append("```")
    out.append(body)
    out.append("```")


def esc_cell(s: str) -> str:
    return s.replace("|", "\\|")


def render(r: Report) -> str:
    out: list[str] = []
    outcome = "OK" if r.success else "FAIL"
    out.append(f"# jk results — {outcome}")
    out.append("")
    head = f"**{outcome}** · {r.kind}"
    if r.coord:
        head += f" · `{r.coord}`"
    if r.wall_ms > 0:
        head += f" · {fmt_duration(r.wall_ms)}"
    head += f" · **exit {r.exit_code}**" if r.exit_code else " · exit 0"
    out.append(head)
    out.append(f"trigger: cli · {r.tool_label}")
    out.append("")
    why = why_lines(r)
    if why:
        out.extend(f"- {w}" for w in why)
        out.append("")
    append_counts(out, r)
    append_files(out, r)
    append_failures(out, r)
    append_tests(out, r)
    append_failed_steps(out, r)
    append_warnings(out, r)
    append_modules(out, r)
    return "\n".join(out).rstrip("\n") + "\n"


def why_lines(r: Report) -> list[str]:
    if r.success:
        return []
    out: list[str] = []
    for d in r.errors():
        if d.task == "run-tests" and r.failed_tests():
            continue
        who = f"`{d.module}` " if d.module else ""
        msg = clip_one_line(d.message.split("\n", 1)[0].strip(), MAX_WHY_CHARS)
        out.append(f"{who}`{d.task}`: {msg}".strip())
        if len(out) >= MAX_WHY:
            return out
    if not out:
        for t in r.failed_tests():
            msg = clip_one_line((t.failure_message or t.failure_type or "failed").split("\n", 1)[0], MAX_WHY_CHARS)
            who = f"`{t.module}` " if t.module else ""
            out.append(f"{who}`run-tests`: {msg}")
            if len(out) >= MAX_WHY:
                return out
    if not out:
        for s in r.steps:
            who = f"`{s.module}` " if s.module else ""
            out.append(f"{who}`{s.task}` failed")
            if len(out) >= MAX_WHY:
                return out
    return out or [f"failed (exit {r.exit_code})"]


def append_counts(out: list[str], r: Report) -> None:
    wrote = False
    if len(r.modules) > 1:
        failed = sum(1 for m in r.modules if m.outcome == "FAIL")
        out.append(f"Modules: {len(r.modules)}" + (f" (**{failed} failed**)" if failed else " (all ok)"))
        wrote = True
    if r.tests:
        fail = len(r.failed_tests())
        skip = sum(1 for t in r.tests if t.status == "skip")
        passed = sum(1 for t in r.tests if t.status == "pass")
        ms = sum(t.duration_ms for t in r.tests)
        line = "Tests: "
        line += "**100%** pass · " if fail == 0 else f"**{fail} failed** · "
        line += f"{passed} passed"
        if skip:
            line += f", {skip} skipped"
        line += f" ({len(r.tests)} total)"
        if ms:
            line += f" · _took {fmt_duration(ms)}_"
        out.append(line)
        wrote = True
    errors = [d for d in r.errors() if not (d.task == "run-tests" and r.failed_tests())]
    warnings = r.warnings()
    if errors or warnings:
        line = "Diagnostics: "
        if errors:
            line += f"**{len(errors)} error{'s' if len(errors) != 1 else ''}**"
        if errors and warnings:
            line += ", "
        if warnings:
            line += f"{len(warnings)} warning{'s' if len(warnings) != 1 else ''}"
        out.append(line)
        wrote = True
    if wrote:
        out.append("")


def append_files(out: list[str], r: Report) -> None:
    out.append("## Files")
    out.append("")
    out.append(f"- High-level report (this file): `{r.results_path}`")
    out.append(f"- Console log: `{r.log_path}` — the tool's own output, verbatim")
    diag_path = str(Path(r.results_path).with_name("jk-diagnostics.json"))
    out.append(f"- Diagnostics JSON: `{diag_path}` — the parsed errors and failing tests")
    if r.junit_dirs:
        out.append("- JUnit XML: " + ", ".join(f"`{d}/`" for d in r.junit_dirs))
    out.append("")


def append_failures(out: list[str], r: Report) -> None:
    errors = [d for d in r.errors() if not (d.task == "run-tests" and r.failed_tests())]
    if not errors:
        return
    out.append("## Failures")
    out.append("")
    last_header = None
    for i, d in enumerate(errors):
        if i >= MAX_ERRORS:
            out.append(f"_+{len(errors) - i} more errors — see the console log._")
            out.append("")
            break
        header = d.task + (f" — {d.module}" if d.module else "")
        if header != last_header:
            out.append(f"### {header}")
            last_header = header
        loc = relative(d.file, r.project_dir)
        if loc:
            if d.line > 0:
                loc += f":{d.line}"
                if d.col > 0:
                    loc += f":{d.col}"
            out.append(f"`{loc}`")
        msg = d.message if len(d.message) <= MAX_MESSAGE_CHARS else d.message[:MAX_MESSAGE_CHARS] + "…"
        if msg.strip():
            fence(out, msg)
        if d.snippet:
            snip = d.snippet[:MAX_SNIPPET_LINES]
            body = "\n".join(snip)
            if len(d.snippet) > MAX_SNIPPET_LINES:
                body += "\n…"
            fence(out, body)
        out.append("")


def append_tests(out: list[str], r: Report) -> None:
    if not r.tests:
        return
    total = len(r.tests)
    fail = len(r.failed_tests())
    ms = sum(t.duration_ms for t in r.tests)
    out.append("## Tests")
    out.append("")
    if fail == 0:
        line = f"**100%** pass rate · No failures for **{total}** tests"
    else:
        rate = int((total - fail) * 100 / total) if total else 0
        line = f"**{rate}%** pass rate · "
        line += "**1 failure** out of **1** test" if total == 1 else f"**{fail} failure{'s' if fail != 1 else ''}** out of **{total}** tests"
    if ms:
        line += f" · _took {fmt_duration(ms)}_"
    out.append(line)
    out.append("")
    multi = len({t.module for t in r.tests}) > 1 or any(t.module for t in r.tests)
    if multi:
        out.append("| Module | Package | Fail | Skip | Pass | Total |")
        out.append("|---|---|---|---|---|---|")
    else:
        out.append("| Package | Fail | Skip | Pass | Total |")
        out.append("|---|---|---|---|---|")
    rows: dict[tuple[str, str], list[int]] = {}
    for t in r.tests:
        row = rows.setdefault((t.module, t.package), [0, 0, 0, 0])
        row[3] += 1
        if t.status == "fail":
            row[0] += 1
        elif t.status == "skip":
            row[1] += 1
        else:
            row[2] += 1
    for n, ((module, pkg), (f, s, p, tot)) in enumerate(rows.items()):
        if n >= MAX_PACKAGES:
            out.append(f"| … | +{len(rows) - n} more | | |" + (" |" if multi else "") + " |")
            break
        cells = [esc_cell(module or "(root)"), esc_cell(pkg)] if multi else [esc_cell(pkg)]
        out.append("| " + " | ".join(cells + [str(f), str(s), str(p), str(tot)]) + " |")
    if fail:
        out.append("")
        out.append("### Failed tests")
        shown = 0
        by_class: dict[tuple[str, str], list[TestCase]] = {}
        for t in r.failed_tests():
            by_class.setdefault((t.module, t.class_name), []).append(t)
        for (module, cls), cases in by_class.items():
            if shown >= MAX_FAILED_TESTS:
                break
            out.append(f"#### {cls}" + (f" — {module}" if multi and module else ""))
            for t in cases:
                if shown >= MAX_FAILED_TESTS:
                    break
                line = f"##### `{t.name}`"
                if t.duration_ms > 0:
                    line += f" — _took {fmt_duration(t.duration_ms)}_"
                out.append(line)
                detail = (t.failure_stack or t.failure_message or "").strip()
                if detail:
                    fence(out, clip_lines(detail, MAX_STACK_LINES))
                shown += 1
        if fail > shown:
            out.append(f"_+{fail - shown} more failed tests — see the JUnit XML._")
    out.append("")


def append_failed_steps(out: list[str], r: Report) -> None:
    if not r.steps:
        return
    out.append("## Failed steps")
    out.append("")
    out.append("| Module | Task | Status | Time |")
    out.append("|---|---|---|---|")
    for i, s in enumerate(r.steps):
        if i >= MAX_FAILED_TASKS:
            out.append(f"| … | +{len(r.steps) - i} more | | |")
            break
        out.append(f"| {esc_cell(s.module)} | `{s.task}` | {s.status} | {fmt_duration(s.duration_ms) if s.duration_ms else ''} |")
    out.append("")


def append_warnings(out: list[str], r: Report) -> None:
    warnings = r.warnings()
    if not warnings:
        return
    out.append("## Warnings")
    out.append("")
    for i, d in enumerate(warnings):
        if i >= MAX_WARNINGS:
            out.append(f"- _+{len(warnings) - i} more — see the console log._")
            break
        loc = relative(d.file, r.project_dir)
        if loc and d.line:
            loc += f":{d.line}"
        first = d.message.split("\n", 1)[0]
        first = re.sub(r"^\S+:\d+: warning: ", "", first)
        out.append(f"- `{d.task}` " + (f"{loc}: " if loc else "") + first)
    out.append("")


def append_modules(out: list[str], r: Report) -> None:
    if len(r.modules) <= 1:
        return
    out.append("## Modules")
    out.append("")
    out.append("| Module | Outcome | Time |")
    out.append("|---|---|---|")
    for i, m in enumerate(r.modules):
        if i >= MAX_MODULES:
            out.append(f"| … | +{len(r.modules) - i} more | |")
            break
        out.append(f"| {esc_cell(m.name)} | {m.outcome} | {fmt_duration(m.duration_ms) if m.duration_ms else ''} |")
    out.append("")


def diagnostics_json(r: Report) -> str:
    return json.dumps(
        {
            "tool": r.tool,
            "exit": r.exit_code,
            "success": r.success,
            "diagnostics": [
                {**asdict(d), "file": relative(d.file, r.project_dir) or None} for d in r.diags
            ],
            "failingTests": [asdict(t) for t in r.failed_tests()],
            "failedSteps": [asdict(s) for s in r.steps],
        },
        indent=1,
    )


# ------------------------------------------------------------------------------ coord


def maven_coord(project_dir: Path) -> str:
    pom = project_dir / "pom.xml"
    if not pom.exists():
        return ""
    try:
        root = ET.parse(pom).getroot()
    except ET.ParseError:
        return ""
    ns = root.tag.split("}")[0] + "}" if root.tag.startswith("{") else ""
    group = root.findtext(f"{ns}groupId") or root.findtext(f"{ns}parent/{ns}groupId") or ""
    artifact = root.findtext(f"{ns}artifactId") or ""
    return f"{group}:{artifact}" if group and artifact else artifact


def gradle_coord(project_dir: Path) -> str:
    name = ""
    for settings in ("settings.gradle.kts", "settings.gradle"):
        p = project_dir / settings
        if p.exists():
            m = re.search(r"""rootProject\.name\s*=\s*['"]([^'"]+)['"]""", p.read_text(errors="replace"))
            if m:
                name = m.group(1)
            break
    name = name or project_dir.name
    group = ""
    for build in ("build.gradle.kts", "build.gradle"):
        p = project_dir / build
        if p.exists():
            m = re.search(r"""^group\s*=\s*['"]([^'"]+)['"]""", p.read_text(errors="replace"), re.M)
            if m:
                group = m.group(1)
            break
    return f"{group}:{name}" if group else name


def wrapper_version(project_dir: Path, tool: str) -> str:
    """Pinned comparator version actually executed. The repo wrapper is not consulted."""
    del project_dir
    key = "maven" if tool == "mvn" else "gradle"
    env = "JK_BENCH_MAVEN_VERSION" if tool == "mvn" else "JK_BENCH_GRADLE_VERSION"
    version = os.environ.get(env)
    if not version:
        bench = Path(__file__).resolve().parents[2]
        if str(bench) not in sys.path:
            sys.path.insert(0, str(bench))
        import benchtools

        version = benchtools.read_pin()[key]
    label = "Maven" if tool == "mvn" else "Gradle"
    return f"{label} {version}"
