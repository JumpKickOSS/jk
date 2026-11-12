#!/usr/bin/env python3
"""Judge cheap effort features for step kinds never measured on a host (JK-PMF9X).

Reads a cold set (coldset.sh's manifest: one first build per project, each copied to scratch) and,
for each step of a kind below, its wall and a feature counted from the project copy, as the planner
could count it before the step runs. Then, leaving one project out at a time, it prices each of that
project's steps three ways and reports the error of each:

- today:   what the run's seed priced it at (eta-model.jsonl)
- flat:    the mean wall of that kind over the other projects
- feature: floor + rate * x, least squares over the other projects (floor and rate kept >= 0)

A feature earns its place only where it beats flat.

    features.py --manifest ~/src/scratch/eta-corpus/<label>/manifest.tsv [--csv out.csv]
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

MS_PER_WEIGHT = 150
SKIP_DIRS = {"node_modules", "target", ".git", ".jk", "dist", ".angular", ".next", ".svelte-kit", "build"}
WEB_SOURCE = re.compile(r"\.(ts|tsx|js|jsx|mjs|svelte|vue|html|css|scss)$")
WEB_TEST = re.compile(r"\.(spec|test)\.(ts|tsx|js|jsx|mjs)$")
CREATE_TABLE = re.compile(r"\bcreate\s+table\b", re.I)


def files(root: Path):
    for p in root.rglob("*"):
        if any(part in SKIP_DIRS for part in p.relative_to(root).parts[:-1]):
            continue
        if p.is_file():
            yield p


def lock_packages(module: Path) -> int | None:
    """Packages a node lockfile pins: package-lock's packages, pnpm's or yarn's entries."""
    for d in (module, *module.parents):
        npm = d / "package-lock.json"
        if npm.is_file():
            try:
                data = json.loads(npm.read_text(encoding="utf-8"))
            except ValueError:
                return None
            pk = data.get("packages") or data.get("dependencies") or {}
            return max(0, len(pk) - (1 if "" in pk else 0))
        pnpm = d / "pnpm-lock.yaml"
        if pnpm.is_file():
            text = pnpm.read_text(encoding="utf-8")
            body = text.split("\npackages:", 1)[1] if "\npackages:" in text else ""
            body = body.split("\nsnapshots:", 1)[0]
            return sum(1 for line in body.splitlines() if re.match(r"^  \S.*:$", line))
        yarn = d / "yarn.lock"
        if yarn.is_file():
            return sum(1 for line in yarn.read_text(encoding="utf-8").splitlines() if line and not line[0].isspace() and line.endswith(":"))
        if (d / ".git").exists():
            break
    return None


def schema_objects(module: Path) -> int | None:
    """Tables plus columns across the module's SQL: each CREATE TABLE and each line inside its body."""
    total, seen = 0, False
    for p in files(module):
        if p.suffix.lower() != ".sql":
            continue
        seen = True
        text = p.read_text(encoding="utf-8", errors="replace")
        for m in CREATE_TABLE.finditer(text):
            total += 1
            depth, i, cols = 0, text.find("(", m.end()), 0
            if i < 0:
                continue
            for line in text[i:].splitlines():
                depth += line.count("(") - line.count(")")
                if line.strip() and not line.strip().startswith("--"):
                    cols += 1
                if depth <= 0:
                    break
            total += cols
    return total if seen else None


def java_sources(module: Path) -> int:
    return sum(1 for p in files(module / "src" / "main") if p.suffix in (".java", ".kt", ".groovy")) if (module / "src" / "main").is_dir() else 0


def project_lock_packages(module: Path) -> int:
    """The JVM packages the project's lockfile pins: the classpath an AOT run starts its context on."""
    for d in (module, *module.parents):
        lock = d / "jk-lock.toml"
        if lock.is_file():
            return sum(1 for line in lock.read_text(encoding="utf-8").splitlines() if line.startswith("[[artifact]]"))
        if (d / ".git").exists():
            break
    return 0


def web_sources(m: Path) -> int:
    return sum(1 for p in files(m) if WEB_SOURCE.search(p.name) and not WEB_TEST.search(p.name))


def web_tests(m: Path) -> int:
    return sum(1 for p in files(m) if WEB_TEST.search(p.name))


def script_tool(script: str):
    """The tool a package.json script runs: its last command's program (`tsc -b && vite build` is vite)."""
    def of(m: Path) -> str | None:
        pj = m / "package.json"
        if not pj.is_file():
            return None
        try:
            cmd = (json.loads(pj.read_text(encoding="utf-8")).get("scripts") or {}).get(script)
        except ValueError:
            return None
        if not cmd:
            return None
        last = cmd.split("&&")[-1].split()
        if last[:2] == ["npm", "run"] and len(last) > 2:
            return "npm-run-" + last[2]
        return last[0] if last else None
    return of


# step kind -> candidate features, each (name, feature of the module dir); every one is judged
KINDS = {
    "node-install": [("lock packages", lock_packages)],
    "node-build": [("web sources", web_sources), ("lock packages", lock_packages), ("tool", script_tool("build"))],
    "node-test": [("web tests", web_tests), ("lock packages", lock_packages), ("tool", script_tool("test"))],
    "plugin-generate-jooq": [("tables+columns", schema_objects)],
    "plugin-spring-aot": [("sources+jvm packages", lambda m: java_sources(m) + project_lock_packages(m))],
}


