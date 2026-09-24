"""Pinned Maven and Gradle for the benchmarks, plus the host a row belongs to.

tools.toml next to this file names the only versions a harness may run. At startup the
latest GA is checked (Maven metadata, Gradle current); an older pin or a failed check
refuses the run unless the matching flag is set. The binaries are provisioned through
`jk mvn` / `jk gradle` in a throwaway directory whose wrapper properties name the pin,
then invoked directly — never a repo wrapper, never `jk mvn` inside the repo.
"""

from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import tempfile
import tomllib
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

PIN = Path(__file__).resolve().parent / "tools.toml"
LEGACY_HOST = "bocabox"

MAVEN_METADATA = "https://repo1.maven.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml"
GRADLE_CURRENT = "https://services.gradle.org/versions/current"
MAVEN_DIST = "https://repo1.maven.org/maven2/org/apache/maven/apache-maven/{v}/apache-maven-{v}-bin.zip"
GRADLE_DIST = "https://services.gradle.org/distributions/gradle-{v}-bin.zip"

# Vars jk strips before it execs a tool (`PassthroughEnv`), so a shell cannot retarget the JVM.
STRIPPED = ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_HOME", "KOTLIN_HOME", "MAVEN_OPTS", "GRADLE_OPTS")

_GA = re.compile(r"\d+(?:\.\d+)*")
_VER_ENV = {"maven": "JK_BENCH_MAVEN_VERSION", "gradle": "JK_BENCH_GRADLE_VERSION"}
_BIN_ENV = {"maven": "JK_BENCH_MAVEN_BIN", "gradle": "JK_BENCH_GRADLE_BIN"}
_INCOMPAT = re.compile(
    r"(?i)("
    r"has been removed|removed in gradle|was removed in gradle|"
    r"could not compile build file|could not compile settings file|script compilation error|"
    r"minimum supported gradle version|"
    r"this version of gradle is (?:not supported|unsupported|incompatible)|"
    r"gradle version [\d.]+ is (?:not supported|unsupported|incompatible)|"
    r"does not support (?:running on )?gradle|requires gradle (?:version )?[\d.]|"
    r"the plugin is too old|"
    r"NoSuchMethodError: .*org\.gradle|NoClassDefFoundError: org[./]gradle"
    r")"
)


class JdkUnavailable(Exception):
    """The named JDK could not be ensured. `str(self)` is the row token."""

    def __init__(self, spec: str, detail: str = ""):
        self.spec = spec
        self.detail = detail.strip()
        super().__init__(f"jdk-unavailable: {spec}")


class GradleRejected(Exception):
    """The build script cannot run on the pinned Gradle. The wrapper is not a fallback."""

    def __init__(self, line: str):
        self.line = line
        super().__init__(f"incompatible-with-latest-gradle: {line}")


@dataclass(frozen=True)
class Tools:
    maven_version: str
    gradle_version: str
    maven_bin: Path | None
    gradle_bin: Path | None
    stale_tools: bool
    offline_tools: bool
    pin_path: Path

    def as_row(self) -> dict:
        row = {
            "maven_version": self.maven_version,
            "gradle_version": self.gradle_version,
            "stale_tools": self.stale_tools,
            "offline_tools": self.offline_tools,
        }
        if self.maven_bin is not None:
            row["maven_binary"] = str(self.maven_bin)
        if self.gradle_bin is not None:
            row["gradle_binary"] = str(self.gradle_bin)
        return row


def add_arguments(ap) -> None:
    ap.add_argument(
        "--allow-stale-tools",
        action="store_true",
        help="run even when tools.toml is older than the latest GA; record stale_tools on every row",
    )
    ap.add_argument(
        "--offline-tools",
        action="store_true",
        help="run when the latest-GA check cannot reach the network; record offline_tools on every row",
    )


def use_flags(ns) -> None:
    """Publish the flags for child processes (the wrappers re-read them)."""
    if getattr(ns, "allow_stale_tools", False):
        os.environ["JK_BENCH_ALLOW_STALE_TOOLS"] = "1"
    if getattr(ns, "offline_tools", False):
        os.environ["JK_BENCH_OFFLINE_TOOLS"] = "1"


def jk_home() -> Path:
    return Path(os.environ.get("JK_HOME", Path.home() / ".jk")).expanduser()


