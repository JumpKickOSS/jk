// SPDX-License-Identifier: Apache-2.0
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { test } from "node:test";
import { familiesSettledBy, familyOf, problemsOf } from "../../src/problems";

const dir = path.resolve("/work/app");
const main = path.join(dir, "src/main/java/a/Main.java");
const manifest = path.join(dir, "jk.toml");

test("a compile error lands at its file, line and column, without the compiler's header", () => {
  const [p] = problemsOf({
    dir,
    diagnostics: [
      {
        severity: "error",
        dir,
        task: "compile-java",
        code: "javac",
        key: "compiler.err.cant.resolve.location.args",
        message: `${main}:15:32: error: cannot find symbol\n  symbol:   method undefinedCall()`,
        file: main,
        line: 15,
        col: 32,
      },
    ],
  });
  assert.deepEqual(p, {
    file: main,
    line: 14,
    column: 31,
    severity: "error",
    message: "cannot find symbol\n  symbol:   method undefinedCall()",
    source: "javac",
    code: "compiler.err.cant.resolve.location.args",
  });
});

test("a manifest or lock error is the engine's own, at the key it names; a file-less row is not a problem", () => {
  const problems = problemsOf({
    dir,
    diagnostics: [
      { severity: "error", dir: "", code: "run-error", message: "java must be an integer", file: manifest, line: 4, col: 1 },
      { severity: "error", dir: "", code: "lock", message: "nothing has a:b", file: manifest, line: 7 },
      { severity: "error", dir: "", code: "run-error", message: "graal missing" },
      { severity: "warning", dir, code: "javac", message: "deprecated", file: "src/main/java/a/Main.java", line: 3 },
    ],
  });
  assert.deepEqual(
    problems.map((p) => [p.source, p.file, p.line, p.column, p.severity]),
    [
      ["jk", manifest, 3, 0, "error"],
      ["lock", manifest, 6, 0, "error"],
      ["javac", main, 2, 0, "warning"],
    ],
  );
});

test("a lock settles the manifest's problems only; a build settles both", () => {
  assert.deepEqual(familiesSettledBy("lock"), ["manifest"]);
  assert.deepEqual(familiesSettledBy("test"), ["manifest", "compile"]);
  const [m, c] = problemsOf({
    dir,
    diagnostics: [
      { severity: "error", code: "lock", message: "x", file: manifest, line: 1 },
      { severity: "error", code: "javac", message: "y", file: main, line: 1 },
    ],
  });
  assert.equal(m && familyOf(m), "manifest");
  assert.equal(c && familyOf(c), "compile");
});