def samples(manifest: Path) -> list[dict]:
    out = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        run, project, _, rc, _, copy = (line.split("\t") + [""] * 6)[:6]
        run_dir = Path(run)
        if rc != "0" or not (run_dir / "details.jsonl").is_file():
            continue
        priced = {}
        model = run_dir / "eta-model.jsonl"
        if model.is_file():
            first = json.loads(model.read_text(encoding="utf-8").splitlines()[0])
            for d, steps in (first.get("steps") or {}).items():
                for s in steps:
                    priced[(d, s[0])] = int(s[1]) * MS_PER_WEIGHT
        dir_of = {}
        for raw in (run_dir / "details.jsonl").read_text(encoding="utf-8").splitlines():
            try:
                e = json.loads(raw)
            except ValueError:
                continue
            if e.get("type") == "module-start":
                dir_of[e.get("coord")] = e.get("dir")
            if e.get("type") != "task-finish" or e.get("status") != "SUCCESS" or e.get("task") not in KINDS:
                continue
            d = e.get("dir") or dir_of.get(e.get("module"))
            if not d:
                continue
            wall = int(e.get("millis") or 0) - int(e.get("waitMillis") or 0)
            for name, feature in KINDS[e["task"]]:
                x = feature(Path(d))
                if x is None:
                    continue
                out.append({"project": project, "module": Path(d).name, "kind": e["task"], "feature": name,
                            "x": x, "wall": wall, "today": priced.get((d, e["task"]))})
    return out


def fit(points: list[tuple[float, float]]) -> tuple[float, float]:
    """Least-squares floor and rate, both kept >= 0; a flat mean when x does not vary."""
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    var = sum((x - mx) ** 2 for x in xs)
    if var == 0:
        return my, 0.0
    rate = max(0.0, sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / var)
    floor = my - rate * mx
    if floor < 0:
        floor, rate = 0.0, sum(x * y for x, y in zip(xs, ys)) / max(1e-9, sum(x * x for x in xs))
    return floor, rate


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--csv", type=Path)
    ap.add_argument("--center", choices=("mean", "median"), default="median",
                    help="how flat and per-group prices summarize the other projects' walls")
    a = ap.parse_args(argv)
    center = statistics.median if a.center == "median" else statistics.fmean
    rows = samples(a.manifest.expanduser())
    if a.csv:
        with a.csv.open("w", encoding="utf-8") as f:
            f.write("project,module,kind,feature,x,wall,today\n")
            for r in rows:
                f.write(f"{r['project']},{r['module']},{r['kind']},{r['feature']},{r['x']},{r['wall']},{r['today'] or ''}\n")
    print(f"{'kind':22s} {'feature':22s} {'n':>3s} {'projects':>8s}  {'|today|':>8s} {'|flat|':>8s} {'|feature|':>9s}  verdict")
    for kind, candidates in KINDS.items():
      for name, _ in candidates:
        rs = [r for r in rows if r["kind"] == kind and r["feature"] == name]
        projects = sorted({r["project"] for r in rs})
        if len(projects) < 3:
            print(f"{kind:22s} {name:22s} {len(rs):3d} {len(projects):8d}  too few projects to judge")
            continue
        errs = {"today": [], "flat": [], "feature": []}
        categorical = isinstance(rs[0]["x"], str)
        for held in projects:
            train = [r for r in rs if r["project"] != held]
            flat = center([r["wall"] for r in train])
            if not categorical:
                floor, rate = fit([(r["x"], r["wall"]) for r in train])
            for r in rs:
                if r["project"] != held or r["wall"] <= 0:
                    continue
                if r["today"]:
                    errs["today"].append(abs(r["today"] - r["wall"]) / r["wall"])
                errs["flat"].append(abs(flat - r["wall"]) / r["wall"])
                if categorical:
                    same = [t["wall"] for t in train if t["x"] == r["x"]]
                    guess = center(same) if same else flat
                else:
                    guess = floor + rate * r["x"]
                errs["feature"].append(abs(guess - r["wall"]) / r["wall"])
        med = {k: (statistics.median(v) if v else None) for k, v in errs.items()}
        better = med["feature"] is not None and med["flat"] is not None and med["feature"] < med["flat"]
        fmt = lambda v: "-" if v is None else f"{v:7.0%}"
        if categorical:
            groups = {}
            for r in rs:
                groups.setdefault(r["x"], []).append(r["wall"])
            fitted = ", ".join(f"{k} {center(v):.0f} ms" for k, v in sorted(groups.items()))
        else:
            floor, rate = fit([(r["x"], r["wall"]) for r in rs])
            fitted = f"{floor:.0f} ms + {rate:.2f} ms/unit"
        print(f"{kind:22s} {name:22s} {len(rs):3d} {len(projects):8d}  {fmt(med['today']):>8s} {fmt(med['flat']):>8s} {fmt(med['feature']):>9s}"
              f"  {'feature beats flat' if better else 'flat is as good'}  (all: {fitted})")
    print("\nper step: project module kind x wall today")
    for r in sorted(rows, key=lambda r: (r["kind"], r["feature"], str(r["x"]))):
        print(f"   {r['project'][:28]:28s} {r['module'][:16]:16s} {r['kind']:22s} {r['feature'][:14]:14s} {str(r['x']):>8s} {r['wall']:7d} {r['today'] or '-':>7}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