def bench_home() -> Path:
    raw = os.environ.get("JK_BENCH_HOME")
    if raw:
        return Path(raw).expanduser()
    xdg = os.environ.get("XDG_CACHE_HOME")
    base = Path(xdg).expanduser() if xdg else Path.home() / ".cache"
    return base / "jk-bench"


def scratch(override_var: str, harness: str) -> Path:
    """`$override_var` if set, else `$JK_BENCH_HOME/<harness>`."""
    raw = os.environ.get(override_var)
    path = Path(raw).expanduser() if raw else bench_home() / harness
    return path.resolve()


def read_pin(path: Path | None = None) -> dict[str, str]:
    path = path or PIN
    if not path.is_file():
        raise SystemExit(f"refusing to run: comparator pin {path} is missing")
    with path.open("rb") as fh:
        data = tomllib.load(fh)
    out = {}
    for key in ("maven", "gradle"):
        value = data.get(key)
        if not isinstance(value, str) or not _GA.fullmatch(value):
            raise SystemExit(f"{path}: {key} must be a GA version (digits and dots), not {value!r}")
        out[key] = value
    return out


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def _fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "jk-bench"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", "replace")


def latest_maven() -> str:
    xml = _fetch(MAVEN_METADATA)
    block = re.search(r"<versions>(.*?)</versions>", xml, re.S)
    versions = re.findall(r"<version>([^<]+)</version>", block.group(1) if block else xml)
    ga = [v for v in versions if _GA.fullmatch(v)]
    if not ga:
        raise RuntimeError(f"{MAVEN_METADATA} listed no GA versions")
    return max(ga, key=version_key)


def latest_gradle() -> str:
    data = json.loads(_fetch(GRADLE_CURRENT))
    version = str(data.get("version", ""))
    if not _GA.fullmatch(version):
        raise RuntimeError(f"{GRADLE_CURRENT} is not a GA version: {version!r}")
    return version


def _latest(tool: str) -> str:
    return latest_maven() if tool == "maven" else latest_gradle()


def _check_fresh(pin: dict[str, str], *, allow_stale: bool, offline: bool) -> tuple[bool, bool]:
    """Return `(stale_tools, offline_tools)`. Refuse when the pin is older or the check fails."""
    latest: dict[str, str] = {}
    try:
        for tool in ("maven", "gradle"):
            latest[tool] = _latest(tool)
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError, RuntimeError, KeyError) as e:
        if not offline:
            raise SystemExit(
                f"refusing to run: could not check the latest GA comparator ({e}).\n"
                "Pass --offline-tools to run the pin without that check; rows will record offline_tools."
            ) from e
        return False, True
    stale = [(tool, pin[tool], latest[tool]) for tool in ("maven", "gradle") if version_key(pin[tool]) < version_key(latest[tool])]
    newer = [(tool, pin[tool], latest[tool]) for tool in ("maven", "gradle") if version_key(pin[tool]) > version_key(latest[tool])]
    for tool, pinned, live in newer:
        print(f"{tool} pin {pinned} is newer than the latest GA {live}; running the pin", flush=True)
    if not stale:
        return False, False
    if not allow_stale:
        lines = "\n".join(f"{tool} pin {pinned} is older than the latest GA {live}." for tool, pinned, live in stale)
        raise SystemExit(
            f"refusing to run: comparator pin is stale.\n{lines}\n"
            f"Bump {PIN} to the latest GA.\n"
            "Pass --allow-stale-tools to run this pin anyway; rows will record stale_tools."
        )
    return True, False


def _tool_bin(tool: str, version: str) -> Path:
    slug = "maven" if tool == "maven" else "gradle"
    name = "mvn" if tool == "maven" else "gradle"
    return jk_home() / "store" / "tools" / slug / version / "bin" / name


def _version_line(binary: Path) -> str:
    env = dict(os.environ)
    cmd = [str(binary), "--version"]
    if binary.name == "gradle":
        cmd.append("--no-daemon")
        probe = Path(tempfile.gettempdir()) / "jk-bench-gradle-probe"
        probe.mkdir(parents=True, exist_ok=True)
        env["GRADLE_USER_HOME"] = str(probe)
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180, env=env)
    text = (proc.stdout or "") + (proc.stderr or "")
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if proc.returncode != 0 or not lines:
        raise SystemExit(f"refusing to run: {binary} --version failed (exit {proc.returncode})")
    # Gradle prints a dash banner before `Gradle <version>`; Maven's first line is `Apache Maven <version>`.
    for line in lines:
        if re.match(r"(?i)(Apache Maven|Gradle) ", line):
            return line
    return lines[0]


