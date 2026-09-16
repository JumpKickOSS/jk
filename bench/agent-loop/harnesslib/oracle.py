"""The scripted driver: a deterministic oracle that reads the results file and applies the known fix.

It never reads the scenario's injection to decide *what* is wrong; the results file has to say.
Each turn it classifies the failure from the file (compile locus, missing packages, a resolve
message, a failing test and its message), applies the fix that class calls for, reruns through
the tool's MCP and reads the results again. When the file names the failure but not enough to
derive the edit (a failing test whose message carries no expected/actual pair, a deleted
resource nobody names), it falls back to the scenario's own inverse edit and records that as a
finding about the results file. When the file names nothing actionable, it stops red and says so.
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from .session import Session, results_ok

BOOT_GROUP = "org.springframework.boot"

# Missing package or symbol → the artifact that provides it, most specific first. The Boot
# major decides between the Boot 3 and Boot 4 starter names.
DEPENDENCY_RULES: list[tuple[str, str, dict[int, str] | str]] = [
    (r"^io\.r2dbc|^org\.springframework\.data\.r2dbc|^ReactiveCrudRepository$", BOOT_GROUP, "spring-boot-starter-data-r2dbc"),
    (r"^jakarta\.persistence|^org\.springframework\.data\.jpa|^(Entity|GeneratedValue|JpaRepository)$", BOOT_GROUP, "spring-boot-starter-data-jpa"),
    (r"^org\.springframework\.batch", BOOT_GROUP, {3: "spring-boot-starter-batch", 4: "spring-boot-starter-batch-jdbc"}),
    (r"^org\.springframework\.graphql", BOOT_GROUP, "spring-boot-starter-graphql"),
    (r"^jakarta\.validation", BOOT_GROUP, "spring-boot-starter-validation"),
    (r"^org\.springframework\.hateoas", BOOT_GROUP, "spring-boot-starter-hateoas"),
    (r"^org\.springframework\.web\.client|^(RestClient|RestTemplate)$", BOOT_GROUP, {3: "spring-boot-starter-web", 4: "spring-boot-starter-restclient"}),
    (r"^org\.springframework\.web\.|^org\.springframework\.http|^(RestController|RequestMapping|GetMapping|PostMapping|RequestParam|ResponseEntity|Controller)$", BOOT_GROUP, {3: "spring-boot-starter-web", 4: "spring-boot-starter-webmvc"}),
    (r"^org\.junit\.jupiter|^(Test|BeforeEach|Assertions)$", "org.junit.jupiter", "junit-jupiter"),
    (r"^org\.slf4j|^org\.springframework\.(boot|context|stereotype|scheduling)|^(SpringBootApplication|Component|Scheduled|EnableScheduling|Logger|LoggerFactory)$", BOOT_GROUP, "spring-boot-starter"),
]

RESOURCE_HINT = re.compile(
    r"Error resolving template \[(?P<template>[\w./-]+)\]|Failed to find document, name='(?P<document>[\w./-]+)'"
    r"|class path resource \[(?P<resource>[^\]]+)\]|(?P<file>[\w./-]+\.(?:sql|csv|properties|ya?ml|json|graphql|graphqls|html|txt|xml))")
RESOURCE_FAILURE = re.compile(
    r"TemplateInputException|Failed to find document|cannot be opened|does not exist|FileNotFound|NoSuchFile|Failed to load ApplicationContext"
    r"|Failed to initialize|BadSqlGrammar|bad SQL grammar|ItemStreamException")
LINK_ERROR = re.compile(r"(?:NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|IncompatibleClassChangeError|NoSuchFieldError)[:\s]+'?(?:[\w.$]+ )?([a-z][\w]*(?:\.[\w$]+)+)")
ENGINE_HINT = re.compile(r"TestEngine with ID '([\w-]+)'|(junit-jupiter[\w-]*)")

PAIR_PATTERNS = [
    re.compile(r"expected:\s*<(?P<e>.*?)> but was:\s*<(?P<a>.*?)>", re.S),
    re.compile(r'expected: "(?P<e>.*?)"\n\s*but was: "(?P<a>.*?)"', re.S),
    re.compile(r"expected: (?P<e>\S.*?)\n\s*but was: (?P<a>\S.*?)$", re.M),
    re.compile(r'Expected: a string containing "(?P<e>.*?)"\n\s*but: was "(?P<a>.*?)"\n', re.S),
    re.compile(r"Expected: (?P<e>.+?)\n\s*got: (?P<a>.+?)\n"),
    re.compile(r'to contain only:\n\s*\["(?P<e>.*?)"\]', re.S),
]
STATUS_METHOD = re.compile(r"^\d{3} ([A-Z_]+)$")
ASSERTJ_NEGATIONS = [
    (re.compile(r"Expecting path:.*not to exist", re.S), "doesNotExist()", "exists()"),
    (re.compile(r"Expecting path:.*to exist", re.S), "exists()", "doesNotExist()"),
    (re.compile(r"expected: not null|not to be null"), "isNotNull()", "isNull()"),
    (re.compile(r"expected: null\n"), "isNull()", "isNotNull()"),
]


# ------------------------------------------------------------------------------ parse


@dataclass
class Locus:
    file: str
    line: int
    message: str


@dataclass
class FailedTest:
    class_name: str
    method: str
    block: str


@dataclass
class Parsed:
    ok: bool
    loci: list[Locus]
    tests: list[FailedTest]
    failures: str
    why: list[str]
    raw: str

    @property
    def names_nothing(self) -> bool:
        return not self.loci and not self.tests and not self.failures.strip()


def section(text: str, heading: str) -> str:
    m = re.search(rf"^## {re.escape(heading)}\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    return m.group(1) if m else ""


def parse(text: str) -> Parsed:
    failures = section(text, "Failures")
    loci = [Locus(m.group(1), int(m.group(2)), m.group(3).strip())
            for m in re.finditer(r"^`([^`\n]+?):(\d+)(?::\d+)?`\n```\n(.*?)\n```", failures, re.S | re.M)]
    tests: list[FailedTest] = []
    tests_section = section(text, "Tests")
    for cm in re.finditer(r"^#### (\S+)\n(.*?)(?=^#### |\Z)", tests_section, re.S | re.M):
        for mm in re.finditer(r"^##### `([^`]*)`[^\n]*\n```\n(.*?)\n```", cm.group(2), re.S | re.M):
            tests.append(FailedTest(cm.group(1), mm.group(1), mm.group(2)))
    why = re.findall(r"^- (.*)$", text.split("## Files", 1)[0], re.M)
    return Parsed(results_ok(text), loci, tests, failures, why, text)


# --------------------------------------------------------------------------- classify


@dataclass
class Diagnosis:
    kind: str                # compile-error | missing-dependency | version-conflict | missing-resource | failing-assertion | unnamed | ok
    signature: str
    loci: list[Locus] = field(default_factory=list)
    tests: list[FailedTest] = field(default_factory=list)
    detail: str = ""


def classify(p: Parsed) -> Diagnosis:
    if p.ok:
        return Diagnosis("ok", "ok")
    if re.search(r"Cannot resolve dependencies|could not resolve|Could not find|cannot be resolved", p.failures, re.I) and not p.loci:
        return Diagnosis("version-conflict", "resolve:" + p.failures.strip()[:200], detail=p.failures)
    if p.loci:
        missing = [l for l in p.loci if re.search(r"package \S+ does not exist|cannot find symbol|static import only", l.message)]
        if missing:
            return Diagnosis("missing-dependency", "missing:" + ",".join(sorted({l.file for l in missing})), loci=missing)
        return Diagnosis("compile-error", "compile:" + ",".join(f"{l.file}:{l.line}" for l in p.loci), loci=p.loci)
    if p.tests:
        joined = "\n".join(t.block for t in p.tests)
        if LINK_ERROR.search(joined) or re.search(r"initializationError|failed to discover tests", joined):
            return Diagnosis("version-conflict", "link:" + joined[:200], tests=p.tests, detail=joined)
        if RESOURCE_FAILURE.search(joined) and (not is_assertion(joined) or re.search(r"\b\w+Exception: ", actual_side(joined) or "")):
            return Diagnosis("missing-resource", "resource:" + ",".join(sorted({t.class_name for t in p.tests})), tests=p.tests, detail=joined)
        return Diagnosis("failing-assertion", "assert:" + ",".join(f"{t.class_name}#{t.method}" for t in p.tests), tests=p.tests, detail=joined)
    if re.search(r"failed to discover tests|Could not complete execution", p.failures) or ENGINE_HINT.search(p.failures):
        return Diagnosis("version-conflict", "engine:" + p.failures.strip()[:200], detail=p.failures)
    return Diagnosis("unnamed", "unnamed:" + (p.why[0] if p.why else "")[:200], detail=p.failures)


def is_assertion(block: str) -> bool:
    return bool(re.search(r"AssertionError|AssertionFailedError|ComparisonFailure|expected:", block))


def actual_side(block: str) -> str | None:
    m = re.search(r"but was:\s*(.*)", block, re.S)
    return m.group(1) if m else None


# ---------------------------------------------------------------------------- fixes


@dataclass
class Fix:
    description: str
    source: str          # results | results-heuristic | git-status | scenario
    finding: str = ""


def git_deleted(sandbox: Path) -> list[str]:
    out = subprocess.run(["git", "status", "--porcelain"], cwd=sandbox, stdout=subprocess.PIPE, text=True).stdout
    return [line[3:].strip() for line in out.splitlines() if line.startswith(" D") or line.startswith("D ")]


def build_file(sandbox: Path, tool: str) -> Path:
    if tool == "jk":
        return sandbox / "jk.toml"
    if tool == "mvn":
        return sandbox / "pom.xml"
    for name in ("build.gradle.kts", "build.gradle"):
        if (sandbox / name).exists():
            return sandbox / name
    raise RuntimeError("no Gradle build file")


def boot_major(text: str) -> int:
    m = re.search(r"\[spring-boot\]\s*\n\s*version\s*=\s*\"(\d+)", text) or re.search(r"(?:spring-boot|springframework\.boot)[^\n]*?(\d+)\.\d+\.\d+", text)
    return int(m.group(1)) if m else 4


def fix_compile_error(sandbox: Path, d: Diagnosis) -> Fix | None:
    edited = []
    for l in d.loci:
        if "';' expected" not in l.message:
            continue
        path = sandbox / l.file
        if not path.exists():
            continue
        lines = path.read_text(encoding="utf-8").split("\n")
        if l.line < 1 or l.line > len(lines):
            continue
        lines[l.line - 1] = lines[l.line - 1].rstrip() + ";"
        path.write_text("\n".join(lines), encoding="utf-8")
        edited.append(f"{l.file}:{l.line}")
    if not edited:
        return None
    return Fix("added the missing ';' at " + ", ".join(edited), "results")


def fix_missing_dependency(sandbox: Path, tool: str, d: Diagnosis) -> Fix | None:
    names: list[str] = []
    for l in d.loci:
        names += re.findall(r"package (\S+) does not exist", l.message)
        names += re.findall(r"symbol:\s+class (\w+)", l.message)
    if not names:
        return None
    path = build_file(sandbox, tool)
    text = path.read_text(encoding="utf-8")
    major = boot_major(text)
    chosen: tuple[str, str] | None = None
    for pattern, group, artifact in DEPENDENCY_RULES:
        if any(re.search(pattern, n) for n in names):
            chosen = (group, artifact[major] if isinstance(artifact, dict) else artifact)
            break
    if chosen is None:
        return None
    group, artifact = chosen
    if re.search(rf"\b{re.escape(artifact)}\b(?!-)", text):
        return None  # already declared: adding it again would loop
    test_scope = all(l.file.startswith("src/test/") or "/src/test/" in l.file for l in d.loci)
    path.write_text(add_dependency(text, tool, path.suffix, group, artifact, test_scope), encoding="utf-8")
    return Fix(f"declared {group}:{artifact}{' (test)' if test_scope else ''} in {path.name}", "results")


def add_dependency(text: str, tool: str, suffix: str, group: str, artifact: str, test_scope: bool) -> str:
    if tool == "mvn":
        scope = "\n      <scope>test</scope>" if test_scope else ""
        block = f"\n    <dependency>\n      <groupId>{group}</groupId>\n      <artifactId>{artifact}</artifactId>{scope}\n    </dependency>"
        return text.replace("<dependencies>", "<dependencies>" + block, 1)
    if tool == "gradle":
        conf = "testImplementation" if test_scope else "implementation"
        line = f'\t{conf}("{group}:{artifact}")\n' if suffix == ".kts" else f"\t{conf} '{group}:{artifact}'\n"
        new, n = re.subn(r"^dependencies\s*\{\n", lambda m: m.group(0) + line, text, count=1, flags=re.M)
        return new if n else text.rstrip("\n") + f"\n\ndependencies {{\n{line}}}\n"
    table = "[test-dependencies]" if test_scope else "[dependencies]"
    line = f'{artifact} = {{ group = "{group}" }}\n'
    if table + "\n" in text:
        return text.replace(table + "\n", table + "\n" + line, 1)
    return text.rstrip("\n") + f"\n\n{table}\n{line}"


@dataclass
class Pin:
    group: str
    artifact: str
    span: tuple[int, int]


def pins(text: str, tool: str) -> list[Pin]:
    """Exact version pins an agent would suspect: `:=` in jk.toml, `strictly` in Gradle, a `<version>` under a parent in Maven."""
    out: list[Pin] = []
    if tool == "jk":
        for m in re.finditer(r'^[\w.-]+\s*=\s*"([\w.-]+):([\w.-]+):=?[^"\n]*"[ \t]*\n', text, re.M):
            out.append(Pin(m.group(1), m.group(2), m.span()))
    elif tool == "gradle":
        for m in re.finditer(r"^[ \t]*\w+\s*\(?\s*['\"]([\w.-]+):([\w.-]+)(?::[^'\"\n]*)?['\"]\s*\)?\s*\{[^\n]*strictly[^\n]*\n", text, re.M):
            out.append(Pin(m.group(1), m.group(2), m.span()))
    else:
        if "<parent>" in text:
            for m in re.finditer(r"\n[ \t]*<dependency>(?:(?!</dependency>).)*?</dependency>[ \t]*", text, re.S):
                block = m.group(0)
                if "<version>" not in block:
                    continue
                g = re.search(r"<groupId>([^<]+)</groupId>", block)
                a = re.search(r"<artifactId>([^<]+)</artifactId>", block)
                if g and a:
                    out.append(Pin(g.group(1).strip(), a.group(1).strip(), m.span()))
    return out


def fix_version_conflict(sandbox: Path, tool: str, d: Diagnosis) -> Fix | None:
    path = build_file(sandbox, tool)
    text = path.read_text(encoding="utf-8")
    candidates = pins(text, tool)
    if not candidates:
        return None
    named = {f"{g}:{a}" for g, a in re.findall(r"depends on ([\w.-]+):([\w.-]+) ", d.detail)}
    prefixes: set[str] = set()
    for cls in LINK_ERROR.findall(d.detail):
        parts = cls.split(".")
        prefixes.update(".".join(parts[:n]) for n in (2, 3) if len(parts) > n)
    for m in ENGINE_HINT.finditer(d.detail):
        hint = m.group(1) or m.group(2)
        if hint and hint.startswith("junit"):
            prefixes.add("org.junit")
    if named:
        picked = [p for p in candidates if f"{p.group}:{p.artifact}" in named]
        source = "results"
    else:
        picked = [p for p in candidates if any(p.group.startswith(pre) or pre.startswith(p.group) for pre in prefixes)]
        source = "results-heuristic"
    if not picked:
        return None
    for p in sorted(picked, key=lambda p: p.span[0], reverse=True):
        text = text[: p.span[0]] + text[p.span[1]:]
    path.write_text(text, encoding="utf-8")
    return Fix("removed the exact pin on " + ", ".join(f"{p.group}:{p.artifact}" for p in picked) + f" from {path.name}", source)


def fix_missing_resource(sandbox: Path, d: Diagnosis) -> Fix | None:
    deleted = git_deleted(sandbox)
    if not deleted:
        return None
    stems: set[str] = set()
    for m in RESOURCE_HINT.finditer(d.detail):
        name = next(v for v in m.groupdict().values() if v)
        stems.add(Path(name).stem)
    matches = [f for f in deleted if Path(f).stem in stems]
    if matches:
        source, finding = "results", ""
    elif len(deleted) == 1:
        matches, source = deleted, "git-status"
        finding = "the failing test's message names no resource; the deleted file came from git status"
    else:
        return None
    subprocess.run(["git", "checkout", "--", *matches], cwd=sandbox, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return Fix("restored " + ", ".join(matches), source, finding)


def test_source(sandbox: Path, class_name: str) -> Path | None:
    simple = class_name.rsplit(".", 1)[-1].split("$")[0]
    for ext in ("java", "kt", "groovy"):
        found = sorted((sandbox / "src" / "test").rglob(f"{simple}.{ext}")) if (sandbox / "src" / "test").exists() else []
        if found:
            return found[0]
    return None


def stack_line(block: str, class_name: str) -> int:
    simple = class_name.rsplit(".", 1)[-1]
    m = re.search(rf"at {re.escape(class_name)}\.\w+\({re.escape(simple)}\.\w+:(\d+)\)", block)
    return int(m.group(1)) if m else 0


def replace_in_source(path: Path, old: str, new: str, line_no: int) -> bool:
    """Replace on the failing line when it carries `old`, else anywhere it occurs exactly once."""
    if not old or old == new:
        return False
    text = path.read_text(encoding="utf-8")
    lines = text.split("\n")
    if 0 < line_no <= len(lines) and lines[line_no - 1].count(old) == 1:
        lines[line_no - 1] = lines[line_no - 1].replace(old, new)
        path.write_text("\n".join(lines), encoding="utf-8")
        return True
    if text.count(old) == 1:
        path.write_text(text.replace(old, new), encoding="utf-8")
        return True
    return False


def diff_core(expected: str, actual: str) -> tuple[str, str]:
    i = 0
    while i < min(len(expected), len(actual)) and expected[i] == actual[i]:
        i += 1
    j = 0
    while j < min(len(expected), len(actual)) - i and expected[-1 - j] == actual[-1 - j]:
        j += 1
    return expected[i: len(expected) - j], actual[i: len(actual) - j]


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def nearest_fragment(expected: str, body: str) -> str | None:
    """The fragment of a rendered body closest to what the test expected to find in it."""
    best, best_d = None, None
    for frag in re.split(r'[<>"\n\t]+', body):
        frag = frag.strip()
        if not frag or frag == expected:
            continue
        d = edit_distance(expected, frag)
        if d <= max(2, len(expected) // 4) and (best_d is None or d < best_d):
            best, best_d = frag, d
    return best


def status_method(status: str) -> str | None:
    m = STATUS_METHOD.match(status.strip())
    return "is" + "".join(w.capitalize() for w in m.group(1).split("_")) + "()" if m else None


def derive_edits(block: str) -> list[tuple[str, str, str]]:
    """Candidate (old, new, source) edits an assertion message supports, best first."""
    out: list[tuple[str, str, str]] = []
    for pat in PAIR_PATTERNS:
        m = pat.search(block)
        if not m:
            continue
        expected = m.group("e")
        if "a" in m.groupdict() and m.group("a") is not None:
            actual = m.group("a")
        else:
            got = re.search(r"Expecting \w+:\n\s*\[\"(.*?)\"\]", block, re.S)
            if not got:
                continue
            actual = got.group(1)
        if pat is PAIR_PATTERNS[3]:
            frag = nearest_fragment(expected, actual)
            if frag:
                out.append((expected, frag, "results-heuristic"))
            continue
        se, sa = status_method(expected), status_method(actual)
        if se and sa:
            out.append((se, sa, "results-heuristic"))
        out.append((expected, actual, "results"))
        core = diff_core(expected, actual)
        if core != (expected, actual):
            out.append((*core, "results-heuristic"))
        break
    for pat, old, new in ASSERTJ_NEGATIONS:
        if pat.search(block):
            out.append((old, new, "results-heuristic"))
            break
    return out


def fix_failing_assertion(sandbox: Path, d: Diagnosis, scenario: dict) -> Fix | None:
    finding = ""
    for t in d.tests:
        path = test_source(sandbox, t.class_name)
        if path is None:
            continue
        line_no = stack_line(t.block, t.class_name)
        edits = derive_edits(t.block)
        for old, new, source in edits:
            # A string literal first, so "First" does not match inside getFirstName().
            for quoted_old, quoted_new in ((f'"{old}"', f'"{new}"'), (old, new)):
                if replace_in_source(path, quoted_old, quoted_new, line_no):
                    return Fix(f"{path.relative_to(sandbox)}: {quoted_old[:60]!r} → {quoted_new[:60]!r}", source)
        if edits:
            finding = f"the message's expected/actual pair ({edits[0][0][:40]!r} → {edits[0][1][:40]!r}) does not occur in {path.name}"
        else:
            finding = f"{t.class_name}#{t.method} failed with a message that carries no expected/actual pair"
    # The results named the failing test but not an edit: the scenario's inverse edit stands in.
    inverse = scenario.get("replace"), scenario.get("find")
    expected_classes = set(scenario.get("tests", []))
    if inverse[0] and expected_classes & {t.class_name for t in d.tests}:
        path = sandbox / scenario["file"]
        text = path.read_text(encoding="utf-8")
        if text.count(inverse[0]) == 1:
            path.write_text(text.replace(inverse[0], inverse[1]), encoding="utf-8")
            return Fix(f"{scenario['file']}: scenario inverse edit", "scenario", finding or "results named the test but no derivable edit")
    return None


# ------------------------------------------------------------------------------ loop


@dataclass
class DriverResult:
    green: bool
    turns: int
    transcript: list[dict]
    fix_sources: list[str]
    finding: str = ""


def scripted(session: Session, scenario: dict, max_turns: int, deadline: float, now) -> DriverResult:
    """Read → classify → fix → rerun, until green or the budget is spent."""
    text = session.results()
    transcript: list[dict] = []
    sources: list[str] = []
    seen: set[str] = set()
    finding = ""
    turns = 0
    for turn in range(1, max_turns + 1):
        if now() > deadline:
            finding = "time budget exhausted"
            break
        turns = turn
        d = classify(parse(text))
        event = {"turn": turn, "classified": d.kind, "signature": d.signature[:120]}
        if d.kind == "ok":
            transcript.append(event)
            return DriverResult(True, turn - 1, transcript, sources)
        if d.signature in seen:
            finding = f"the fix did not change the results ({d.kind})"
            transcript.append({**event, "stop": finding})
            break
        seen.add(d.signature)
        try:
            fix = apply(session, d, scenario)
        except Exception as e:  # a fix that crashes is a red row with the reason, not a crashed matrix
            fix = None
            finding = f"fix raised {type(e).__name__}: {e}"
        if fix is None:
            finding = finding or f"results name nothing the oracle can act on ({d.kind}: {d.signature[:80]})"
            transcript.append({**event, "stop": finding})
            break
        sources.append(fix.source)
        if fix.finding and not finding:
            finding = fix.finding
        green, text = session.run()
        transcript.append({**event, "fix": fix.description, "source": fix.source, "green": green})
        if green:
            return DriverResult(True, turn, transcript, sources, finding)
    else:
        finding = finding or "turn budget exhausted"
    return DriverResult(False, turns, transcript, sources, finding)


def apply(session: Session, d: Diagnosis, scenario: dict) -> Fix | None:
    if d.kind == "compile-error":
        return fix_compile_error(session.sandbox, d)
    if d.kind == "missing-dependency":
        return fix_missing_dependency(session.sandbox, session.tool, d)
    if d.kind == "version-conflict":
        return fix_version_conflict(session.sandbox, session.tool, d)
    if d.kind == "missing-resource":
        return fix_missing_resource(session.sandbox, d)
    if d.kind == "failing-assertion":
        return fix_failing_assertion(session.sandbox, d, scenario)
    return None
