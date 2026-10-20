"""The scoreboard's petclinic: the wall harness's pinned spring-petclinic as a jk project, plus the
private engines the dimensions run it on.

A private engine is any jk whose JK_STATE_DIR is its own directory: it shares the user's store and
caches, so nothing is downloaded twice, while another session's jobs never land in its counters or
its walls. Every helper takes that state directory.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BENCH))
import benchtools  # noqa: E402

PROJECT = "spring-petclinic"
COMMIT = "818c4136ea971c21674525f9053de0d9c7ad8cfe"
SOURCE = "https://github.com/spring-projects/spring-petclinic"
EDIT = "src/main/java/org/springframework/samples/petclinic/system/WelcomeController.java"
DROP = ("src/test/java/org/springframework/samples/petclinic/PostgresIntegrationTests.java",)
STRAYS = ("pom.xml", "build.gradle", "settings.gradle", "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd", "gradle")
EDIT_MARK = "// scoreboard edit"


def working_copy(home: Path, jk: str, state: Path, name: str = PROJECT) -> Path:
    """The pinned petclinic as a jk project under home/work/<name>, built once; reused while its marker matches."""
    work = home / "work" / name
    marker = work / ".scoreboard-ready"
    if marker.is_file() and marker.read_text().strip() == COMMIT:
        return work
    repo = home / "repos" / PROJECT
    if not (repo / ".git").is_dir():
        repo.parent.mkdir(parents=True, exist_ok=True)
        wall = benchtools.bench_home() / "wall" / "repos" / PROJECT
        subprocess.run(["git", "clone", "-q", str(wall) if (wall / ".git").is_dir() else SOURCE, str(repo)], check=True)
    subprocess.run(["git", "fetch", "-q", "origin"], cwd=repo, check=False)
    shutil.rmtree(work, ignore_errors=True)
    work.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "clone", "-q", "--shared", str(repo), str(work)], check=True)
    subprocess.run(["git", "checkout", "-q", COMMIT], cwd=work, check=True)
    for rel in DROP:
        (work / rel).unlink()
    for stray in STRAYS:
        p = work / stray
        shutil.rmtree(p) if p.is_dir() else p.unlink(missing_ok=True)
    for f in ("jk.toml", "jk-lock.toml"):
        shutil.copy(BENCH / "wall" / PROJECT / f, work / f)
    subprocess.run([jk, "build", "--skip-tests", "--no-ansi"], cwd=work, env=engine_env(state), check=True,
                   stdout=subprocess.DEVNULL)
    marker.write_text(COMMIT + "\n")
    return work


def engine_env(state: Path, extra: dict[str, str] | None = None) -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if k not in benchtools.STRIPPED}
    env["JK_STATE_DIR"] = str(state)
    env.update(extra or {})
    return env


def engine_tree(state: Path) -> list[int]:
    """Every process carrying this JK_STATE_DIR: the engine and the workers it forked."""
    needle = f"JK_STATE_DIR={state}\0".encode()
    pids = []
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            if needle in Path(f"/proc/{entry}/environ").read_bytes() + b"\0":
                pids.append(int(entry))
        except OSError:
            continue
    return pids


def engine_pid(state: Path) -> int | None:
    for pid in engine_tree(state):
        try:
            cmdline = Path(f"/proc/{pid}/cmdline").read_bytes()
        except OSError:
            continue
        if b"EngineMain" in cmdline or b"jk-engine" in cmdline:
            return pid
    return None


def io_snapshot(state: Path) -> dict[int, int]:
    snap = {}
    for pid in engine_tree(state):
        try:
            fields = dict(line.split(": ") for line in Path(f"/proc/{pid}/io").read_text().splitlines() if line)
            snap[pid] = int(fields["write_bytes"])
        except (OSError, KeyError, ValueError):
            continue
    return snap


def quiesce(state: Path, quiet_s: float = 1.0, cap_s: float = 10.0) -> dict[int, int]:
    """Wait until the engine tree has written no file bytes for quiet_s, and return that snapshot.

    The engine finishes some of a job's work after the reply: the idle boundary flushes the hash
    memo, the journal completes the run record. Counting up to a quiet point puts that work inside
    the job that caused it rather than in whichever later window it happens to land.
    """
    deadline = time.monotonic() + cap_s
    last, since = io_snapshot(state), time.monotonic()
    while time.monotonic() < deadline:
        time.sleep(0.1)
        now = io_snapshot(state)
        if now != last:
            last, since = now, time.monotonic()
        elif time.monotonic() - since >= quiet_s:
            break
    return last


def edit(work: Path) -> None:
    with open(work / EDIT, "a", encoding="utf-8") as fh:
        fh.write(f"\n{EDIT_MARK} {time.time_ns()}\n")


def unedit(work: Path) -> None:
    subprocess.run(["git", "checkout", "-q", "--", EDIT], cwd=work, check=True)


def build(work: Path, jk: str, env: dict, *extra: str) -> subprocess.CompletedProcess:
    return subprocess.run([jk, "build", "--skip-tests", *extra], cwd=work, env=env, capture_output=True)


def settle(work: Path, jk: str, env: dict) -> None:
    """Put the tree back at a built baseline, so every scenario starts from the same state."""
    unedit(work)
    res = build(work, jk, env, "--no-ansi")
    if res.returncode:
        raise SystemExit(f"jk build failed settling {work}:\n{res.stdout.decode()[-2000:]}{res.stderr.decode()[-2000:]}")


def stop(jk: str, state: Path) -> None:
    subprocess.run([jk, "engine", "stop"], env=engine_env(state), capture_output=True)


def version(jk: str) -> str:
    res = subprocess.run([jk, "--version"], capture_output=True, text=True)
    return res.stdout.strip() or "?"