def _names_version(binary: Path, version: str) -> bool:
    return re.search(rf"(?<![\d.]){re.escape(version)}(?![\d.])", _version_line(binary)) is not None


def _provision(tool: str, version: str) -> Path:
    binary = _tool_bin(tool, version)
    if binary.is_file():
        if _names_version(binary, version):
            return binary.resolve()
        raise SystemExit(
            f"refusing to run: {binary} exists but its --version does not name {version}: {_version_line(binary)}"
        )
    url = (MAVEN_DIST if tool == "maven" else GRADLE_DIST).format(v=version)
    with tempfile.TemporaryDirectory(prefix="jk-bench-tool-") as td:
        root = Path(td)
        if tool == "maven":
            wrapper = root / ".mvn" / "wrapper"
            wrapper.mkdir(parents=True)
            (wrapper / "maven-wrapper.properties").write_text(f"distributionUrl={url}\n", encoding="utf-8")
            cmd = ["jk", "mvn", "--version"]
        else:
            wrapper = root / "gradle" / "wrapper"
            wrapper.mkdir(parents=True)
            (wrapper / "gradle-wrapper.properties").write_text(f"distributionUrl={url}\n", encoding="utf-8")
            cmd = ["jk", "gradle", "--version"]
        proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=900)
        if proc.returncode != 0:
            tail = ((proc.stdout or "") + (proc.stderr or ""))[-2000:]
            raise SystemExit(f"refusing to run: {' '.join(cmd)} failed to provision {tool} {version} (exit {proc.returncode}):\n{tail}")
    if not binary.is_file() or not _names_version(binary, version):
        seen = _version_line(binary) if binary.is_file() else "binary missing"
        raise SystemExit(f"refusing to run: provisioning {tool} {version} did not yield {binary} ({seen})")
    return binary.resolve()


def _from_env(pin: dict[str, str], need: tuple[str, ...]) -> Tools | None:
    if "JK_BENCH_STALE_TOOLS" not in os.environ or "JK_BENCH_OFFLINE_RESULT" not in os.environ:
        return None
    bins: dict[str, Path | None] = {"maven": None, "gradle": None}
    for tool in need:
        version = os.environ.get(_VER_ENV[tool])
        raw = os.environ.get(_BIN_ENV[tool])
        if version != pin[tool] or not raw:
            return None
        path = Path(raw)
        if not path.is_file() or not _names_version(path, version):
            return None
        bins[tool] = path.resolve()
    return Tools(
        maven_version=pin["maven"],
        gradle_version=pin["gradle"],
        maven_bin=bins["maven"],
        gradle_bin=bins["gradle"],
        stale_tools=os.environ["JK_BENCH_STALE_TOOLS"] == "1",
        offline_tools=os.environ["JK_BENCH_OFFLINE_RESULT"] == "1",
        pin_path=PIN,
    )


def _export(tools: Tools) -> None:
    os.environ["JK_BENCH_MAVEN_VERSION"] = tools.maven_version
    os.environ["JK_BENCH_GRADLE_VERSION"] = tools.gradle_version
    os.environ["JK_BENCH_STALE_TOOLS"] = "1" if tools.stale_tools else "0"
    os.environ["JK_BENCH_OFFLINE_RESULT"] = "1" if tools.offline_tools else "0"
    if tools.maven_bin is not None:
        os.environ["JK_BENCH_MAVEN_BIN"] = str(tools.maven_bin)
    if tools.gradle_bin is not None:
        os.environ["JK_BENCH_GRADLE_BIN"] = str(tools.gradle_bin)


def resolve(*, allow_stale: bool = False, offline: bool = False, need: tuple[str, ...] = ("maven", "gradle")) -> Tools:
    """The pinned tools. A child process reuses bins this process already verified."""
    allow_stale = allow_stale or os.environ.get("JK_BENCH_ALLOW_STALE_TOOLS") == "1"
    offline = offline or os.environ.get("JK_BENCH_OFFLINE_TOOLS") == "1"
    pin = read_pin()
    cached = _from_env(pin, need)
    if cached is not None:
        return cached
    stale, off = _check_fresh(pin, allow_stale=allow_stale, offline=offline)
    bins: dict[str, Path | None] = {"maven": None, "gradle": None}
    for tool in need:
        bins[tool] = _provision(tool, pin[tool])
    tools = Tools(pin["maven"], pin["gradle"], bins["maven"], bins["gradle"], stale, off, PIN)
    _export(tools)
    return tools


def stop_gradle() -> None:
    """Stop the pinned Gradle's daemons. Each sandbox is a new project, so a matrix otherwise leaves
    a daemon and a pool of compiler workers per Gradle user home holding gigabytes after it ends."""
    binary = os.environ.get(_BIN_ENV["gradle"])
    if binary and Path(binary).is_file():
        subprocess.run([binary, "--stop", "-q"], capture_output=True, timeout=120)


def gradle_incompatibility(text: str) -> str | None:
    """First line showing the build script cannot run on this Gradle, else None."""
    if not text or not _INCOMPAT.search(text):
        return None
    for raw in text.splitlines():
        line = raw.strip().lstrip(">").strip()
        if line and _INCOMPAT.search(line):
            return line[:500]
    match = _INCOMPAT.search(text)
    return " ".join(match.group(0).split())[:500] if match else None


def _major_from_version(version: str) -> str:
    if version.startswith("1."):
        return version.split(".")[1]
    return version.split(".", 1)[0]


def spec_major(spec: str) -> str | None:
    match = re.search(r"(?:^|-)(\d+)(?:\.|$)", spec.strip())
    return match.group(1) if match else None


def jdk_major(home: Path) -> str | None:
    release = home / "release"
    if release.is_file():
        match = re.search(r'JAVA_VERSION="([^"]+)"', release.read_text(encoding="utf-8", errors="replace"))
        if match:
            return _major_from_version(match.group(1))
    line = java_version_line(home)
    match = re.search(r'version "([^"]+)"', line)
    return _major_from_version(match.group(1)) if match else None


def java_version_line(home: Path) -> str:
    binary = home / "bin" / "java"
    proc = subprocess.run([str(binary), "-version"], capture_output=True, text=True, timeout=60)
    text = (proc.stderr or "") + (proc.stdout or "")
    for line in text.splitlines():
        if line.strip():
            return line.strip()
    raise JdkUnavailable(str(home), "java -version produced no output")


def _accept_home(spec: str, home: Path) -> Path:
    if not (home / "bin" / "java").is_file():
        raise JdkUnavailable(spec, f"no bin/java under {home}")
    major = jdk_major(home)
    want = spec_major(spec)
    if not major or (want and major != want):
        raise JdkUnavailable(spec, f"JDK at {home} is major {major}, wanted {want or spec}")
    return home.resolve()


def ensure_jdk(spec: str) -> Path:
    """`jk jdk ensure <spec>`. A different major than `spec` is a failure, not a substitute."""
    proc = subprocess.run(["jk", "--no-ansi", "jdk", "ensure", spec], capture_output=True, text=True, timeout=900)
    text = (proc.stdout or "") + "\n" + (proc.stderr or "")
    if proc.returncode != 0:
        tail = next((line.strip() for line in reversed(text.splitlines()) if line.strip()), f"exit {proc.returncode}")
        raise JdkUnavailable(spec, tail)
    if re.search(r"latest LTS|instead", text, re.I):
        raise JdkUnavailable(spec, "jk jdk ensure substituted a different JDK")
    pointer = Path.home() / ".jdks" / spec
    if (pointer / "bin" / "java").is_file():
        return _accept_home(spec, pointer)
    match = re.search(r"available at\s+(\S+)", text)
    if not match:
        raise JdkUnavailable(spec, "jk jdk ensure did not report a home")
    return _accept_home(spec, Path(match.group(1)).expanduser())


def _gradle_java_home(project: Path) -> str | None:
    props = project / "gradle.properties"
    if not props.is_file():
        return None
    for line in props.read_text(encoding="utf-8", errors="replace").splitlines():
        body = line.split("#", 1)[0].strip()
        if body.startswith("org.gradle.java.home") and "=" in body:
            value = body.split("=", 1)[1].strip()
            return value or None
    return None


def _home_from_java(binary: Path) -> Path:
    resolved = binary.resolve()
    if resolved.parent.name == "bin":
        return resolved.parent.parent
    raise JdkUnavailable("host", f"cannot derive a JDK home from {resolved}")


def java_for_project(project: Path, tool: str) -> tuple[Path, str]:
    """JDK the tool process will run on. Never substitutes a different one.

    Gradle honors `org.gradle.java.home` when the project sets it (the path must exist).
    Otherwise `.jdk-version` is ensured. With neither, the environment's `JAVA_HOME` or
    the `java` on `PATH` is recorded and used as-is.
    """
    if tool == "gradle":
        declared = _gradle_java_home(project)
        if declared:
            home = Path(declared).expanduser()
            if not home.is_absolute():
                home = (project / home).resolve()
            if not (home / "bin" / "java").is_file():
                raise JdkUnavailable(f"org.gradle.java.home={declared}", "path has no bin/java")
            return home.resolve(), java_version_line(home)
    pin = project / ".jdk-version"
    if pin.is_file():
        spec = pin.read_text(encoding="utf-8", errors="replace").strip()
        if not spec:
            raise JdkUnavailable(".jdk-version", "empty")
        home = ensure_jdk(spec)
        return home, java_version_line(home)
    env_home = os.environ.get("JAVA_HOME")
    if env_home:
        home = Path(env_home).expanduser()
        if not (home / "bin" / "java").is_file():
            raise JdkUnavailable(f"JAVA_HOME={env_home}", "path has no bin/java")
        return home.resolve(), java_version_line(home)
    found = shutil.which("java")
    if not found:
        raise JdkUnavailable("host", "no JAVA_HOME and no java on PATH")
    home = _home_from_java(Path(found))
    return home.resolve(), java_version_line(home)


_HOST: dict | None = None


def _cpu_model() -> str:
    text = Path("/proc/cpuinfo").read_text(encoding="utf-8", errors="replace")
    for key in ("model name", "Model", "Hardware"):
        for line in text.splitlines():
            if line.lower().startswith(key.lower()) and ":" in line:
                value = line.split(":", 1)[1].strip()
                if value:
                    return value
    raise SystemExit("refusing to run: /proc/cpuinfo has no CPU model")


def _os_id() -> str:
    info: dict[str, str] = {}
    path = Path("/etc/os-release")
    if path.is_file():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                info[key] = value.strip().strip('"')
    if info.get("ID"):
        return info["ID"] + (f"-{info['VERSION_ID']}" if info.get("VERSION_ID") else "")
    system = platform.system().lower()
    if not system:
        raise SystemExit("refusing to run: cannot read an OS id")
    return system


def host_info() -> dict:
    global _HOST
    if _HOST is None:
        try:
            cpu = _cpu_model()
            mem = next(int(line.split()[1]) for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemTotal"))
        except (OSError, StopIteration, ValueError, IndexError) as e:
            raise SystemExit(f"refusing to run: cannot read host cpu/memory ({e})") from e
        try:
            proc = Path("/proc/version").read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            raise SystemExit(f"refusing to run: cannot read /proc/version ({e})") from e
        cores = os.cpu_count()
        if not cores:
            raise SystemExit("refusing to run: os.cpu_count() is empty")
        _HOST = {
            "cpu": cpu,
            "logical_cpus": cores,
            "ram_gib": round(mem / 1024 / 1024),
            "os": _os_id(),
            "kernel": platform.release(),
            "wsl": "microsoft" in proc.lower(),
        }
    return dict(_HOST)


def host_id(info: dict | None = None) -> str:
    override = os.environ.get("JK_BENCH_HOST")
    if override:
        return override
    info = info or host_info()
    blob = f"{info['cpu']}\n{info['logical_cpus']}\n{info['ram_gib']}\n{info['os']}"
    return hashlib.sha256(blob.encode()).hexdigest()[:12]


def host_summary(info: dict | None = None) -> str:
    info = info or host_info()
    wsl = "WSL" if info["wsl"] else "not WSL"
    return (
        f"{info['cpu']} ({info['logical_cpus']} logical CPUs, {info['ram_gib']} GiB RAM), "
        f"{info['os']}, kernel {info['kernel']}, {wsl}"
    )


def row_host(row: dict) -> str:
    hid = row.get("host_id")
    return hid if isinstance(hid, str) and hid else LEGACY_HOST


def stamp(tools: Tools | None = None) -> dict:
    info = host_info()
    row = {"host_id": host_id(info), "host": info}
    if tools is not None:
        row.update(tools.as_row())
    return row
